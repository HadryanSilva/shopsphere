package br.com.hadryan.shosphere.cart.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "cart_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cart_product",
                columnNames = {"cart_id", "product_id"}
        )
)
public class CartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
    }

    public CartItem(Cart cart, UUID productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        changeQuantity(quantity);
    }

    public void changeQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "A quantidade deve ser positiva"
            );
        }
        this.quantity = quantity;
    }
}
