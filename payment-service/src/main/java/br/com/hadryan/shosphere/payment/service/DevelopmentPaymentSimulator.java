package br.com.hadryan.shosphere.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Local decisions only. Financial effects are the database movements in the same transaction as their result. */
@Component
public class DevelopmentPaymentSimulator implements PaymentSimulator {
    private final boolean enabled;
    private final CaptureResult captureResult;
    private final RefundResult refundResult;

    public DevelopmentPaymentSimulator(@Value("${payment.simulator.enabled:false}") boolean enabled,
            @Value("${payment.simulator.capture-result:CAPTURED}") CaptureResult captureResult,
            @Value("${payment.simulator.refund-result:REFUNDED}") RefundResult refundResult) {
        this.enabled = enabled;
        this.captureResult = captureResult;
        this.refundResult = refundResult;
    }
    @Override public CaptureResult capture() { requireEnabled(); return captureResult; }
    @Override public RefundResult refund() { requireEnabled(); return refundResult; }
    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development simulator is disabled");
    }
}
