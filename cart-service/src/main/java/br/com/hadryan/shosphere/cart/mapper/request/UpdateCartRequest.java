package br.com.hadryan.shosphere.cart.mapper.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateCartRequest(
        @NotNull @Positive Integer quantity
) {
}
