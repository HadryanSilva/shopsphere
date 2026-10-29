package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryReservationItem;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationCoordinationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.AbortReservationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AbortReservationUseCaseImpl implements AbortReservationUseCase {
    private final ReservationCoordinationTransaction coordination;
    private final InventoryReservationCoordinationRepository coordinations;
    private final InventoryReservationRepository reservations;
    private final InventoryStockRepository stocks;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result execute(String client, UUID id, ReserveStockRequest request) {
        var intent = coordination.lock(client, id, request);
        var existing = reservations.findWithLockByOrderId(id);
        ReservationResponse response = null;
        if (existing.isPresent()) {
            var reservation = existing.get();
            if (!reservation.getServiceClientId().equals(client))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
            if (!intent.sameItems(reservation.getItems()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to a different reservation intent");
            if (reservation.getStatus() == InventoryReservationStatus.ACTIVE) {
                var items = reservation.getItems().stream().sorted(Comparator.comparing(InventoryReservationItem::getProductId)).toList();
                var locked = items.stream().map(item -> stocks.findWithLockByProductId(item.getProductId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found"))).toList();
                reservation.release(Instant.now());
                for (int index = 0; index < items.size(); index++) locked.get(index).releaseReserved(items.get(index).getQuantity());
            } else reservation.release(Instant.now()); // CONFIRMED conflicts; terminal release/expiry is idempotent.
            reservations.flush();
            response = ReservationResponse.fromReservation(reservation);
        }
        intent.abort(Instant.now());
        coordinations.flush();
        return new Result(id, response);
    }
}
