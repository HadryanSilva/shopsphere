package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "stock_work")
public class StockWork {
    @Id @Column(updatable = false)
    private UUID orderId;
    @Column(nullable = false, updatable = false)
    private UUID checkoutId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private StockWorkPhase phase;
    @Column(nullable = false)
    private boolean abortRequested;
    @Enumerated(EnumType.STRING) @Column(length = 40)
    private StockWorkReason reason;
    @Enumerated(EnumType.STRING) @Column(length = 30)
    private StockReservationStatus reservationStatus;
    private Instant reservationCreatedAt;
    private Instant reservationExpiresAt;
    private Instant reservationUpdatedAt;
    private Instant nextAttemptAt;
    private UUID claimToken;
    private Instant claimedUntil;
    private Instant lastWarningAt;
    @Column(nullable = false)
    private long attemptCount;
    @Enumerated(EnumType.STRING) @Column(length = 40)
    private StockFailureCategory lastFailureCategory;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected StockWork() {}

    public static StockWork waiting(UUID orderId, UUID checkoutId) {
        var work = new StockWork();
        work.orderId = Objects.requireNonNull(orderId);
        work.checkoutId = Objects.requireNonNull(checkoutId);
        work.phase = StockWorkPhase.WAITING_CART;
        work.createdAt = now();
        work.updatedAt = work.createdAt;
        work.nextAttemptAt = work.createdAt;
        return work;
    }

    public void cartCompleted() {
        if (phase == StockWorkPhase.WAITING_CART) {
            phase = StockWorkPhase.RESERVE_PENDING;
            progress();
        }
    }

    public void requestAbort(StockWorkReason reason) {
        Objects.requireNonNull(reason);
        if (!isManagedByStock() || phase == StockWorkPhase.REVIEW_REQUIRED) return;
        if (!abortRequested) {
            abortRequested = true;
            this.reason = reason;
            phase = StockWorkPhase.ABORT_PENDING;
            progress();
        }
    }

    // Identity/items/timestamps are validated against the persisted order by the transaction service.
    public void observe(StockReservationStatus status, Instant createdAt, Instant expiresAt,
                        Instant remoteUpdatedAt, Instant nextAttemptAt) {
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(remoteUpdatedAt);
        Objects.requireNonNull(nextAttemptAt);
        if (!isManagedByStock() || phase == StockWorkPhase.REVIEW_REQUIRED) return;
        if (phase == StockWorkPhase.WAITING_CART) throw new IllegalStateException("Cart is not completed");
        if (!createdAt.isBefore(expiresAt) || remoteUpdatedAt.isBefore(createdAt)
                || reservationCreatedAt != null && (!reservationCreatedAt.equals(createdAt)
                    || !reservationExpiresAt.equals(expiresAt))) {
            review(StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
            return;
        }
        if (status == StockReservationStatus.CONFIRMED) {
            review(StockFailureCategory.UNEXPECTED_CONFIRMED);
            return;
        }
        if (reservationUpdatedAt != null && remoteUpdatedAt.isBefore(reservationUpdatedAt)) return;
        // An ACTIVE response from a request already in flight cannot reverse a durable abort decision.
        if (abortRequested && status == StockReservationStatus.ACTIVE) return;
        reservationStatus = status;
        reservationCreatedAt = createdAt;
        reservationExpiresAt = expiresAt;
        reservationUpdatedAt = remoteUpdatedAt;
        if (status == StockReservationStatus.ACTIVE) {
            phase = StockWorkPhase.RESERVED;
            progress();
            this.nextAttemptAt = micros(nextAttemptAt);
        } else {
            if (reason == null) reason = status == StockReservationStatus.EXPIRED
                    ? StockWorkReason.RESERVATION_EXPIRED : StockWorkReason.RESERVATION_RELEASED;
            close();
        }
    }

    public void abortedWithoutReservation() {
        if (!isManagedByStock()) return;
        if (phase != StockWorkPhase.ABORT_PENDING || reservationStatus != null)
            throw new IllegalStateException("Abort proof conflicts with local state");
        close();
    }

    public void review(StockFailureCategory category) {
        Objects.requireNonNull(category);
        if (!isManagedByStock()) return;
        phase = StockWorkPhase.REVIEW_REQUIRED;
        lastFailureCategory = category;
        updatedAt = now();
        nextAttemptAt = updatedAt;
    }

    public void recordFailure(StockWorkPhase expected, StockFailureCategory category, Instant nextAttemptAt) {
        Objects.requireNonNull(category);
        Objects.requireNonNull(nextAttemptAt);
        if (phase != expected || !isManagedByStock()) return;
        var attempts = Math.addExact(attemptCount, 1L);
        this.nextAttemptAt = micros(nextAttemptAt);
        attemptCount = attempts;
        lastFailureCategory = category;
        updatedAt = now();
    }

    public void claim(UUID token, Instant until) {
        if (!isManagedByStock()) throw new IllegalStateException("Stock work is not managed by stock");
        claimToken = Objects.requireNonNull(token);
        claimedUntil = micros(Objects.requireNonNull(until));
    }

    public boolean ownsClaim(UUID token) { return token != null && token.equals(claimToken); }

    public void releaseClaim() {
        claimToken = null;
        claimedUntil = null;
    }

    public void schedule(Instant nextAttemptAt) {
        if (isManagedByStock()) this.nextAttemptAt = micros(Objects.requireNonNull(nextAttemptAt));
    }

    public void warned(Instant at) { if (isManagedByStock()) lastWarningAt = micros(Objects.requireNonNull(at)); }

    public boolean isManagedByStock() { return phase != StockWorkPhase.CLOSED && phase != StockWorkPhase.PAYMENT_PENDING; }

    public void transferToPayment() {
        if (phase != StockWorkPhase.RESERVED || abortRequested || reason != null || reservationStatus != StockReservationStatus.ACTIVE)
            throw new IllegalStateException("Stock work is not eligible for payment");
        phase = StockWorkPhase.PAYMENT_PENDING;
        nextAttemptAt = null;
        lastFailureCategory = null;
        releaseClaim();
        updatedAt = now();
    }

    private void close() {
        phase = StockWorkPhase.CLOSED;
        updatedAt = now();
        nextAttemptAt = null;
        lastFailureCategory = null;
    }

    private void progress() {
        updatedAt = now();
        nextAttemptAt = updatedAt;
        lastFailureCategory = null;
    }

    private static Instant now() { return micros(Instant.now()); }
    private static Instant micros(Instant time) { return time.truncatedTo(ChronoUnit.MICROS); }
}
