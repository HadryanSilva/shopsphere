package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.model.InventoryReservationCoordination;
import br.com.hadryan.shosphere.inventory.model.InventoryReservationItem;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationCoordinationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationCoordinationTransaction {
    private final InventoryReservationCoordinationRepository coordination;

    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryReservationCoordination lock(String client, UUID id, ReserveStockRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reservation requires 1 to 100 distinct products");
        var ids = new HashSet<UUID>();
        for (var item : request.items())
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0 || !ids.add(item.productId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or duplicate reservation item");
        if (client == null || client.isBlank() || client.length() > 255 || id == null)
            throw new IllegalArgumentException("Invalid reservation identity");
        var snapshot = request.items().stream().sorted(Comparator.comparing(ReserveStockRequest.Item::productId))
                .map(item -> new InventoryReservationItem(item.productId(), item.quantity())).toList();
        boolean created = coordination.insertIfAbsent(id, client, Instant.now()) == 1;
        var stored = coordination.findWithLockByOrderId(id)
                .filter(row -> row.getServiceClientId().equals(client))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        if (created) stored.initializeItems(snapshot);
        else if (!stored.sameItems(snapshot))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to a different reservation intent");
        return stored;
    }
}
