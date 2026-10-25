package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegisterStockUseCaseTests {
    private final StockRegistrationIdempotency idempotency = mock(StockRegistrationIdempotency.class);
    private final InventoryStockRepository stocks = mock(InventoryStockRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final RegisterStockTransaction transaction = mock(RegisterStockTransaction.class);
    private final RegisterStockUseCaseImpl useCase = new RegisterStockUseCaseImpl(idempotency, stocks, catalog, transaction);
    private final String actor = UUID.randomUUID().toString();
    private final UUID key = UUID.randomUUID();
    private final RegisterStockRequest request = new RegisterStockRequest(UUID.randomUUID(), 7);
    private final StockResponse response = new StockResponse(request.productId(), 7, 0, 7, 0L, Instant.now());

    @Test
    void validatesCatalogBeforeWritingInSeparateComponent() {
        when(transaction.execute(actor, key, request)).thenReturn(response);
        assertEquals(response, useCase.execute(actor, key, request));
        var order = inOrder(idempotency, stocks, catalog, transaction);
        order.verify(idempotency).findExisting(actor, key, request);
        order.verify(stocks).existsById(request.productId());
        order.verify(catalog).requireProduct(request.productId());
        order.verify(transaction).execute(actor, key, request);
    }

    @Test
    void replayDoesNotDependOnCatalogOrWriteAgain() {
        when(idempotency.findExisting(actor, key, request)).thenReturn(Optional.of(response));
        assertEquals(response, useCase.execute(actor, key, request));
        verifyNoInteractions(stocks, catalog, transaction);
    }

    @Test
    void duplicateProductIsConflictWithoutCallingCatalog() {
        when(stocks.existsById(request.productId())).thenReturn(true);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> useCase.execute(actor, key, request)).getStatusCode().value());
        verifyNoInteractions(catalog, transaction);
    }

    @Test
    void catalogFailureDoesNotWriteOrConvertIntoSuccess() {
        var failure = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service unavailable");
        doThrow(failure).when(catalog).requireProduct(request.productId());
        assertSame(failure, assertThrows(ResponseStatusException.class, () -> useCase.execute(actor, key, request)));
        verifyNoInteractions(transaction);
    }

    @Test
    void unrelatedIntegrityFailureIsNotTreatedAsReplay() {
        var failure = new DataIntegrityViolationException("Unexpected constraint");
        when(transaction.execute(actor, key, request)).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> useCase.execute(actor, key, request)));
        verify(idempotency).findExisting(actor, key, request);
        verifyNoMoreInteractions(idempotency);
    }
}
