package br.com.hadryan.shosphere.inventory.exception;

public class StockAdjustmentConflictException extends RuntimeException {
    public StockAdjustmentConflictException() {
        super("Adjustment would violate stock balances or quantity limits");
    }
}
