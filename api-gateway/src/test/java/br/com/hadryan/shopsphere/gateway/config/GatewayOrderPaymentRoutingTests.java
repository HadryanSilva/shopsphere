package br.com.hadryan.shopsphere.gateway.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayOrderPaymentRoutingTests {
    private static final String ORDER = "20000000-0000-0000-0000-000000000001";
    private static final BlockingQueue<Forwarded> REQUESTS = new LinkedBlockingQueue<>();
    private static final AtomicInteger STATUS = new AtomicInteger(202);
    private static final HttpServer ORDER_SERVER = startOrder();
    @Value("${local.server.port}") private int port;
    @MockitoBean private JwtDecoder decoder;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource static void urls(DynamicPropertyRegistry properties) {
        properties.add("services.order.url", () -> "http://127.0.0.1:" + ORDER_SERVER.getAddress().getPort());
    }
    @BeforeEach void setup() {
        REQUESTS.clear(); STATUS.set(202);
        when(decoder.decode("customer-token")).thenReturn(Jwt.withTokenValue("customer-token").header("alg", "RS256")
                .subject("10000000-0000-0000-0000-000000000001").claim("realm_access", Map.of("roles", List.of("CUSTOMER"))).build());
    }
    @AfterAll static void stop() { ORDER_SERVER.stop(0); }
    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void paymentPutUsesExistingRouteAndPreservesIdentityBodyStatusAndLocation() throws Exception {
        var response = send("PUT", "/api/v1/orders/" + ORDER + "/payment", "customer-token");
        assertEquals(202, response.statusCode()); assertEquals("{\"paymentStatus\":\"PROCESSING\"}", response.body());
        assertEquals("/api/v1/orders/" + ORDER, response.headers().firstValue("Location").orElseThrow());
        var forwarded = REQUESTS.poll(1, TimeUnit.SECONDS); assertNotNull(forwarded);
        assertEquals("PUT", forwarded.method()); assertEquals("/api/v1/orders/" + ORDER + "/payment", forwarded.path());
        assertEquals("{}", forwarded.body()); assertEquals("Bearer customer-token", forwarded.authorization());
    }
    @Test void upstreamRejectionAndTerminalReplayPassThroughUnchanged() throws Exception {
        for (var status : List.of(200, 400, 404, 409, 500)) {
            STATUS.set(status);
            assertEquals(status, send("PUT", "/api/v1/orders/" + ORDER + "/payment", "customer-token").statusCode());
            assertNotNull(REQUESTS.poll(1, TimeUnit.SECONDS));
        }
    }
    @Test void anonymousPaymentPutIsUnauthorizedBeforeOrder() throws Exception {
        assertEquals(401, send("PUT", "/api/v1/orders/" + ORDER + "/payment", null).statusCode());
        assertTrue(REQUESTS.isEmpty());
    }
    @Test void directPaymentAndInternalPaymentPathsRemainUnexposed() throws Exception {
        for (var path : List.of("/internal/api/v1/payments/" + ORDER, "/api/v1/payments/" + ORDER,
                "/internal/api/v1/payments/" + ORDER + "/cancel"))
            for (var method : List.of("GET", "PUT", "POST"))
                assertEquals(403, send(method, path, "customer-token").statusCode());
        assertTrue(REQUESTS.isEmpty());
    }
    private static HttpServer startOrder() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    REQUESTS.add(new Forwarded(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            exchange.getRequestHeaders().getFirst("Authorization")));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Location", "/api/v1/orders/" + ORDER);
                    var bytes = "{\"paymentStatus\":\"PROCESSING\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(STATUS.get(), bytes.length); exchange.getResponseBody().write(bytes);
                }
            });
            server.start(); return server;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private record Forwarded(String method, String path, String body, String authorization) {}
}
