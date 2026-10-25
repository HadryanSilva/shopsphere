package br.com.hadryan.shopsphere.gateway.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayCategoryRoutingTests {
    private static final String CATEGORY_ID = "10000000-0000-0000-0000-000000000001";
    private static final String CATEGORY = "{\"id\":\"" + CATEGORY_ID + "\",\"name\":\"Books\"}";
    private static final BlockingQueue<ForwardedRequest> REQUESTS = new LinkedBlockingQueue<>();
    private static final HttpServer CATALOG = startCatalog();
    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}") private int port;
    @MockitoBean private JwtDecoder decoder;

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry properties) {
        properties.add("services.catalog.url", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
    }

    @BeforeEach
    void setUp() {
        REQUESTS.clear();
        when(decoder.decode("admin-token")).thenReturn(token("admin-token", "ADMIN"));
        when(decoder.decode("customer-token")).thenReturn(token("customer-token", "CUSTOMER"));
    }

    @AfterAll
    static void stopCatalog() {
        CATALOG.stop(0);
    }

    @Test
    void publicCategoryReadReachesCatalogWithPathAndQueryIntact() throws Exception {
        var response = send(HttpRequest.newBuilder(url("/api/v1/categories/" + CATEGORY_ID + "?lang=pt")).GET());

        assertEquals(200, response.statusCode());
        assertEquals(CATEGORY, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var forwarded = REQUESTS.poll(1, TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertEquals("GET", forwarded.method());
        assertEquals("/api/v1/categories/" + CATEGORY_ID + "?lang=pt", forwarded.uri());
        assertNull(forwarded.authorization());
        verifyNoInteractions(decoder);
    }

    @Test
    void adminCategoryCreationForwardsBodyAndTokenAndPreservesResponse() throws Exception {
        var body = "{\"name\":\"Books\",\"description\":\"Reading\"}";
        var response = send(HttpRequest.newBuilder(url("/api/v1/categories"))
                .header("Authorization", "Bearer admin-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertEquals(201, response.statusCode());
        assertEquals(CATEGORY, response.body());
        assertEquals("/api/v1/categories/" + CATEGORY_ID, response.headers().firstValue("Location").orElseThrow());
        var forwarded = REQUESTS.poll(1, TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertEquals("POST", forwarded.method());
        assertEquals("/api/v1/categories", forwarded.uri());
        assertEquals(body, forwarded.body());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(forwarded.contentType())));
        assertEquals("Bearer admin-token", forwarded.authorization());
    }

    @Test
    void anonymousCategoryCreationIsRejectedBeforeCatalog() throws Exception {
        var response = send(HttpRequest.newBuilder(url("/api/v1/categories"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(401, response.statusCode());
        assertTrue(REQUESTS.isEmpty());
    }

    @Test
    void customerCategoryCreationIsRejectedBeforeCatalog() throws Exception {
        var response = send(HttpRequest.newBuilder(url("/api/v1/categories"))
                .header("Authorization", "Bearer customer-token")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(403, response.statusCode());
        assertTrue(REQUESTS.isEmpty());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Jwt token(String value, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256")
                .subject("10000000-0000-0000-0000-000000000002")
                .claim("realm_access", Map.of("roles", List.of(role))).build();
    }

    private static HttpServer startCatalog() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    REQUESTS.add(new ForwardedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type")));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Location", "/api/v1/categories/" + CATEGORY_ID);
                    var body = CATEGORY.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 201 : 200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record ForwardedRequest(String method, String uri, String body, String authorization, String contentType) {}
}
