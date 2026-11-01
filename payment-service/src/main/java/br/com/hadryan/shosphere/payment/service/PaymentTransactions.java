package br.com.hadryan.shosphere.payment.service;

import br.com.hadryan.shosphere.payment.mapper.request.PaymentRequest;
import br.com.hadryan.shosphere.payment.mapper.response.PaymentResponse;
import br.com.hadryan.shosphere.payment.model.*;
import br.com.hadryan.shosphere.payment.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentTransactions {
    private final PaymentRepository payments;
    private final PaymentMovementRepository movements;
    private final PaymentSimulator simulator;

    public record Registration(boolean created) {}

    // Intention/cancellation decision must commit before attempting a financial result.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Registration register(String client, UUID id, PaymentRequest request, boolean cancel) {
        var amount = request.normalizedAmount();
        if (id == null || client == null || client.isBlank() || client.length() > 255)
            throw new IllegalArgumentException("Invalid payment identity");
        boolean created = payments.insertIfAbsent(id, client, amount, request.currency(),
                Instant.now().truncatedTo(ChronoUnit.MICROS)) == 1;
        var payment = owned(payments.findWithLockByOrderId(id), client);
        sameTerms(payment, request);
        if (cancel) payment.cancel();
        else requireCaptureAllowed(payment);
        return new Registration(created);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResponse capture(String client, UUID id, PaymentRequest request) {
        var payment = owned(payments.findWithLockByOrderId(id), client);
        sameTerms(payment, request);
        requireCaptureAllowed(payment);
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            switch (simulator.capture()) {
                case CAPTURED -> {
                    var now = payment.nextInstant();
                    movements.saveAndFlush(new PaymentMovement(payment, PaymentMovement.Kind.CAPTURE, now));
                    payment.capture(now);
                }
                case DECLINED -> payment.decline();
                case PENDING -> { }
                case UNAVAILABLE -> throw unavailable();
            }
        }
        return PaymentResponse.from(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResponse refund(String client, UUID id, PaymentRequest request) {
        var payment = owned(payments.findWithLockByOrderId(id), client);
        sameTerms(payment, request);
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            switch (simulator.refund()) {
                case REFUNDED -> {
                    var now = payment.nextInstant();
                    movements.saveAndFlush(new PaymentMovement(payment, PaymentMovement.Kind.REFUND, now));
                    payment.refund(now);
                }
                case PENDING -> { }
                case UNAVAILABLE -> throw unavailable();
            }
        }
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PaymentResponse get(String client, UUID id) {
        return PaymentResponse.from(owned(payments.findById(id), client));
    }

    private Payment owned(java.util.Optional<Payment> payment, String client) {
        return payment.filter(row -> row.getServiceClientId().equals(client))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }
    private void sameTerms(Payment payment, PaymentRequest request) {
        if (payment.getAmount().compareTo(request.normalizedAmount()) != 0 || !payment.getCurrency().equals(request.currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to a different payment intent");
    }
    private void requireCaptureAllowed(Payment payment) {
        if (payment.isCancelRequested()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment capture is closed");
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development simulator unavailable");
    }
}
