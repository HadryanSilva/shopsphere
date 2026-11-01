package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PaymentWorkTransaction {
    private final StockWorkRepository stock;
    private final PaymentWorkRepository payments;
    @PersistenceContext private EntityManager entityManager;
    @Value("#{T(java.time.Duration).parse('${payment.recovery.initial-backoff:PT60S}')}")
    private Duration initialBackoff = Duration.ofSeconds(60);
    @Value("#{T(java.time.Duration).parse('${payment.recovery.max-backoff:PT15M}')}")
    private Duration maxBackoff = Duration.ofMinutes(15);
    @Value("#{T(java.time.Duration).parse('${payment.recovery.interval:PT60S}')}")
    private Duration interval = Duration.ofSeconds(60);
    private record Locked(StockWork stock, Order order, PaymentWork payment) {}

    public PaymentWorkSnapshot get(UUID id) { return snapshot(lock(id)); }
    public PaymentWorkSnapshot get(UUID id, UUID token) { var state = lock(id); requireClaim(state, token); return snapshot(state); }
    @jakarta.annotation.PostConstruct
    void validateSchedule() {
        Assert.isTrue(initialBackoff.isPositive() && maxBackoff.compareTo(initialBackoff) >= 0 && interval.isPositive(), "Invalid payment recovery schedule");
    }
    public List<UUID> due(Instant now, int limit) {
        Assert.isTrue(limit > 0, "Payment recovery batch size must be positive");
        return entityManager.createQuery("select p.orderId from PaymentWork p where p.phase not in :terminal "
                        + "and p.nextAttemptAt <= :now and (p.claimedUntil is null or p.claimedUntil <= :now) order by p.nextAttemptAt,p.orderId", UUID.class)
                .setParameter("terminal", List.of(PaymentWorkPhase.DONE, PaymentWorkPhase.CLOSED)).setParameter("now", now)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", -2).setMaxResults(limit).getResultList();
    }
    public Optional<PaymentWorkClaim> claim(UUID id, Instant now, Duration lease) {
        Assert.isTrue(lease.isPositive(), "Payment recovery lease must be positive");
        var stocks = entityManager.createQuery("select s from StockWork s where s.orderId=:id", StockWork.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", -2).getResultList();
        if (stocks.isEmpty()) return Optional.empty();
        var orders = entityManager.createQuery("select o from Order o where o.id=:id", Order.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", -2).getResultList();
        if (orders.isEmpty()) return Optional.empty();
        var rows = entityManager.createQuery("select p from PaymentWork p where p.orderId=:id and p.phase not in :terminal "
                        + "and p.nextAttemptAt <= :now and (p.claimedUntil is null or p.claimedUntil <= :now)", PaymentWork.class)
                .setParameter("id", id).setParameter("terminal", List.of(PaymentWorkPhase.DONE, PaymentWorkPhase.CLOSED)).setParameter("now", now)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", -2).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        var work = rows.getFirst(); var token = UUID.randomUUID(); work.claim(token, now.plus(lease));
        return Optional.of(new PaymentWorkClaim(token, snapshot(new Locked(stocks.getFirst(), orders.getFirst(), work)), work.getAttemptCount()));
    }
    public Optional<PaymentRecoveryResult> finish(PaymentWorkClaim claim, boolean failed, Instant now, Duration warnAfter) {
        Assert.isTrue(warnAfter.isPositive(), "Payment warning age must be positive");
        var state = lock(claim.work().orderId()); var work = state.payment;
        if (!work.ownsClaim(claim.token())) return Optional.empty();
        if (!work.getPhase().terminal()) {
            if (failed && work.getAttemptCount() == claim.attempts()) work.failure(PaymentFailureCategory.PERSISTENCE_FAILURE);
            var delay = interval;
            if (work.getAttemptCount() > claim.attempts()) {
                delay = initialBackoff;
                for (long n = 1; n < work.getAttemptCount() && delay.compareTo(maxBackoff) < 0; n++)
                    delay = delay.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : delay.multipliedBy(2);
            }
            if (work.getPhase() == PaymentWorkPhase.REVIEW_REQUIRED) delay = maxBackoff;
            var next = now.plus(delay);
            var expiry = state.stock.getReservationExpiresAt();
            if (work.getPhase() != PaymentWorkPhase.REVIEW_REQUIRED && !work.isStockSafe()
                    && expiry != null && expiry.isAfter(now) && expiry.isBefore(next)) next = expiry;
            work.schedule(next);
        }
        boolean warn = !work.getPhase().terminal() && !work.getCreatedAt().plus(warnAfter).isAfter(now)
                && (work.getLastWarningAt() == null || !work.getLastWarningAt().plus(warnAfter).isAfter(now));
        if (warn) work.warned(now);
        work.releaseClaim();
        return Optional.of(new PaymentRecoveryResult(snapshot(state), work.getAttemptCount(), warn));
    }
    public PaymentWorkSnapshot resolution(UUID id, PaymentWorkPhase expected) { return resolution(id, expected, null); }
    public PaymentWorkSnapshot resolution(UUID id, PaymentWorkPhase expected, UUID token) {
        var state = lock(id);
        requireClaim(state, token);
        if (eligible(state, expected)) state.payment.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
        return snapshot(state);
    }
    public PaymentWorkSnapshot payment(UUID id, PaymentWorkPhase expected, PaymentObservation observation) { return payment(id, expected, observation, null); }
    public PaymentWorkSnapshot payment(UUID id, PaymentWorkPhase expected, PaymentObservation observation, UUID token) {
        var state = lock(id); var work = state.payment;
        requireClaim(state, token);
        if (!eligible(state, expected)) return snapshot(state);
        try { work.remember(observation); }
        catch (IllegalArgumentException failure) { return review(state, PaymentFailureCategory.INCONSISTENT_PROOF); }
        work.clearFailure();
        var status = work.getPaymentStatus();
        if (work.getPhase() == PaymentWorkPhase.CAPTURE_PENDING) {
            if (status == OrderPaymentStatus.CAPTURED) work.moveTo(PaymentWorkPhase.CONFIRM_PENDING);
            else if (status != OrderPaymentStatus.PROCESSING) work.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
        } else if (work.getPhase() == PaymentWorkPhase.CONFIRM_PENDING && status != OrderPaymentStatus.CAPTURED)
            work.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
        if (work.getPhase() == PaymentWorkPhase.CANCEL_PAYMENT_PENDING
                && (status == OrderPaymentStatus.DECLINED || status == OrderPaymentStatus.CANCELLED || status == OrderPaymentStatus.REFUNDED)) {
            state.order.observePayment(status); state.order.cancel();
            state.stock.completePayment(false, work.isStockAbsent()); work.moveTo(PaymentWorkPhase.CLOSED);
        } else if (state.stock.getReservationStatus() == StockReservationStatus.CONFIRMED) {
            if (status == OrderPaymentStatus.CAPTURED && !work.isStockSafe()) complete(state);
            else if (status != OrderPaymentStatus.PROCESSING) return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        } else updatePublic(state);
        return snapshot(state);
    }
    public PaymentWorkSnapshot inventory(UUID id, PaymentWorkPhase expected, StockReservationObservation observation) { return inventory(id, expected, observation, null); }
    public PaymentWorkSnapshot inventory(UUID id, PaymentWorkPhase expected, StockReservationObservation observation, UUID token) {
        var state = lock(id);
        requireClaim(state, token);
        if ((state.payment.getPhase() == PaymentWorkPhase.CANCEL_PAYMENT_PENDING || state.payment.getPhase() == PaymentWorkPhase.CLOSED)
                && state.payment.isStockSafe()
                && observation != null && observation.status() == StockReservationStatus.CONFIRMED && valid(state, observation))
            return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        if (!eligible(state, expected)) return snapshot(state);
        if (!valid(state, observation)) return review(state, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE);
        if (state.payment.isStockSafe()) {
            if (observation.status() == StockReservationStatus.CONFIRMED) return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
            return snapshot(state);
        }
        try { state.stock.paymentObserved(observation.status(), observation.createdAt(), observation.expiresAt(), observation.updatedAt()); }
        catch (IllegalArgumentException failure) { return review(state, PaymentFailureCategory.INCONSISTENT_PROOF); }
        var status = state.stock.getReservationStatus();
        if (status == StockReservationStatus.CONFIRMED) {
            if (state.payment.getPaymentStatus() == OrderPaymentStatus.CAPTURED) complete(state);
            else if (state.payment.getPaymentStatus() == null || state.payment.getPaymentStatus() == OrderPaymentStatus.PROCESSING)
                state.payment.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
            else return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        } else if (status == StockReservationStatus.RELEASED || status == StockReservationStatus.EXPIRED) safeStock(state, false);
        return snapshot(state);
    }
    public PaymentWorkSnapshot aborted(UUID id, UUID proofId, StockReservationObservation reservation) { return aborted(id, proofId, reservation, null); }
    public PaymentWorkSnapshot aborted(UUID id, UUID proofId, StockReservationObservation reservation, UUID token) {
        if (reservation != null) {
            if (reservation.status() != StockReservationStatus.RELEASED && reservation.status() != StockReservationStatus.EXPIRED)
                return failure(id, PaymentWorkPhase.RESOLUTION_PENDING, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE, token);
            if (!id.equals(proofId)) return failure(id, PaymentWorkPhase.RESOLUTION_PENDING, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE, token);
            return inventory(id, PaymentWorkPhase.RESOLUTION_PENDING, reservation, token);
        }
        var state = lock(id);
        requireClaim(state, token);
        if (!eligible(state, PaymentWorkPhase.RESOLUTION_PENDING)) return snapshot(state);
        if (!id.equals(proofId) || state.stock.getReservationStatus() == StockReservationStatus.CONFIRMED)
            return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        safeStock(state, true); return snapshot(state);
    }
    public PaymentWorkSnapshot failure(UUID id, PaymentWorkPhase expected, PaymentFailureCategory category) { return failure(id, expected, category, null); }
    public PaymentWorkSnapshot failure(UUID id, PaymentWorkPhase expected, PaymentFailureCategory category, UUID token) {
        var state = lock(id);
        requireClaim(state, token);
        if (eligible(state, expected)) {
            if (category == PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE || category == PaymentFailureCategory.INCONSISTENT_PROOF)
                return review(state, category);
            state.payment.failure(category);
        }
        return snapshot(state);
    }
    private void safeStock(Locked state, boolean absent) {
        state.payment.proveStockSafe(absent); state.payment.moveTo(PaymentWorkPhase.CANCEL_PAYMENT_PENDING); updatePublic(state);
    }
    private void complete(Locked state) {
        if (state.payment.isStockSafe() || state.payment.getPaymentStatus() != OrderPaymentStatus.CAPTURED
                || state.stock.getReservationStatus() != StockReservationStatus.CONFIRMED) throw new IllegalStateException("Missing completion proofs");
        state.order.observePayment(OrderPaymentStatus.CAPTURED); state.order.confirm();
        state.stock.completePayment(true, false); state.payment.moveTo(PaymentWorkPhase.DONE);
    }
    private void updatePublic(Locked state) {
        var work = state.payment;
        state.order.observePayment(work.getCapturedAt() == null ? OrderPaymentStatus.PROCESSING
                : work.isStockSafe() || work.getPaymentStatus() == OrderPaymentStatus.REFUND_PENDING || work.getPaymentStatus() == OrderPaymentStatus.REFUNDED
                    ? OrderPaymentStatus.REFUND_PENDING : OrderPaymentStatus.CAPTURED);
    }
    private PaymentWorkSnapshot review(Locked state, PaymentFailureCategory category) {
        state.payment.moveTo(PaymentWorkPhase.REVIEW_REQUIRED); state.payment.failure(category);
        state.order.reviewPayment(); return snapshot(state);
    }
    private boolean eligible(Locked state, PaymentWorkPhase expected) {
        if (state.payment.getPhase() != expected || expected.terminal() || expected == PaymentWorkPhase.REVIEW_REQUIRED) return false;
        if (state.stock.getPhase() != StockWorkPhase.PAYMENT_PENDING || state.order.getStatus() != OrderStatus.PENDING_PAYMENT
                || state.order.getTotalAmount().compareTo(state.payment.getAmount()) != 0
                || !state.order.getCurrency().equals(state.payment.getCurrency())) throw new IllegalStateException("Payment handoff is inconsistent");
        return true;
    }
    private void requireClaim(Locked state, UUID token) {
        if (!state.payment.canExecute(token, Instant.now())) throw new PaymentClaimLostException();
    }
    private boolean valid(Locked state, StockReservationObservation proof) {
        if (proof == null || !state.order.getId().equals(proof.orderId()) || proof.status() == null || proof.createdAt() == null
                || proof.expiresAt() == null || proof.updatedAt() == null || !proof.createdAt().isBefore(proof.expiresAt())
                || proof.updatedAt().isBefore(proof.createdAt()) || proof.items() == null) return false;
        var expected = new HashMap<UUID, Integer>(); var actual = new HashMap<UUID, Integer>();
        state.order.getItems().forEach(item -> expected.put(item.getProductId(), item.getQuantity()));
        for (var item : proof.items())
            if (item == null || item.productId() == null || item.quantity() <= 0 || actual.putIfAbsent(item.productId(), item.quantity()) != null) return false;
        return !expected.isEmpty() && expected.equals(actual);
    }
    private Locked lock(UUID id) {
        var reservation = stock.findWithLockByOrderId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock work not found"));
        var order = Objects.requireNonNull(entityManager.find(Order.class, id, LockModeType.PESSIMISTIC_WRITE));
        var payment = payments.findWithLockByOrderId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment work not found"));
        return new Locked(reservation, order, payment);
    }
    private PaymentWorkSnapshot snapshot(Locked state) { payments.flush(); return PaymentWorkSnapshot.from(state.payment, state.stock, state.order); }
}
