package br.com.hadryan.shosphere.order.repository;

import br.com.hadryan.shosphere.order.model.PaymentWork;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;

public interface PaymentWorkRepository extends JpaRepository<PaymentWork, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentWork> findWithLockByOrderId(UUID orderId);
}
