package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.RegisterStockUseCase;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegisterStockUseCaseImpl implements RegisterStockUseCase {
    private final StockRegistrationIdempotency idempotency;
    private final InventoryStockRepository stocks;
    private final CatalogClient catalog;
    private final RegisterStockTransaction transaction;

    @Override
    public StockResponse execute(String actor, UUID key, RegisterStockRequest request) {
        var existing = idempotency.findExisting(actor, key, request);
        if (existing.isPresent()) return existing.get();
        if (stocks.existsById(request.productId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock already exists");
        }
        catalog.requireProduct(request.productId());
        try {
            return transaction.execute(actor, key, request);
        } catch (DataIntegrityViolationException exception) {
            var constraint = registrationConstraint(exception);
            if (constraint == null) throw exception;
            var replay = idempotency.findExisting(actor, key, request);
            if (replay.isPresent()) return replay.get();
            if (constraint.equals("pk_inventory_stock")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock already exists", exception);
            }
            throw exception;
        }
    }

    private String registrationConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                var name = violation.getConstraintName();
                if ("pk_inventory_stock".equals(name) || "uk_inventory_movements_actor_type_key".equals(name)) return name;
            }
        }
        return null;
    }
}
