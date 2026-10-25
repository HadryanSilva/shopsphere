package br.com.hadryan.shosphere.inventory.mapper.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record RegisterStockRequest(@NotNull UUID productId, @NotNull @PositiveOrZero Integer onHand) {
}
