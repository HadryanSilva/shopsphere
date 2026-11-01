package br.com.hadryan.shosphere.payment.repository;

import br.com.hadryan.shosphere.payment.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}payments
                (order_id, service_client_id, status, amount, currency, cancel_requested, created_at, updated_at, version)
            VALUES (:id, :client, 'PROCESSING', :amount, :currency, false, :now, :now, 0)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("client") String client, @Param("amount") BigDecimal amount,
                       @Param("currency") String currency, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockByOrderId(UUID orderId);
}
