package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.service.GetStockAdjustmentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetStockAdjustmentUseCaseImpl implements GetStockAdjustmentUseCase {
    private final InventoryMovementRepository movements;

    @Override
    @Transactional(readOnly = true)
    public AdjustmentResponse execute(UUID productId, UUID id) {
        return AdjustmentResponse.fromMovement(movements.findByIdAndProductIdAndMovementType(id, productId,
                        InventoryMovementType.ADJUSTMENT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjustment not found")));
    }
}
