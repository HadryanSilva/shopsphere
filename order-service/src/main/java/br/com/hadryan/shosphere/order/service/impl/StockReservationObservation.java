package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.enums.StockReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Local input to persistence transitions; HTTP decoding belongs to the Inventory client.
public record StockReservationObservation(UUID orderId, StockReservationStatus status,
        List<StockWorkSnapshot.Item> items, Instant createdAt, Instant expiresAt, Instant updatedAt) {}
