package br.com.hadryan.shosphere.order.controller;

import br.com.hadryan.shosphere.order.config.SecurityConfig;
import br.com.hadryan.shosphere.order.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.order.mapper.response.OrderItemResponse;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import br.com.hadryan.shosphere.order.service.CreateOrderUseCase;
import br.com.hadryan.shosphere.order.service.GetOrderByIdUseCase;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@ContextConfiguration(classes = {OrderController.class, SecurityConfig.class, ApiExceptionHandler.class})
class OrderControllerTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private CreateOrderUseCase useCase;
    @MockitoBean private GetOrderByIdUseCase getOrder;
    @MockitoBean private JwtDecoder decoder;
    private final UUID customerId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();
    private final CreateOrderRequest request = new CreateOrderRequest(null,
            new ShippingAddressRequest("Customer", "Street 1", null, "Lisbon", "1000-001", "PT"));
    private static final String VALID_BODY = """
            {"shippingAddress":{"recipient":"Customer","addressLine":"Street 1",
            "city":"Lisbon","postalCode":"1000-001","countryCode":"PT"}}
            """;

    @Test
    void readsOrderUsingJwtIdentityInsteadOfCustomerQueryParameter() throws Exception {
        var orderId = UUID.randomUUID();
        when(getOrder.execute(customerId, orderId)).thenReturn(response(orderId));

        mvc.perform(get("/api/v1/orders/{id}", orderId)
                        .with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .param("customerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_STOCK"))
                .andExpect(jsonPath("$.totalAmount").value(20))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        verify(getOrder).execute(customerId, orderId);
        verifyNoInteractions(useCase);
    }

    @Test
    void anonymousOrderReadIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(getOrder, useCase);
    }

    @Test
    void invalidOrderIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/orders/invalid").with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(getOrder);
    }

    @Test
    void delegatesJwtIdentityAndIdempotencyKeyAndReturnsCreatedOrder() throws Exception {
        var orderId = UUID.randomUUID();
        when(useCase.execute(customerId, idempotencyKey, request)).thenReturn(response(orderId));

        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json")
                        .content(VALID_BODY.strip().replaceFirst("\\{", "{\"customerId\":\"" + UUID.randomUUID() + "\",\"totalAmount\":0,")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_STOCK"))
                .andExpect(jsonPath("$.totalAmount").value(20))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        verify(useCase).execute(customerId, idempotencyKey, request);
        verifyNoMoreInteractions(useCase);
    }

    @Test
    void requiresIdempotencyHeader() throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsInvalidIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    @Test
    void nullUseCaseResultCannotBeReportedAsCreated() throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(VALID_BODY))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Location"));
        verify(useCase).execute(customerId, idempotencyKey, request);
    }

    @Test
    void missingOrderIdCannotBeReportedAsCreated() throws Exception {
        when(useCase.execute(customerId, idempotencyKey, request)).thenReturn(response(null));
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(VALID_BODY))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Location"));
    }

    @Test
    void propagatesUseCaseFailureWithoutSuccessLocation() throws Exception {
        when(useCase.execute(customerId, idempotencyKey, request)).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "Checkout conflict"));
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(VALID_BODY))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"));
    }

    private OrderResponse response(UUID id) {
        return new OrderResponse(id, OrderStatus.PENDING_STOCK,
                List.of(new OrderItemResponse(UUID.randomUUID(), "Product", 2, BigDecimal.TEN, new BigDecimal("20.00"))),
                new BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20.00"),
                "EUR", null, Instant.parse("2026-10-24T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"shippingAddress\":null}", "{\"shippingAddress\":{}}"})
    void requiresCompleteShippingAddress(String body) throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsInvalidCountryCode() throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json")
                        .content(VALID_BODY.replace("\"PT\"", "\"Portugal\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }
}
