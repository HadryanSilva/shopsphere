package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.model.CartCheckout;
import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record CartCheckoutSnapshot(UUID checkoutId, UUID customerId, UUID cartId,
        CartCheckoutStatus status, List<Item> items, UUID orderId,
        Instant preparedAt, Instant completedAt, Instant abortedAt) {
    public record Item(UUID productId, int quantity) {}

    static CartCheckoutSnapshot from(CartCheckout checkout) {
        var items = checkout.getItems().stream().map(item -> new Item(item.getProductId(), item.getQuantity()))
                .sorted(Comparator.comparing(item -> item.productId().toString())).toList();
        return new CartCheckoutSnapshot(checkout.getCheckoutId(), checkout.getCustomerId(), checkout.getCartId(),
                checkout.getStatus(), items, checkout.getOrderId(), checkout.getPreparedAt(),
                checkout.getCompletedAt(), checkout.getAbortedAt());
    }
}
