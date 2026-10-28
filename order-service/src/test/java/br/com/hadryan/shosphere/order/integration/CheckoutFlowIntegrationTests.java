package br.com.hadryan.shosphere.order.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static br.com.hadryan.shosphere.order.integration.CheckoutTestRuntime.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_CHECKOUT_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class CheckoutFlowIntegrationTests {
    private CheckoutTestRuntime runtime;

    @BeforeAll
    void start() throws Exception {
        runtime = new CheckoutTestRuntime();
        runtime.start();
    }

    @AfterAll
    void stop() throws Exception { if (runtime != null) runtime.close(); }

    @AfterEach
    void resetNetwork() {
        runtime.cartProxy.reset();
        runtime.catalogProxy.reset();
        runtime.serviceRole.set(true);
    }

    private Map<String, Object> request() {
        return Map.of("shippingAddress", Map.of("recipient", "Integration customer", "addressLine", "Test street",
                "city", "Lisbon", "postalCode", "1000-001", "countryCode", "PT"),
                "items", List.of(Map.of("productId", UUID.randomUUID(), "quantity", 999)));
    }

    private java.net.http.HttpResponse<String> checkout(UUID customer, UUID key) throws Exception {
        return runtime.request(runtime.gateway(), "POST", "/api/v1/orders", runtime.user(customer), request(), key);
    }

    private void terminal(UUID customer, UUID key, String status) throws Exception {
        await(() -> runtime.intent(customer, key).status().equals(status), 30);
    }

    @Test
    void createsThroughGatewayPreservesSnapshotReplayAndOwnerThenAllowsNewCart() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var product = runtime.product();
        var cart = runtime.cart(customer, product);
        var response = checkout(customer, key);
        var saved = runtime.expect(201, response);
        var location = response.headers().firstValue("Location").orElseThrow();
        assertTrue(location.endsWith("/api/v1/orders/" + saved.get("id").asText()));
        assertEquals("PENDING_STOCK", saved.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("25.00").compareTo(saved.get("totalAmount").decimalValue()));
        assertEquals(2, saved.get("items").get(0).get("quantity").asInt());
        assertEquals(product.toString(), saved.get("items").get(0).get("productId").asText());
        assertEquals(saved, runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + saved.get("id").asText(), runtime.user(customer), null)));
        runtime.expect(404, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + saved.get("id").asText(), runtime.user(UUID.randomUUID()), null));
        runtime.expect(404, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + saved.get("id").asText(), runtime.admin(), null));
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/products/" + product, runtime.admin(), Map.of("name", "Changed catalog", "price", 99)));
        assertEquals(saved, runtime.expect(201, checkout(customer, key)));
        runtime.expect(409, runtime.request(runtime.gateway(), "POST", "/api/v1/orders", runtime.user(customer),
                Map.of("shippingAddress", Map.of("recipient", "Different", "addressLine", "Test street", "city", "Lisbon", "postalCode", "1000-001", "countryCode", "PT")), key));
        terminal(customer, key, "COMPLETED");
        runtime.expect(404, runtime.request(runtime.gateway(), "GET", "/api/v1/carts/me", runtime.user(customer), null));
        assertNotEquals(cart, runtime.cart(customer, product));
        assertEquals(1, runtime.orders(cart));
        assertTrue(runtime.tokenRequests.get() > 0);
    }

    @Test
    void preparationBlocksMutationsAndSecondKeyWithoutLosingTheWinningSnapshot() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var loser = UUID.randomUUID();
        var product = runtime.product();
        var cart = runtime.cart(customer, product);
        runtime.cartProxy.fault = Fault.HOLD_PREPARE;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var winner = executor.submit(() -> checkout(customer, key));
            try {
                assertTrue(runtime.cartProxy.prepared.await(10, TimeUnit.SECONDS));
                runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/carts/me", runtime.user(customer), null));
                runtime.expect(409, runtime.request(runtime.gateway(), "POST", "/api/v1/carts/me/items", runtime.user(customer), Map.of("productId", product, "quantity", 1)));
                runtime.expect(409, runtime.request(runtime.gateway(), "PATCH", "/api/v1/carts/me/items/" + product, runtime.user(customer), Map.of("quantity", 8)));
                runtime.expect(409, runtime.request(runtime.gateway(), "DELETE", "/api/v1/carts/me/items/" + product, runtime.user(customer), null));
                runtime.expect(409, checkout(customer, loser));
            } finally { runtime.cartProxy.reset(); }
            var response = winner.get(20, TimeUnit.SECONDS);
            assertTrue(response.statusCode() == 201 || response.statusCode() == 503, "A gated request may time out before release");
        }
        terminal(customer, key, "COMPLETED");
        var saved = runtime.expect(201, checkout(customer, key));
        assertEquals(2, saved.get("items").get(0).get("quantity").asInt());
        assertEquals(1, runtime.orders(cart));
        assertEquals("ABORTED", runtime.intent(customer, loser).status());
        runtime.expect(409, checkout(customer, loser));
    }

    @Test
    void simultaneousSameKeyRequestsConvergeToOneOrder() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var cart = runtime.cart(customer, runtime.product());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var calls = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                assertTrue(gate.await(10, TimeUnit.SECONDS));
                return checkout(customer, key);
            })).toList();
            gate.countDown();
            var ids = new java.util.HashSet<String>();
            for (var call : calls) ids.add(runtime.expect(201, call.get(30, TimeUnit.SECONDS)).get("id").asText());
            assertEquals(1, ids.size());
        }
        terminal(customer, key, "COMPLETED");
        assertEquals(1, runtime.orders(cart));
    }

    @Test
    void lostPreparationResponseSurvivesCartAndOrderRestartsAndWorkerCreatesLater() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var cart = runtime.cart(customer, runtime.product());
        runtime.cartProxy.fault = Fault.LOSE_PREPARE;
        runtime.expect(503, checkout(customer, key));
        var pending = runtime.intent(customer, key);
        assertEquals("PREPARING", pending.status());
        assertEquals(0, runtime.orders(cart));
        var remote = runtime.expect(200, runtime.request(runtime.url("cart-service"), "GET", "/internal/api/v1/cart-checkouts/" + pending.id(), runtime.service(), null));
        assertEquals("PREPARED", remote.get("status").asText());
        runtime.restart("cart-service");
        runtime.restart("order-service");
        runtime.cartProxy.reset();
        terminal(customer, key, "COMPLETED");
        var completed = runtime.intent(customer, key);
        assertEquals(pending.id(), completed.id());
        assertEquals(1, runtime.orders(cart));
        var replay = runtime.expect(201, checkout(customer, key));
        assertEquals(completed.orderId().toString(), replay.get("id").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK_COMPLETE", "LOSE_COMPLETE"})
    void pendingCompletionReturns201AndRestartWorkerFinishesWithoutRepricing(String fault) throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var product = runtime.product();
        var cart = runtime.cart(customer, product);
        runtime.cartProxy.fault = Fault.valueOf(fault);
        var saved = runtime.expect(201, checkout(customer, key));
        var pending = runtime.intent(customer, key);
        assertEquals("COMPLETION_PENDING", pending.status());
        assertEquals(1, runtime.orders(cart));
        var remote = runtime.expect(200, runtime.request(runtime.url("cart-service"), "GET", "/internal/api/v1/cart-checkouts/" + pending.id(), runtime.service(), null));
        assertEquals(fault.equals("BLOCK_COMPLETE") ? "PREPARED" : "COMPLETED", remote.get("status").asText());
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/products/" + product, runtime.admin(), Map.of("price", 88)));
        assertEquals(saved, runtime.expect(201, checkout(customer, key)));
        runtime.restart("order-service");
        runtime.cartProxy.reset();
        terminal(customer, key, "COMPLETED");
        assertEquals(saved, runtime.expect(201, checkout(customer, key)));
        assertEquals(1, runtime.orders(cart));
        var same = runtime.expect(200, runtime.request(runtime.url("cart-service"), "POST", "/internal/api/v1/cart-checkouts/" + pending.id() + "/complete", runtime.service(), Map.of("orderId", pending.orderId())));
        assertEquals("COMPLETED", same.get("status").asText());
        runtime.expect(409, runtime.request(runtime.url("cart-service"), "POST", "/internal/api/v1/cart-checkouts/" + pending.id() + "/complete", runtime.service(), Map.of("orderId", UUID.randomUUID())));
    }

    @Test
    void lostAbortResponseRecoversAndConsumedKeyCannotCreateOrderAfterProductIsFixed() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var product = runtime.product();
        var cart = runtime.cart(customer, product);
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/products/" + product, runtime.admin(), Map.of("status", "INACTIVE")));
        runtime.cartProxy.fault = Fault.LOSE_ABORT;
        runtime.expect(409, checkout(customer, key));
        var pending = runtime.intent(customer, key);
        assertEquals("ABORT_PENDING", pending.status());
        assertEquals(0, runtime.orders(cart));
        runtime.restart("order-service");
        runtime.cartProxy.reset();
        terminal(customer, key, "ABORTED");
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/products/" + product, runtime.admin(), Map.of("status", "ACTIVE")));
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/carts/me/items/" + product, runtime.user(customer), Map.of("quantity", 3)));
        runtime.expect(409, checkout(customer, key));
        assertEquals(0, runtime.orders(cart));
        var retry = runtime.expect(201, checkout(customer, UUID.randomUUID()));
        assertEquals(3, retry.get("items").get(0).get("quantity").asInt());
        assertEquals(1, runtime.orders(cart));
    }

    @Test
    void catalogOutageKeepsPreparedSnapshotAndWorkerResumesAfterRestoration() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var cart = runtime.cart(customer, runtime.product());
        runtime.catalogProxy.fault = Fault.BLOCK_BATCH;
        runtime.expect(502, checkout(customer, key));
        assertEquals("PREPARED", runtime.intent(customer, key).status());
        assertEquals(0, runtime.orders(cart));
        runtime.expect(409, runtime.request(runtime.gateway(), "PATCH", "/api/v1/carts/me/items/" + UUID.randomUUID(), runtime.user(customer), Map.of("quantity", 3)));
        runtime.restart("order-service");
        runtime.catalogProxy.reset();
        terminal(customer, key, "COMPLETED");
        assertEquals(1, runtime.orders(cart));
    }

    @Test
    void servicePermissionFailureReturns502AndRecoversWithRestoredServiceIdentity() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var cart = runtime.cart(customer, runtime.product());
        runtime.serviceRole.set(false);
        runtime.restart("order-service");
        runtime.expect(502, checkout(customer, key));
        assertEquals("PREPARING", runtime.intent(customer, key).status());
        assertEquals(0, runtime.orders(cart));
        runtime.serviceRole.set(true);
        terminal(customer, key, "COMPLETED");
        assertEquals(1, runtime.orders(cart));
    }

    @Test
    void actualJwtFiltersProtectPublicAndInternalRoutesAndGatewayDoesNotExposeProtocol() throws Exception {
        var path = "/internal/api/v1/cart-checkouts/" + UUID.randomUUID();
        for (var base : List.of(runtime.gateway(), runtime.url("order-service")))
            runtime.expect(401, runtime.request(base, "POST", "/api/v1/orders", null, request(), UUID.randomUUID()));
        runtime.expect(401, runtime.request(runtime.url("cart-service"), "GET", path, null, null));
        runtime.expect(403, runtime.request(runtime.url("cart-service"), "GET", path, runtime.admin(), null));
        runtime.expect(403, runtime.request(runtime.url("cart-service"), "GET", path,
                runtime.token(UUID.randomUUID(), "shopsphere-web", List.of("CART_CHECKOUT_SERVICE"), "shopsphere-api"), null));
        runtime.expect(401, runtime.request(runtime.url("cart-service"), "GET", path,
                runtime.token(UUID.randomUUID(), "shopsphere-order", List.of("CART_CHECKOUT_SERVICE"), "other-audience"), null));
        for (var token : List.of(runtime.admin(), runtime.service())) {
            runtime.expect(403, runtime.request(runtime.gateway(), "GET", path, token, null));
            runtime.expect(403, runtime.request(runtime.gateway(), "PUT", path, token, Map.of("customerId", UUID.randomUUID())));
        }
        runtime.expect(404, runtime.request(runtime.url("cart-service"), "GET", path, runtime.service(), null));
        runtime.expect(401, runtime.request(runtime.url("order-service"), "GET", "/api/v1/orders/" + runtime.legacyOrder, "invalid-token", null));
    }

    @Test
    void historicalOrderRemainsQueryableAfterRealV4UpgradeAndApplicationRestart() throws Exception {
        var saved = runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + runtime.legacyOrder, runtime.user(runtime.legacyCustomer), null));
        assertEquals("COMPLETED", saved.get("status").asText());
        assertEquals("Historical snapshot", saved.get("items").get(0).get("productName").asText());
        assertNull(runtime.jdbc.queryForObject("SELECT source_cart_id FROM " + runtime.schemas.get("order-service") + ".orders WHERE id = ?", UUID.class, runtime.legacyOrder));
        runtime.restart("order-service");
        assertEquals(saved, runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + runtime.legacyOrder, runtime.user(runtime.legacyCustomer), null)));
        runtime.expect(404, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + runtime.legacyOrder, runtime.user(UUID.randomUUID()), null));
    }

    @Test
    void lostPublicCreationResponseIsRecoveredByTheSameKeyWithoutDuplicatingOrder() throws Exception {
        var customer = UUID.randomUUID();
        var key = UUID.randomUUID();
        var cart = runtime.cart(customer, runtime.product());
        try (var proxy = runtime.new FaultProxy(runtime.gateway(), false)) {
            proxy.fault = Fault.LOSE_ORDER_RESPONSE;
            var call = java.net.http.HttpRequest.newBuilder(java.net.URI.create(proxy.url() + "/api/v1/orders"))
                    .timeout(java.time.Duration.ofSeconds(1)).header("Authorization", "Bearer " + runtime.user(customer))
                    .header("Idempotency-Key", key.toString()).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(runtime.json.writeValueAsString(request()))).build();
            assertThrows(java.net.http.HttpTimeoutException.class, () -> runtime.http.send(call, java.net.http.HttpResponse.BodyHandlers.ofString()));
            await(() -> proxy.forwarded.get() > 0, 10);
            var pending = runtime.intent(customer, key);
            assertNotNull(pending.orderId());
            var replay = runtime.expect(201, checkout(customer, key));
            assertEquals(pending.orderId().toString(), replay.get("id").asText());
            assertEquals(1, runtime.orders(cart));
        }
    }

    @Test
    void abortBeforeDelayedPreparationPermanentlyConsumesCheckoutIdWithoutLockingCart() throws Exception {
        var customer = UUID.randomUUID();
        var product = runtime.product();
        var cart = runtime.cart(customer, product);
        var path = "/internal/api/v1/cart-checkouts/" + UUID.randomUUID();
        var aborted = runtime.expect(200, runtime.request(runtime.url("cart-service"), "POST", path + "/abort", runtime.service(), Map.of("customerId", customer)));
        assertEquals("ABORTED", aborted.get("status").asText());
        assertTrue(aborted.get("cartId").isNull());
        assertTrue(aborted.get("items").isEmpty());
        runtime.expect(409, runtime.request(runtime.url("cart-service"), "PUT", path, runtime.service(), Map.of("customerId", customer)));
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/carts/me/items/" + product, runtime.user(customer), Map.of("quantity", 3)));
        runtime.expect(201, checkout(customer, UUID.randomUUID()));
        assertEquals(1, runtime.orders(cart));
    }
}
