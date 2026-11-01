package br.com.hadryan.shosphere.payment.service;

public interface PaymentSimulator {
    enum CaptureResult { CAPTURED, DECLINED, PENDING, UNAVAILABLE }
    enum RefundResult { REFUNDED, PENDING, UNAVAILABLE }
    CaptureResult capture();
    RefundResult refund();
}
