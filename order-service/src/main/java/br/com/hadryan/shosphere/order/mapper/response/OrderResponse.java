package br.com.hadryan.shosphere.order.mapper.response;

import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import br.com.hadryan.shosphere.order.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        OrderStatus status,
        List<OrderItemResponse> items,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal shippingAmount,
        BigDecimal totalAmount,
        String currency,
        String paymentStatus,
        Instant updatedAt
) {
    public static OrderResponse fromOrder(Order order) {
        var items = order.getItems().stream().map(item -> new OrderItemResponse(
                item.getProductId(), item.getProductName(), item.getQuantity(),
                item.getUnitPrice(), item.getLineTotal())).toList();
        return new OrderResponse(order.getId(), order.getStatus(), items, order.getSubtotal(),
                order.getDiscountAmount(), order.getShippingAmount(), order.getTotalAmount(),
                order.getCurrency(), null, order.getUpdatedAt());
    }
}
