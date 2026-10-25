package br.com.hadryan.shopsphere.gateway.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayInventoryRoutingTests {
    private static final String ITEM = "/api/v1/inventory/items/10000000-0000-0000-0000-000000000001";
    private static final String ADJUSTMENT = ITEM + "/adjustments/10000000-0000-0000-0000-000000000002";
    private static final String STOCK_BODY = "{\"productId\":\"10000000-0000-0000-0000-000000000001\",\"onHand\":0,\"reserved\":0,\"available\":0,\"version\":0,\"updatedAt\":\"2026-10-25T12:00:00Z\"}";
    private static final String ADJUSTMENT_BODY = "{\"id\":\"10000000-0000-0000-0000-000000000002\",\"productId\":\"10000000-0000-0000-0000-000000000001\",\"delta\":-1,\"reason\":\" contagem de ação \",\"onHandAfter\":0,\"reservedAfter\":0,\"createdAt\":\"2026-10-25T12:00:00Z\"}";
    private static final BlockingQueue<ForwardedRequest> REQUESTS = new LinkedBlockingQueue<>();
    private static final BlockingQueue<StubResponse> RESPONSES = new LinkedBlockingQueue<>();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer INVENTORY = startInventory();
    private final HttpClient client = HttpClient.newHttpClient();
    @Value("${local.server.port}") private int port;
    @MockitoBean private JwtDecoder decoder;

    @DynamicPropertySource static void inventoryUrl(DynamicPropertyRegistry properties) {
        properties.add("services.inventory.url", () -> "http://127.0.0.1:" + INVENTORY.getAddress().getPort());
        properties.add("services.inventory.read-timeout", () -> 500);
    }
    @BeforeEach void setUp() {
        REQUESTS.clear(); RESPONSES.clear();
        when(decoder.decode("admin-token")).thenReturn(token("admin-token", "ADMIN"));
        when(decoder.decode("customer-token")).thenReturn(token("customer-token", "CUSTOMER"));
        when(decoder.decode("service-token")).thenReturn(token("service-token", "INVENTORY_SERVICE"));
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        when(decoder.decode("scope-token")).thenReturn(Jwt.withTokenValue("scope-token").header("alg", "RS256")
                .subject("test-admin").claim("scope", "ADMIN").build());
    }
    @AfterAll static void stopInventory() { INVENTORY.stop(0); WORKERS.close(); }

    @Test void upstreamReadTimeoutReturns503WithoutRetryingOrLeakingInternalDetails() throws Exception {
        RESPONSES.add(new StubResponse(200, "{}", "application/json", null, 1500));
        var response = send("POST", "/api/v1/inventory/items", "admin-token", "{}", "test-key");
        assertEquals(503, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertTrue(response.body().contains("Inventory is unavailable."));
        assertFalse(response.body().contains("127.0.0.1")); assertFalse(response.body().contains("Exception"));
        assertNotNull(REQUESTS.poll(1, TimeUnit.SECONDS)); assertTrue(REQUESTS.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventory/items/10000000-0000-0000-0000-000000000001",
            "/api/v1/inventory/items/batch?productIds=10000000-0000-0000-0000-000000000001&productIds=10000000-0000-0000-0000-000000000002",
            "/api/v1/inventory/items/10000000-0000-0000-0000-000000000001/adjustments/10000000-0000-0000-0000-000000000002"})
    void adminReadsPreservePathQueryTokenAndResponse(String path) throws Exception {
        var body = path.contains("batch") ? "[]" : path.contains("/adjustments/") ? ADJUSTMENT_BODY : STOCK_BODY;
        RESPONSES.add(new StubResponse(200, body, "application/json", null));
        var response = send("GET", path, "admin-token", null, null);
        assertEquals(200, response.statusCode()); assertEquals(body, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var forwarded = REQUESTS.poll(1, TimeUnit.SECONDS); assertNotNull(forwarded);
        assertEquals(path, forwarded.uri()); assertEquals("GET", forwarded.method());
        assertEquals("Bearer admin-token", forwarded.authorization());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void adminWritesPreserveBodyIdempotencyAndLocation(boolean adjustment) throws Exception {
        var path = adjustment ? ITEM + "/adjustments" : "/api/v1/inventory/items";
        var location = adjustment ? ADJUSTMENT : ITEM;
        var body = adjustment ? "{\"delta\":-1,\"reason\":\" contagem de ação \"}"
                : "{\"productId\":\"10000000-0000-0000-0000-000000000001\",\"onHand\":0}";
        var responseBody = adjustment ? ADJUSTMENT_BODY : STOCK_BODY;
        var key = "10000000-0000-0000-0000-000000000003";
        RESPONSES.add(new StubResponse(201, responseBody, "application/json", location));
        var response = send("POST", path, "admin-token", body, key);
        assertEquals(201, response.statusCode()); assertEquals(responseBody, response.body());
        assertEquals(location, response.headers().firstValue("Location").orElseThrow());
        var forwarded = REQUESTS.poll(1, TimeUnit.SECONDS); assertNotNull(forwarded);
        assertEquals("POST", forwarded.method()); assertEquals(path, forwarded.uri()); assertEquals(body, forwarded.body());
        assertEquals("Bearer admin-token", forwarded.authorization()); assertEquals(key, forwarded.idempotencyKey());
        assertTrue(forwarded.contentType().startsWith("application/json"));
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 409, 500, 502, 503})
    void preservesDownstreamProblemResponses(int status) throws Exception {
        var problem = "{\"type\":\"about:blank\",\"status\":" + status + ",\"detail\":\"Inventory failure\"}";
        RESPONSES.add(new StubResponse(status, problem, "application/problem+json", null));
        var response = send("GET", ITEM, "admin-token", null, null);
        assertEquals(status, response.statusCode()); assertEquals(problem, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertNotNull(REQUESTS.poll(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/inventory/items/123", "GET,/api/v1/inventory/items/batch?productIds=123",
            "GET,/api/v1/inventory/items/123/adjustments/456", "POST,/api/v1/inventory/items",
            "POST,/api/v1/inventory/items/123/adjustments"})
    void rejectsAnonymousAndNonAdminsBeforeForwarding(String method, String path) throws Exception {
        assertEquals(401, send(method, path, null, "{}", null).statusCode());
        for (var token : List.of("customer-token", "service-token", "scope-token")) {
            assertEquals(403, send(method, path, token, "{}", null).statusCode());
        }
        assertEquals(401, send(method, path, "invalid-token", "{}", null).statusCode());
        assertTrue(REQUESTS.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"PUT,/internal/api/v1/inventory/reservations/123", "GET,/internal/api/v1/inventory/reservations/123",
            "POST,/internal/api/v1/inventory/reservations/123/confirm", "POST,/internal/api/v1/inventory/reservations/123/release",
            "GET,/api/v1/inventory/reservations/123", "POST,/api/v1/inventory/items/123/reserve",
            "DELETE,/api/v1/inventory/items/123", "PUT,/api/v1/inventory/items/123", "PATCH,/api/v1/inventory/items/123",
            "GET,/api/v1/inventory/items", "GET,/api/v1/inventory/items/123/adjustments", "POST,/api/v1/inventory/items/batch",
            "HEAD,/api/v1/inventory/items/123", "OPTIONS,/api/v1/inventory/items/123"})
    void internalAndUnapprovedOperationsAreNotForwardedEvenForAdmin(String method, String path) throws Exception {
        for (var token : List.of("admin-token", "service-token")) {
            assertEquals(403, send(method, path, token, "{}", null).statusCode());
        }
        assertTrue(REQUESTS.isEmpty());
    }

    private HttpResponse<String> send(String method, String path, String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Jwt token(String value, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject("test-admin")
                .claim("azp", "shopsphere-order").claim("realm_access", Map.of("roles", List.of(role))).build();
    }
    private static HttpServer startInventory() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", exchange -> {
                try (exchange) {
                    REQUESTS.add(new ForwardedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"),
                            exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                    var configured = RESPONSES.poll();
                    var response = configured == null ? new StubResponse(200, "{}", "application/json", null) : configured;
                    if (response.delayMillis() > 0) {
                        try { Thread.sleep(response.delayMillis()); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    exchange.getResponseHeaders().set("Content-Type", response.contentType());
                    if (response.location() != null) exchange.getResponseHeaders().set("Location", response.location());
                    var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(response.status(), bytes.length); exchange.getResponseBody().write(bytes);
                }
            });
            server.start(); return server;
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    private record StubResponse(int status, String body, String contentType, String location, int delayMillis) {
        StubResponse(int status, String body, String contentType, String location) { this(status, body, contentType, location, 0); }
    }
    private record ForwardedRequest(String method, String uri, String body, String authorization, String contentType, String idempotencyKey) {}
}
