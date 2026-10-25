package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.CartClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import br.com.hadryan.shosphere.order.mapper.response.*;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import br.com.hadryan.shosphere.order.util.CurrentUserTokenProvider;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderIdempotencyTests {
    private static ValidatorFactory validation;
    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderIdempotency idempotency = mock(OrderIdempotency.class);
    private final CurrentUserTokenProvider token = mock(CurrentUserTokenProvider.class);
    private final CartClient cart = mock(CartClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final CreateOrderTransaction transaction = mock(CreateOrderTransaction.class);
    private final UUID customerId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final CreateOrderRequest request = new CreateOrderRequest(null, address());

    @BeforeAll static void startValidation() { validation = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void closeValidation() { validation.close(); }

    private CreateOrderUseCaseImpl useCase() {
        return new CreateOrderUseCaseImpl(token, cart, catalog, transaction, idempotency, validation.getValidator());
    }

    private static ShippingAddressRequest address() {
        return new ShippingAddressRequest("Customer", "Street 1", null, "Lisbon", "1000-001", "PT");
    }

    private void validServices() {
        when(token.getCurrentUserToken()).thenReturn("Bearer test-token");
        when(cart.getCustomerCart("Bearer test-token")).thenReturn(new CartResponse(UUID.randomUUID(), "ACTIVE",
                List.of(new CartItemResponse(productId, 2))));
        when(catalog.getProducts(List.of(productId))).thenReturn(
                List.of(new ProductResponse(productId, "Product", BigDecimal.TEN, "EUR", "ACTIVE")));
    }

    @Test
    void replayDoesNotDependOnCartCatalogOrNewWrite() {
        var saved = mock(OrderResponse.class);
        when(idempotency.findExisting(customerId, key, address())).thenReturn(Optional.of(saved));

        assertSame(saved, useCase().execute(customerId, key, request));

        verifyNoInteractions(token, cart, catalog, transaction);
    }

    @Test
    void duplicateKeyRaceReloadsWinnerWithoutRetryingWrite() {
        validServices();
        var saved = mock(OrderResponse.class);
        when(idempotency.findExisting(customerId, key, address())).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
        var failure = integrityViolation("uk_orders_customer_idempotency");
        when(transaction.execute(any())).thenThrow(failure);

        assertSame(saved, useCase().execute(customerId, key, request));

        verify(idempotency, times(2)).findExisting(customerId, key, address());
        verify(transaction).execute(any());
    }

    @Test
    void unrelatedIntegrityFailureIsNotTreatedAsReplay() {
        validServices();
        var failure = integrityViolation("uk_order_items_order_product");
        when(transaction.execute(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> useCase().execute(customerId, key, request)));

        verify(idempotency).findExisting(customerId, key, address());
    }

    @Test
    void duplicateKeyWithoutReadableWinnerKeepsOriginalFailure() {
        validServices();
        var failure = integrityViolation("uk_orders_customer_idempotency");
        when(transaction.execute(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> useCase().execute(customerId, key, request)));
        verify(idempotency, times(2)).findExisting(customerId, key, address());
        verify(transaction).execute(any());
    }

    @Test
    void readsByCustomerAndKeyAndReturnsStoredSnapshot() {
        var saved = storedOrder();
        when(repository.findByCustomerIdAndIdempotencyKey(customerId, key.toString())).thenReturn(Optional.of(saved));

        var result = new OrderIdempotency(repository).findExisting(customerId, key, address()).orElseThrow();

        assertEquals(saved.getId(), result.id());
        assertEquals(saved.getTotalAmount(), result.totalAmount());
        verify(repository).findByCustomerIdAndIdempotencyKey(customerId, key.toString());
    }

    @ParameterizedTest
    @MethodSource("differentAddresses")
    void differentPayloadCannotReuseKey(ShippingAddressRequest different) {
        when(repository.findByCustomerIdAndIdempotencyKey(customerId, key.toString())).thenReturn(Optional.of(storedOrder()));

        var failure = assertThrows(ResponseStatusException.class, () ->
                new OrderIdempotency(repository).findExisting(customerId, key, different));

        assertEquals(409, failure.getStatusCode().value());
    }

    static Stream<ShippingAddressRequest> differentAddresses() {
        return Stream.of(new ShippingAddressRequest("Other", "Street 1", null, "Lisbon", "1000-001", "PT"),
                new ShippingAddressRequest("Customer", "Street 2", null, "Lisbon", "1000-001", "PT"),
                new ShippingAddressRequest("Customer", "Street 1", "Suite 2", "Lisbon", "1000-001", "PT"),
                new ShippingAddressRequest("Customer", "Street 1", null, "Porto", "1000-001", "PT"),
                new ShippingAddressRequest("Customer", "Street 1", null, "Lisbon", "2000-001", "PT"),
                new ShippingAddressRequest("Customer", "Street 1", null, "Lisbon", "1000-001", "ES"));
    }

    private Order storedOrder() {
        var order = Order.create(customerId, key, "EUR", new ShippingAddress("Customer", "Street 1", "Lisbon", "1000-001", "PT"));
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.addItem(productId, "Original product", BigDecimal.TEN, 2);
        return order;
    }

    private DataIntegrityViolationException integrityViolation(String constraintName) {
        var cause = mock(ConstraintViolationException.class);
        when(cause.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException("Integrity violation", cause);
    }
}
