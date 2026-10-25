package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.config.JwtSecurityConfig;
import br.com.hadryan.shosphere.inventory.config.SecurityConfig;
import br.com.hadryan.shosphere.inventory.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.inventory.exception.StockReservationConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
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
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryReservationController.class)
@ContextConfiguration(classes = {InventoryReservationController.class, SecurityConfig.class, JwtSecurityConfig.class, ApiExceptionHandler.class})
class InventoryReservationHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean ReserveStockUseCase reserve;
    @MockitoBean GetReservationUseCase query;
    @MockitoBean br.com.hadryan.shosphere.inventory.service.TransitionReservationUseCase transition;
    @MockitoBean JwtDecoder decoder;
    final UUID order = UUID.randomUUID(), product = UUID.randomUUID();
    String path() { return "/internal/api/v1/inventory/reservations/" + order; }
    String body() { return "{\"items\":[{\"productId\":\"" + product + "\",\"quantity\":1}]}"; }
    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service-account").claim("azp", "shopsphere-order"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_SERVICE"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void returns201Or200WithLocationAndUsesValidatedClient(boolean created) throws Exception {
        var now = Instant.now();
        var response = new ReservationResponse(order, InventoryReservationStatus.ACTIVE,
                List.of(new ReservationResponse.Item(product, 1)), now.plusSeconds(900), now, now);
        var request = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 1)));
        when(reserve.execute("shopsphere-order", order, request)).thenReturn(new ReserveStockUseCase.Result(created, response));
        when(query.execute("shopsphere-order", order)).thenReturn(response);
        mvc.perform(put(path()).with(service()).contentType("application/json").content(body()
                .replace("{\"items\"", "{\"serviceClientId\":\"untrusted\",\"expiresAt\":\"2000-01-01T00:00:00Z\",\"items\"")))
                .andExpect(status().is(created ? 201 : 200)).andExpect(header().string("Location", path()))
                .andExpect(jsonPath("$.orderId").value(order.toString())).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.serviceClientId").doesNotExist());
        mvc.perform(get(path()).with(service())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1));
        verify(reserve).execute("shopsphere-order", order, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":[]}", "{\"items\":[null]}", "{\"items\":[{}]}",
            "{\"items\":[{\"productId\":\"invalid\",\"quantity\":1}]}"})
    void rejectsMissingOrInvalidItems(String body) throws Exception {
        mvc.perform(put(path()).with(service()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(reserve);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "0", "-1", "2147483648"})
    void rejectsInvalidQuantity(String quantity) throws Exception {
        mvc.perform(put(path()).with(service()).contentType("application/json").content(body().replace(":1", ":" + quantity)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reserve);
    }

    @Test void rejectsOversizeBodyAndInvalidOrderId() throws Exception {
        var item = "{\"productId\":\"" + product + "\",\"quantity\":1}";
        mvc.perform(put(path()).with(service()).contentType("application/json")
                .content("{\"items\":[" + String.join(",", java.util.Collections.nCopies(101, item)) + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/api/v1/inventory/reservations/invalid").with(service())).andExpect(status().isBadRequest());
        verifyNoInteractions(reserve, query);
    }

    @Test void requiresRoleAndExactAuthorizedPartyForBothMethods() throws Exception {
        for (var method : List.of(get(path()), put(path()).contentType("application/json").content(body()))) {
            mvc.perform(method).andExpect(status().isUnauthorized());
        }
        for (var role : List.of("ADMIN", "CUSTOMER", "INVENTORY_SERVICE")) {
            var token = jwt().jwt(j -> j.claim("azp", "another-service")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
            mvc.perform(get(path()).with(token)).andExpect(status().isForbidden());
            mvc.perform(put(path()).with(token).contentType("application/json").content(body())).andExpect(status().isForbidden());
        }
        mvc.perform(get(path()).with(jwt().jwt(j -> j.claim("azp", "shopsphere-order"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(put(path()).with(jwt().jwt(j -> j.claim("azp", "shopsphere-order"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reserve, query);
    }

    @Test void mapsInsufficientBalanceAndMissingReservation() throws Exception {
        when(reserve.execute(any(), any(), any())).thenThrow(new StockReservationConflictException());
        mvc.perform(put(path()).with(service()).contentType("application/json").content(body()))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"));
        when(query.execute(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        mvc.perform(get(path()).with(service())).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
