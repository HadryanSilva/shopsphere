package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.StockWork;
import br.com.hadryan.shosphere.order.model.enums.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockWorkSnapshot(UUID orderId, UUID checkoutId, StockWorkPhase phase,
        boolean abortRequested, StockWorkReason reason, StockReservationStatus reservationStatus,
        Instant reservationCreatedAt, Instant reservationExpiresAt, Instant reservationUpdatedAt,
        Instant nextAttemptAt, long attemptCount, StockFailureCategory lastFailureCategory,
        Instant createdAt, Instant updatedAt, long version, List<Item> items) {
    public StockWorkSnapshot { items = List.copyOf(items); }
    public record Item(UUID productId, int quantity) {}

    static StockWorkSnapshot from(StockWork work, Order order) {
        return new StockWorkSnapshot(work.getOrderId(), work.getCheckoutId(), work.getPhase(),
                work.isAbortRequested(), work.getReason(), work.getReservationStatus(),
                work.getReservationCreatedAt(), work.getReservationExpiresAt(), work.getReservationUpdatedAt(),
                work.getNextAttemptAt(), work.getAttemptCount(), work.getLastFailureCategory(),
                work.getCreatedAt(), work.getUpdatedAt(), work.getVersion(),
                order.getItems().stream().map(i -> new Item(i.getProductId(), i.getQuantity())).toList());
    }
}
