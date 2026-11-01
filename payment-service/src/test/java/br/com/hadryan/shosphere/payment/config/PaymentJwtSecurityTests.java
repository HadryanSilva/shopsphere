package br.com.hadryan.shosphere.payment.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentJwtSecurityTests {
    private static final String ISSUER = "https://issuer.payment.test";
    private static final String PATH = "/internal/api/v1/payments/20000000-0000-0000-0000-000000000001";
    private static RSAKey key;
    private static HttpServer server;

    @BeforeAll static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("payment-test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            try (exchange) {
                var bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, JwtSecurityConfig.class, ProbeConfig.class)
                .withPropertyValues("spring.profiles.active=default", "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
    }
    private MockMvc mvc(WebApplicationContext context) { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private JWTClaimsSet.Builder claims(List<String> roles, String client) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(UUID.randomUUID().toString()).audience("shopsphere-api")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("azp", client).claim("realm_access", Map.of("roles", roles));
    }
    private String sign(RSAKey signingKey, JWTClaimsSet.Builder claims) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
        token.sign(new RSASSASigner(signingKey)); return token.serialize();
    }
    static Stream<String[]> operations() {
        return Stream.of(new String[]{"GET", PATH}, new String[]{"PUT", PATH}, new String[]{"POST", PATH + "/cancel"});
    }

    @ParameterizedTest @MethodSource("operations")
    void authorizesOnlyPaymentRoleAndOrderAzpWithRealDecoder(String method, String path) throws Exception {
        var token = sign(key, claims(List.of("PAYMENT_SERVICE"), "shopsphere-order"));
        runner().run(context -> {
            assertNotNull(context.getBean(JwtDecoder.class));
            mvc(context.getSourceApplicationContext()).perform(request(HttpMethod.valueOf(method), path)
                    .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "CUSTOMER", "INVENTORY_SERVICE", "CART_CHECKOUT_SERVICE", "SCOPE_PAYMENT_SERVICE", "none"})
    void otherRolesNeverGrantPaymentAccess(String role) throws Exception {
        var token = sign(key, claims(role.equals("none") ? List.of() : List.of(role), "shopsphere-order"));
        assertAllOperations(token, 403);
    }

    @ParameterizedTest @ValueSource(strings = {"shopsphere-web", "other-service", "", "missing"})
    void paymentRoleWithoutCorrectClientFailsClosed(String client) throws Exception {
        var builder = claims(List.of("PAYMENT_SERVICE"), client.equals("missing") ? null : client);
        assertAllOperations(sign(key, builder), 403);
    }

    @ParameterizedTest @ValueSource(strings = {"signature", "expired", "issuer", "audience", "no-audience", "future", "malformed"})
    void invalidJwtIsUnauthorizedOnEveryOperation(String variant) throws Exception {
        var builder = claims(List.of("PAYMENT_SERVICE"), "shopsphere-order");
        var signingKey = key;
        switch (variant) {
            case "signature" -> signingKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
            case "expired" -> builder.expirationTime(Date.from(Instant.now().minusSeconds(120)));
            case "issuer" -> builder.issuer("https://wrong.payment.test");
            case "audience" -> builder.audience("other-api");
            case "no-audience" -> builder.audience((List<String>) null);
            case "future" -> builder.notBeforeTime(Date.from(Instant.now().plusSeconds(120)));
        }
        assertAllOperations(variant.equals("malformed") ? "invalid-token" : sign(signingKey, builder), 401);
    }

    @Test void scopesAndMalformedRealmRolesDoNotBecomeRealmAuthority() throws Exception {
        for (var builder : List.of(claims(List.of(), "shopsphere-order").claim("scope", "PAYMENT_SERVICE"),
                claims(List.of(), "shopsphere-order").claim("realm_access", "PAYMENT_SERVICE"),
                claims(List.of(), "shopsphere-order").claim("realm_access", Map.of("roles", "PAYMENT_SERVICE"))))
            assertAllOperations(sign(key, builder), 403);
    }

    @Test void anonymousUsesBearerAndProblemDetailsWithoutSession() {
        runner().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(put(PATH)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.errors").isArray());
            mvc.perform(put(PATH).header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PATCH", "DELETE"})
    void unapprovedMethodsAreDeniedEvenToService(String method) throws Exception {
        var token = sign(key, claims(List.of("PAYMENT_SERVICE"), "shopsphere-order"));
        runner().run(context -> mvc(context.getSourceApplicationContext()).perform(request(HttpMethod.valueOf(method), PATH)
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden()));
    }

    @Test void publicAndUnknownPathsAreDeniedEvenToAdminAndService() throws Exception {
        for (var role : List.of("PAYMENT_SERVICE", "ADMIN")) {
            var token = sign(key, claims(List.of(role), "shopsphere-order"));
            runner().run(context -> {
                for (var path : List.of("/api/v1/payments", "/actuator/health", "/v3/api-docs", "/internal/api/v1/payments", PATH + "/other"))
                    mvc(context.getSourceApplicationContext()).perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            });
        }
    }

    @Test @EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_PAYMENT_SERVICE_TOKEN", matches = ".+")
    void acceptsProvisionedLocalKeycloakServiceToken() {
        var issuer = System.getenv().getOrDefault("SHOPSPHERE_TEST_KEYCLOAK_ISSUER", "http://localhost:8180/realms/shopsphere");
        runner().withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer + "/protocol/openid-connect/certs")
                .run(context -> mvc(context.getSourceApplicationContext()).perform(put(PATH)
                        .header("Authorization", "Bearer " + System.getenv("SHOPSPHERE_TEST_PAYMENT_SERVICE_TOKEN")))
                        .andExpect(status().isNoContent()));
    }

    private void assertAllOperations(String token, int expected) {
        runner().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var operation : operations().toList()) {
                var result = mvc.perform(request(HttpMethod.valueOf(operation[0]), operation[1]).header("Authorization", "Bearer " + token))
                        .andExpect(status().is(expected)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                        .andExpect(jsonPath("$.status").value(expected)).andExpect(jsonPath("$.errors").isArray());
                if (expected == 401) result.andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
            }
        });
    }

    @TestConfiguration @EnableWebMvc @EnableWebSecurity
    static class ProbeConfig { @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); } @Bean Probe probe() { return new Probe(); } }
    @TestComponent @RestController
    static class Probe {
        @RequestMapping({PATH, PATH + "/cancel"}) ResponseEntity<Void> operation() { return ResponseEntity.noContent().build(); }
    }
}
