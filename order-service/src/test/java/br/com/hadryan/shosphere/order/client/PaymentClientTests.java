package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.config.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.service.impl.PaymentWorkSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentClientTests {
    private HttpServer server;
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<String> response = new AtomicReference<>(), body = new AtomicReference<>(), route = new AtomicReference<>(), bearer = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), tokenCalls = new AtomicInteger(), delay = new AtomicInteger();
    private BigDecimal amount = new BigDecimal("25.00");
    @BeforeEach void start() throws Exception {
        response.set(json("CAPTURED"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            try (exchange) {
                tokenCalls.incrementAndGet();
                var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(form.contains("grant_type=client_credentials")); assertTrue(form.contains("client_id=shopsphere-order"));
                var bytes = "{\"access_token\":\"test-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.createContext("/internal/api/v1/payments/", exchange -> {
            calls.incrementAndGet(); route.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(delay.get()); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            try (exchange) {
                var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.getResponseHeaders().set("Location", "/never-follow");
                exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes);
            }
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private ApplicationContextRunner runner() {
        var url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ApplicationContextRunner().withUserConfiguration(CartCheckoutClientConfig.class, PaymentClientConfig.class)
                .withPropertyValues("checkout.snapshot.enabled=true", "services.cart.url=" + url, "services.payment.url=" + url,
                        "checkout.snapshot.token-uri=" + url + "/token", "checkout.snapshot.client-secret=test-only-secret");
    }
    private PaymentWorkSnapshot work() {
        return new PaymentWorkSnapshot(id, PaymentWorkPhase.CAPTURE_PENDING, amount, "EUR", null, false, false, null, 0, null);
    }
    private String json(String state) {
        var captured = state.equals("CAPTURED") || state.equals("REFUND_PENDING") || state.equals("REFUNDED");
        return "{\"orderId\":\"" + id + "\",\"status\":\"" + state + "\",\"amount\":" + amount + ",\"currency\":\"EUR\","
                + "\"createdAt\":\"2026-11-01T12:00:00Z\",\"updatedAt\":\"2026-11-01T12:00:02Z\",\"capturedAt\":"
                + (captured ? "\"2026-11-01T12:00:01Z\"" : "null") + ",\"refundedAt\":"
                + (state.equals("REFUNDED") ? "\"2026-11-01T12:00:02Z\"" : "null") + "}";
    }
    @Test void usesOnlySnapshotAndServiceIdentityWithIndependentTimeouts() {
        runner().run(context -> {
            var client = context.getBean(PaymentClient.class); var work = work(); status.set(201);
            assertEquals(OrderPaymentStatus.CAPTURED, client.capture(work).status());
            assertEquals("PUT /internal/api/v1/payments/" + id, route.get());
            assertTrue(body.get().contains("\"amount\":25.00")); assertTrue(body.get().contains("\"currency\":\"EUR\""));
            assertFalse(body.get().contains("customer")); assertEquals("Bearer test-service-token", bearer.get());
            status.set(200); client.get(work); assertEquals("GET /internal/api/v1/payments/" + id, route.get()); assertEquals("", body.get());
            response.set(json("REFUNDED")); client.cancel(work); assertEquals("POST /internal/api/v1/payments/" + id + "/cancel", route.get());
            assertEquals(1, tokenCalls.get()); assertEquals(3, calls.get());
            var http = context.getBean("paymentHttpClient", HttpClient.class);
            assertEquals(2000, http.connectTimeout().orElseThrow().toMillis()); assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
        });
    }
    @ParameterizedTest @ValueSource(strings = {"PROCESSING", "CAPTURED", "DECLINED", "CANCELLED", "REFUND_PENDING", "REFUNDED"})
    void decodesEveryValidStateWithExplicitNulls(String state) {
        response.set(json(state)); runner().run(context -> assertEquals(OrderPaymentStatus.valueOf(state), context.getBean(PaymentClient.class).get(work()).status()));
    }
    @Test void neverLosesMonetaryPrecisionThroughDoubleConversion() {
        amount = new BigDecimal("99999999999999999.99"); response.set(json("CAPTURED"));
        runner().run(context -> assertEquals(amount, context.getBean(PaymentClient.class).get(work()).amount()));
    }
    @Test void processingAndRefundPendingUse202AndBusinessDeclineIsSuccess() {
        runner().run(context -> {
            var client = context.getBean(PaymentClient.class);
            status.set(202); response.set(json("PROCESSING")); assertEquals(OrderPaymentStatus.PROCESSING, client.capture(work()).status());
            response.set(json("REFUND_PENDING")); assertEquals(OrderPaymentStatus.REFUND_PENDING, client.cancel(work()).status());
            status.set(201); response.set(json("DECLINED")); assertEquals(OrderPaymentStatus.DECLINED, client.capture(work()).status());
        });
    }
    @ParameterizedTest @ValueSource(strings = {"id", "amount", "string-amount", "currency", "created", "capture", "refund", "missing-null", "unknown", "malformed", "null", "future-capture", "premature-refund"})
    void rejectsUnusableProofsWithoutEchoingBody(String variant) {
        var valid = json("CAPTURED");
        response.set(switch (variant) {
            case "id" -> valid.replace(id.toString(), UUID.randomUUID().toString());
            case "amount" -> valid.replace(":25.00", ":25.000000000000000001");
            case "string-amount" -> valid.replace(":25.00", ":\"25.00\"");
            case "currency" -> valid.replace("EUR", "USD");
            case "created" -> valid.replace("12:00:00Z", "12:00:03Z");
            case "capture" -> valid.replace("\"2026-11-01T12:00:01Z\"", "null");
            case "refund" -> valid.replace("CAPTURED", "REFUNDED");
            case "missing-null" -> valid.replace(",\"refundedAt\":null", "");
            case "unknown" -> valid.replace("CAPTURED", "APPROVED");
            case "null" -> "null";
            case "future-capture" -> valid.replace("12:00:01Z", "12:00:03Z");
            case "premature-refund" -> json("REFUNDED").replace("\"refundedAt\":\"2026-11-01T12:00:02Z\"", "\"refundedAt\":\"2026-11-01T11:59:59Z\"");
            default -> "{sensitive-body";
        });
        runner().run(context -> {
            var failure = assertThrows(PaymentIntegrationFailure.class, () -> context.getBean(PaymentClient.class).get(work()));
            assertEquals(PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE, failure.category()); assertFalse(failure.toString().contains("sensitive"));
        });
    }
    @ParameterizedTest @ValueSource(ints = {302, 400, 401, 403, 404, 409, 500, 503})
    void errorsDoNotRetryOrBecomeFinancialSuccess(int code) {
        status.set(code); response.set("{\"detail\":\"sensitive\"}");
        runner().run(context -> {
            var client = context.getBean(PaymentClient.class);
            var failure = assertThrows(PaymentIntegrationFailure.class, () -> client.capture(work()));
            assertEquals(1, calls.get()); assertFalse(failure.toString().contains("sensitive"));
            assertEquals(code == 401 || code == 403 ? PaymentFailureCategory.AUTHENTICATION_CONFIGURATION
                    : code >= 500 ? PaymentFailureCategory.UPSTREAM_UNAVAILABLE : PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE, failure.category());
            if (code == 401 || code == 403) { status.set(200); response.set(json("CAPTURED")); client.get(work()); assertEquals(2, tokenCalls.get()); }
        });
    }
    @Test void rejectsSuccessfulCodesThatDisagreeWithFinancialState() {
        runner().run(context -> {
            var client = context.getBean(PaymentClient.class);
            for (var code : new int[]{200, 201, 202, 204}) {
                status.set(code); response.set(json(code == 202 ? "CAPTURED" : "PROCESSING"));
                assertThrows(PaymentIntegrationFailure.class, () -> client.capture(work()));
            }
            status.set(200); response.set(json("CAPTURED")); assertThrows(PaymentIntegrationFailure.class, () -> client.cancel(work()));
        });
    }
    @Test void readTimeoutIsBoundedWithoutTransportRetry() {
        delay.set(200); runner().withPropertyValues("payment.read-timeout=30").run(context -> {
            assertEquals(PaymentFailureCategory.UPSTREAM_UNAVAILABLE, assertThrows(PaymentIntegrationFailure.class,
                    () -> context.getBean(PaymentClient.class).get(work())).category()); assertTrue(calls.get() <= 1);
        });
    }
    @ParameterizedTest @ValueSource(strings = {"http://user:secret@localhost", "http://localhost/path", "http://localhost?x=1", "ftp://localhost", "not-a-url"})
    void invalidBaseUrlFailsAtStartup(String url) {
        runner().withPropertyValues("services.payment.url=" + url).run(context -> assertNotNull(context.getStartupFailure()));
    }
    @Test void disabledSnapshotDoesNotCreateFinancialClient() {
        runner().withPropertyValues("checkout.snapshot.enabled=false").run(context -> assertFalse(context.containsBean("paymentClient")));
    }
}
