package br.com.hadryan.shosphere.cart.mapper.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record AddProductRequest(
        UUID cartId,
        @NotNull UUID productId,
        @NotNull @Positive Integer quantity
) {
}
