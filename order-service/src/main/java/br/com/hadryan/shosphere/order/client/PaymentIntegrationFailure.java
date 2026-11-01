package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.model.enums.PaymentFailureCategory;

public class PaymentIntegrationFailure extends RuntimeException {
    private final int status;
    private final PaymentFailureCategory category;
    public PaymentIntegrationFailure(int status, PaymentFailureCategory category) {
        super("Payment request failed"); this.status = status; this.category = category;
    }
    public int status() { return status; }
    public PaymentFailureCategory category() { return category; }
}
