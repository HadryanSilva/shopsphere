package br.com.hadryan.shosphere.order.mapper.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartCheckoutResponse(UUID checkoutId, UUID customerId, UUID cartId, String status,
        List<Item> items, UUID orderId, Instant preparedAt, Instant completedAt, Instant abortedAt) {
    public record Item(UUID productId, Integer quantity) {}
}
