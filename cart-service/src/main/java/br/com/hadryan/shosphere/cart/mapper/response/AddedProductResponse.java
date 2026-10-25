package br.com.hadryan.shosphere.cart.mapper.response;

import java.util.UUID;

public record AddedProductResponse(
        UUID cartId,
        UUID productId,
        Integer quantity,
        String message
) {
}
