package br.com.hadryan.shosphere.cart.mapper.response;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        BigDecimal price,
        String status
) {
}
