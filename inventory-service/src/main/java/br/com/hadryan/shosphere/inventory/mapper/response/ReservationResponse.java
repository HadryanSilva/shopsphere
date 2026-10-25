package br.com.hadryan.shosphere.inventory.mapper.response;

import br.com.hadryan.shosphere.inventory.model.InventoryReservation;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(UUID orderId, InventoryReservationStatus status, List<Item> items,
                                  Instant expiresAt, Instant createdAt, Instant updatedAt) {
    public record Item(UUID productId, int quantity) {}

    public static ReservationResponse fromReservation(InventoryReservation reservation) {
        return new ReservationResponse(reservation.getOrderId(), reservation.getStatus(),
                reservation.getItems().stream().map(item -> new Item(item.getProductId(), item.getQuantity()))
                        .sorted(Comparator.comparing(Item::productId)).toList(),
                reservation.getExpiresAt(), reservation.getCreatedAt(), reservation.getUpdatedAt());
    }
}
