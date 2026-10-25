package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetReservationUseCaseImpl implements GetReservationUseCase {
    private final InventoryReservationRepository reservations;

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse execute(String serviceClientId, UUID orderId) {
        return reservations.findByOrderIdAndServiceClientId(orderId, serviceClientId)
                .map(ReservationResponse::fromReservation)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }
}
