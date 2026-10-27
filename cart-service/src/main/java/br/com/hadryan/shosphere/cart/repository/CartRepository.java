package br.com.hadryan.shosphere.cart.repository;

import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CartRepository extends JpaRepository<Cart, UUID> {

    // Call only after locking the cart root when deciding whether to mutate it.
    @Query(value = "SELECT EXISTS (SELECT 1 FROM {h-schema}cart_checkouts WHERE cart_id = :cartId AND status = 'PREPARED')",
            nativeQuery = true)
    boolean isCheckoutPrepared(@Param("cartId") UUID cartId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findWithLockById(UUID id);

    @EntityGraph(attributePaths = "items")
    Optional<Cart> findByCustomerIdAndStatus(UUID customerId, CartStatus status);

    Optional<Cart> findByIdAndCustomerId(UUID id, UUID customerId);

    boolean existsByIdAndCustomerIdAndStatus(UUID id, UUID customerId, CartStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findWithLockByIdAndCustomerIdAndStatus(UUID id, UUID customerId, CartStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findWithLockByCustomerIdAndStatus(UUID customerId, CartStatus status);

    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}carts (id, customer_id, status, version, created_at, updated_at)
            VALUES (:id, :customerId, 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (customer_id) WHERE status = 'ACTIVE' DO NOTHING
            """, nativeQuery = true)
    int createActiveCartIfAbsent(@Param("id") UUID id, @Param("customerId") UUID customerId);
}
