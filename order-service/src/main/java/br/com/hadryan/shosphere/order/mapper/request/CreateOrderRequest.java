package br.com.hadryan.shosphere.order.mapper.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
    List<OrderItemRequest> items,
    @NotNull @Valid ShippingAddressRequest shippingAddress
) {
}
