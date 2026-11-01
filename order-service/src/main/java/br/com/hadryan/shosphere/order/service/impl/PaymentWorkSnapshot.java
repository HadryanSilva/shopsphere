package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentWorkSnapshot(UUID orderId, PaymentWorkPhase phase, BigDecimal amount, String currency,
                                  PaymentObservation payment, boolean stockSafe, boolean stockAbsent,
                                  PaymentFailureCategory lastFailureCategory, long version, StockWorkSnapshot stock) {
    static PaymentWorkSnapshot from(PaymentWork payment, StockWork stock, Order order) {
        return new PaymentWorkSnapshot(payment.getOrderId(), payment.getPhase(), payment.getAmount(), payment.getCurrency(),
                payment.paymentProof(), payment.isStockSafe(), payment.isStockAbsent(), payment.getLastFailureCategory(),
                payment.getVersion(), StockWorkSnapshot.from(stock, order));
    }
}
