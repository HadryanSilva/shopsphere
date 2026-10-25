package br.com.hadryan.shosphere.inventory.repository;

import br.com.hadryan.shosphere.inventory.model.InventoryMovement;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {
    Optional<InventoryMovement> findByIdAndProductIdAndMovementType(UUID id, UUID productId, InventoryMovementType movementType);

    Optional<InventoryMovement> findByActorSubjectAndMovementTypeAndIdempotencyKey(
            String actorSubject, InventoryMovementType movementType, UUID idempotencyKey);
}
