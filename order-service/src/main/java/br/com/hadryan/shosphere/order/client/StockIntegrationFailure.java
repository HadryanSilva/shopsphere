package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.model.enums.StockFailureCategory;

public class StockIntegrationFailure extends RuntimeException {
    private final int status;
    private final StockFailureCategory category;
    public StockIntegrationFailure(int status, StockFailureCategory category) {
        super("Inventory request failed");
        this.status = status;
        this.category = category;
    }
    public int status() { return status; }
    public StockFailureCategory category() { return category; }
}
