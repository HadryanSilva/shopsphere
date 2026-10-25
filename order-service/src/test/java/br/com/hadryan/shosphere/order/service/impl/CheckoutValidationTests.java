package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.CartClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.request.OrderItemRequest;
import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import br.com.hadryan.shosphere.order.mapper.response.CartItemResponse;
import br.com.hadryan.shosphere.order.mapper.response.CartResponse;
import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import org.mockito.ArgumentCaptor;
import br.com.hadryan.shosphere.order.util.CurrentUserTokenProvider;
import feign.FeignException;
import feign.RetryableException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutValidationTests {
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID SECOND_ID = UUID.randomUUID();
    private static ValidatorFactory validation;
    private final CurrentUserTokenProvider token = mock(CurrentUserTokenProvider.class);
    private final CartClient cart = mock(CartClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final CreateOrderTransaction transaction = mock(CreateOrderTransaction.class);
    private final OrderIdempotency idempotency = mock(OrderIdempotency.class);
    private final CreateOrderUseCaseImpl useCase = new CreateOrderUseCaseImpl(token, cart, catalog, transaction, idempotency,
            validation.getValidator());

    @BeforeAll
    static void startValidation() {
        validation = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidation() {
        validation.close();
    }

    private void validCart() {
        when(token.getCurrentUserToken()).thenReturn("Bearer test-token");
        when(cart.getCustomerCart("Bearer test-token")).thenReturn(
                new CartResponse(UUID.randomUUID(), "ACTIVE", List.of(new CartItemResponse(PRODUCT_ID, 2))));
    }

    private static ShippingAddressRequest address() {
        return new ShippingAddressRequest("Customer", "Street 1", null, "Lisbon", "1000-001", "PT");
    }

    private static CreateOrderRequest request() {
        return new CreateOrderRequest(null, address());
    }

    private void assertFailure(CreateOrderRequest request, int expectedStatus) {
        var exception = assertThrows(ResponseStatusException.class, () ->
                useCase.execute(UUID.randomUUID(), UUID.randomUUID(), request));
        assertEquals(expectedStatus, exception.getStatusCode().value());
        verifyNoInteractions(transaction);
    }

    @ParameterizedTest
    @MethodSource("invalidCarts")
    void rejectsInvalidCartBeforeConsultingCatalog(CartResponse response, int status) {
        when(token.getCurrentUserToken()).thenReturn("Bearer test-token");
        when(cart.getCustomerCart("Bearer test-token")).thenReturn(response);
        assertFailure(request(), status);
        verifyNoInteractions(catalog);
    }

    static Stream<Arguments> invalidCarts() {
        var id = UUID.randomUUID();
        var item = new CartItemResponse(PRODUCT_ID, 2);
        return Stream.of(
                Arguments.of(null, 502),
                Arguments.of(new CartResponse(null, "ACTIVE", List.of(item)), 502),
                Arguments.of(new CartResponse(id, null, List.of(item)), 502),
                Arguments.of(new CartResponse(id, "ACTIVE", null), 502),
                Arguments.of(new CartResponse(id, "ACTIVE", List.of()), 409),
                Arguments.of(new CartResponse(id, "CHECKED_OUT", List.of(item)), 409),
                Arguments.of(new CartResponse(id, "ABANDONED", List.of(item)), 409),
                Arguments.of(new CartResponse(id, "ACTIVE", Arrays.asList((CartItemResponse) null)), 409),
                Arguments.of(new CartResponse(id, "ACTIVE", List.of(new CartItemResponse(null, 2))), 409),
                Arguments.of(new CartResponse(id, "ACTIVE", List.of(new CartItemResponse(PRODUCT_ID, null))), 409),
                Arguments.of(new CartResponse(id, "ACTIVE", List.of(new CartItemResponse(PRODUCT_ID, 0))), 409),
                Arguments.of(new CartResponse(id, "ACTIVE", List.of(new CartItemResponse(PRODUCT_ID, -1))), 409),
                Arguments.of(new CartResponse(id, "ACTIVE", List.of(item, item)), 409));
    }

    @ParameterizedTest
    @MethodSource("invalidProducts")
    void rejectsInvalidOrIncompleteCatalog(List<ProductResponse> response, int status) {
        validCart();
        when(catalog.getProducts(List.of(PRODUCT_ID))).thenReturn(response);
        assertFailure(request(), status);
    }

    static Stream<Arguments> invalidProducts() {
        var product = product(PRODUCT_ID, "Product", BigDecimal.TEN, "EUR", "ACTIVE");
        return Stream.of(
                Arguments.of(null, 502), Arguments.of(List.of(), 409),
                Arguments.of(Arrays.asList((ProductResponse) null), 502),
                Arguments.of(List.of(product(null, "Product", BigDecimal.TEN, "EUR", "ACTIVE")), 502),
                Arguments.of(List.of(product(SECOND_ID, "Product", BigDecimal.TEN, "EUR", "ACTIVE")), 502),
                Arguments.of(List.of(product, product), 502),
                Arguments.of(List.of(product(PRODUCT_ID, null, BigDecimal.TEN, "EUR", "ACTIVE")), 502),
                Arguments.of(List.of(product(PRODUCT_ID, " ", BigDecimal.TEN, "EUR", "ACTIVE")), 502),
                Arguments.of(List.of(product(PRODUCT_ID, "a".repeat(151), BigDecimal.TEN, "EUR", "ACTIVE")), 502),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", null, "EUR", "ACTIVE")), 409),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", BigDecimal.ZERO, "EUR", "ACTIVE")), 409),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", BigDecimal.ONE.negate(), "EUR", "ACTIVE")), 409),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", BigDecimal.TEN, "EUR", "INACTIVE")), 409),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", BigDecimal.TEN, "EUR", "OUT_OF_STOCK")), 409),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", BigDecimal.TEN, null, "ACTIVE")), 502),
                Arguments.of(List.of(product(PRODUCT_ID, "Product", BigDecimal.TEN, "invalid", "ACTIVE")), 502));
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void rejectsAddressBeforeCallingOtherServices(CreateOrderRequest request) {
        assertFailure(request, 400);
        verifyNoInteractions(token, cart, catalog);
    }

    static Stream<Arguments> invalidAddresses() {
        return Stream.of(
                Arguments.of((CreateOrderRequest) null),
                Arguments.of(new CreateOrderRequest(null, null)),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest(" ", "Street", null, "City", "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("a".repeat(151), "Street", null, "City", "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "", null, "City", "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "a".repeat(201), null, "City", "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", "a".repeat(201), "City", "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", null, "", "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", null, "a".repeat(101), "1000", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", null, "City", "", "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", null, "City", "a".repeat(21), "PT"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", null, "City", "1000", "pt"))),
                Arguments.of(new CreateOrderRequest(null, new ShippingAddressRequest("Name", "Street", null, "City", "1000", null))));
    }

    @Test
    void acceptsCompleteProductsInAnyOrderAndIgnoresFrontendItems() {
        validCart();
        when(cart.getCustomerCart("Bearer test-token")).thenReturn(new CartResponse(UUID.randomUUID(), "ACTIVE",
                List.of(new CartItemResponse(PRODUCT_ID, 2), new CartItemResponse(SECOND_ID, 1))));
        when(catalog.getProducts(List.of(PRODUCT_ID, SECOND_ID))).thenReturn(List.of(
                product(SECOND_ID, "Second", BigDecimal.ONE, "EUR", "ACTIVE"),
                product(PRODUCT_ID, "First", BigDecimal.TEN, "EUR", "ACTIVE")));
        var request = new CreateOrderRequest(List.of(new OrderItemRequest(UUID.randomUUID(), -1)), address());
        var expected = mock(OrderResponse.class);
        when(transaction.execute(any(Order.class))).thenReturn(expected);

        assertSame(expected, useCase.execute(UUID.randomUUID(), UUID.randomUUID(), request));

        verify(catalog).getProducts(List.of(PRODUCT_ID, SECOND_ID));
        verify(cart).getCustomerCart("Bearer test-token");
        var captured = ArgumentCaptor.forClass(Order.class);
        verify(transaction).execute(captured.capture());
        assertEquals(new BigDecimal("21.00"), captured.getValue().getSubtotal());
        assertEquals(new BigDecimal("21.00"), captured.getValue().getTotalAmount());
        assertEquals(PRODUCT_ID, captured.getValue().getItems().getFirst().getProductId());
        assertEquals("First", captured.getValue().getItems().getFirst().getProductName());
        assertEquals(2, captured.getValue().getItems().getFirst().getQuantity());
    }

    @Test
    void rejectsMixedCurrencies() {
        validCart();
        when(cart.getCustomerCart("Bearer test-token")).thenReturn(new CartResponse(UUID.randomUUID(), "ACTIVE",
                List.of(new CartItemResponse(PRODUCT_ID, 2), new CartItemResponse(SECOND_ID, 1))));
        when(catalog.getProducts(List.of(PRODUCT_ID, SECOND_ID))).thenReturn(List.of(
                product(PRODUCT_ID, "First", BigDecimal.TEN, "EUR", "ACTIVE"),
                product(SECOND_ID, "Second", BigDecimal.ONE, "USD", "ACTIVE")));
        assertFailure(request(), 409);
    }

    @Test
    void rejectsNonemptyCatalogResponseMissingOneRequestedProduct() {
        validCart();
        when(cart.getCustomerCart("Bearer test-token")).thenReturn(new CartResponse(UUID.randomUUID(), "ACTIVE",
                List.of(new CartItemResponse(PRODUCT_ID, 2), new CartItemResponse(SECOND_ID, 1))));
        when(catalog.getProducts(List.of(PRODUCT_ID, SECOND_ID))).thenReturn(
                List.of(product(PRODUCT_ID, "First", BigDecimal.TEN, "EUR", "ACTIVE")));
        assertFailure(request(), 409);
    }

    @ParameterizedTest
    @MethodSource("cartFailures")
    void handlesCartIntegrationFailure(RuntimeException failure, int status) {
        validCart();
        when(cart.getCustomerCart("Bearer test-token")).thenThrow(failure);
        assertFailure(request(), status);
        verifyNoInteractions(catalog);
    }

    static Stream<Arguments> cartFailures() {
        return Stream.of(Arguments.of(mock(FeignException.NotFound.class), 404),
                Arguments.of(mock(FeignException.InternalServerError.class), 502),
                Arguments.of(mock(RetryableException.class), 503));
    }

    @ParameterizedTest
    @MethodSource("catalogFailures")
    void handlesCatalogIntegrationFailure(RuntimeException failure, int status) {
        validCart();
        when(catalog.getProducts(List.of(PRODUCT_ID))).thenThrow(failure);
        assertFailure(request(), status);
    }

    static Stream<Arguments> catalogFailures() {
        return Stream.of(Arguments.of(mock(FeignException.NotFound.class), 502),
                Arguments.of(mock(FeignException.InternalServerError.class), 502),
                Arguments.of(mock(RetryableException.class), 503));
    }

    private static ProductResponse product(UUID id, String name, BigDecimal price, String currency, String status) {
        return new ProductResponse(id, name, price, currency, status);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0.001", "100000000000000000.00", "99999999999999999.99"})
    void rejectsUnrepresentablePricesOrLineTotalsBeforeWriting(String price) {
        validCart();
        when(catalog.getProducts(List.of(PRODUCT_ID))).thenReturn(
                List.of(product(PRODUCT_ID, "Product", new BigDecimal(price), "EUR", "ACTIVE")));
        assertFailure(request(), 409);
    }
}
