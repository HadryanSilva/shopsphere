package br.com.hadryan.shosphere.inventory.mapper.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

public record ReserveStockRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull UUID productId, @NotNull @Positive Integer quantity) {}
}
