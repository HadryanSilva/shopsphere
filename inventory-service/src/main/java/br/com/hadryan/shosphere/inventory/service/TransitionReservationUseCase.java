package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;

import java.util.UUID;

public interface TransitionReservationUseCase {
    ReservationResponse confirm(String serviceClientId, UUID orderId);
    ReservationResponse release(String serviceClientId, UUID orderId);
}
