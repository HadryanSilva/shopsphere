package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import br.com.hadryan.shosphere.inventory.service.AdjustStockUseCase;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdjustStockUseCaseImpl implements AdjustStockUseCase {
    private final StockAdjustmentIdempotency idempotency;
    private final AdjustStockTransaction transaction;

    @Override
    public AdjustmentResponse execute(String actor, UUID key, UUID productId, AdjustStockRequest request) {
        var existing = idempotency.findExisting(actor, key, productId, request);
        if (existing.isPresent()) return existing.get();
        try {
            return transaction.execute(actor, key, productId, request);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_inventory_movements_actor_type_key".equals(violation.getConstraintName())) {
                    return idempotency.findExisting(actor, key, productId, request).orElseThrow(() -> exception);
                }
            }
            throw exception;
        }
    }
}
