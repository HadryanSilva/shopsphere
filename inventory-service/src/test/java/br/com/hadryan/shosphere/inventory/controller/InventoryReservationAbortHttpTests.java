package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.config.JwtSecurityConfig;
import br.com.hadryan.shosphere.inventory.config.SecurityConfig;
import br.com.hadryan.shosphere.inventory.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryReservationController.class)
@ContextConfiguration(classes = {InventoryReservationController.class, SecurityConfig.class, JwtSecurityConfig.class, ApiExceptionHandler.class})
class InventoryReservationAbortHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean ReserveStockUseCase reserve;
    @MockitoBean GetReservationUseCase query;
    @MockitoBean TransitionReservationUseCase transition;
    @MockitoBean AbortReservationUseCase abort;
    @MockitoBean JwtDecoder decoder;
    final UUID order = UUID.randomUUID(), product = UUID.randomUUID();
    String path() { return "/internal/api/v1/inventory/reservations/" + order + "/abort"; }
    String body() { return "{\"items\":[{\"productId\":\"" + product + "\",\"quantity\":1}]}"; }
    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service-account-subject").claim("azp", "shopsphere-order"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_SERVICE"));
    }

    @Test void initialAndReplayReturn200WithExplicitNullReservationAndIdentityFromJwt() throws Exception {
        var payload = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 1)));
        when(abort.execute("shopsphere-order", order, payload)).thenReturn(new AbortReservationUseCase.Result(order, null));
        var supplied = body().replace("{\"items\"", "{\"serviceClientId\":\"untrusted\",\"customerId\":\"untrusted\",\"items\"");
        for (int i = 0; i < 2; i++)
            mvc.perform(post(path()).with(service()).contentType("application/json").content(supplied))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(content().json("{\"orderId\":\"" + order + "\",\"status\":\"ABORTED\",\"reservation\":null}",
                            org.springframework.test.json.JsonCompareMode.STRICT));
        verify(abort, times(2)).execute("shopsphere-order", order, payload);
        verifyNoInteractions(reserve, query, transition);
    }

    @ParameterizedTest @ValueSource(strings = {"RELEASED", "EXPIRED"})
    void serializesExistingTerminalReservationWithoutChangingItsContract(String status) throws Exception {
        var now = Instant.parse("2026-10-29T12:00:00Z");
        var reservation = new ReservationResponse(order, InventoryReservationStatus.valueOf(status),
                List.of(new ReservationResponse.Item(product, 1)), now.plusSeconds(900), now, now.plusSeconds(901));
        when(abort.execute(any(), any(), any())).thenReturn(new AbortReservationUseCase.Result(order, reservation));
        mvc.perform(post(path()).with(service()).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.reservation.orderId").value(order.toString()))
                .andExpect(jsonPath("$.reservation.status").value(status))
                .andExpect(jsonPath("$.reservation.items[0].productId").value(product.toString()))
                .andExpect(jsonPath("$.reservation.items[0].quantity").value(1))
                .andExpect(jsonPath("$.reservation.expiresAt").value(now.plusSeconds(900).toString()))
                .andExpect(jsonPath("$.reservation.createdAt").value(now.toString()))
                .andExpect(jsonPath("$.reservation.updatedAt").value(now.plusSeconds(901).toString()));
    }

    @ParameterizedTest @ValueSource(strings = {"", "{}", "null", "{\"items\":null}", "{\"items\":[]}",
            "{\"items\":[null]}", "{\"items\":[{}]}", "{\"items\":[{\"productId\":\"invalid\",\"quantity\":1}]}", "{"})
    void rejectsMissingOrMalformedBodyBeforeUseCase(String payload) throws Exception {
        mvc.perform(post(path()).with(service()).contentType("application/json").content(payload)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(abort);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "0", "-1", "2147483648"})
    void rejectsInvalidQuantities(String quantity) throws Exception {
        mvc.perform(post(path()).with(service()).contentType("application/json").content(body().replace(":1", ":" + quantity)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(abort);
    }

    @Test void rejects101ItemsAndMalformedPath() throws Exception {
        var items = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}").toList();
        mvc.perform(post(path()).with(service()).contentType("application/json").content("{\"items\":[" + String.join(",", items) + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/api/v1/inventory/reservations/invalid/abort").with(service()).contentType("application/json").content(body()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(abort);
    }

    @Test void rejectsAnonymousWrongRoleAndWrongAzpWithoutReachingUseCase() throws Exception {
        mvc.perform(post(path()).contentType("application/json").content(body())).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        for (var role : List.of("ADMIN", "CUSTOMER", "CART_CHECKOUT_SERVICE", "INVENTORY_SERVICE"))
            mvc.perform(post(path()).with(jwt().jwt(j -> j.claim("azp", "shopsphere-web"))
                            .authorities(new SimpleGrantedAuthority("ROLE_" + role))).contentType("application/json").content(body()))
                    .andExpect(status().isForbidden());
        mvc.perform(post(path()).with(jwt().jwt(j -> j.claim("azp", "shopsphere-order"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(abort);
    }

    @Test void stateAndIntentConflictsAndForeignResourceUseProblemDetails() throws Exception {
        when(abort.execute(any(), any(), any())).thenThrow(new ReservationStateConflictException());
        mvc.perform(post(path()).with(service()).contentType("application/json").content(body()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.reservation").doesNotExist());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to a different reservation intent"))
                .when(abort).execute(any(), any(), any());
        mvc.perform(post(path()).with(service()).contentType("application/json").content(body())).andExpect(status().isConflict());
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found")).when(abort).execute(any(), any(), any());
        mvc.perform(post(path()).with(service()).contentType("application/json").content(body()))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
    void deniesOtherMethodsEvenForAuthorizedService(String method) throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path()).with(service())
                        .contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(abort);
    }
}
