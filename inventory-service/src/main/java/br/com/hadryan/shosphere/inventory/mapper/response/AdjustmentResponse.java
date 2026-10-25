package br.com.hadryan.shosphere.inventory.mapper.response;

import br.com.hadryan.shosphere.inventory.model.InventoryMovement;

import java.time.Instant;
import java.util.UUID;

public record AdjustmentResponse(UUID id, UUID productId, int delta, String reason,
                                 int onHandAfter, int reservedAfter, Instant createdAt) {
    public static AdjustmentResponse fromMovement(InventoryMovement movement) {
        return new AdjustmentResponse(movement.getId(), movement.getProductId(), movement.getDelta(),
                movement.getReason(), movement.getOnHandAfter(), movement.getReservedAfter(), movement.getCreatedAt());
    }
}
