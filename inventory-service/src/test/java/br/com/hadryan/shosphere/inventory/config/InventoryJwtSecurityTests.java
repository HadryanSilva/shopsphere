package br.com.hadryan.shosphere.inventory.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryJwtSecurityTests {
    private static final String ISSUER = "https://issuer.inventory.test";
    private static final String ITEM = "/api/v1/inventory/items/10000000-0000-0000-0000-000000000001";
    private static final String RESERVATION = "/internal/api/v1/inventory/reservations/20000000-0000-0000-0000-000000000001";
    private static RSAKey key;
    private static HttpServer jwksServer;

    @BeforeAll
    static void startJwksServer() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("inventory-test-key").generate();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/certs", exchange -> {
            try (exchange) {
                var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        jwksServer.start();
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, JwtSecurityConfig.class, ProbeConfig.class)
                .withPropertyValues("spring.profiles.active=default",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
                                + jwksServer.getAddress().getPort() + "/certs");
    }

    static Stream<String[]> adminOperations() {
        return Stream.of(new String[] {"POST", "/api/v1/inventory/items"},
                new String[] {"GET", ITEM}, new String[] {"GET", "/api/v1/inventory/items/batch"},
                new String[] {"POST", ITEM + "/adjustments"},
                new String[] {"GET", ITEM + "/adjustments/30000000-0000-0000-0000-000000000001"});
    }

    static Stream<String[]> internalOperations() {
        return Stream.of(new String[] {"PUT", RESERVATION}, new String[] {"GET", RESERVATION},
                new String[] {"POST", RESERVATION + "/confirm"}, new String[] {"POST", RESERVATION + "/release"});
    }

    @ParameterizedTest
    @MethodSource("adminOperations")
    void adminCanUseOnlyApprovedAdministrativeOperations(String method, String path) throws Exception {
        var jwt = token(List.of("ADMIN"), "shopsphere-web");
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
            var result = mvc(context.getSourceApplicationContext())
                    .perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isNoContent()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("internalOperations")
    void authorizedOrderServiceCanUseReservationOperations(String method, String path) throws Exception {
        var jwt = token(List.of("INVENTORY_SERVICE"), "shopsphere-order");
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent()));
    }

    @ParameterizedTest
    @MethodSource("adminOperations")
    void customerCannotUseAdministrativeOperations(String method, String path) throws Exception {
        assertForbidden(method, path, token(List.of("CUSTOMER"), "shopsphere-web"));
    }

    @ParameterizedTest
    @MethodSource("adminOperations")
    void serviceRoleDoesNotGrantAdministrativeAccess(String method, String path) throws Exception {
        assertForbidden(method, path, token(List.of("INVENTORY_SERVICE"), "shopsphere-order"));
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,shopsphere-web", "ADMIN,shopsphere-order", "INVENTORY_SERVICE,shopsphere-web",
            "CUSTOMER,shopsphere-order", "INVENTORY_SERVICE,"})
    void reservationsRequireServiceRoleAndCorrectClientTogether(String role, String client) throws Exception {
        var jwt = token(List.of(role), client);
        for (var operation : internalOperations().toList()) {
            assertForbidden(operation[0], operation[1], jwt);
        }
    }

    @Test
    void anonymousRequestsRequireBearerAuthenticationAndReturnProblemDetails() {
        runner().run(context -> mvc(context.getSourceApplicationContext()).perform(get(ITEM).queryParam("secret", "private-value"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.instance").value(ITEM))
                .andExpect(jsonPath("$.errors").isArray()).andExpect(content().string(not(containsString("private-value")))));
    }

    @ParameterizedTest
    @CsvSource({"DELETE,/api/v1/inventory/items/123", "PATCH,/api/v1/inventory/items/123",
            "PUT,/api/v1/inventory/items/123", "GET,/api/v1/inventory/items/123/private", "GET,/outside",
            "POST,/internal/api/v1/inventory/reservations", "DELETE,/internal/api/v1/inventory/reservations/123",
            "PUT,/internal/api/v1/inventory/reservations/123/confirm"})
    void deniesUnapprovedRoutesAndMethodsEvenWithBothRoles(String method, String path) throws Exception {
        assertForbidden(method, path, token(List.of("ADMIN", "INVENTORY_SERVICE"), "shopsphere-order"));
    }

    @Test
    void scopesCannotReplaceRealmRoles() throws Exception {
        var claims = claims(ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"), List.of(), "shopsphere-order")
                .claim("scope", "ADMIN INVENTORY_SERVICE");
        var jwt = sign(key, claims);
        assertForbidden("GET", ITEM, jwt);
        assertForbidden("PUT", RESERVATION, jwt);
    }

    @Test
    void malformedRealmRolesFailClosed() throws Exception {
        var jwt = sign(key, claims(ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"), List.of(), "shopsphere-order")
                .claim("realm_access", "ADMIN"));
        assertForbidden("GET", ITEM, jwt);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        var otherKey = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        assertUnauthorized(sign(otherKey, claims(ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"), List.of("ADMIN"), "shopsphere-web")));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertUnauthorized(sign(key, claims(ISSUER, Instant.now().minusSeconds(300), List.of("shopsphere-api"), List.of("ADMIN"), "shopsphere-web")));
    }

    @Test
    void rejectsUnexpectedIssuer() throws Exception {
        assertUnauthorized(sign(key, claims("https://other-issuer.test", Instant.now().plusSeconds(300), List.of("shopsphere-api"), List.of("ADMIN"), "shopsphere-web")));
    }

    @ParameterizedTest
    @CsvSource({"other-api", "''"})
    void rejectsWrongOrMissingAudience(String audience) throws Exception {
        assertUnauthorized(sign(key, claims(ISSUER, Instant.now().plusSeconds(300),
                audience.isEmpty() ? List.of() : List.of(audience), List.of("ADMIN"), "shopsphere-web")));
    }

    @Test
    void rejectsMalformedToken() {
        assertUnauthorized("invalid-token");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN", matches = ".+")
    void acceptsProvisionedLocalKeycloakServiceToken() {
        var issuer = System.getenv().getOrDefault("SHOPSPHERE_TEST_KEYCLOAK_ISSUER", "http://localhost:8180/realms/shopsphere");
        runner().withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer + "/protocol/openid-connect/certs")
                .run(context -> mvc(context.getSourceApplicationContext())
                        .perform(put(RESERVATION).header("Authorization", "Bearer "
                                + System.getenv("SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN")))
                        .andExpect(status().isNoContent()));
    }

    private void assertForbidden(String method, String path, String jwt) {
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.errors").isArray()));
    }

    private void assertUnauthorized(String jwt) {
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(get(ITEM).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.detail").value("Authentication is required.")));
    }

    private String token(List<String> roles, String client) throws JOSEException {
        return sign(key, claims(ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"), roles, client));
    }

    private JWTClaimsSet.Builder claims(String issuer, Instant expiration, List<String> audience, List<String> roles, String client) {
        var claims = new JWTClaimsSet.Builder().subject("10000000-0000-0000-0000-000000000002")
                .issuer(issuer).audience(audience).issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiration)).claim("realm_access", Map.of("roles", roles));
        if (client != null) claims.claim("azp", client);
        return claims;
    }

    private String sign(RSAKey signingKey, JWTClaimsSet.Builder claims) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void correlationWrapsSecurityRejectionsAndSuccessfulRequests() throws Exception {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
                    .addFilters(new RequestCorrelationFilter()).apply(springSecurity()).build();
            var unauthenticated = mvc.perform(get(ITEM)).andExpect(status().isUnauthorized())
                    .andExpect(header().exists("X-Request-ID")).andReturn();
            var denied = mvc.perform(get(ITEM).header("Authorization", "Bearer "
                            + token(List.of("CUSTOMER"), "shopsphere-web")))
                    .andExpect(status().isForbidden()).andExpect(header().exists("X-Request-ID")).andReturn();
            var allowed = mvc.perform(get(ITEM).header("Authorization", "Bearer "
                            + token(List.of("ADMIN"), "shopsphere-web")))
                    .andExpect(status().isNoContent()).andExpect(header().exists("X-Request-ID")).andReturn();
            assertThat(unauthenticated.getResponse().getHeader("X-Request-ID"))
                    .isNotEqualTo(denied.getResponse().getHeader("X-Request-ID"))
                    .isNotEqualTo(allowed.getResponse().getHeader("X-Request-ID"));
            assertThat(org.slf4j.MDC.get("requestId")).isNull();
        });
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    static class ProbeConfig {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean SecurityProbeController securityProbeController() { return new SecurityProbeController(); }
    }

    @RestController
    @TestComponent
    static class SecurityProbeController {
        @RequestMapping({"/api/v1/inventory/items", "/api/v1/inventory/items/{id}",
                "/api/v1/inventory/items/{id}/adjustments", "/api/v1/inventory/items/{id}/adjustments/{adjustmentId}",
                "/internal/api/v1/inventory/reservations/{orderId}",
                "/internal/api/v1/inventory/reservations/{orderId}/confirm",
                "/internal/api/v1/inventory/reservations/{orderId}/release", "/outside"})
        ResponseEntity<Void> handle() { return ResponseEntity.noContent().build(); }
    }
}
