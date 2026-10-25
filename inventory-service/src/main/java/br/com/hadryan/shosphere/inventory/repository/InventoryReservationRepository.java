package br.com.hadryan.shosphere.inventory.repository;

import br.com.hadryan.shosphere.inventory.model.InventoryReservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;

@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    interface ExpirationCandidate {
        UUID getOrderId();
        Instant getExpiresAt();
    }

    @Transactional(readOnly = true)
    @Query("""
            select r.orderId as orderId, r.expiresAt as expiresAt from InventoryReservation r
            where r.status = :status and r.expiresAt <= :cutoff
            order by r.expiresAt, r.orderId
            """)
    List<ExpirationCandidate> findExpirationCandidates(@Param("status") InventoryReservationStatus status,
            @Param("cutoff") Instant cutoff, Pageable page);

    @Transactional(readOnly = true)
    @Query("""
            select r.orderId as orderId, r.expiresAt as expiresAt from InventoryReservation r
            where r.status = :status and r.expiresAt <= :cutoff
            and (r.expiresAt > :afterExpiry
                 or (r.expiresAt = :afterExpiry and r.orderId > :afterId))
            order by r.expiresAt, r.orderId
            """)
    List<ExpirationCandidate> findExpirationCandidatesAfter(@Param("status") InventoryReservationStatus status,
            @Param("cutoff") Instant cutoff, @Param("afterExpiry") Instant afterExpiry,
            @Param("afterId") UUID afterId, Pageable page);

    @Query(value = """
            SELECT * FROM {h-schema}inventory_reservations
            WHERE order_id = :orderId AND status = 'ACTIVE' AND expires_at <= :cutoff
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<InventoryReservation> findExpiredWithLock(@Param("orderId") UUID orderId, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}inventory_reservations
                (order_id, service_client_id, status, expires_at, version, created_at, updated_at)
            VALUES (:orderId, :client, 'ACTIVE', :expiry, 0, :now, :now)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") UUID orderId, @Param("client") String client,
                       @Param("now") Instant now, @Param("expiry") Instant expiry);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryReservation> findWithLockByOrderId(UUID orderId);

    @EntityGraph(attributePaths = "items")
    Optional<InventoryReservation> findByOrderIdAndServiceClientId(UUID orderId, String serviceClientId);
}
