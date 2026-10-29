package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationCoordinationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReserveStockUseCaseTests {
    @Test void rejectsDuplicateIntentBeforeAnyDatabaseOperation() {
        var reservations = mock(InventoryReservationRepository.class);
        var stocks = mock(InventoryStockRepository.class);
        var coordinations = mock(InventoryReservationCoordinationRepository.class);
        var useCase = new ReserveStockUseCaseImpl(reservations, stocks, new ReservationCoordinationTransaction(coordinations), "PT15M");
        var product = UUID.randomUUID();
        var request = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 1), new ReserveStockRequest.Item(product, 2)));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> useCase.execute("shopsphere-order", UUID.randomUUID(), request)).getStatusCode().value());
        verifyNoInteractions(reservations, stocks, coordinations);
    }

    @Test void rejectsNonPositiveOrUnrepresentableTtl() {
        for (var ttl : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> new ReserveStockUseCaseImpl(
                    mock(InventoryReservationRepository.class), mock(InventoryStockRepository.class),
                    mock(ReservationCoordinationTransaction.class), ttl.toString()));
        }
    }
}
