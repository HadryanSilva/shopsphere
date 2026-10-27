package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.config.CartCheckoutClientConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CartCheckoutClientTests {
    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger cartCalls = new AtomicInteger();
    private final AtomicReference<String> tokenBody = new AtomicReference<>();
    private final AtomicReference<String> cartBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> methodAndPath = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicInteger tokenStatus = new AtomicInteger(200);
    private final AtomicReference<String> tokenJson = new AtomicReference<>("{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}");
    private final UUID id = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = tokenJson.get().getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(tokenStatus.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.createContext("/internal/api/v1/cart-checkouts/", exchange -> {
            cartCalls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            methodAndPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            cartBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = ("{\"checkoutId\":\"" + id + "\",\"customerId\":\"" + customer
                    + "\",\"cartId\":\"" + UUID.randomUUID() + "\",\"status\":\"PREPARED\",\"items\":[]}").getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/redirect-must-not-be-followed");
                exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private ApplicationContextRunner runner() {
        var url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ApplicationContextRunner().withUserConfiguration(CartCheckoutClientConfig.class)
                .withPropertyValues("checkout.snapshot.enabled=true", "services.cart.url=" + url,
                        "checkout.snapshot.token-uri=" + url + "/token", "checkout.snapshot.client-secret=test-only-secret");
    }

    @Test
    void sendsApprovedBodiesWithServiceTokenAndCachesCredentialsWithoutUserJwt() {
        runner().run(context -> {
            var client = context.getBean(CartCheckoutClient.class);
            assertEquals(id, client.prepare(id, customer).checkoutId());
            assertEquals("PUT /internal/api/v1/cart-checkouts/" + id, methodAndPath.get());
            assertTrue(cartBody.get().contains("\"customerId\":\"" + customer + "\""));
            assertEquals("Bearer service-token", authorization.get());
            assertTrue(tokenBody.get().contains("grant_type=client_credentials"));
            assertTrue(tokenBody.get().contains("client_id=shopsphere-order"));
            client.get(id);
            assertEquals("GET /internal/api/v1/cart-checkouts/" + id, methodAndPath.get());
            assertEquals("", cartBody.get());
            var order = UUID.randomUUID();
            client.complete(id, order);
            assertEquals("POST /internal/api/v1/cart-checkouts/" + id + "/complete", methodAndPath.get());
            assertTrue(cartBody.get().contains(order.toString()));
            client.abort(id, customer);
            assertEquals("POST /internal/api/v1/cart-checkouts/" + id + "/abort", methodAndPath.get());
            assertEquals(1, tokenCalls.get());
            assertEquals(4, cartCalls.get());
            var http = context.getBean("checkoutHttpClient", HttpClient.class);
            assertEquals(2000, http.connectTimeout().orElseThrow().toMillis());
            assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409})
    void preparationBusinessErrorsKeepTheirStatus(int status) {
        responseStatus.set(status);
        runner().run(context -> {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> context.getBean(CartCheckoutClient.class).prepare(id, customer));
            assertEquals(status, failure.getStatusCode().value());
            assertEquals(1, cartCalls.get());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500, 503})
    void upstreamErrorsAreSanitizedAndNotAutomaticallyRetried(int status) {
        responseStatus.set(status);
        runner().run(context -> {
            var failure = assertThrows(CheckoutIntegrationFailure.class,
                    () -> context.getBean(CartCheckoutClient.class).prepare(id, customer));
            assertEquals(502, failure.status());
            assertFalse(failure.toString().contains("test-only-secret"));
            assertFalse(failure.toString().contains("service-token"));
            assertEquals(1, cartCalls.get());
            if (status == 401 || status == 403) {
                context.getBean(CheckoutServiceTokenProvider.class).authorization();
                assertEquals(2, tokenCalls.get());
            }
        });
    }

    @Test
    void completion404DoesNotBecomeABusinessAbort() {
        responseStatus.set(404);
        runner().run(context -> assertEquals(502, assertThrows(CheckoutIntegrationFailure.class,
                () -> context.getBean(CartCheckoutClient.class).complete(id, UUID.randomUUID())).status()));
    }

    @Test
    void tokenFailureDoesNotSendCartRequestOrLeakCredentials() {
        tokenStatus.set(401);
        runner().run(context -> {
            assertEquals(502, assertThrows(CheckoutIntegrationFailure.class,
                    () -> context.getBean(CartCheckoutClient.class).prepare(id, customer)).status());
            assertEquals(1, tokenCalls.get());
            assertEquals(0, cartCalls.get());
        });
    }

    @Test
    void invalidTokenPayloadIsRejectedAndShortLivedTokenIsRefreshed() {
        tokenJson.set("{\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        runner().run(context -> {
            var tokens = context.getBean(CheckoutServiceTokenProvider.class);
            assertEquals(502, assertThrows(CheckoutIntegrationFailure.class, tokens::authorization).status());
            tokenJson.set("{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":1}");
            tokens.authorization();
            tokens.authorization();
            assertEquals(3, tokenCalls.get());
        });
    }

    @Test
    void configuredReadTimeoutReturns503WithoutCartRequest() {
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow-token", exchange -> {
            try (exchange) { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        runner().withPropertyValues("checkout.snapshot.read-timeout=100",
                "checkout.snapshot.token-uri=http://127.0.0.1:" + server.getAddress().getPort() + "/slow-token")
                .run(context -> {
                    try {
                        assertEquals(503, assertThrows(CheckoutIntegrationFailure.class,
                                () -> context.getBean(CartCheckoutClient.class).prepare(id, customer)).status());
                        assertEquals(0, cartCalls.get());
                    } finally { release.countDown(); }
                });
    }

    @Test
    void connectionFailureReturns503AndDisabledModeDoesNotCreateClients() {
        runner().run(context -> {
            server.stop(0);
            assertEquals(503, assertThrows(CheckoutIntegrationFailure.class,
                    () -> context.getBean(CartCheckoutClient.class).get(id)).status());
        });
        new ApplicationContextRunner().withUserConfiguration(CartCheckoutClientConfig.class)
                .run(context -> assertFalse(context.containsBean("cartCheckoutClient")));
    }
}
