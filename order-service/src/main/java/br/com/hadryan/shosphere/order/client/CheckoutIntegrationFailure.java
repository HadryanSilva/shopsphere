package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.model.enums.CheckoutRetryCategory;

public class CheckoutIntegrationFailure extends RuntimeException {
    private final int status;
    private final CheckoutRetryCategory category;

    public CheckoutIntegrationFailure(int status, CheckoutRetryCategory category) {
        super("Checkout upstream request failed");
        this.status = status;
        this.category = category;
    }
    public int status() { return status; }
    public CheckoutRetryCategory category() { return category; }
}
