package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.ShippingAddress;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutIntentService {
    private final CheckoutIntentTransaction transaction;

    public CheckoutIntentSnapshot start(UUID customerId, UUID key, ShippingAddress address) {
        try {
            return transaction.start(customerId, key, address);
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_checkout_intents_customer_key".equals(violation.getConstraintName()))
                    // The losing transaction has already rolled back at the proxy boundary.
                    return transaction.start(customerId, key, address);
            }
            throw failure;
        }
    }
}
