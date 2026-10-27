package br.com.hadryan.shosphere.cart.repository;

import br.com.hadryan.shosphere.cart.model.CartCheckout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface CartCheckoutRepository extends JpaRepository<CartCheckout, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CartCheckout> findWithLockByCheckoutId(UUID checkoutId);
}
