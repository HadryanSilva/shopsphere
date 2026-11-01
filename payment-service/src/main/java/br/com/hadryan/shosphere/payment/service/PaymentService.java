package br.com.hadryan.shosphere.payment.service;

import br.com.hadryan.shosphere.payment.mapper.request.PaymentRequest;
import br.com.hadryan.shosphere.payment.mapper.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentTransactions transactions;
    public record Capture(boolean created, PaymentResponse payment) {}

    public Capture capture(String client, UUID id, PaymentRequest request) {
        var registration = transactions.register(client, id, request, false);
        return new Capture(registration.created(), transactions.capture(client, id, request));
    }
    public PaymentResponse cancel(String client, UUID id, PaymentRequest request) {
        transactions.register(client, id, request, true);
        return transactions.refund(client, id, request);
    }
    public PaymentResponse get(String client, UUID id) { return transactions.get(client, id); }
}
