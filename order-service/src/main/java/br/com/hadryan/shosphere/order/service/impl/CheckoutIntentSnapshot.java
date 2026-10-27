package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.CheckoutIntent;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.model.enums.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record CheckoutIntentSnapshot(UUID checkoutId, UUID customerId, String idempotencyKey,
        ShippingAddress shippingAddress, CheckoutIntentStatus status, UUID cartId, List<Item> items,
        UUID orderId, CheckoutFailureReason failureReason, Instant nextAttemptAt, long attemptCount,
        CheckoutRetryCategory lastFailureCategory, Instant createdAt, Instant updatedAt) {
    public record Item(UUID productId, int quantity) {}

    static CheckoutIntentSnapshot from(CheckoutIntent intent) {
        return new CheckoutIntentSnapshot(intent.getCheckoutId(), intent.getCustomerId(), intent.getIdempotencyKey(),
                intent.getShippingAddress(), intent.getStatus(), intent.getCartId(), intent.getItems().stream()
                    .map(item -> new Item(item.getProductId(), item.getQuantity()))
                    .sorted(Comparator.comparing(item -> item.productId().toString())).toList(),
                intent.getOrderId(), intent.getFailureReason(), intent.getNextAttemptAt(), intent.getAttemptCount(),
                intent.getLastFailureCategory(), intent.getCreatedAt(), intent.getUpdatedAt());
    }
}
