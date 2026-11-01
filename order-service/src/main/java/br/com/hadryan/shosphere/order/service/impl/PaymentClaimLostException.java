package br.com.hadryan.shosphere.order.service.impl;

public class PaymentClaimLostException extends RuntimeException {
    public PaymentClaimLostException() { super("Payment claim is no longer current"); }
}
