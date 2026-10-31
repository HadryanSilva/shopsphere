package br.com.hadryan.shosphere.order.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static br.com.hadryan.shosphere.order.integration.CheckoutTestRuntime.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_STOCK_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class StockFlowIntegrationTests {
    private CheckoutTestRuntime runtime;

    @BeforeAll void start() throws Exception { runtime = new CheckoutTestRuntime(true); runtime.start(); }
    @AfterAll void stop() throws Exception {
        if (runtime != null) {
            runtime.close();
            try (var logs = java.nio.file.Files.list(runtime.logs)) {
                for (var path : logs.toList()) {
                    var content = java.nio.file.Files.readString(path);
                    assertFalse(content.contains(runtime.secret), "Service secret in process log: " + path);
                    assertFalse(content.contains("Bearer eyJ"), "JWT in process log: " + path);
                    assertFalse(content.contains("Integration customer") || content.contains("Test street"), "Shipping address in process log: " + path);
                }
            }
        }
    }
    @AfterEach void resetNetwork() throws Exception {
        runtime.inventoryProxy.reset(); runtime.cartProxy.reset(); runtime.catalogProxy.reset();
        runtime.inventoryRole.set(true); runtime.serviceRole.set(true);
        runtime.stop("order-replica");
        if (!runtime.processes.containsKey("order-service") || !runtime.processes.get("order-service").isAlive()) runtime.restart("order-service");
        if (!runtime.inventoryTtl.equals(Duration.ofMinutes(2)) || !runtime.inventoryExpiration) {
            runtime.inventoryTtl = Duration.ofMinutes(2); runtime.inventoryExpiration = true;
            runtime.restart("inventory-service");
        }
    }

    private Map<String, Object> request() {
        return Map.of("shippingAddress", Map.of("recipient", "Integration customer", "addressLine", "Test street",
                "city", "Lisbon", "postalCode", "1000-001", "countryCode", "PT"),
                "items", List.of(Map.of("productId", UUID.randomUUID(), "quantity", 999)));
    }
    private JsonNode checkout(UUID customer, UUID key) throws Exception {
        return runtime.expect(201, runtime.request(runtime.gateway(), "POST", "/api/v1/orders", runtime.user(customer), request(), key));
    }
    private UUID id(JsonNode saved) { return UUID.fromString(saved.get("id").asText()); }
    private String phase(UUID id) {
        return runtime.jdbc.queryForObject("SELECT phase FROM " + runtime.schemas.get("order-service") + ".stock_work WHERE order_id = ?", String.class, id);
    }
    private String category(UUID id) {
        return runtime.jdbc.queryForObject("SELECT last_failure_category FROM " + runtime.schemas.get("order-service") + ".stock_work WHERE order_id = ?", String.class, id);
    }
    private void terminal(UUID id, String phase) throws Exception { await(() -> phase.equals(phase(id)), 35); }
    private String reservationPath(UUID id) { return "/internal/api/v1/inventory/reservations/" + id; }
    private Object items(UUID product) { return Map.of("items", List.of(Map.of("productId", product, "quantity", 2))); }
    private JsonNode reservation(UUID id) throws Exception {
        return runtime.expect(200, runtime.request(runtime.url("inventory-service"), "GET", reservationPath(id), runtime.inventoryService(), null));
    }
    private void stock(UUID product, int quantity) throws Exception {
        runtime.expect(201, runtime.request(runtime.gateway(), "POST", "/api/v1/inventory/items", runtime.admin(), Map.of("productId", product, "onHand", quantity), UUID.randomUUID()));
    }
    private JsonNode balance(UUID product) throws Exception {
        return runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/inventory/items/" + product, runtime.admin(), null));
    }
    private JsonNode order(UUID customer, UUID id) throws Exception {
        return runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + id, runtime.user(customer), null));
    }
    private int calls(String method, UUID id) {
        return runtime.inventoryProxy.requests.getOrDefault(method + " " + reservationPath(id), new java.util.concurrent.atomic.AtomicInteger()).get();
    }
    private void sameSnapshot(JsonNode original, JsonNode current) {
        for (var field : List.of("id", "items", "subtotal", "discountAmount", "shippingAmount", "totalAmount", "currency", "paymentStatus"))
            assertEquals(original.get(field), current.get(field), field);
    }

    @Test void createsReservesAndReplaysCurrentStateWithoutRepricingOrConsumingPhysicalStock() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product();
        stock(product, 4); var cart = runtime.cart(customer, product);
        var saved = checkout(customer, key); var id = id(saved);
        assertEquals("PENDING_STOCK", saved.get("status").asText());
        terminal(id, "RESERVED");
        var current = order(customer, id);
        assertEquals("PENDING_PAYMENT", current.get("status").asText());
        assertTrue(current.get("paymentStatus") == null || current.get("paymentStatus").isNull());
        sameSnapshot(saved, current);
        assertEquals(4, balance(product).get("onHand").asInt()); assertEquals(2, balance(product).get("reserved").asInt());
        assertEquals("ACTIVE", reservation(id).get("status").asText());
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/products/" + product, runtime.admin(), Map.of("price", 99)));
        sameSnapshot(saved, checkout(customer, key));
        assertEquals("COMPLETED", runtime.intent(customer, key).status()); assertEquals(1, runtime.orders(cart));
        runtime.expect(404, runtime.request(runtime.gateway(), "GET", "/api/v1/carts/me", runtime.user(customer), null));
        runtime.expect(404, runtime.request(runtime.gateway(), "GET", "/api/v1/orders/" + id, runtime.user(UUID.randomUUID()), null));
        assertNotEquals(cart, runtime.cart(customer, product));
    }

    @Test void concurrentReplayReservesOnceAndUsesOrderIdentity() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2);
        var cart = runtime.cart(customer, product); var ids = new HashSet<UUID>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var gate = new CountDownLatch(1);
            var calls = java.util.stream.IntStream.range(0, 4).mapToObj(n -> executor.submit(() -> { gate.await(); return checkout(customer, key); })).toList();
            gate.countDown(); for (var call : calls) ids.add(id(call.get(30, TimeUnit.SECONDS)));
        }
        assertEquals(1, ids.size()); var id = ids.iterator().next(); terminal(id, "RESERVED");
        assertEquals(1, runtime.orders(cart)); assertEquals(2, balance(product).get("reserved").asInt());
        assertEquals(id.toString(), reservation(id).get("orderId").asText());
    }

    @Test void lastAvailableUnitsHaveOneWinnerAndReplenishmentCannotReviveCancelledOrder() throws Exception {
        var product = runtime.product(); stock(product, 2);
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var ka = UUID.randomUUID(); var kb = UUID.randomUUID();
        runtime.cart(a, product); runtime.cart(b, product);
        JsonNode one, two;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> checkout(a, ka)); var second = executor.submit(() -> checkout(b, kb));
            one = first.get(30, TimeUnit.SECONDS); two = second.get(30, TimeUnit.SECONDS);
        }
        await(() -> Set.of("RESERVED", "CLOSED").equals(new HashSet<>(List.of(phase(id(one)), phase(id(two))))), 35);
        var loser = phase(id(one)).equals("CLOSED") ? id(one) : id(two);
        var customer = loser.equals(id(one)) ? a : b;
        assertEquals("CANCELLED", order(customer, loser).get("status").asText());
        assertEquals(2, balance(product).get("onHand").asInt()); assertEquals(2, balance(product).get("reserved").asInt());
        assertEquals("COMPLETED", runtime.intent(a, ka).status()); assertEquals("COMPLETED", runtime.intent(b, kb).status());
        runtime.expect(201, runtime.request(runtime.gateway(), "POST", "/api/v1/inventory/items/" + product + "/adjustments", runtime.admin(), Map.of("delta", 2, "reason", "Integration replenishment"), UUID.randomUUID()));
        runtime.expect(409, runtime.request(runtime.url("inventory-service"), "PUT", reservationPath(loser), runtime.inventoryService(), items(product)));
        assertEquals("CLOSED", phase(loser));
        runtime.cart(customer, product); var fresh = checkout(customer, UUID.randomUUID()); terminal(id(fresh), "RESERVED");
        assertNotEquals(loser, id(fresh)); assertEquals(4, balance(product).get("reserved").asInt());
    }

    @Test void committedOrderWaitsForCartCompletionBeforeAnyInventoryRequest() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.cartProxy.fault = Fault.BLOCK_COMPLETE;
        var saved = checkout(customer, key); var id = id(saved);
        await(() -> runtime.jdbc.queryForObject("SELECT version FROM " + runtime.schemas.get("order-service") + ".stock_work WHERE order_id = ?", Long.class, id) > 0, 10);
        assertEquals("WAITING_CART", phase(id)); assertEquals(0, calls("PUT", id)); assertEquals(0, calls("GET", id));
        assertEquals(0, balance(product).get("reserved").asInt());
        runtime.cartProxy.reset(); terminal(id, "RESERVED"); sameSnapshot(saved, checkout(customer, key));
    }

    @Test void lostReservationResponseAndBothServiceRestartsRecoverTheSameReservation() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.LOSE_RESERVE;
        var saved = checkout(customer, key); var id = id(saved);
        await(() -> runtime.jdbc.queryForObject("SELECT count(*) FROM " + runtime.schemas.get("inventory-service") + ".inventory_reservations WHERE order_id = ?", Long.class, id) == 1, 10);
        var remote = reservation(id); runtime.stop("order-service");
        runtime.restart("inventory-service"); runtime.inventoryProxy.reset(); runtime.restart("order-service");
        terminal(id, "RESERVED");
        assertEquals(remote.get("createdAt"), reservation(id).get("createdAt")); assertEquals(remote.get("expiresAt"), reservation(id).get("expiresAt"));
        assertEquals(1, calls("PUT", id)); assertEquals(2, balance(product).get("reserved").asInt()); sameSnapshot(saved, checkout(customer, key));
    }

    @Test void lostAbortResponseRetainsBarrierThroughRestartsAndNeverNeedsReservationToExist() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.LOSE_ABORT;
        var saved = checkout(customer, key); var id = id(saved);
        await(() -> runtime.jdbc.queryForObject("SELECT count(*) FROM " + runtime.schemas.get("inventory-service") + ".inventory_reservation_coordination WHERE order_id = ? AND aborted_at IS NOT NULL", Long.class, id) == 1, 15);
        assertEquals("ABORT_PENDING", phase(id)); runtime.stop("order-service");
        runtime.restart("inventory-service"); runtime.inventoryProxy.reset(); runtime.restart("order-service");
        terminal(id, "CLOSED"); assertEquals("CANCELLED", order(customer, id).get("status").asText());
        runtime.expect(404, runtime.request(runtime.url("inventory-service"), "GET", reservationPath(id), runtime.inventoryService(), null));
        runtime.expect(409, runtime.request(runtime.url("inventory-service"), "PUT", reservationPath(id), runtime.inventoryService(), items(product)));
        sameSnapshot(saved, checkout(customer, key));
    }

    @Test void inventoryOutageKeepsCommittedOrderQueryableAndReplay201ThenRecoversWithoutRepricing() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.BLOCK_INVENTORY;
        var saved = checkout(customer, key); var id = id(saved);
        await(() -> "UPSTREAM_UNAVAILABLE".equals(category(id)), 10);
        assertEquals("PENDING_STOCK", order(customer, id).get("status").asText()); sameSnapshot(saved, checkout(customer, key));
        assertEquals("COMPLETED", runtime.intent(customer, key).status());
        runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/products/" + product, runtime.admin(), Map.of("price", 77)));
        runtime.inventoryProxy.reset(); terminal(id, "RESERVED"); sameSnapshot(saved, order(customer, id));
    }

    @Test void missingInventoryRealmRoleIsTechnicalAndRestoredIdentityResumesAutomatically() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryRole.set(false); runtime.restart("order-service");
        var saved = checkout(customer, key); var id = id(saved);
        await(() -> "AUTHENTICATION_CONFIGURATION".equals(category(id)), 15);
        assertEquals("RESERVE_PENDING", phase(id)); assertEquals("PENDING_STOCK", order(customer, id).get("status").asText());
        runtime.inventoryRole.set(true); terminal(id, "RESERVED"); assertEquals(2, balance(product).get("reserved").asInt());
    }

    @Test void malformedResponseRequiresReviewWhileAnotherOrderKeepsProgressing() throws Exception {
        var customer = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.INVALID_INVENTORY;
        var saved = checkout(customer, UUID.randomUUID()); var id = id(saved); runtime.inventoryProxy.orderFilter = id;
        terminal(id, "REVIEW_REQUIRED"); assertEquals("PENDING_STOCK", order(customer, id).get("status").asText());
        assertEquals(0, balance(product).get("reserved").asInt()); assertEquals(0, calls("PUT", id));
        var other = UUID.randomUUID(); var otherProduct = runtime.product(); stock(otherProduct, 2); runtime.cart(other, otherProduct);
        var progressing = checkout(other, UUID.randomUUID()); terminal(id(progressing), "RESERVED"); assertEquals("REVIEW_REQUIRED", phase(id));
    }

    @Test void unexpectedConfirmedReservationRequiresReviewAndDoesNotConfirmOrderOrUndoConsumption() throws Exception {
        var customer = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        var saved = checkout(customer, UUID.randomUUID()); var id = id(saved); terminal(id, "RESERVED");
        runtime.expect(200, runtime.request(runtime.url("inventory-service"), "POST", reservationPath(id) + "/confirm", runtime.inventoryService(), null));
        terminal(id, "REVIEW_REQUIRED"); assertEquals("PENDING_PAYMENT", order(customer, id).get("status").asText());
        assertEquals(0, balance(product).get("onHand").asInt()); assertEquals(0, balance(product).get("reserved").asInt());
        assertEquals("CONFIRMED", reservation(id).get("status").asText());
    }

    @Test void expiredActiveWithoutExpirationJobIsResolvedByAbortAfterRestarts() throws Exception {
        runtime.inventoryTtl = Duration.ofSeconds(3); runtime.inventoryExpiration = false; runtime.restart("inventory-service");
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.LOSE_RESERVE;
        var saved = checkout(customer, key); var id = id(saved);
        await(() -> runtime.jdbc.queryForObject("SELECT count(*) FROM " + runtime.schemas.get("inventory-service") + ".inventory_reservations WHERE order_id = ?", Long.class, id) == 1, 10);
        runtime.inventoryProxy.fault = Fault.BLOCK_INVENTORY; runtime.stop("order-service"); runtime.restart("inventory-service"); runtime.restart("order-service");
        var remote = reservation(id); assertTrue(Instant.parse(remote.get("expiresAt").asText()).isBefore(Instant.now())); assertEquals("ACTIVE", remote.get("status").asText());
        assertEquals("PENDING_STOCK", order(customer, id).get("status").asText());
        runtime.inventoryProxy.reset(); terminal(id, "CLOSED");
        assertEquals("EXPIRED", reservation(id).get("status").asText()); assertEquals(0, balance(product).get("reserved").asInt());
        assertEquals("CANCELLED", order(customer, id).get("status").asText());
    }

    @Test void twoOrderProcessesAndPutPausedLongerThanLeaseCannotCreateReservationAfterAbort() throws Exception {
        runtime.startOrderReplica();
        var customer = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.LATE_RESERVE_REJECTION;
        var saved = checkout(customer, UUID.randomUUID()); var id = id(saved);
        try {
            assertTrue(runtime.inventoryProxy.prepared.await(10, TimeUnit.SECONDS));
            terminal(id, "CLOSED"); assertEquals("CANCELLED", order(customer, id).get("status").asText());
        } finally { runtime.inventoryProxy.release.countDown(); }
        await(() -> runtime.inventoryProxy.lateStatus.get() != 0, 10);
        assertEquals(409, runtime.inventoryProxy.lateStatus.get());
        runtime.expect(404, runtime.request(runtime.url("inventory-service"), "GET", reservationPath(id), runtime.inventoryService(), null));
        assertEquals(2, balance(product).get("onHand").asInt()); assertEquals(0, balance(product).get("reserved").asInt());
        assertEquals("CLOSED", phase(id));
    }

    @Test void differingClockViewRequestsAbortButInventoryStillDecidesReleaseInsteadOfAssumedExpiry() throws Exception {
        var customer = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.CLOCK_OFFSET_INVENTORY;
        var saved = checkout(customer, UUID.randomUUID()); var id = id(saved); terminal(id, "CLOSED");
        assertEquals("CANCELLED", order(customer, id).get("status").asText());
        var actual = reservation(id);
        assertEquals("RELEASED", actual.get("status").asText());
        assertTrue(Instant.parse(actual.get("expiresAt").asText()).isAfter(Instant.now()));
        assertEquals(2, balance(product).get("onHand").asInt()); assertEquals(0, balance(product).get("reserved").asInt());
    }

    @ParameterizedTest @ValueSource(ints = {100, 101})
    void actualCartLimitIsOneAtomicReservationOrDurableAbortBeforeOrder(int size) throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var products = new ArrayList<UUID>();
        for (int n = 0; n < size; n++) {
            var product = runtime.product(); products.add(product); if (size == 100) stock(product, 1);
            runtime.expect(200, runtime.request(runtime.gateway(), "POST", "/api/v1/carts/me/items", runtime.user(customer), Map.of("productId", product, "quantity", 1)));
        }
        var cart = UUID.fromString(runtime.expect(200, runtime.request(runtime.gateway(), "GET", "/api/v1/carts/me", runtime.user(customer), null)).get("id").asText());
        if (size == 101) {
            runtime.expect(409, runtime.request(runtime.gateway(), "POST", "/api/v1/orders", runtime.user(customer), request(), key));
            await(() -> "ABORTED".equals(runtime.intent(customer, key).status()), 15);
            assertEquals(0, runtime.orders(cart));
            runtime.expect(204, runtime.request(runtime.gateway(), "PATCH", "/api/v1/carts/me/items/" + products.getFirst(), runtime.user(customer), Map.of("quantity", 2)));
            runtime.expect(409, runtime.request(runtime.gateway(), "POST", "/api/v1/orders", runtime.user(customer), request(), key));
        } else {
            var saved = checkout(customer, key); terminal(id(saved), "RESERVED"); assertEquals(100, saved.get("items").size());
            assertEquals(100, reservation(id(saved)).get("items").size()); assertEquals(1, runtime.orders(cart));
            assertEquals(1, balance(products.getFirst()).get("reserved").asInt()); assertEquals(1, balance(products.getLast()).get("reserved").asInt());
        }
    }

    @Test void failedLocalObservationCommitAfterRemoteReservationRecoversByGetWithoutSecondPut() throws Exception {
        var customer = UUID.randomUUID(); var key = UUID.randomUUID(); var product = runtime.product(); stock(product, 2); runtime.cart(customer, product);
        runtime.inventoryProxy.fault = Fault.BLOCK_INVENTORY;
        var saved = checkout(customer, key); var id = id(saved); var table = runtime.schemas.get("order-service") + ".stock_work";
        runtime.jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT reject_integrated_reserved CHECK (order_id <> '" + id + "' OR phase <> 'RESERVED')");
        try {
            runtime.inventoryProxy.reset(); await(() -> "PERSISTENCE_FAILURE".equals(category(id)), 15);
            assertEquals("ACTIVE", reservation(id).get("status").asText()); assertEquals("PENDING_STOCK", order(customer, id).get("status").asText());
        } finally { runtime.jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT reject_integrated_reserved"); }
        terminal(id, "RESERVED"); assertEquals(1, calls("PUT", id)); assertEquals(2, balance(product).get("reserved").asInt());
    }

    @Test void realInventoryFiltersAndGatewayKeepInternalProtocolPrivate() throws Exception {
        var path = reservationPath(UUID.randomUUID()); var direct = runtime.url("inventory-service");
        runtime.expect(401, runtime.request(direct, "GET", path, null, null));
        runtime.expect(403, runtime.request(direct, "GET", path, runtime.admin(), null));
        runtime.expect(403, runtime.request(direct, "GET", path, runtime.service(), null));
        runtime.expect(403, runtime.request(direct, "GET", path, runtime.token(UUID.randomUUID(), "shopsphere-web", List.of("INVENTORY_SERVICE"), "shopsphere-api"), null));
        runtime.expect(401, runtime.request(direct, "GET", path, runtime.token(UUID.randomUUID(), "shopsphere-order", List.of("INVENTORY_SERVICE"), "wrong-audience"), null));
        runtime.expect(404, runtime.request(direct, "GET", path, runtime.inventoryService(), null));
        for (var token : List.of(runtime.admin(), runtime.inventoryService())) {
            runtime.expect(403, runtime.request(runtime.gateway(), "GET", path, token, null));
            runtime.expect(403, runtime.request(runtime.gateway(), "PUT", path, token, items(UUID.randomUUID())));
            runtime.expect(403, runtime.request(runtime.gateway(), "POST", path + "/abort", token, items(UUID.randomUUID())));
        }
        assertEquals(0, runtime.jdbc.queryForObject("SELECT count(*) FROM " + runtime.schemas.get("order-service") + ".stock_work WHERE order_id = ?", Long.class, runtime.legacyOrder));
    }
}
