package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.config.CartCheckoutClientConfig;
import br.com.hadryan.shosphere.order.config.InventoryClientConfig;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.service.impl.StockWorkSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReservationClientTests {
    private HttpServer server;
    private final UUID order = UUID.randomUUID(), product = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger(), tokenCalls = new AtomicInteger(), status = new AtomicInteger(200), tokenStatus = new AtomicInteger(200);
    private final AtomicInteger delayMillis = new AtomicInteger();
    private final AtomicReference<String> response = new AtomicReference<>(), body = new AtomicReference<>(), authorization = new AtomicReference<>(), route = new AtomicReference<>();
    private final Instant created = Instant.parse("2026-10-31T12:00:00Z");

    @BeforeEach void start() throws Exception {
        response.set(reservation("ACTIVE"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(form.contains("grant_type=client_credentials"));
            assertTrue(form.contains("client_id=shopsphere-order"));
            try (exchange) {
                var bytes = "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(tokenStatus.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.createContext("/internal/api/v1/inventory/reservations/", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            route.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            try { Thread.sleep(delayMillis.get()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            try (exchange) {
                var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/redirect-must-not-be-followed");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private ApplicationContextRunner runner() {
        var url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ApplicationContextRunner().withUserConfiguration(CartCheckoutClientConfig.class, InventoryClientConfig.class)
                .withPropertyValues("checkout.snapshot.enabled=true", "services.cart.url=" + url, "services.inventory.url=" + url,
                        "checkout.snapshot.token-uri=" + url + "/token", "checkout.snapshot.client-secret=test-only-secret");
    }
    private StockWorkSnapshot work() {
        return new StockWorkSnapshot(order, UUID.randomUUID(), StockWorkPhase.RESERVE_PENDING, false, null, null,
                null, null, null, Instant.now(), 0, null, Instant.now(), Instant.now(), 0,
                List.of(new StockWorkSnapshot.Item(product, 2)));
    }
    private String reservation(String state) {
        return "{\"orderId\":\"" + order + "\",\"status\":\"" + state + "\",\"items\":[{\"productId\":\"" + product
                + "\",\"quantity\":2}],\"createdAt\":\"" + created + "\",\"expiresAt\":\"" + created.plusSeconds(900)
                + "\",\"updatedAt\":\"" + created + "\"}";
    }

    @Test void sendsOnlyPersistedItemsAndServiceCredentialsAndNeverCallsConfirmOrRelease() {
        runner().run(context -> {
            var client = context.getBean(InventoryReservationClient.class);
            var work = work();
            status.set(201);
            assertEquals(order, client.reserve(work).orderId());
            assertEquals("PUT /internal/api/v1/inventory/reservations/" + order, route.get());
            assertEquals("{\"items\":[{\"productId\":\"" + product + "\",\"quantity\":2}]}", body.get());
            assertEquals("Bearer service-token", authorization.get());
            status.set(200);
            client.reserve(work);
            client.get(work);
            assertEquals("GET /internal/api/v1/inventory/reservations/" + order, route.get());
            assertEquals("", body.get());
            response.set("{\"orderId\":\"" + order + "\",\"status\":\"ABORTED\",\"reservation\":null}");
            assertNull(client.abort(work).reservation());
            assertEquals("POST /internal/api/v1/inventory/reservations/" + order + "/abort", route.get());
            assertEquals("{\"items\":[{\"productId\":\"" + product + "\",\"quantity\":2}]}", body.get());
            assertEquals(1, tokenCalls.get());
            assertEquals(4, calls.get());
            var http = context.getBean("inventoryHttpClient", HttpClient.class);
            assertEquals(2000, http.connectTimeout().orElseThrow().toMillis());
            assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"RELEASED", "EXPIRED"})
    void acceptsTerminalAbortProof(String state) {
        response.set("{\"orderId\":\"" + order + "\",\"status\":\"ABORTED\",\"reservation\":" + reservation(state) + "}");
        runner().run(context -> assertEquals(StockReservationStatus.valueOf(state), context.getBean(InventoryReservationClient.class).abort(work()).reservation().status()));
    }

    @Test void confirmsUsingServiceIdentityAndRequiresConfirmedProof() {
        runner().run(context -> {
            var client = context.getBean(InventoryReservationClient.class);
            response.set(reservation("CONFIRMED")); assertEquals(StockReservationStatus.CONFIRMED, client.confirm(work()).status());
            assertEquals("POST /internal/api/v1/inventory/reservations/" + order + "/confirm", route.get());
            assertEquals("", body.get()); assertEquals("Bearer service-token", authorization.get());
            response.set(reservation("ACTIVE")); assertThrows(StockIntegrationFailure.class, () -> client.confirm(work()));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "active", "identity", "null-body", "wrong-status"})
    void rejectsInvalidAbortEnvelopeInsteadOfClaimingCancellation(String variant) {
        response.set(switch (variant) {
            case "missing" -> "{\"orderId\":\"" + order + "\",\"status\":\"ABORTED\"}";
            case "active" -> "{\"orderId\":\"" + order + "\",\"status\":\"ABORTED\",\"reservation\":" + reservation("ACTIVE") + "}";
            case "identity" -> "{\"orderId\":\"" + UUID.randomUUID() + "\",\"status\":\"ABORTED\",\"reservation\":null}";
            case "wrong-status" -> "{\"orderId\":\"" + order + "\",\"status\":\"RELEASED\",\"reservation\":null}";
            default -> "null";
        });
        runner().run(context -> assertEquals(StockFailureCategory.INVALID_UPSTREAM_RESPONSE,
                assertThrows(StockIntegrationFailure.class, () -> context.getBean(InventoryReservationClient.class).abort(work())).category()));
    }

    @ParameterizedTest @ValueSource(strings = {"identity", "items", "duplicates", "float", "overflow", "missing-date", "dates", "state", "malformed"})
    void rejectsIncompatibleReservations(String variant) {
        var valid = reservation("ACTIVE");
        response.set(switch (variant) {
            case "identity" -> valid.replace(order.toString(), UUID.randomUUID().toString());
            case "items" -> valid.replace(product.toString(), UUID.randomUUID().toString());
            case "duplicates" -> valid.replace("}],", "},{\"productId\":\"" + product + "\",\"quantity\":2}],");
            case "float" -> valid.replace(":2}", ":2.5}");
            case "overflow" -> valid.replace(":2}", ":2147483648}");
            case "missing-date" -> valid.replace("\"expiresAt\"", "\"missing\"");
            case "dates" -> valid.replace(created.plusSeconds(900).toString(), created.minusSeconds(1).toString());
            case "state" -> valid.replace("ACTIVE", "UNKNOWN");
            default -> "{";
        });
        runner().run(context -> assertEquals(StockFailureCategory.INVALID_UPSTREAM_RESPONSE,
                assertThrows(StockIntegrationFailure.class, () -> context.getBean(InventoryReservationClient.class).get(work())).category()));
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503, 302})
    void preservesOnlySafeStatusAndDoesNotRetryOrFollowRedirect(int code) {
        status.set(code);
        response.set("{\"detail\":\"sensitive downstream detail\"}");
        runner().run(context -> {
            var client = context.getBean(InventoryReservationClient.class);
            var failure = assertThrows(StockIntegrationFailure.class, () -> client.reserve(work()));
            assertFalse(failure.toString().contains("sensitive"));
            assertEquals(1, calls.get());
            assertEquals(code == 401 || code == 403 ? StockFailureCategory.AUTHENTICATION_CONFIGURATION
                    : code >= 500 ? StockFailureCategory.UPSTREAM_UNAVAILABLE : StockFailureCategory.INVALID_UPSTREAM_RESPONSE, failure.category());
            if (code == 401 || code == 403) {
                status.set(200); response.set(reservation("ACTIVE"));
                client.get(work());
                assertEquals(2, tokenCalls.get());
            }
        });
    }

    @ParameterizedTest @ValueSource(ints = {401, 403})
    void tokenEndpointAuthenticationFailureIsConfigurationAndNeverCallsInventory(int code) {
        tokenStatus.set(code);
        runner().run(context -> {
            assertEquals(StockFailureCategory.AUTHENTICATION_CONFIGURATION,
                    assertThrows(StockIntegrationFailure.class, () -> context.getBean(InventoryReservationClient.class).get(work())).category());
            assertEquals(0, calls.get());
            assertEquals(1, tokenCalls.get());
        });
    }

    @Test void configurationRejectsInvalidTimeoutsAndUrlAndIsInactiveWithSnapshotDisabled() {
        runner().withPropertyValues("stock.connect-timeout=0").run(context -> assertNotNull(context.getStartupFailure()));
        runner().withPropertyValues("stock.read-timeout=0").run(context -> assertNotNull(context.getStartupFailure()));
        runner().withPropertyValues("services.inventory.url=http://user:password@localhost").run(context -> assertNotNull(context.getStartupFailure()));
        runner().withPropertyValues("checkout.snapshot.enabled=false").run(context -> assertTrue(context.getBeansOfType(InventoryReservationClient.class).isEmpty()));
        assertEquals(0, calls.get());
    }

    @Test void realReadTimeoutIsTransientAndDoesNotAutomaticallyRepeatMutation() {
        delayMillis.set(250);
        runner().withPropertyValues("stock.read-timeout=50").run(context -> {
            var failure = assertThrows(StockIntegrationFailure.class,
                    () -> context.getBean(InventoryReservationClient.class).reserve(work()));
            assertEquals(StockFailureCategory.UPSTREAM_UNAVAILABLE, failure.category());
            assertEquals(1, calls.get());
        });
    }

    @Test void replayCannotRenewPreviouslyObservedExpiry() {
        var initial = work();
        var known = new StockWorkSnapshot(order, initial.checkoutId(), StockWorkPhase.RESERVED, false, null, StockReservationStatus.ACTIVE,
                created, created.plusSeconds(900), created, initial.nextAttemptAt(), 0, null, initial.createdAt(), initial.updatedAt(), 1, initial.items());
        response.set(reservation("ACTIVE").replace(created.plusSeconds(900).toString(), created.plusSeconds(901).toString()));
        runner().run(context -> assertEquals(StockFailureCategory.INVALID_UPSTREAM_RESPONSE,
                assertThrows(StockIntegrationFailure.class, () -> context.getBean(InventoryReservationClient.class).get(known)).category()));
    }
}
