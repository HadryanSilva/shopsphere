package br.com.hadryan.shosphere.payment.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payment_movements")
@IdClass(PaymentMovement.Key.class)
public class PaymentMovement {
    public enum Kind { CAPTURE, REFUND }
    @Id @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Id @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 20) private Kind kind;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected PaymentMovement() {}
    public PaymentMovement(Payment payment, Kind kind, Instant at) {
        orderId = payment.getOrderId();
        this.kind = Objects.requireNonNull(kind);
        amount = payment.getAmount();
        currency = payment.getCurrency();
        createdAt = Objects.requireNonNull(at);
    }

    public static class Key implements Serializable {
        private UUID orderId;
        private Kind kind;
        public Key() {}
        public Key(UUID orderId, Kind kind) { this.orderId = orderId; this.kind = kind; }
        @Override public boolean equals(Object other) {
            return other instanceof Key id && Objects.equals(orderId, id.orderId) && kind == id.kind;
        }
        @Override public int hashCode() { return Objects.hash(orderId, kind); }
    }
}
