package br.com.hadryan.shosphere.order.config;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderJwtSecurityTests {
    private static final String ISSUER = "https://issuer.shopsphere.test";
    private static final String SUBJECT = UUID.randomUUID().toString();
    private static RSAKey key;
    private static HttpServer jwksServer;

    @BeforeAll
    static void startJwksServer() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("order-test-key").generate();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/certs", exchange -> {
            var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        jwksServer.start();
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, ProbeConfig.class)
                .withInitializer(context -> {
                    try {
                        var properties = new YamlPropertySourceLoader()
                                .load("order-test", new ClassPathResource("application-test.yaml"));
                        properties.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
                        + jwksServer.getAddress().getPort() + "/certs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void anonymousOrderRequestsRequireAuthentication(String method) {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
            mvc(context.getSourceApplicationContext()).perform(request(HttpMethod.valueOf(method), "/api/v1/orders"))
                    .andExpect(status().isUnauthorized());
        });
    }

    @Test
    void validSignatureAuthenticatesJwtSubject() throws Exception {
        var token = token(key, ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"));
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(SUBJECT)));
    }

    @Test
    void authenticatedPostWorksWithoutCsrfOrHttpSession() throws Exception {
        var token = token(key, ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"));
        runner().run(context -> {
            var result = mvc(context.getSourceApplicationContext())
                    .perform(post("/api/v1/orders").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        });
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        var otherKey = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        assertUnauthorized(token(otherKey, ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api")));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertUnauthorized(token(key, ISSUER, Instant.now().minusSeconds(300), List.of("shopsphere-api")));
    }

    @Test
    void rejectsUnexpectedIssuer() throws Exception {
        assertUnauthorized(token(key, "https://other-issuer.test", Instant.now().plusSeconds(300), List.of("shopsphere-api")));
    }

    @Test
    void rejectsMalformedToken() {
        assertUnauthorized("invalid-token");
    }

    @Test
    void acceptsExpectedAudienceWhenEnabled() throws Exception {
        var token = token(key, ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"));
        runner().withPropertyValues("spring.security.oauth2.resourceserver.jwt.audiences=shopsphere-api")
                .run(context -> mvc(context.getSourceApplicationContext())
                        .perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-api", ""})
    void rejectsWrongOrMissingAudienceWhenEnabled(String audience) throws Exception {
        var token = token(key, ISSUER, Instant.now().plusSeconds(300),
                audience.isEmpty() ? List.of() : List.of(audience));
        runner().withPropertyValues("spring.security.oauth2.resourceserver.jwt.audiences=shopsphere-api")
                .run(context -> mvc(context.getSourceApplicationContext())
                        .perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                        .andExpect(status().isUnauthorized()));
    }

    @Test
    void audienceValidationIsOptionalUntilKeycloakMapperIsConfirmed() throws Exception {
        var token = token(key, ISSUER, Instant.now().plusSeconds(300), List.of());
        runner().withPropertyValues("spring.security.oauth2.resourceserver.jwt.audiences=")
                .run(context -> mvc(context.getSourceApplicationContext())
                        .perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk()));
    }

    @Test
    void authenticatedRequestsOutsideOrdersAreForbidden() throws Exception {
        var token = token(key, ISSUER, Instant.now().plusSeconds(300), List.of("shopsphere-api"));
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(get("/other").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()));
    }

    private void assertUnauthorized(String token) {
        runner().run(context -> mvc(context.getSourceApplicationContext())
                .perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()));
    }

    private MockMvc mvc(org.springframework.web.context.WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String token(RSAKey signingKey, String issuer, Instant expiration, List<String> audience) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject(SUBJECT).issuer(issuer).audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiration)).build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    static class ProbeConfig {
        @Bean
        SecurityProbeController securityProbeController() {
            return new SecurityProbeController();
        }
    }

    // Isola os filtros de segurança do checkout, que ainda está em implementação.
    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class SecurityProbeController {
        @GetMapping("/api/v1/orders")
        String subject(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

        @PostMapping("/api/v1/orders")
        ResponseEntity<Void> create() {
            return ResponseEntity.noContent().build();
        }
    }
}
