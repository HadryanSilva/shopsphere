package br.com.hadryan.shosphere.inventory.model;

import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false, length = 20)
    private InventoryMovementType movementType;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int delta;

    @Column(updatable = false, length = 500)
    private String reason;

    @Column(name = "actor_subject", nullable = false, updatable = false, length = 255)
    private String actorSubject;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "on_hand_after", nullable = false, updatable = false)
    private int onHandAfter;

    @Column(name = "reserved_after", nullable = false, updatable = false)
    private int reservedAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InventoryMovement() {}

    public static InventoryMovement initial(InventoryStock stock, String actorSubject, UUID key) {
        var movement = new InventoryMovement();
        movement.movementType = InventoryMovementType.INITIAL;
        movement.productId = stock.getProductId();
        movement.delta = stock.getOnHand();
        movement.actorSubject = Objects.requireNonNull(actorSubject);
        movement.idempotencyKey = Objects.requireNonNull(key);
        movement.onHandAfter = stock.getOnHand();
        movement.reservedAfter = stock.getReserved();
        return movement;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (movementType == InventoryMovementType.ADJUSTMENT) {
            createdAt = createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
    }

    public static InventoryMovement adjustment(InventoryStock stock, String actorSubject, UUID key,
                                               int delta, String reason) {
        if (delta == 0 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("Invalid adjustment delta or reason");
        }
        var movement = new InventoryMovement();
        movement.movementType = InventoryMovementType.ADJUSTMENT;
        movement.productId = stock.getProductId();
        movement.delta = delta;
        movement.reason = reason;
        movement.actorSubject = Objects.requireNonNull(actorSubject);
        movement.idempotencyKey = Objects.requireNonNull(key);
        movement.onHandAfter = stock.getOnHand();
        movement.reservedAfter = stock.getReserved();
        return movement;
    }
}
