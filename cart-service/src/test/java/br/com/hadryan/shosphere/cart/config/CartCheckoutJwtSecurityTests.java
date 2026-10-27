package br.com.hadryan.shosphere.cart.config;

import br.com.hadryan.shosphere.cart.controller.CartCheckoutController;
import br.com.hadryan.shosphere.cart.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;
import br.com.hadryan.shosphere.cart.service.impl.CartCheckoutService;
import br.com.hadryan.shosphere.cart.service.impl.CartCheckoutSnapshot;
import br.com.hadryan.shosphere.cart.service.impl.CartCheckoutTransaction;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CartCheckoutJwtSecurityTests {
    private static final String ISSUER = "https://issuer.cart.test";
    private static final UUID ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORDER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String PATH = "/internal/api/v1/cart-checkouts/" + ID;
    private static RSAKey key;
    private static HttpServer jwks;

    @BeforeAll
    static void startJwks() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("cart-test-key").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/certs", exchange -> {
            try (exchange) {
                var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        jwks.start();
    }

    @AfterAll
    static void stopJwks() { if (jwks != null) jwks.stop(0); }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, CartCheckoutController.class, ApiExceptionHandler.class, TestConfig.class)
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs");
    }

    static Stream<String[]> operations() {
        return Stream.of(new String[]{"PUT", PATH, "{\"customerId\":\"" + CUSTOMER + "\"}"},
                new String[]{"GET", PATH, ""},
                new String[]{"POST", PATH + "/complete", "{\"orderId\":\"" + ORDER + "\"}"},
                new String[]{"POST", PATH + "/abort", "{\"customerId\":\"" + CUSTOMER + "\"}"});
    }

    @Test
    void prepareUsesAzpNotSubjectOrHeadersAndReturnsLocationAndExplicitNulls() throws Exception {
        var bearer = token(List.of("CART_CHECKOUT_SERVICE"), "shopsphere-order");
        runner().run(context -> {
            var service = context.getBean(CartCheckoutService.class);
            var snapshot = snapshot(CartCheckoutStatus.PREPARED);
            when(service.prepare(ID, CUSTOMER, "shopsphere-order"))
                    .thenReturn(new CartCheckoutTransaction.Preparation(snapshot, true))
                    .thenReturn(new CartCheckoutTransaction.Preparation(snapshot, false));
            var mvc = mvc(context.getSourceApplicationContext());
            for (int status : List.of(201, 200)) {
                var result = mvc.perform(put(PATH).header("Authorization", "Bearer " + bearer)
                                .header("X-Service-Client", "spoofed-client").contentType("application/json")
                                .content("{\"customerId\":\"" + CUSTOMER + "\"}"))
                        .andExpect(status().is(status)).andExpect(header().string("Location", PATH))
                        .andExpect(jsonPath("$.checkoutId").value(ID.toString()))
                        .andExpect(jsonPath("$.customerId").value(CUSTOMER.toString()))
                        .andExpect(jsonPath("$.status").value("PREPARED"))
                        .andExpect(jsonPath("$.items[0].quantity").value(2))
                        .andExpect(jsonPath("$.orderId").value(nullValue()))
                        .andExpect(jsonPath("$.completedAt").value(nullValue()))
                        .andExpect(jsonPath("$.abortedAt").value(nullValue()))
                        .andExpect(content().string(containsString("\"orderId\":null"))).andReturn();
                assertThat(result.getRequest().getSession(false)).isNull();
            }
            verify(service, times(2)).prepare(ID, CUSTOMER, "shopsphere-order");
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void getCompleteAndEarlyAbortReturnApprovedPayloads() throws Exception {
        var bearer = token(List.of("CART_CHECKOUT_SERVICE"), "shopsphere-order");
        runner().run(context -> {
            var service = context.getBean(CartCheckoutService.class);
            var completed = snapshot(CartCheckoutStatus.COMPLETED);
            var aborted = new CartCheckoutSnapshot(ID, CUSTOMER, null, CartCheckoutStatus.ABORTED,
                    List.of(), null, null, null, Instant.parse("2026-10-27T12:00:00Z"));
            when(service.get(ID, "shopsphere-order")).thenReturn(completed);
            when(service.complete(ID, ORDER, "shopsphere-order")).thenReturn(completed);
            when(service.abort(ID, CUSTOMER, "shopsphere-order")).thenReturn(aborted);
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(get(PATH).header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(ORDER.toString()));
            for (int i = 0; i < 2; i++) {
                mvc.perform(post(PATH + "/complete").header("Authorization", "Bearer " + bearer)
                                .contentType("application/json").content("{\"orderId\":\"" + ORDER + "\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
                mvc.perform(post(PATH + "/abort").header("Authorization", "Bearer " + bearer)
                                .contentType("application/json").content("{\"customerId\":\"" + CUSTOMER + "\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ABORTED"))
                        .andExpect(jsonPath("$.cartId").value(nullValue())).andExpect(jsonPath("$.items").isEmpty());
            }
        });
    }

    @ParameterizedTest
    @MethodSource("operations")
    void allOperationsRequireAuthenticationBeforeCallingService(String method, String path, String body) {
        runner().run(context -> {
            mvc(context.getSourceApplicationContext()).perform(request(HttpMethod.valueOf(method), path)
                            .queryParam("sensitive", "private-value").contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.instance").value(path)).andExpect(jsonPath("$.errors").isEmpty())
                    .andExpect(content().string(not(containsString("private-value"))));
            verifyNoInteractions(context.getBean(CartCheckoutService.class));
        });
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,shopsphere-order", "ADMIN,shopsphere-web", "CUSTOMER,shopsphere-order",
            "INVENTORY_SERVICE,shopsphere-order", "CART_CHECKOUT_SERVICE,shopsphere-web", "CART_CHECKOUT_SERVICE,"})
    void requiresRealmRoleAndOrderClientTogether(String role, String client) throws Exception {
        var bearer = token(List.of(role), client);
        runner().run(context -> {
            for (var op : operations().toList()) mvc(context.getSourceApplicationContext())
                    .perform(request(HttpMethod.valueOf(op[0]), op[1]).header("Authorization", "Bearer " + bearer)
                            .contentType("application/json").content(op[2]))
                    .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(403));
            verifyNoInteractions(context.getBean(CartCheckoutService.class));
        });
    }

    @ParameterizedTest
    @CsvSource({"DELETE,/internal/api/v1/cart-checkouts/123", "PATCH,/internal/api/v1/cart-checkouts/123",
            "POST,/internal/api/v1/cart-checkouts/123", "PUT,/internal/api/v1/cart-checkouts/123/complete",
            "GET,/internal/api/v1/cart-checkouts/123/abort", "GET,/internal/unknown"})
    void unapprovedMethodsAndPathsStayDenied(String method, String path) throws Exception {
        var bearer = token(List.of("ADMIN", "CART_CHECKOUT_SERVICE"), "shopsphere-order");
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope", "malformedRealm", "malformedRoles", "malformedClient"})
    void malformedClaimsOrScopesCannotGrantServiceAccess(String variant) throws Exception {
        var claims = claims();
        switch (variant) {
            case "scope" -> claims.claim("realm_access", Map.of("roles", List.of())).claim("scope", "CART_CHECKOUT_SERVICE");
            case "malformedRealm" -> claims.claim("realm_access", "CART_CHECKOUT_SERVICE");
            case "malformedRoles" -> claims.claim("realm_access", Map.of("roles", "CART_CHECKOUT_SERVICE"));
            case "malformedClient" -> claims.claim("azp", List.of("shopsphere-order"));
        }
        var bearer = sign(key, claims);
        runner().run(context -> mvc(context.getSourceApplicationContext()).perform(get(PATH)
                        .header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingAudience", "wrongAudience", "expired", "issuer", "signature", "malformed"})
    void realDecoderRejectsInvalidTokens(String variant) throws Exception {
        var claims = claims();
        var signingKey = key;
        switch (variant) {
            case "missingAudience" -> claims.audience(List.of());
            case "wrongAudience" -> claims.audience("other-api");
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(300)));
            case "issuer" -> claims.issuer("https://other-issuer.test");
            case "signature" -> signingKey = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        }
        var bearer = variant.equals("malformed") ? "invalid-token" : sign(signingKey, claims);
        runner().run(context -> {
            mvc(context.getSourceApplicationContext()).perform(get(PATH).header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                    .andExpect(jsonPath("$.detail").value("Authentication is required."));
            verifyNoInteractions(context.getBean(CartCheckoutService.class));
        });
    }

    @Test
    void additionalAudienceCheckDoesNotChangePublicCartAuthentication() throws Exception {
        var bearer = sign(key, claims().audience(List.of()).claim("realm_access", Map.of("roles", List.of())));
        runner().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(get("/api/v1/carts/me").header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isNoContent());
            mvc.perform(get(PATH).header("Authorization", "Bearer " + bearer)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/carts/me")).andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"customerId\":null}", "{\"customerId\":\"invalid\"}", "null"})
    void invalidPreparationBodiesAreRejectedBeforeCallingService(String body) throws Exception {
        var bearer = token(List.of("CART_CHECKOUT_SERVICE"), "shopsphere-order");
        runner().run(context -> {
            mvc(context.getSourceApplicationContext()).perform(put(PATH).header("Authorization", "Bearer " + bearer)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            verifyNoInteractions(context.getBean(CartCheckoutService.class));
        });
    }

    @ParameterizedTest
    @CsvSource({"complete,orderId", "abort,customerId"})
    void transitionBodiesAndCheckoutIdMustBeValid(String action, String field) throws Exception {
        var bearer = token(List.of("CART_CHECKOUT_SERVICE"), "shopsphere-order");
        runner().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (String body : List.of("{}", "{\"" + field + "\":\"invalid\"}"))
                mvc.perform(post(PATH + "/" + action).header("Authorization", "Bearer " + bearer)
                                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(get("/internal/api/v1/cart-checkouts/invalid").header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(CartCheckoutService.class));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409})
    void domainIsolationAndStateConflictsKeepTheirHttpStatus(int code) throws Exception {
        var bearer = token(List.of("CART_CHECKOUT_SERVICE"), "shopsphere-order");
        runner().run(context -> {
            when(context.getBean(CartCheckoutService.class).get(ID, "shopsphere-order"))
                    .thenThrow(new ResponseStatusException(HttpStatus.valueOf(code), "Checkout unavailable"));
            mvc(context.getSourceApplicationContext()).perform(get(PATH).header("Authorization", "Bearer " + bearer))
                    .andExpect(status().is(code)).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_CART_CHECKOUT_TOKEN", matches = ".+")
    void acceptsProvisionedLocalKeycloakServiceToken() {
        var issuer = System.getenv().getOrDefault("SHOPSPHERE_TEST_KEYCLOAK_ISSUER", "http://localhost:8180/realms/shopsphere");
        runner().withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer + "/protocol/openid-connect/certs")
                .run(context -> {
                    when(context.getBean(CartCheckoutService.class).get(ID, "shopsphere-order"))
                            .thenReturn(snapshot(CartCheckoutStatus.PREPARED));
                    mvc(context.getSourceApplicationContext()).perform(get(PATH).header("Authorization", "Bearer "
                                    + System.getenv("SHOPSPHERE_TEST_CART_CHECKOUT_TOKEN")))
                            .andExpect(status().isOk());
                });
    }

    private CartCheckoutSnapshot snapshot(CartCheckoutStatus status) {
        var instant = Instant.parse("2026-10-27T12:00:00Z");
        return new CartCheckoutSnapshot(ID, CUSTOMER, UUID.randomUUID(), status,
                List.of(new CartCheckoutSnapshot.Item(UUID.randomUUID(), 2)),
                status == CartCheckoutStatus.COMPLETED ? ORDER : null, instant,
                status == CartCheckoutStatus.COMPLETED ? instant : null, null);
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().subject("service-account-subject")
                .issuer(ISSUER).audience("shopsphere-api").issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("azp", "shopsphere-order")
                .claim("realm_access", Map.of("roles", List.of("CART_CHECKOUT_SERVICE")));
    }

    private String token(List<String> roles, String client) throws Exception {
        return sign(key, claims().claim("realm_access", Map.of("roles", roles)).claim("azp", client));
    }

    private String sign(RSAKey signer, JWTClaimsSet.Builder claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private MockMvc mvc(org.springframework.web.context.WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfig {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean CartCheckoutService cartCheckoutService() { return mock(CartCheckoutService.class); }
        @Bean PublicCartProbe publicCartProbe() { return new PublicCartProbe(); }
    }

    @RestController
    @TestComponent
    static class PublicCartProbe {
        @GetMapping("/api/v1/carts/me")
        ResponseEntity<Void> get() { return ResponseEntity.noContent().build(); }
    }
}
