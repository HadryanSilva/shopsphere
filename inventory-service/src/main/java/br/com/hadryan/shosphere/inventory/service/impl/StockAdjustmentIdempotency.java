package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryMovement;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
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
public class StockAdjustmentIdempotency {
    private final InventoryMovementRepository movements;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<AdjustmentResponse> findExisting(String actor, UUID key, UUID productId, AdjustStockRequest request) {
        return movements.findByActorSubjectAndMovementTypeAndIdempotencyKey(actor, InventoryMovementType.ADJUSTMENT, key)
                .map(movement -> matchingResponse(movement, productId, request));
    }

    static AdjustmentResponse matchingResponse(InventoryMovement movement, UUID productId, AdjustStockRequest request) {
        if (!movement.getProductId().equals(productId) || movement.getDelta() != request.delta()
                || !movement.getReason().equals(request.reason())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different adjustment");
        }
        return AdjustmentResponse.fromMovement(movement);
    }
}
