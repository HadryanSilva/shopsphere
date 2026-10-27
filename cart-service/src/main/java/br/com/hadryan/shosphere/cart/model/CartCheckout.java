package br.com.hadryan.shosphere.cart.model;

import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "cart_checkouts")
public class CartCheckout {
    @Id
    @Column(updatable = false)
    private UUID checkoutId;
    @Column(nullable = false, updatable = false)
    private UUID customerId;
    @Column(nullable = false, updatable = false, length = 255)
    private String serviceClient;
    @Column(updatable = false)
    private UUID cartId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CartCheckoutStatus status;
    private UUID orderId;
    @Column(updatable = false)
    private Instant preparedAt;
    private Instant completedAt;
    private Instant abortedAt;
    @Version
    private Long version;

    @ElementCollection
    @CollectionTable(name = "cart_checkout_items", joinColumns = @JoinColumn(name = "checkout_id"))
    @OrderBy("productId ASC")
    private List<CartCheckoutItem> items = new ArrayList<>();

    protected CartCheckout() {}

    private CartCheckout(UUID id, UUID customerId, String serviceClient) {
        this.checkoutId = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        if (serviceClient == null || serviceClient.isBlank() || serviceClient.length() > 255)
            throw new IllegalArgumentException("Invalid service client");
        this.serviceClient = serviceClient;
    }

    public static CartCheckout prepare(UUID id, UUID customerId, String serviceClient, Cart cart) {
        if (!customerId.equals(cart.getCustomerId()) || cart.getStatus() != CartStatus.ACTIVE || cart.getItems().isEmpty())
            throw new IllegalArgumentException("Invalid checkout cart");
        var checkout = new CartCheckout(id, customerId, serviceClient);
        checkout.cartId = Objects.requireNonNull(cart.getId());
        checkout.status = CartCheckoutStatus.PREPARED;
        checkout.preparedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (var item : cart.getItems())
            checkout.items.add(new CartCheckoutItem(item.getProductId(), item.getQuantity()));
        return checkout;
    }

    public static CartCheckout aborted(UUID id, UUID customerId, String serviceClient) {
        var checkout = new CartCheckout(id, customerId, serviceClient);
        checkout.status = CartCheckoutStatus.ABORTED;
        checkout.abortedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return checkout;
    }

    public List<CartCheckoutItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void complete(UUID orderId) {
        Objects.requireNonNull(orderId);
        if (status == CartCheckoutStatus.COMPLETED && orderId.equals(this.orderId)) return;
        if (status != CartCheckoutStatus.PREPARED) throw new IllegalStateException("Checkout cannot be completed");
        this.orderId = orderId;
        completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        status = CartCheckoutStatus.COMPLETED;
    }

    public void abort() {
        if (status == CartCheckoutStatus.ABORTED) return;
        if (status != CartCheckoutStatus.PREPARED) throw new IllegalStateException("Checkout cannot be aborted");
        abortedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        status = CartCheckoutStatus.ABORTED;
    }
}
