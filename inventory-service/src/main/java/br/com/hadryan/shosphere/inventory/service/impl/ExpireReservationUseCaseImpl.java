package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.model.InventoryReservationItem;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.ExpireReservationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExpireReservationUseCaseImpl implements ExpireReservationUseCase {
    private final InventoryReservationRepository reservations;
    private final InventoryStockRepository stocks;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(UUID orderId) {
        var candidate = reservations.findExpiredWithLock(orderId, Instant.now());
        if (candidate.isEmpty()) return false;
        var reservation = candidate.get();
        var items = reservation.getItems().stream()
                .sorted(Comparator.comparing(InventoryReservationItem::getProductId)).toList();
        var locked = items.stream().map(item -> stocks.findWithLockByProductId(item.getProductId())
                .orElseThrow(() -> new IllegalStateException("Reservation stock position is missing"))).toList();
        if (!reservation.expire(Instant.now())) return false;
        for (int index = 0; index < items.size(); index++) {
            locked.get(index).releaseReserved(items.get(index).getQuantity());
        }
        reservations.flush();
        return true;
    }
}
