package br.com.hadryan.shosphere.order.mapper.response;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        BigDecimal price,
        String currency,
        String status
) {
}
