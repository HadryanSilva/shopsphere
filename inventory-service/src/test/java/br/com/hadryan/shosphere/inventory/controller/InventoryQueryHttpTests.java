package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.config.JwtSecurityConfig;
import br.com.hadryan.shosphere.inventory.config.SecurityConfig;
import br.com.hadryan.shosphere.inventory.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.inventory.service.impl.GetStockUseCaseImpl;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryQueryController.class)
@ContextConfiguration(classes = {InventoryQueryController.class, GetStockUseCaseImpl.class,
        SecurityConfig.class, JwtSecurityConfig.class, ApiExceptionHandler.class})
class InventoryQueryHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean InventoryStockRepository stocks;
    @MockitoBean JwtDecoder decoder;
    final String base = "/api/v1/inventory/items";

    @Test void individualReturnsZeroAndDistinguishesMissingStock() throws Exception {
        var id = UUID.randomUUID();
        when(stocks.findById(id)).thenReturn(Optional.of(new InventoryStock(id, 0)));
        mvc.perform(get(base + "/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.productId").value(id.toString()))
                .andExpect(jsonPath("$.onHand").value(0)).andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(0));
        mvc.perform(get(base + "/" + UUID.randomUUID()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test void batchAcceptsCommaSeparatedAndRepeatedParametersAndOmitsMissing() throws Exception {
        var id = UUID.randomUUID(); var missing = UUID.randomUUID();
        when(stocks.findAllById(List.of(id, missing))).thenReturn(List.of(new InventoryStock(id, Integer.MAX_VALUE)));
        for (var ids : List.of(new String[]{id + "," + missing}, new String[]{id.toString(), missing.toString()})) {
            mvc.perform(get(base + "/batch").param("productIds", ids)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].available").value(Integer.MAX_VALUE));
        }
        mvc.perform(get(base + "/batch").param("productIds", UUID.randomUUID().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", ",", "00000000-0000-0000-0000-000000000001,"})
    void rejectsInvalidBatchIds(String ids) throws Exception {
        mvc.perform(get(base + "/batch").param("productIds", ids)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(stocks);
    }

    @Test void requiresParameterAndDistinctIdsAndEnforcesLimit() throws Exception {
        var id = UUID.randomUUID().toString();
        mvc.perform(get(base + "/batch").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "/batch").param("productIds", id, id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        var ids = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);
        mvc.perform(get(base + "/batch").param("productIds", ids)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(stocks);
        mvc.perform(get(base + "/batch").param("productIds", java.util.Arrays.copyOf(ids, 100))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test void invalidIndividualUuidReturns400() throws Exception {
        mvc.perform(get(base + "/invalid").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(stocks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "INVENTORY_SERVICE"})
    void requiresAdminForBothQueries(String role) throws Exception {
        for (var path : List.of(base + "/" + UUID.randomUUID(), base + "/batch")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", "shopsphere-order"))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(stocks);
    }
}
