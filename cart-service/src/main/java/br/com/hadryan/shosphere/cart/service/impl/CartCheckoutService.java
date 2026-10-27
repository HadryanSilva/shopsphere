package br.com.hadryan.shosphere.cart.service.impl;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class CartCheckoutService {
    private final CartCheckoutTransaction transaction;

    public CartCheckoutTransaction.Preparation prepare(UUID id, UUID customerId, String serviceClient) {
        return resolveCreationRace(() -> transaction.prepare(id, customerId, serviceClient));
    }

    public CartCheckoutSnapshot get(UUID id, String serviceClient) {
        return transaction.get(id, serviceClient);
    }

    public CartCheckoutSnapshot complete(UUID id, UUID orderId, String serviceClient) {
        return transaction.complete(id, orderId, serviceClient);
    }

    public CartCheckoutSnapshot abort(UUID id, UUID customerId, String serviceClient) {
        return resolveCreationRace(() -> transaction.abort(id, customerId, serviceClient));
    }

    private <T> T resolveCreationRace(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "pk_cart_checkouts".equals(violation.getConstraintName())) {
                    // The proxy has rolled back the losing transaction before the reread.
                    return operation.get();
                }
            }
            throw failure;
        }
    }
}
