package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.PaymentWorkPhase;
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

    protected PaymentWork() {}

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
