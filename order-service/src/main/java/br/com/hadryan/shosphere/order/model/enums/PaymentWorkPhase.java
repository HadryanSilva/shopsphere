package br.com.hadryan.shosphere.order.model.enums;

public enum PaymentWorkPhase {
    CAPTURE_PENDING, CONFIRM_PENDING, RESOLUTION_PENDING, CANCEL_PAYMENT_PENDING, DONE, CLOSED, REVIEW_REQUIRED;

    public boolean terminal() { return this == DONE || this == CLOSED; }
}
