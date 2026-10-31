package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.model.enums.CheckoutRetryCategory;

public class CheckoutIntegrationFailure extends RuntimeException {
    private final int status;
    private final CheckoutRetryCategory category;
    private final boolean authenticationConfiguration;

    public CheckoutIntegrationFailure(int status, CheckoutRetryCategory category) {
        this(status, category, false);
    }
    public CheckoutIntegrationFailure(int status, CheckoutRetryCategory category, boolean authenticationConfiguration) {
        super("Checkout upstream request failed");
        this.status = status;
        this.category = category;
        this.authenticationConfiguration = authenticationConfiguration;
    }
    public int status() { return status; }
    public CheckoutRetryCategory category() { return category; }
    public boolean authenticationConfiguration() { return authenticationConfiguration; }
}
