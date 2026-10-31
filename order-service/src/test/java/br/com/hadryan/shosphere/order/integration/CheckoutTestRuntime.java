package br.com.hadryan.shosphere.order.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Launches packaged applications; only identity issuance and network faults are test doubles. */
final class CheckoutTestRuntime implements AutoCloseable {
    final Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    final Path logs = root.resolve("order-service/build/checkout-integration/" + UUID.randomUUID());
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
            System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
    final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    final Map<String, String> schemas = new LinkedHashMap<>();
    final Map<String, Process> processes = new LinkedHashMap<>();
    final Map<String, Integer> ports = new HashMap<>();
    final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    final RSAKey signingKey = new RSAKeyGenerator(2048).keyID("checkout-integration").generate();
    final String secret = UUID.randomUUID().toString();
    final UUID serviceSubject = UUID.randomUUID();
    final UUID legacyCustomer = UUID.randomUUID();
    final UUID legacyOrder = UUID.randomUUID();
    final AtomicBoolean serviceRole = new AtomicBoolean(true);
    final AtomicInteger tokenRequests = new AtomicInteger();
    final HttpServer issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final String issuerUrl = "http://127.0.0.1:" + issuer.getAddress().getPort();
    FaultProxy cartProxy;
    FaultProxy catalogProxy;
    FaultProxy inventoryProxy;
    final boolean withInventory;
    final AtomicBoolean inventoryRole = new AtomicBoolean(true);
    Duration inventoryTtl = Duration.ofMinutes(2);
    boolean inventoryExpiration = true;

    CheckoutTestRuntime() throws Exception { this(false); }
    CheckoutTestRuntime(boolean withInventory) throws Exception { this.withInventory = withInventory; }

    void start() throws Exception {
        Files.createDirectories(logs);
        var modules = new ArrayList<>(List.of("catalog-service", "cart-service", "order-service", "api-gateway"));
        if (withInventory) modules.add("inventory-service");
        for (var module : modules) {
            // Fail before starting any process if a required bootJar has not been built.
            jar(module);
            ports.put(module, freePort());
        }
        for (var module : modules.stream().filter(name -> !name.equals("api-gateway")).toList()) {
            var schema = "checkout_flow_" + module.substring(0, module.indexOf('-')) + "_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.execute("CREATE SCHEMA " + schema);
            schemas.put(module, schema);
        }
        // Start Order from its previous schema with a real historical order, then let the app apply V4.
        var orderSchema = schemas.get("order-service");
        Flyway.configure().dataSource(dataSource).schemas(orderSchema).defaultSchema(orderSchema).target("3")
                .locations("filesystem:" + root.resolve("order-service/src/main/resources/db/migration").toString().replace('\\', '/'))
                .load().migrate();
        jdbc.update("INSERT INTO " + orderSchema + ".orders(id, customer_id, status, total_amount, subtotal) VALUES (?, ?, 'COMPLETED', 9, 9)", legacyOrder, legacyCustomer);
        jdbc.update("INSERT INTO " + orderSchema + ".order_items(id, order_id, product_id, product_name, quantity, unit_price, line_total) VALUES (?, ?, ?, 'Historical snapshot', 1, 9, 9)", UUID.randomUUID(), legacyOrder, UUID.randomUUID());

        issuer.setExecutor(handlers);
        issuer.createContext("/certs", exchange -> reply(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString()));
        issuer.createContext("/token", exchange -> {
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var fields = new HashMap<String, String>();
            for (var pair : form.split("&")) {
                var parts = pair.split("=", 2);
                if (parts.length == 2) fields.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            if (!"POST".equals(exchange.getRequestMethod()) || !"client_credentials".equals(fields.get("grant_type"))
                    || !"shopsphere-order".equals(fields.get("client_id")) || !secret.equals(fields.get("client_secret"))) {
                reply(exchange, 401, "{}");
                return;
            }
            tokenRequests.incrementAndGet();
            var roles = new ArrayList<String>();
            if (serviceRole.get()) roles.add("CART_CHECKOUT_SERVICE");
            if (withInventory ? inventoryRole.get() : !serviceRole.get()) roles.add("INVENTORY_SERVICE");
            reply(exchange, 200, json.writeValueAsString(Map.of("access_token", token(serviceSubject, "shopsphere-order",
                    roles, "shopsphere-api"),
                    "token_type", "Bearer", "expires_in", 300)));
        });
        issuer.start();
        launch("catalog-service");
        catalogProxy = new FaultProxy(url("catalog-service"), false);
        launch("cart-service");
        cartProxy = new FaultProxy(url("cart-service"), true);
        if (withInventory) {
            launch("inventory-service");
            inventoryProxy = new FaultProxy(url("inventory-service"), true);
        }
        launch("order-service");
        launch("api-gateway");
    }

    private Path jar(String module) throws IOException {
        try (var files = Files.list(root.resolve(module + "/build/libs"))) {
            var jars = files.filter(path -> path.toString().endsWith(".jar") && !path.toString().endsWith("-plain.jar")).toList();
            assertEquals(1, jars.size(), "Build bootJar first for " + module);
            return jars.getFirst();
        }
    }

    private void launch(String module) throws Exception { launch(module, module); }

    private void launch(String module, String processKey) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", jar(module).toString(), "--server.address=127.0.0.1", "--server.port=" + ports.get(processKey),
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuerUrl,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuerUrl + "/certs",
                "--spring.security.oauth2.resourceserver.jwt.audiences=shopsphere-api",
                "--services.catalog.url=" + (module.equals("order-service") ? catalogProxy.url() : url("catalog-service")),
                "--services.cart.url=" + (module.equals("order-service") ? cartProxy.url() : url("cart-service")),
                "--services.order.url=" + url("order-service"), "--services.inventory.url="
                        + (withInventory ? module.equals("order-service") ? inventoryProxy.url() : url("inventory-service") : "http://127.0.0.1:1"),
                "--spring.jpa.show-sql=false", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.cloud.openfeign.client.config.catalog-service.readTimeout=800"));
        if (module.equals("order-service")) command.addAll(List.of("--checkout.snapshot.enabled=true",
                "--checkout.snapshot.token-uri=" + issuerUrl + "/token", "--checkout.snapshot.read-timeout=2000",
                "--checkout.snapshot.recovery.enabled=true", "--checkout.snapshot.recovery.interval=PT2S",
                "--checkout.snapshot.recovery.lease=PT2S", "--checkout.snapshot.recovery.initial-backoff=PT2S",
                "--checkout.snapshot.recovery.max-backoff=PT4S"));
        if (module.equals("order-service")) command.add("--stock.recovery.enabled=" + withInventory);
        if (module.equals("order-service") && withInventory) command.addAll(List.of(
                "--stock.recovery.interval=PT0.25S", "--stock.recovery.monitor-interval=PT0.25S",
                "--stock.recovery.lease=PT1S", "--stock.recovery.initial-backoff=PT0.5S",
                "--stock.recovery.max-backoff=PT2S", "--stock.read-timeout=2000"));
        if (module.equals("inventory-service")) command.addAll(List.of("--inventory.reservations.ttl=" + inventoryTtl,
                "--inventory.reservations.expiration.enabled=" + inventoryExpiration,
                "--inventory.reservations.expiration.delay=PT1S"));
        var builder = new ProcessBuilder(command).directory(root.resolve(module).toFile()).redirectErrorStream(true)
                .redirectOutput(logs.resolve(processKey + "-" + System.nanoTime() + ".log").toFile());
        if (schemas.containsKey(module)) {
            var schema = schemas.get(module);
            var dbUrl = Objects.requireNonNull(dataSource.getUrl());
            builder.environment().put("SPRING_DATASOURCE_URL", dbUrl + (dbUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            builder.environment().put("SPRING_DATASOURCE_USERNAME", Objects.requireNonNull(dataSource.getUsername()));
            builder.environment().put("SPRING_DATASOURCE_PASSWORD", Objects.requireNonNull(dataSource.getPassword()));
            command.add("--spring.flyway.schemas=" + schema);
            command.add("--spring.flyway.default-schema=" + schema);
            command.add("--spring.jpa.properties.hibernate.default_schema=" + schema);
        }
        builder.environment().put("SHOPSPHERE_ORDER_CLIENT_SECRET", secret);
        // Include schema arguments in the launched command as well.
        builder.command(command);
        var process = builder.start();
        processes.put(processKey, process);
        try {
            await(() -> {
                assertTrue(process.isAlive(), "Application exited: " + module + "; logs: " + logs);
                try {
                    int status = request(url(processKey), "GET", module.equals("catalog-service") || module.equals("api-gateway")
                            ? "/api/v1/products" : "/api/v1/" + (module.equals("cart-service") ? "carts/me" : "orders"), null, null).statusCode();
                    return status == 200 || status == 401;
                } catch (Exception unavailable) { return false; }
            }, 60);
        } catch (Throwable failure) { throw new IllegalStateException("Application not ready: " + module + "; logs: " + logs, failure); }
    }

    void restart(String module) throws Exception { stop(module); launch(module); }

    void startOrderReplica() throws Exception {
        ports.put("order-replica", freePort());
        launch("order-service", "order-replica");
    }

    void stop(String module) throws InterruptedException {
        var process = processes.remove(module);
        if (process != null) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Application failed to stop: " + module);
            }
        }
    }

    String url(String module) { return "http://127.0.0.1:" + ports.get(module); }
    String gateway() { return url("api-gateway"); }
    String user(UUID id) { return token(id, "shopsphere-web", List.of("CUSTOMER"), "shopsphere-api"); }
    String admin() { return token(UUID.randomUUID(), "shopsphere-web", List.of("ADMIN"), "shopsphere-api"); }
    String service() { return token(serviceSubject, "shopsphere-order", List.of("CART_CHECKOUT_SERVICE"), "shopsphere-api"); }
    String inventoryService() { return token(serviceSubject, "shopsphere-order", List.of("INVENTORY_SERVICE"), "shopsphere-api"); }

    String token(UUID subject, String azp, List<String> roles, String audience) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(issuerUrl).subject(subject.toString()).audience(audience)
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(1800)))
                    .claim("azp", azp).claim("realm_access", Map.of("roles", roles)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception failure) { throw new IllegalStateException("Unable to sign test identity"); }
    }

    HttpResponse<String> request(String base, String method, String path, String token, Object body) throws Exception {
        return request(base, method, path, token, body, null);
    }

    HttpResponse<String> request(String base, String method, String path, String token, Object body, UUID key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key.toString());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode expect(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    UUID product() throws Exception {
        var result = expect(201, request(gateway(), "POST", "/api/v1/products", admin(), Map.of(
                "name", "Integration product", "categoryId", "10000000-0000-0000-0000-000000000001", "price", new java.math.BigDecimal("12.50"), "currency", "EUR")));
        return UUID.fromString(result.get("id").asText());
    }

    UUID cart(UUID customer, UUID product) throws Exception {
        expect(200, request(gateway(), "POST", "/api/v1/carts/me/items", user(customer), Map.of("productId", product, "quantity", 2)));
        return UUID.fromString(expect(200, request(gateway(), "GET", "/api/v1/carts/me", user(customer), null)).get("id").asText());
    }

    record Intent(UUID id, String status, UUID orderId) { }
    Intent intent(UUID customer, UUID key) {
        return jdbc.queryForObject("SELECT checkout_id, status, order_id FROM " + schemas.get("order-service")
                + ".checkout_intents WHERE customer_id = ? AND idempotency_key = ?",
                (rs, row) -> new Intent(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)), customer, key.toString());
    }

    long orders(UUID cart) { return jdbc.queryForObject("SELECT count(*) FROM " + schemas.get("order-service") + ".orders WHERE source_cart_id = ?", Long.class, cart); }

    static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        var deadline = Instant.now().plusSeconds(seconds);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("Condition did not converge within " + seconds + " seconds");
    }

    private static int freePort() throws IOException { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    enum Fault { NONE, HOLD_PREPARE, LOSE_PREPARE, BLOCK_COMPLETE, LOSE_COMPLETE, LOSE_ABORT, BLOCK_BATCH, LOSE_ORDER_RESPONSE,
        BLOCK_INVENTORY, LOSE_RESERVE, LATE_RESERVE_REJECTION, INVALID_INVENTORY, CLOCK_OFFSET_INVENTORY }

    final class FaultProxy implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger forwarded = new AtomicInteger();
        final AtomicInteger lateStatus = new AtomicInteger();
        final AtomicBoolean held = new AtomicBoolean();
        final ConcurrentMap<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        volatile UUID orderFilter;
        volatile Fault fault = Fault.NONE;
        volatile CountDownLatch prepared = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        FaultProxy(String target, boolean internalCart) throws IOException {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                try {
                    var path = exchange.getRequestURI().getPath();
                    var selected = orderFilter == null || path.contains(orderFilter.toString()) ? fault : Fault.NONE;
                    var gate = release;
                    var signal = prepared;
                    var preparing = "PUT".equals(exchange.getRequestMethod());
                    requests.computeIfAbsent(exchange.getRequestMethod() + " " + path, ignored -> new AtomicInteger()).incrementAndGet();
                    if (selected == Fault.BLOCK_INVENTORY) { reply(exchange, 503, "{}"); return; }
                    if (selected == Fault.INVALID_INVENTORY) { reply(exchange, 200, "{invalid-json"); return; }
                    boolean delayedPut = selected == Fault.LATE_RESERVE_REJECTION && preparing && held.compareAndSet(false, true);
                    if (selected == Fault.LATE_RESERVE_REJECTION && preparing) {
                        if (!delayedPut) { reply(exchange, 409, "{}"); return; }
                        signal.countDown();
                        if (!gate.await(60, TimeUnit.SECONDS)) throw new IOException("Reservation gate timed out");
                    }
                    if (selected == Fault.BLOCK_COMPLETE && path.endsWith("/complete")
                            || selected == Fault.BLOCK_BATCH && path.endsWith("/batch")) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    var builder = HttpRequest.newBuilder(URI.create(target + exchange.getRequestURI())).timeout(Duration.ofSeconds(10));
                    exchange.getRequestHeaders().forEach((name, values) -> {
                        if (List.of("authorization", "content-type", "idempotency-key").contains(name.toLowerCase(Locale.ROOT)))
                            values.forEach(value -> builder.header(name, value));
                    });
                    if (internalCart) {
                        // Inspect identity only in memory; never log a JWT or credentials.
                        var authorization = exchange.getRequestHeaders().getFirst("Authorization");
                        var claims = SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet();
                        assertEquals("shopsphere-order", claims.getStringClaim("azp"));
                        assertEquals(serviceSubject.toString(), claims.getSubject());
                    }
                    builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                    var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                    var responseBody = response.body();
                    if (selected == Fault.CLOCK_OFFSET_INVENTORY && response.statusCode() < 300) {
                        var payload = json.readTree(responseBody);
                        var node = path.endsWith("/abort") ? payload.get("reservation") : payload;
                        if (node != null && node.isObject() && node.has("expiresAt")) {
                            var translated = (tools.jackson.databind.node.ObjectNode) node;
                            for (var field : List.of("createdAt", "expiresAt", "updatedAt"))
                                translated.put(field, Instant.parse(node.get(field).asText()).minusSeconds(180).toString());
                            responseBody = json.writeValueAsBytes(payload);
                        }
                    }
                    if (delayedPut) lateStatus.set(response.statusCode());
                    forwarded.incrementAndGet();
                    if (preparing && selected == Fault.HOLD_PREPARE && response.statusCode() < 300) {
                        signal.countDown();
                        if (!gate.await(15, TimeUnit.SECONDS)) throw new IOException("Preparation gate timed out");
                    }
                    if (preparing && selected == Fault.LOSE_PREPARE || path.endsWith("/complete") && selected == Fault.LOSE_COMPLETE
                            || path.endsWith("/abort") && selected == Fault.LOSE_ABORT
                            || path.equals("/api/v1/orders") && selected == Fault.LOSE_ORDER_RESPONSE
                            || preparing && selected == Fault.LOSE_RESERVE) {
                        Thread.sleep(3000); // The real downstream commit succeeded; the caller times out at 2000 ms.
                        return;
                    }
                    response.headers().firstValue("Content-Type").ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
                    exchange.sendResponseHeaders(response.statusCode(), responseBody.length == 0 ? -1 : responseBody.length);
                    try (var output = exchange.getResponseBody()) { output.write(responseBody); }
                } catch (Exception failure) {
                    // Socket cancellation/restart is expected during fault injection. Never log authorization/body.
                    exchange.close();
                } finally { exchange.close(); }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void reset() { fault = Fault.NONE; release.countDown(); prepared = new CountDownLatch(1); release = new CountDownLatch(1); held.set(false); lateStatus.set(0); orderFilter = null; }
        @Override public void close() { release.countDown(); server.stop(0); }
    }

    @Override public void close() throws Exception {
        if (cartProxy != null) cartProxy.reset();
        if (catalogProxy != null) catalogProxy.reset();
        if (inventoryProxy != null) inventoryProxy.reset();
        try {
            for (var module : List.of("order-replica", "api-gateway", "order-service", "inventory-service", "cart-service", "catalog-service")) stop(module);
        } finally {
            if (cartProxy != null) cartProxy.close();
            if (catalogProxy != null) catalogProxy.close();
            if (inventoryProxy != null) inventoryProxy.close();
            issuer.stop(0);
            handlers.close();
            http.close();
            // Only schemas created and recorded by this runtime are eligible for cleanup.
            for (var schema : schemas.values()) {
                if (!schema.matches("checkout_flow_(catalog|cart|order|inventory)_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
