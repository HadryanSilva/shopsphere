package br.com.hadryan.shosphere.order.client;

import feign.Client;
import feign.Contract;
import feign.Feign;
import feign.Request;
import feign.Response;
import feign.codec.Decoder;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClientsConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CatalogClientContractTests {
    private final List<UUID> ids = List.of(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            UUID.fromString("20000000-0000-0000-0000-000000000002"));

    @Test
    void sendsGetQueryWithoutBodyAndDecodesSharedResponseContract() throws Exception {
        var captured = new AtomicReference<Request>();
        var json = Files.readString(Path.of("..", "contracts", "catalog-products-batch-response.json"));
        try (var context = new AnnotationConfigApplicationContext(FeignClientsConfiguration.class)) {
            var client = client(context, json, captured);

            var products = client.getProducts(ids);

            var request = captured.get();
            assertEquals(Request.HttpMethod.GET, request.httpMethod());
            assertNull(request.body());
            var url = URLDecoder.decode(request.url(), StandardCharsets.UTF_8);
            assertTrue(url.startsWith("http://catalog.test/api/v1/products/batch?"));
            assertTrue(url.contains("productIds="));
            ids.forEach(id -> assertTrue(url.contains(id.toString())));
            assertEquals(2, products.size());
            assertEquals(ids.getFirst(), products.getFirst().id());
            assertEquals("Keyboard", products.getFirst().name());
            assertEquals(new BigDecimal("49.90"), products.getFirst().price());
            assertEquals("EUR", products.getFirst().currency());
            assertEquals("ACTIVE", products.getFirst().status());
            assertEquals(ids.getLast(), products.getLast().id());
            assertEquals("OUT_OF_STOCK", products.getLast().status());
        }
    }

    @Test
    void decodesEmptyResponseArray() {
        try (var context = new AnnotationConfigApplicationContext(FeignClientsConfiguration.class)) {
            assertTrue(client(context, "[]", new AtomicReference<>()).getProducts(ids).isEmpty());
        }
    }

    private CatalogClient client(AnnotationConfigApplicationContext context, String json,
                                 AtomicReference<Request> captured) {
        Client transport = (request, options) -> {
            captured.set(request);
            return Response.builder().request(request).status(200).reason("OK")
                    .headers(Map.of("Content-Type", List.of("application/json")))
                    .body(json, StandardCharsets.UTF_8).build();
        };
        return Feign.builder().contract(context.getBean(Contract.class))
                .decoder(context.getBean(Decoder.class)).client(transport)
                .target(CatalogClient.class, "http://catalog.test/api/v1/products");
    }
}
