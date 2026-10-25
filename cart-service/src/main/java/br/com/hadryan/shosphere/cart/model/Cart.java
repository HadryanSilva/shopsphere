package br.com.hadryan.shosphere.cart.model;

import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "carts",
        indexes = @Index(
                name = "idx_cart_customer",
                columnList = "customer_id"
        )
)
public class Cart extends BaseEntity {
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CartStatus status = CartStatus.ACTIVE;

    @Version
    private long version;

    @OneToMany(
            mappedBy = "cart",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {}

    public void addItem(UUID productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "A quantidade deve ser positiva"
            );
        }

        items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst()
                .ifPresentOrElse(
                        item -> item.changeQuantity(
                                Math.addExact(item.getQuantity(), quantity)
                        ),
                        () -> items.add(new CartItem(this, productId, quantity))
                );
    }

    public void removeItem(UUID productId) {
        items.removeIf(
                item -> item.getProductId().equals(productId)
        );
    }

    public void clear() {
        items.clear();
    }
}
