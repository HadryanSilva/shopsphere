package br.com.hadryan.shosphere.inventory.mapper.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdjustStockRequest(@NotNull Integer delta, @NotBlank @Size(max = 500) String reason) {
}
