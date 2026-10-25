package br.com.hadryan.shosphere.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException;
import br.com.hadryan.shosphere.inventory.exception.StockReservationConflictException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "inventory_stock")
public class InventoryStock {
    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryStock() {}

    public InventoryStock(UUID productId, int onHand) {
        this.productId = Objects.requireNonNull(productId);
        if (onHand < 0) {
            throw new IllegalArgumentException("Stock quantity must not be negative");
        }
        this.onHand = onHand;
    }

    public int getAvailable() {
        return onHand - reserved;
    }

    public void adjust(int delta) {
        if (delta == 0) throw new IllegalArgumentException("Adjustment delta must not be zero");
        long next = (long) onHand + delta;
        if (next < reserved || next > Integer.MAX_VALUE) throw new StockAdjustmentConflictException();
        onHand = (int) next;
    }

    public void reserve(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Reservation quantity must be positive");
        if (quantity > getAvailable()) {
            throw new StockReservationConflictException();
        }
        reserved += quantity;
    }

    public void consumeReserved(int quantity) {
        requireReserved(quantity);
        onHand -= quantity;
        reserved -= quantity;
    }

    public void releaseReserved(int quantity) {
        requireReserved(quantity);
        reserved -= quantity;
    }

    private void requireReserved(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Reservation quantity must be positive");
        if (quantity > reserved) throw new IllegalStateException("Stock counters are inconsistent with reservation");
    }

    @PrePersist
    protected void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
