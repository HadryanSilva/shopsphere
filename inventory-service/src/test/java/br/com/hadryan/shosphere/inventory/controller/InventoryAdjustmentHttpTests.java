package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.config.*;
import br.com.hadryan.shosphere.inventory.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import br.com.hadryan.shosphere.inventory.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@ContextConfiguration(classes = {InventoryController.class, SecurityConfig.class, JwtSecurityConfig.class, ApiExceptionHandler.class})
class InventoryAdjustmentHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean RegisterStockUseCase register;
    @MockitoBean AdjustStockUseCase adjust;
    @MockitoBean GetStockAdjustmentUseCase query;
    @MockitoBean JwtDecoder decoder;
    final UUID product = UUID.randomUUID(), key = UUID.randomUUID(), id = UUID.randomUUID();
    String path() { return "/api/v1/inventory/items/" + product + "/adjustments"; }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void createsAdjustmentAndExposesItsLocation(int delta) throws Exception {
        var response = new AdjustmentResponse(id, product, delta, " count ", 4, 2, Instant.now());
        when(adjust.execute("admin", key, product, new AdjustStockRequest(delta, " count "))).thenReturn(response);
        when(query.execute(product, id)).thenReturn(response);
        mvc.perform(post(path()).with(jwt().jwt(j -> j.subject("admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType("application/json")
                .content("{\"delta\":" + delta + ",\"reason\":\" count \",\"actorSubject\":\"untrusted\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", path() + "/" + id))
                .andExpect(jsonPath("$.reservedAfter").value(2));
        mvc.perform(get(path() + "/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        verify(adjust).execute("admin", key, product, new AdjustStockRequest(delta, " count "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"delta\":0,\"reason\":\"x\"}", "{\"delta\":2147483648,\"reason\":\"x\"}",
            "{\"delta\":1,\"reason\":\" \"}", "{\"delta\":1,\"reason\":null}"})
    void rejectsInvalidPayload(String body) throws Exception {
        mvc.perform(post(path()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adjust);
    }

    @Test
    void validatesHeaderReasonLengthAndPermissions() throws Exception {
        mvc.perform(post(path()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(post(path()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType("application/json")
                .content("{\"delta\":1,\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path())).andExpect(status().isUnauthorized());
        mvc.perform(get(path() + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path() + "/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adjust, query);
    }

    @Test
    void balanceConflictReturns409WithoutLocation() throws Exception {
        when(adjust.execute(any(), any(), any(), any())).thenThrow(new br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException());
        mvc.perform(post(path()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType("application/json").content("{\"delta\":-1,\"reason\":\"count\"}"))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"));
    }
}
