package br.com.hadryan.shosphere.inventory.client;

import br.com.hadryan.shosphere.inventory.config.CatalogClientConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CatalogClientTests {
    private HttpServer server;
    private HttpClient httpClient;
    private CatalogClient client;
    private final UUID productId = UUID.randomUUID();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile int delay;
    private volatile String body;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                receivedPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                if (delay > 0) {
                    try { Thread.sleep(delay); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                }
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // The timeout test closes the client connection before the response is written.
            }
        });
        body = "{\"id\":\"" + productId + "\",\"name\":\"Product\",\"price\":10,\"currency\":\"EUR\",\"status\":\"ACTIVE\"}";
        server.start();
        var config = new CatalogClientConfig();
        httpClient = config.catalogHttpClient(2000);
        client = new CatalogClient(config.catalogRestClient(httpClient, baseUrl(), 5000));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        httpClient.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE", "OUT_OF_STOCK"})
    void verifiesProductIdentityWithoutRestrictingCommercialStatusOrForwardingJwt(String productStatus) {
        body = body.replace("ACTIVE", productStatus);

        assertDoesNotThrow(() -> client.requireProduct(productId));
        assertEquals("GET /api/v1/products/" + productId, receivedPath.get());
        assertNull(authorization.get());
        assertEquals(1, requests.get());
        assertEquals(2000, httpClient.connectTimeout().orElseThrow().toMillis());
    }

    @ParameterizedTest
    @CsvSource({"404,404", "400,502", "500,502", "503,502"})
    void mapsHttpFailuresWithoutApplicationRetries(int upstreamStatus, int expectedStatus) {
        status = upstreamStatus;
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireProduct(productId));
        assertEquals(expectedStatus, failure.getStatusCode().value());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "invalid-json", "{\"id\":\"10000000-0000-0000-0000-000000000001\"}"})
    void rejectsMalformedOrMismatchedProductResponse(String responseBody) {
        body = responseBody;
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireProduct(productId));
        assertEquals(502, failure.getStatusCode().value());
    }

    @Test
    void connectionFailureIsServiceUnavailable() {
        server.stop(0);
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireProduct(productId));
        assertEquals(503, failure.getStatusCode().value());
    }

    @Test
    void configuredReadTimeoutIsEnforced() {
        delay = 1000;
        client = new CatalogClient(new CatalogClientConfig().catalogRestClient(httpClient, baseUrl(), 100));
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireProduct(productId));
        assertEquals(503, failure.getStatusCode().value());
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
