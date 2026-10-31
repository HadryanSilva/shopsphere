package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.StockWork;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.CheckoutIntentRepository;
import br.com.hadryan.shosphere.order.repository.StockWorkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class StockWorkTransaction {
    private final StockWorkRepository works;
    private final CheckoutIntentRepository intents;
    @PersistenceContext private EntityManager entityManager;
    @Value("#{T(java.time.Duration).parse('${stock.recovery.initial-backoff:PT60S}')}")
    private Duration initialBackoff = Duration.ofSeconds(60);
    @Value("#{T(java.time.Duration).parse('${stock.recovery.max-backoff:PT15M}')}")
    private Duration maxBackoff = Duration.ofMinutes(15);
    @Value("#{T(java.time.Duration).parse('${stock.recovery.monitor-interval:PT60S}')}")
    private Duration monitorInterval = Duration.ofSeconds(60);

    @jakarta.annotation.PostConstruct
    void validateSchedule() {
        Assert.isTrue(initialBackoff.isPositive() && maxBackoff.compareTo(initialBackoff) >= 0,
                "Invalid stock recovery backoff");
        Assert.isTrue(monitorInterval.isPositive() && monitorInterval.compareTo(Duration.ofSeconds(60)) <= 0,
                "Stock monitoring interval must be positive and at most PT60S");
    }

    public List<UUID> due(Instant now, int limit) {
        Assert.isTrue(limit > 0, "Stock recovery batch size must be positive");
        return entityManager.createQuery("select w.orderId from StockWork w where w.phase <> :closed "
                        + "and w.nextAttemptAt <= :now and (w.claimedUntil is null or w.claimedUntil <= :now) "
                        + "order by w.nextAttemptAt, w.orderId", UUID.class)
                .setParameter("closed", StockWorkPhase.CLOSED).setParameter("now", now)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", -2)
                .setMaxResults(limit).getResultList();
    }

    public Optional<StockWorkClaim> claim(UUID orderId, Instant now, Duration lease) {
        Assert.isTrue(lease.isPositive(), "Stock recovery lease must be positive");
        var matches = entityManager.createQuery("select w from StockWork w where w.orderId = :id "
                        + "and w.phase <> :closed and w.nextAttemptAt <= :now "
                        + "and (w.claimedUntil is null or w.claimedUntil <= :now)", StockWork.class)
                .setParameter("id", orderId).setParameter("closed", StockWorkPhase.CLOSED).setParameter("now", now)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", -2).getResultList();
        if (matches.isEmpty()) return Optional.empty();
        var work = matches.getFirst();
        var token = UUID.randomUUID();
        work.claim(token, now.plus(lease));
        return Optional.of(new StockWorkClaim(token, snapshot(work, order(work))));
    }

    public Optional<StockRecoveryResult> finish(StockWorkClaim claim, boolean failed, Instant now, Duration warnAfter) {
        Assert.isTrue(warnAfter.isPositive(), "Stock recovery warning age must be positive");
        var work = lock(claim.work().orderId());
        if (!work.ownsClaim(claim.token())) return Optional.empty();
        if (work.getPhase() != StockWorkPhase.CLOSED) {
            if (failed && work.getAttemptCount() == claim.work().attemptCount()) retry(work, StockFailureCategory.PERSISTENCE_FAILURE, now);
            if (work.getPhase() == StockWorkPhase.REVIEW_REQUIRED) {
                var minimum = now.plus(maxBackoff);
                if (work.getNextAttemptAt().isBefore(minimum)) work.schedule(minimum);
            } else if (work.getNextAttemptAt().compareTo(now) <= 0) work.schedule(now.plus(monitorInterval));
        }
        boolean warn = work.getPhase() != StockWorkPhase.CLOSED && !work.getCreatedAt().plus(warnAfter).isAfter(now)
                && (work.getLastWarningAt() == null || !work.getLastWarningAt().plus(warnAfter).isAfter(now));
        if (warn) work.warned(now);
        work.releaseClaim();
        return Optional.of(new StockRecoveryResult(snapshot(work, order(work)), warn));
    }

    public StockWorkSnapshot retry(UUID orderId, StockWorkPhase expected, StockFailureCategory category) {
        var work = lock(orderId);
        var order = order(work);
        if (work.getPhase() == expected && work.getPhase() != StockWorkPhase.CLOSED) retry(work, category, Instant.now());
        return snapshot(work, order);
    }

    private void retry(StockWork work, StockFailureCategory category, Instant now) {
        var delay = initialBackoff;
        for (long n = 0; n < work.getAttemptCount() && delay.compareTo(maxBackoff) < 0; n++)
            delay = delay.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : delay.multipliedBy(2);
        if (work.getPhase() == StockWorkPhase.RESERVED && delay.compareTo(monitorInterval) > 0) delay = monitorInterval;
        work.recordFailure(work.getPhase(), category, now.plus(delay));
    }

    public StockWorkSnapshot observeForMonitoring(UUID orderId, StockReservationObservation observation) {
        var next = Instant.now().plus(monitorInterval);
        if (observation != null && observation.expiresAt() != null && observation.expiresAt().isBefore(next)) next = observation.expiresAt();
        return observe(orderId, observation, next);
    }

    public StockWorkSnapshot get(UUID orderId) {
        var work = lock(orderId);
        return snapshot(work, order(work));
    }

    public StockWorkSnapshot cartCompleted(UUID orderId) {
        var work = lock(orderId);
        var order = order(work);
        var intent = intents.findWithLockByCheckoutId(work.getCheckoutId()).orElseThrow();
        if (!orderId.equals(intent.getOrderId())) throw new IllegalStateException("Work checkout identity mismatch");
        if (intent.getStatus() == CheckoutIntentStatus.COMPLETED) work.cartCompleted();
        return snapshot(work, order);
    }

    public StockWorkSnapshot requestAbort(UUID orderId, StockWorkReason reason) {
        var work = lock(orderId);
        var order = order(work);
        work.requestAbort(reason);
        return snapshot(work, order);
    }

    public StockWorkSnapshot observe(UUID orderId, StockReservationObservation observation, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt);
        var work = lock(orderId);
        var order = order(work);
        if (work.getPhase() != StockWorkPhase.CLOSED && work.getPhase() != StockWorkPhase.REVIEW_REQUIRED) {
            if (!valid(order, observation)) work.review(StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
            else if (!mutableOrder(order)) work.review(StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
            else {
                work.observe(observation.status(), observation.createdAt(), observation.expiresAt(),
                        observation.updatedAt(), nextAttemptAt);
                applyPublicState(work, order);
            }
        }
        return snapshot(work, order);
    }

    // The client must validate the ABORTED envelope before invoking this local proof transition.
    public StockWorkSnapshot aborted(UUID orderId, UUID proofOrderId, StockReservationObservation reservation) {
        var work = lock(orderId);
        var order = order(work);
        if (work.getPhase() == StockWorkPhase.CLOSED || work.getPhase() == StockWorkPhase.REVIEW_REQUIRED)
            return snapshot(work, order);
        if (!orderId.equals(proofOrderId) || !mutableOrder(order) || work.getPhase() != StockWorkPhase.ABORT_PENDING
                || reservation == null && work.getReservationStatus() != null
                || reservation != null && (!valid(order, reservation)
                    || reservation.status() != StockReservationStatus.RELEASED && reservation.status() != StockReservationStatus.EXPIRED)) {
            work.review(StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
        } else if (reservation == null) work.abortedWithoutReservation();
        else work.observe(reservation.status(), reservation.createdAt(), reservation.expiresAt(),
                    reservation.updatedAt(), Instant.now());
        applyPublicState(work, order);
        return snapshot(work, order);
    }

    public StockWorkSnapshot review(UUID orderId, StockFailureCategory category) {
        var work = lock(orderId);
        var order = order(work);
        work.review(category);
        return snapshot(work, order);
    }

    public StockWorkSnapshot recordFailure(UUID orderId, StockWorkPhase expected,
                                          StockFailureCategory category, Instant nextAttemptAt) {
        var work = lock(orderId);
        var order = order(work);
        work.recordFailure(expected, category, nextAttemptAt);
        return snapshot(work, order);
    }

    private boolean valid(Order order, StockReservationObservation observation) {
        if (observation == null || !order.getId().equals(observation.orderId()) || observation.status() == null
                || observation.createdAt() == null || observation.expiresAt() == null || observation.updatedAt() == null
                || !observation.createdAt().isBefore(observation.expiresAt())
                || observation.updatedAt().isBefore(observation.createdAt()) || observation.items() == null) return false;
        var expected = new HashMap<UUID, Integer>();
        for (var item : order.getItems())
            if (expected.putIfAbsent(item.getProductId(), item.getQuantity()) != null) return false;
        var received = new HashMap<UUID, Integer>();
        for (var item : observation.items())
            if (item == null || item.productId() == null || item.quantity() <= 0
                    || received.putIfAbsent(item.productId(), item.quantity()) != null) return false;
        return !expected.isEmpty() && expected.equals(received);
    }

    private boolean mutableOrder(Order order) {
        return order.getStatus() == OrderStatus.PENDING_STOCK || order.getStatus() == OrderStatus.PENDING_PAYMENT;
    }

    private void applyPublicState(StockWork work, Order order) {
        if (work.getPhase() == StockWorkPhase.RESERVED) order.setStatus(OrderStatus.PENDING_PAYMENT);
        else if (work.getPhase() == StockWorkPhase.CLOSED && order.getStatus() != OrderStatus.CANCELLED) order.cancel();
    }

    private StockWork lock(UUID orderId) {
        return works.findWithLockByOrderId(orderId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock work not found"));
    }

    private Order order(StockWork work) {
        return Objects.requireNonNull(entityManager.find(Order.class, work.getOrderId(), LockModeType.PESSIMISTIC_WRITE));
    }

    private StockWorkSnapshot snapshot(StockWork work, Order order) {
        works.flush();
        return StockWorkSnapshot.from(work, order);
    }
}
