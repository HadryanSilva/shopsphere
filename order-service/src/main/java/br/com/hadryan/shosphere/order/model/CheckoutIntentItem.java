package br.com.hadryan.shosphere.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import java.util.Objects;
import java.util.UUID;

@Getter
@Embeddable
public class CheckoutIntentItem {
    @Column(nullable = false, updatable = false)
    private UUID productId;
    @Column(nullable = false, updatable = false)
    private int quantity;

    protected CheckoutIntentItem() {}

    public CheckoutIntentItem(UUID productId, int quantity) {
        this.productId = Objects.requireNonNull(productId);
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        this.quantity = quantity;
    }
}
