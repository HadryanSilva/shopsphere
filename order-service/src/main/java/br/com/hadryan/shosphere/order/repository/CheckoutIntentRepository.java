package br.com.hadryan.shosphere.order.repository;

import br.com.hadryan.shosphere.order.model.CheckoutIntent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutIntentRepository extends JpaRepository<CheckoutIntent, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutIntent> findWithLockByCheckoutId(UUID checkoutId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutIntent> findWithLockByCustomerIdAndIdempotencyKey(UUID customerId, String key);
}
