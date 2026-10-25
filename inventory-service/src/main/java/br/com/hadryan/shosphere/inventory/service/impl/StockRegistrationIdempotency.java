package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockRegistrationIdempotency {
    private final InventoryMovementRepository movements;
    private final InventoryStockRepository stocks;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<StockResponse> findExisting(String actor, UUID key, RegisterStockRequest request) {
        return movements.findByActorSubjectAndMovementTypeAndIdempotencyKey(actor, InventoryMovementType.INITIAL, key)
                .map(movement -> {
                    if (!movement.getProductId().equals(request.productId()) || movement.getDelta() != request.onHand()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different registration");
                    }
                    return StockResponse.fromStock(stocks.findById(movement.getProductId()).orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Recorded stock is unavailable")));
                });
    }
}
