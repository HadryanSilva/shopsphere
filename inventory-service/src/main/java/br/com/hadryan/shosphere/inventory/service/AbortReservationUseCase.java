package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import java.util.UUID;

public interface AbortReservationUseCase {
    Result execute(String serviceClientId, UUID orderId, ReserveStockRequest request);
    record Result(UUID orderId, ReservationResponse reservation) { }
}
