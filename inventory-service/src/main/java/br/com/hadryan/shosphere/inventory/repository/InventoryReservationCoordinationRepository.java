package br.com.hadryan.shosphere.inventory.repository;

import br.com.hadryan.shosphere.inventory.model.InventoryReservationCoordination;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationCoordinationRepository extends JpaRepository<InventoryReservationCoordination, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}inventory_reservation_coordination
                (order_id, service_client_id, created_at, version)
            VALUES (:id, :client, :now, 0)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("client") String client, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryReservationCoordination> findWithLockByOrderId(UUID id);
}
