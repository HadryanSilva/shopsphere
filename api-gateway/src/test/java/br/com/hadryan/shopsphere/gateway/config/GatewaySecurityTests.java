package br.com.hadryan.shopsphere.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(GatewaySecurityTests.TestConfig.class)
@WebAppConfiguration
class GatewaySecurityTests {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JwtDecoder decoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(decoder);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(decoder.decode("admin-token")).thenReturn(token("admin-token", List.of("ADMIN")));
        when(decoder.decode("user-token")).thenReturn(token("user-token", List.of("CUSTOMER")));
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/v1/products", "POST,/api/v1/categories", "PATCH,/api/v1/products/123"})
    void anonymousWritesRequireAuthentication(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/v1/products", "POST,/api/v1/categories", "PATCH,/api/v1/products/123"})
    void authenticatedCustomerCannotWrite(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer user-token")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/v1/products", "POST,/api/v1/categories", "PATCH,/api/v1/products/123"})
    void adminRealmRoleAllowsWrites(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer admin-token")).andExpect(status().isNoContent());
        verify(decoder).decode("admin-token");
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/products", "/api/v1/products/123", "/api/v1/products/batch", "/api/v1/categories/123"})
    void readsRemainPublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isNoContent());
        verifyNoInteractions(decoder);
    }

    @Test
    void adminScopeDoesNotGrantAdminRole() throws Exception {
        when(decoder.decode("scope-token")).thenReturn(Jwt.withTokenValue("scope-token")
                .header("alg", "RS256").subject("customer").claim("scope", "ADMIN").build());

        mvc.perform(patch("/api/v1/products/123").header("Authorization", "Bearer scope-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerTokenIsRejected() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));

        mvc.perform(patch("/api/v1/products/123").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unspecifiedOperationsAreDeniedEvenForAdmin() throws Exception {
        mvc.perform(delete("/api/v1/products/123").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/categories/123", "/api/v1/products", "/api/v1/products/123/extra"})
    void patchPermissionIsLimitedToIndividualProducts(String path) throws Exception {
        mvc.perform(patch(path).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    private Jwt token(String value, List<String> roles) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject("customer")
                .claim("realm_access", Map.of("roles", roles)).build();
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtSecurityConfig.class})
    static class TestConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        SecurityProbeController securityProbeController() {
            return new SecurityProbeController();
        }
    }

    // These handlers isolate the real security filter chain from persistence and business validation.
    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class SecurityProbeController {
        @RequestMapping({"/api/v1/products", "/api/v1/products/{id}",
                "/api/v1/categories", "/api/v1/categories/{id}"})
        ResponseEntity<Void> handle() {
            return ResponseEntity.noContent().build();
        }
    }
}

