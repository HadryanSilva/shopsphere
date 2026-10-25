package br.com.hadryan.shosphere.order.repository;

import br.com.hadryan.shosphere.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
}
