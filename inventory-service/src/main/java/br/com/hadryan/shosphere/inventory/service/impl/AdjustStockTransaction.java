package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryMovement;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdjustStockTransaction {
    private final InventoryStockRepository stocks;
    private final InventoryMovementRepository movements;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdjustmentResponse execute(String actor, UUID key, UUID productId, AdjustStockRequest request) {
        var stock = stocks.findWithLockByProductId(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found"));
        var existing = movements.findByActorSubjectAndMovementTypeAndIdempotencyKey(actor, InventoryMovementType.ADJUSTMENT, key);
        if (existing.isPresent()) return StockAdjustmentIdempotency.matchingResponse(existing.get(), productId, request);
        stock.adjust(request.delta());
        stocks.saveAndFlush(stock);
        return AdjustmentResponse.fromMovement(movements.saveAndFlush(
                InventoryMovement.adjustment(stock, actor, key, request.delta(), request.reason())));
    }
}
