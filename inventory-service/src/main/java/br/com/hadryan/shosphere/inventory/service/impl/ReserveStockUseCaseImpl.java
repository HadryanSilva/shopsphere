package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryReservationItem;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;

@Service
public class ReserveStockUseCaseImpl implements ReserveStockUseCase {
    private final InventoryReservationRepository reservations;
    private final InventoryStockRepository stocks;
    private final Duration ttl;

    public ReserveStockUseCaseImpl(InventoryReservationRepository reservations, InventoryStockRepository stocks,
            @Value("${inventory.reservations.ttl:PT15M}") String ttl) {
        var duration = Duration.parse(ttl);
        if (duration.compareTo(Duration.ofNanos(1000)) < 0) {
            throw new IllegalArgumentException("Reservation TTL must be at least one microsecond");
        }
        this.reservations = reservations;
        this.stocks = stocks;
        this.ttl = duration;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result execute(String serviceClientId, UUID orderId, ReserveStockRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reservation requires 1 to 100 distinct products");
        }
        var ids = new HashSet<UUID>();
        for (var item : request.items()) {
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0
                    || !ids.add(item.productId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or duplicate reservation item");
            }
        }
        var items = request.items().stream().sorted(Comparator.comparing(ReserveStockRequest.Item::productId)).toList();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        boolean created = reservations.insertIfAbsent(orderId, serviceClientId, now, now.plus(ttl).truncatedTo(ChronoUnit.MICROS)) == 1;
        var reservation = reservations.findWithLockByOrderId(orderId)
                .filter(stored -> stored.getServiceClientId().equals(serviceClientId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        if (!created) {
            var response = ReservationResponse.fromReservation(reservation);
            var intent = items.stream().map(item -> new ReservationResponse.Item(item.productId(), item.quantity())).toList();
            if (!response.items().equals(intent)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to a different reservation intent");
            }
            return new Result(false, response);
        }
        // Lock in a consistent UUID order before inserting item FKs or mutating counters.
        var locked = items.stream().map(item -> stocks.findWithLockByProductId(item.productId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found"))).toList();
        for (int index = 0; index < items.size(); index++) {
            locked.get(index).reserve(items.get(index).quantity());
        }
        reservation.initializeItems(items.stream()
                .map(item -> new InventoryReservationItem(item.productId(), item.quantity())).toList());
        reservations.flush();
        return new Result(true, ReservationResponse.fromReservation(reservation));
    }
}
