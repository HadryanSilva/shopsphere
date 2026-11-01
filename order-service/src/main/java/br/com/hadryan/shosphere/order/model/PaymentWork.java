package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "payment_work")
public class PaymentWork {
    @Id @Column(nullable = false, updatable = false) private UUID orderId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PaymentWorkPhase phase;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    private Instant nextAttemptAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;
    @Enumerated(EnumType.STRING) @Column(length = 30) private OrderPaymentStatus paymentStatus;
    private Instant paymentCreatedAt;
    private Instant paymentUpdatedAt;
    private Instant capturedAt;
    private Instant refundedAt;
    @Column(nullable = false) private boolean stockSafe;
    @Column(nullable = false) private boolean stockAbsent;
    @Enumerated(EnumType.STRING) @Column(length = 40) private PaymentFailureCategory lastFailureCategory;
    private UUID claimToken;
    private Instant claimedUntil;
    @Column(nullable = false) private long attemptCount;
    private Instant lastWarningAt;

    protected PaymentWork() {}

    public PaymentObservation paymentProof() {
        return paymentStatus == null ? null : new PaymentObservation(orderId, paymentStatus, amount, currency,
                paymentCreatedAt, paymentUpdatedAt, capturedAt, refundedAt);
    }

    public void remember(PaymentObservation proof) {
        if (proof == null || !proof.validFor(orderId, amount, currency)) throw new IllegalArgumentException("Invalid payment proof");
        if (paymentCreatedAt != null && !paymentCreatedAt.equals(proof.createdAt())) throw new IllegalArgumentException("Payment identity changed");
        if (paymentUpdatedAt != null && proof.updatedAt().isBefore(paymentUpdatedAt)) return;
        if (capturedAt != null && !capturedAt.equals(proof.capturedAt()) || refundedAt != null && !refundedAt.equals(proof.refundedAt()))
            throw new IllegalArgumentException("Payment proof changed");
        if (paymentStatus != null && paymentStatus != proof.status()) {
            boolean allowed = switch (paymentStatus) {
                case PROCESSING -> true;
                case CAPTURED -> proof.status() == OrderPaymentStatus.REFUND_PENDING || proof.status() == OrderPaymentStatus.REFUNDED;
                case REFUND_PENDING -> proof.status() == OrderPaymentStatus.REFUNDED;
                default -> false;
            };
            if (!allowed) throw new IllegalArgumentException("Payment proof regressed");
        }
        if (paymentStatus == proof.status() && Objects.equals(paymentUpdatedAt, proof.updatedAt())) return;
        paymentStatus = proof.status(); paymentCreatedAt = proof.createdAt(); paymentUpdatedAt = proof.updatedAt();
        capturedAt = proof.capturedAt(); refundedAt = proof.refundedAt(); touch();
    }

    public void moveTo(PaymentWorkPhase next) {
        if (phase == next) return;
        boolean allowed = next == PaymentWorkPhase.REVIEW_REQUIRED && phase != PaymentWorkPhase.DONE || switch (phase) {
            case CAPTURE_PENDING -> next == PaymentWorkPhase.CONFIRM_PENDING || next == PaymentWorkPhase.RESOLUTION_PENDING || next == PaymentWorkPhase.CANCEL_PAYMENT_PENDING;
            case CONFIRM_PENDING -> next == PaymentWorkPhase.RESOLUTION_PENDING || next == PaymentWorkPhase.CANCEL_PAYMENT_PENDING || next == PaymentWorkPhase.DONE;
            case RESOLUTION_PENDING -> next == PaymentWorkPhase.CANCEL_PAYMENT_PENDING || next == PaymentWorkPhase.DONE;
            case CANCEL_PAYMENT_PENDING -> next == PaymentWorkPhase.CLOSED;
            default -> false;
        };
        if (!allowed) throw new IllegalStateException("Invalid payment work transition");
        phase = next; lastFailureCategory = null; touch(); nextAttemptAt = next.terminal() ? null : updatedAt;
    }
    public void proveStockSafe(boolean absent) {
        if (stockSafe && stockAbsent != absent) throw new IllegalArgumentException("Stock proof changed");
        if (!stockSafe) { stockSafe = true; stockAbsent = absent; touch(); }
    }
    public void failure(PaymentFailureCategory category) {
        attemptCount = Math.addExact(attemptCount, 1L);
        lastFailureCategory = Objects.requireNonNull(category); touch();
    }
    public void clearFailure() { lastFailureCategory = null; }
    public void claim(UUID token, Instant until) {
        if (phase.terminal()) throw new IllegalStateException("Payment work is terminal");
        claimToken = Objects.requireNonNull(token); claimedUntil = Objects.requireNonNull(until).truncatedTo(ChronoUnit.MICROS);
    }
    public boolean ownsClaim(UUID token) { return token != null && token.equals(claimToken); }
    public boolean canExecute(UUID token, Instant now) {
        return token == null ? claimToken == null : ownsClaim(token) && claimedUntil.isAfter(now);
    }
    public void releaseClaim() { claimToken = null; claimedUntil = null; }
    public void schedule(Instant at) { if (!phase.terminal()) nextAttemptAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS); }
    public void warned(Instant at) { lastWarningAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS); }
    private void touch() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = now.isBefore(updatedAt) ? updatedAt : now;
    }

    public static PaymentWork start(Order order) {
        var amount = order.getTotalAmount();
        if (amount == null || amount.signum() <= 0 || !"EUR".equals(order.getCurrency())
                || (long) amount.precision() - amount.scale() > 17) throw new IllegalArgumentException("Invalid payment terms");
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (amount.precision() > 19) throw new IllegalArgumentException("Invalid payment amount");
        var work = new PaymentWork();
        work.orderId = Objects.requireNonNull(order.getId());
        work.amount = amount;
        work.currency = order.getCurrency();
        work.phase = PaymentWorkPhase.CAPTURE_PENDING;
        work.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        work.updatedAt = work.createdAt;
        work.nextAttemptAt = work.createdAt;
        return work;
    }
}
