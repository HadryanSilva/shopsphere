package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.config.JwtSecurityConfig;
import br.com.hadryan.shosphere.inventory.config.SecurityConfig;
import br.com.hadryan.shosphere.inventory.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
import br.com.hadryan.shosphere.inventory.service.TransitionReservationUseCase;
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
class InventoryReservationTransitionHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean ReserveStockUseCase reserve;
    @MockitoBean GetReservationUseCase query;
    @MockitoBean TransitionReservationUseCase transition;
    @MockitoBean JwtDecoder decoder;
    final UUID order = UUID.randomUUID();
    String path(String operation) { return "/internal/api/v1/inventory/reservations/" + order + "/" + operation; }
    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.claim("azp", "shopsphere-order"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_SERVICE"));
    }

    @ParameterizedTest @ValueSource(strings = {"confirm", "release"})
    void transitionsWithoutBodyAndUsesJwtClient(String operation) throws Exception {
        var now = Instant.now();
        var status = operation.equals("confirm") ? InventoryReservationStatus.CONFIRMED : InventoryReservationStatus.RELEASED;
        var response = new ReservationResponse(order, status, List.of(new ReservationResponse.Item(UUID.randomUUID(), 1)),
                now.plusSeconds(900), now, now);
        when(transition.confirm("shopsphere-order", order)).thenReturn(response);
        when(transition.release("shopsphere-order", order)).thenReturn(response);
        mvc.perform(post(path(operation)).with(service())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.toString())).andExpect(jsonPath("$.status").value(status.name()));
        if (operation.equals("confirm")) verify(transition).confirm("shopsphere-order", order);
        else verify(transition).release("shopsphere-order", order);
    }

    @ParameterizedTest @ValueSource(strings = {"confirm", "release"})
    void requiresServiceRoleAndExactAzp(String operation) throws Exception {
        mvc.perform(post(path(operation))).andExpect(status().isUnauthorized());
        for (var role : List.of("ADMIN", "CUSTOMER", "INVENTORY_SERVICE")) {
            mvc.perform(post(path(operation)).with(jwt().jwt(j -> j.claim("azp", "another-service"))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)))).andExpect(status().isForbidden());
        }
        mvc.perform(post(path(operation)).with(jwt().jwt(j -> j.claim("azp", "shopsphere-order"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isForbidden());
        verifyNoInteractions(transition);
    }

    @Test void mapsStateConflictAndMissingOrInaccessibleReservation() throws Exception {
        when(transition.confirm(any(), any())).thenThrow(new ReservationStateConflictException());
        mvc.perform(post(path("confirm")).with(service())).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        when(transition.release(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        mvc.perform(post(path("release")).with(service())).andExpect(status().isNotFound());
    }

    @Test void validatesOrderIdAndDeniesUnapprovedMethods() throws Exception {
        mvc.perform(post("/internal/api/v1/inventory/reservations/invalid/confirm").with(service())).andExpect(status().isBadRequest());
        mvc.perform(get(path("release")).with(service())).andExpect(status().isForbidden());
        verifyNoInteractions(transition);
    }
}
