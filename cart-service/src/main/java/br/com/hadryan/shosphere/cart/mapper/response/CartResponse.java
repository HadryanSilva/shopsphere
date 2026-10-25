package br.com.hadryan.shosphere.cart.mapper.response;

import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.model.enums.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        CartStatus status,
        List<CartItemResponse> items,
        Integer totalItems,
        BigDecimal estimatedTotal,
        Currency currency,
        Instant updatedAt
) {
}
