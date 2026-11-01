package br.com.hadryan.shosphere.order.controller;

import br.com.hadryan.shosphere.order.config.SecurityConfig;
import br.com.hadryan.shosphere.order.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import br.com.hadryan.shosphere.order.service.impl.OrderPaymentTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderPaymentController.class)
@ContextConfiguration(classes = {OrderPaymentController.class, SecurityConfig.class, ApiExceptionHandler.class})
class OrderPaymentControllerTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private OrderPaymentTransaction payment;
    @MockitoBean private JwtDecoder decoder;
    private final UUID customer = UUID.randomUUID(), id = UUID.randomUUID();
    private String path() { return "/api/v1/orders/" + id + "/payment"; }
    private OrderResponse response() {
        return new OrderResponse(id, OrderStatus.PENDING_PAYMENT, List.of(), new BigDecimal("25.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("25.00"), "EUR", "PROCESSING", Instant.parse("2026-11-01T12:00:00Z"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "{}"})
    void acceptsOnlyEmptyBodyAndUsesJwtOwnerWithoutIdempotencyKey(String body) throws Exception {
        when(payment.request(customer, id)).thenReturn(new OrderPaymentTransaction.Result(response(), false));
        mvc.perform(put(path()).with(jwt().jwt(token -> token.subject(customer.toString())))
                        .param("customerId", UUID.randomUUID().toString()).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/orders/" + id))
                .andExpect(jsonPath("$.paymentStatus").value("PROCESSING")).andExpect(jsonPath("$.totalAmount").value(25));
        verify(payment).request(customer, id); verifyNoMoreInteractions(payment);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "false", "0", "\"\"", "{broken", "{\"amount\":25}",
            "{\"currency\":\"EUR\"}", "{\"customerId\":\"other\"}", "{\"items\":[]}", "{\"card\":\"forbidden\"}", "{\"status\":\"CAPTURED\"}"})
    void invalidOrNonemptyBodyIsRejectedBeforeUseCase(String body) throws Exception {
        mvc.perform(put(path()).with(jwt().jwt(token -> token.subject(customer.toString())))
                        .contentType("application/json").content(body)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(payment);
    }

    @Test void terminalReplayIs200WithCurrentRepresentation() throws Exception {
        when(payment.request(customer, id)).thenReturn(new OrderPaymentTransaction.Result(response(), true));
        mvc.perform(put(path()).with(jwt().jwt(token -> token.subject(customer.toString()))))
                .andExpect(status().isOk()).andExpect(header().string("Location", "/api/v1/orders/" + id));
    }
    @Test void failuresAreNotAcceptedAndDoNotReturnSuccessLocation() throws Exception {
        for (var status : List.of(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.INTERNAL_SERVER_ERROR)) {
            doThrow(new ResponseStatusException(status)).when(payment).request(customer, id);
            mvc.perform(put(path()).with(jwt().jwt(token -> token.subject(customer.toString()))))
                    .andExpect(status().is(status.value())).andExpect(header().doesNotExist("Location"));
        }
    }
    @Test void anonymousAndInvalidIdNeverInvokeUseCase() throws Exception {
        mvc.perform(put(path())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/orders/invalid/payment").with(jwt())).andExpect(status().isBadRequest());
        verifyNoInteractions(payment);
    }
}
