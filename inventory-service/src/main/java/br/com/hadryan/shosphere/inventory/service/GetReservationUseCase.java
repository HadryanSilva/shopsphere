package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;

import java.util.UUID;

public interface GetReservationUseCase {
    ReservationResponse execute(String serviceClientId, UUID orderId);
}
