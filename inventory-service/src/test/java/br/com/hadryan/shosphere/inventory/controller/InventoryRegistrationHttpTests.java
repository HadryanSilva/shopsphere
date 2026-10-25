package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.config.JwtSecurityConfig;
import br.com.hadryan.shosphere.inventory.config.SecurityConfig;
import br.com.hadryan.shosphere.inventory.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.service.RegisterStockUseCase;
import br.com.hadryan.shosphere.inventory.service.AdjustStockUseCase;
import br.com.hadryan.shosphere.inventory.service.GetStockAdjustmentUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@ContextConfiguration(classes = {InventoryController.class, SecurityConfig.class, JwtSecurityConfig.class, ApiExceptionHandler.class})
class InventoryRegistrationHttpTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private RegisterStockUseCase useCase;
    @MockitoBean private AdjustStockUseCase adjustStock;
    @MockitoBean private GetStockAdjustmentUseCase getAdjustment;
    @MockitoBean private JwtDecoder decoder;
    private final UUID productId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final String actor = UUID.randomUUID().toString();

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void registersUsingJwtActorAndIgnoresUntrustedCounters(int quantity) throws Exception {
        var request = new RegisterStockRequest(productId, quantity);
        when(useCase.execute(actor, key, request)).thenReturn(new StockResponse(productId, quantity, 0, quantity, 0L, Instant.now()));

        mvc.perform(post("/api/v1/inventory/items")
                        .with(jwt().jwt(token -> token.subject(actor)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"onHand\":" + quantity
                                + ",\"reserved\":99,\"actorSubject\":\"someone-else\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/inventory/items/" + productId))
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.onHand").value(quantity))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(quantity))
                .andExpect(jsonPath("$.version").value(0));
        verify(useCase).execute(actor, key, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"productId\":null,\"onHand\":1}", "{\"productId\":\"invalid\",\"onHand\":1}"})
    void rejectsInvalidOrMissingFields(String body) throws Exception {
        assertInvalid(body, key.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "2147483648"})
    void rejectsInvalidQuantity(String quantity) throws Exception {
        assertInvalid("{\"productId\":\"" + productId + "\",\"onHand\":" + quantity + "}", key.toString());
    }

    @Test
    void requiresIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/inventory/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsInvalidIdempotencyKey() throws Exception {
        assertInvalid("{\"productId\":\"" + productId + "\",\"onHand\":1}", "invalid");
    }

    @Test
    void anonymousCannotRegisterStock() throws Exception {
        mvc.perform(post("/api/v1/inventory/items")).andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "INVENTORY_SERVICE"})
    void nonAdminCannotRegisterStock(String role) throws Exception {
        mvc.perform(post("/api/v1/inventory/items").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test
    void invalidAuthenticatedSubjectCannotBecomeAuditActor() throws Exception {
        mvc.perform(post("/api/v1/inventory/items")
                        .with(jwt().jwt(token -> token.subject(" ")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"onHand\":1}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    @Test
    void nullUseCaseResultDoesNotReportCreatedOrLocation() throws Exception {
        mvc.perform(post("/api/v1/inventory/items")
                        .with(jwt().jwt(token -> token.subject(actor)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"onHand\":1}"))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @CsvSource({"404,Product not found", "409,Stock already exists", "502,Upstream service request failed.", "503,Upstream service unavailable."})
    void preservesBusinessAndIntegrationFailuresWithoutSuccessLocation(int status, String detail) throws Exception {
        when(useCase.execute(anyString(), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status), detail));
        mvc.perform(post("/api/v1/inventory/items")
                        .with(jwt().jwt(token -> token.subject(actor)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"onHand\":1}"))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(detail)).andExpect(header().doesNotExist("Location"));
    }

    private void assertInvalid(String body, String idempotencyKey) throws Exception {
        mvc.perform(post("/api/v1/inventory/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(useCase);
    }
}
