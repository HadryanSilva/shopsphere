package br.com.hadryan.shosphere.order.mapper.request;

import java.util.UUID;

public record OrderItemRequest(
        UUID productId,
        Integer quantity
) {
}
