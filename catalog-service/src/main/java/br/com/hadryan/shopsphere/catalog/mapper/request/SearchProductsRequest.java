package br.com.hadryan.shopsphere.catalog.mapper.request;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record SearchProductsRequest(
        @NotEmpty List<@NotNull UUID> productIds
) {
}
