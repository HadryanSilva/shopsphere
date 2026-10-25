package br.com.hadryan.shosphere.cart.mapper.response;

import br.com.hadryan.shosphere.cart.model.CartItem;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public record CartItemResponse(
        UUID productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal subTotal
) {
}
