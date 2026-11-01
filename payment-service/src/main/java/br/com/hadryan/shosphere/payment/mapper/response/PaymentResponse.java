package br.com.hadryan.shosphere.payment.mapper.response;

import br.com.hadryan.shosphere.payment.model.Payment;
import br.com.hadryan.shosphere.payment.model.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentResponse(UUID orderId, PaymentStatus status, BigDecimal amount, String currency,
                              Instant createdAt, Instant updatedAt, Instant capturedAt, Instant refundedAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getOrderId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getCreatedAt(), payment.getUpdatedAt(), payment.getCapturedAt(), payment.getRefundedAt());
    }
}
