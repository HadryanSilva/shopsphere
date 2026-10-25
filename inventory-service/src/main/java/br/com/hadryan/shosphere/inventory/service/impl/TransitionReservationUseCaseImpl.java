package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryReservation;
import br.com.hadryan.shosphere.inventory.model.InventoryReservationItem;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.TransitionReservationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class TransitionReservationUseCaseImpl implements TransitionReservationUseCase {
    private final InventoryReservationRepository reservations;
    private final InventoryStockRepository stocks;

    @Override
    public ReservationResponse confirm(String serviceClientId, UUID orderId) {
        var reservation = lockedReservation(serviceClientId, orderId);
        if (reservation.getStatus() != InventoryReservationStatus.ACTIVE) {
            reservation.confirm(Instant.now());
            return ReservationResponse.fromReservation(reservation);
        }
        var items = orderedItems(reservation);
        var locked = lockedStocks(items);
        reservation.confirm(Instant.now());
        for (int index = 0; index < items.size(); index++) {
            locked.get(index).consumeReserved(items.get(index).getQuantity());
        }
        reservations.flush();
        return ReservationResponse.fromReservation(reservation);
    }

    @Override
    public ReservationResponse release(String serviceClientId, UUID orderId) {
        var reservation = lockedReservation(serviceClientId, orderId);
        if (reservation.getStatus() != InventoryReservationStatus.ACTIVE) {
            reservation.release(Instant.now());
            return ReservationResponse.fromReservation(reservation);
        }
        var items = orderedItems(reservation);
        var locked = lockedStocks(items);
        reservation.release(Instant.now());
        for (int index = 0; index < items.size(); index++) {
            locked.get(index).releaseReserved(items.get(index).getQuantity());
        }
        reservations.flush();
        return ReservationResponse.fromReservation(reservation);
    }

    private InventoryReservation lockedReservation(String client, UUID orderId) {
        return reservations.findWithLockByOrderId(orderId)
                .filter(reservation -> reservation.getServiceClientId().equals(client))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }

    private List<InventoryReservationItem> orderedItems(InventoryReservation reservation) {
        return reservation.getItems().stream().sorted(Comparator.comparing(InventoryReservationItem::getProductId)).toList();
    }

    private List<InventoryStock> lockedStocks(List<InventoryReservationItem> items) {
        return items.stream().map(item -> stocks.findWithLockByProductId(item.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found"))).toList();
    }
}
