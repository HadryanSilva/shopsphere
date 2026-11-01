package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.OrderPaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record PaymentObservation(UUID orderId, OrderPaymentStatus status, BigDecimal amount, String currency,
                                 Instant createdAt, Instant updatedAt, Instant capturedAt, Instant refundedAt) {
    public boolean validFor(UUID id, BigDecimal expectedAmount, String expectedCurrency) {
        if (!id.equals(orderId) || status == null || status == OrderPaymentStatus.REVIEW_REQUIRED || amount == null
                || !expectedCurrency.equals(currency) || amount.compareTo(expectedAmount) != 0
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) return false;
        try { if (amount.signum() <= 0 || amount.setScale(2, RoundingMode.UNNECESSARY).precision() > 19) return false; }
        catch (ArithmeticException invalid) { return false; }
        if (capturedAt != null && (capturedAt.isBefore(createdAt) || updatedAt.isBefore(capturedAt))
                || refundedAt != null && (capturedAt == null || refundedAt.isBefore(capturedAt) || updatedAt.isBefore(refundedAt))) return false;
        return switch (status) {
            case PROCESSING, DECLINED, CANCELLED -> capturedAt == null && refundedAt == null;
            case CAPTURED, REFUND_PENDING -> capturedAt != null && refundedAt == null;
            case REFUNDED -> capturedAt != null && refundedAt != null;
            case REVIEW_REQUIRED -> false;
        };
    }
}
