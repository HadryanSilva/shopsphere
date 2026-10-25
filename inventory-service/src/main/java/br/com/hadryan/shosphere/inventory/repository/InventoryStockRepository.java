package br.com.hadryan.shosphere.inventory.repository;

import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface InventoryStockRepository extends JpaRepository<InventoryStock, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryStock> findWithLockByProductId(UUID productId);
}
