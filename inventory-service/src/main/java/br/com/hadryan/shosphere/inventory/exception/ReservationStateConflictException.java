package br.com.hadryan.shosphere.inventory.exception;

public class ReservationStateConflictException extends RuntimeException {
    public ReservationStateConflictException() {
        super("Reservation state or deadline conflicts with the requested operation");
    }
}
