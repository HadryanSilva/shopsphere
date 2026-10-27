package br.com.hadryan.shosphere.cart.client;

import br.com.hadryan.shosphere.cart.repository.CartItemRepository;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import feign.Client;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class CatalogClientContractTests {
    @Autowired private CatalogClient catalog;
    @MockitoBean private Client transport;
    @MockitoBean private CartRepository carts;
    @MockitoBean private br.com.hadryan.shosphere.cart.repository.CartCheckoutRepository checkouts;
    @MockitoBean private CartItemRepository items;
    @MockitoBean private JwtDecoder jwtDecoder;

    private final List<UUID> ids = List.of(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            UUID.fromString("20000000-0000-0000-0000-000000000002"));

    @Test
    void sendsSingleBatchGetAndDecodesSharedContractWithConfiguredTimeouts() throws Exception {
        var json = Files.readString(Path.of("..", "contracts", "catalog-products-batch-response.json"));
        when(transport.execute(any(), any())).thenAnswer(invocation -> Response.builder()
                .request(invocation.getArgument(0)).status(200).reason("OK")
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(json, StandardCharsets.UTF_8).build());

        var products = catalog.getProducts(ids);

        var request = ArgumentCaptor.forClass(Request.class);
        var options = ArgumentCaptor.forClass(Request.Options.class);
        verify(transport).execute(request.capture(), options.capture());
        verifyNoMoreInteractions(transport);
        assertEquals(Request.HttpMethod.GET, request.getValue().httpMethod());
        assertNull(request.getValue().body());
        var url = URLDecoder.decode(request.getValue().url(), StandardCharsets.UTF_8);
        assertTrue(url.startsWith("http://catalog.invalid/api/v1/products/batch?"));
        assertTrue(url.contains("productIds="));
        ids.forEach(id -> assertTrue(url.contains(id.toString())));
        assertEquals(2000, options.getValue().connectTimeoutMillis());
        assertEquals(5000, options.getValue().readTimeoutMillis());
        assertEquals(2, products.size());
        assertEquals(ids.getFirst(), products.getFirst().id());
        assertEquals("Keyboard", products.getFirst().name());
        assertEquals(new BigDecimal("49.90"), products.getFirst().price());
        assertEquals("OUT_OF_STOCK", products.getLast().status());
    }

    @Test
    void connectionFailureIsNotAutomaticallyRetried() throws Exception {
        when(transport.execute(any(), any())).thenThrow(new IOException("Connection unavailable"));

        assertThrows(RetryableException.class, () -> catalog.getProducts(ids));
        verify(transport).execute(any(), any());
        verifyNoMoreInteractions(transport);
    }
}
