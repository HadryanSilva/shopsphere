package br.com.hadryan.shosphere.cart.mapper.response;

import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;
import br.com.hadryan.shosphere.cart.service.impl.CartCheckoutSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CartCheckoutResponse(UUID checkoutId, UUID customerId, UUID cartId, CartCheckoutStatus status,
        List<Item> items, UUID orderId, Instant preparedAt, Instant completedAt, Instant abortedAt) {
    public record Item(UUID productId, int quantity) {}

    public static CartCheckoutResponse from(CartCheckoutSnapshot snapshot) {
        return new CartCheckoutResponse(snapshot.checkoutId(), snapshot.customerId(), snapshot.cartId(), snapshot.status(),
                snapshot.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList(),
                snapshot.orderId(), snapshot.preparedAt(), snapshot.completedAt(), snapshot.abortedAt());
    }
}
