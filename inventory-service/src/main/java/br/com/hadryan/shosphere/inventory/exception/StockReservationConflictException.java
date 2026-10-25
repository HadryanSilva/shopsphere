package br.com.hadryan.shosphere.inventory.exception;

public class StockReservationConflictException extends RuntimeException {
    public StockReservationConflictException() {
        super("Insufficient available stock");
    }
}
