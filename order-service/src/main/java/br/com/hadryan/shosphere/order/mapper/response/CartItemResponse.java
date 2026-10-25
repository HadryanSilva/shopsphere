package br.com.hadryan.shosphere.order.mapper.response;

import java.util.UUID;

public record CartItemResponse(
        UUID productId,
        Integer quantity
) {
}
