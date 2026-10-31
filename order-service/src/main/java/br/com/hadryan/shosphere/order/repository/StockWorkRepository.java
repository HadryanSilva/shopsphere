package br.com.hadryan.shosphere.order.repository;

import br.com.hadryan.shosphere.order.model.StockWork;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import java.util.UUID;

public interface StockWorkRepository extends JpaRepository<StockWork, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StockWork> findWithLockByOrderId(UUID orderId);
}
