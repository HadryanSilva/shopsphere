package br.com.hadryan.shosphere.payment.model;

import jakarta.persistence.*;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Getter
@Entity
@Table(name = "payments")
public class Payment {
    @Id @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Column(name = "service_client_id", nullable = false, updatable = false, length = 255)
    private String serviceClientId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private PaymentStatus status;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;
    @Column(name = "captured_at") private Instant capturedAt;
    @Column(name = "refunded_at") private Instant refundedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private Long version;

    protected Payment() {}

    public void cancel() {
        if (status == PaymentStatus.PROCESSING) {
            cancelRequested = true;
            status = PaymentStatus.CANCELLED;
            updatedAt = nextInstant();
        } else if (status == PaymentStatus.CAPTURED) {
            cancelRequested = true;
            status = PaymentStatus.REFUND_PENDING;
            updatedAt = nextInstant();
        }
    }

    public void capture(Instant at) {
        if (status != PaymentStatus.PROCESSING || cancelRequested) throw new IllegalStateException("Capture is not pending");
        status = PaymentStatus.CAPTURED;
        capturedAt = at;
        updatedAt = at;
    }

    public void decline() {
        if (status != PaymentStatus.PROCESSING || cancelRequested) throw new IllegalStateException("Capture is not pending");
        status = PaymentStatus.DECLINED;
        updatedAt = nextInstant();
    }

    public void refund(Instant at) {
        if (status != PaymentStatus.REFUND_PENDING || !cancelRequested) throw new IllegalStateException("Refund is not pending");
        status = PaymentStatus.REFUNDED;
        refundedAt = at;
        updatedAt = at;
    }

    public Instant nextInstant() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(updatedAt) ? updatedAt : now;
    }
}
