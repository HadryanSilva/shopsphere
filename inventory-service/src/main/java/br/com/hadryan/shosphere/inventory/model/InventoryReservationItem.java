package br.com.hadryan.shosphere.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

@Getter
@Embeddable
public class InventoryReservationItem {
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    protected InventoryReservationItem() {}

    public InventoryReservationItem(UUID productId, int quantity) {
        this.productId = Objects.requireNonNull(productId);
        if (quantity <= 0) throw new IllegalArgumentException("Reservation quantity must be positive");
        this.quantity = quantity;
    }
}
