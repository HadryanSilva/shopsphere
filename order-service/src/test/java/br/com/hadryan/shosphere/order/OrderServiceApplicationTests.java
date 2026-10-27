package br.com.hadryan.shosphere.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import br.com.hadryan.shosphere.order.client.CartClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.mapper.response.CartResponse;
import br.com.hadryan.shosphere.order.mapper.response.CartItemResponse;
import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.order.model.Order;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderServiceApplicationTests {

    @Autowired private MockMvc mvc;

    @MockitoBean private OrderRepository orderRepository;
    @MockitoBean private br.com.hadryan.shosphere.order.service.impl.CheckoutIntentTransaction checkoutIntentTransaction;
    @MockitoBean private CartClient cartClient;
    @MockitoBean private CatalogClient catalogClient;
    @MockitoBean private JwtDecoder jwtDecoder;

	@Test
	void contextLoads() {
	}

    @Test
    void readsPersistedSnapshotWithoutDownstreamCallsOrWrites() throws Exception {
        var customerId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var saved = Order.create(customerId, UUID.randomUUID(), "EUR",
                new ShippingAddress("Customer", "Street 1", "Lisbon", "1000-001", "PT"));
        saved.addItem(productId, "Original product", new BigDecimal("12.50"), 2);
        ReflectionTestUtils.setField(saved, "id", orderId);
        when(orderRepository.findByIdAndCustomerId(orderId, customerId)).thenReturn(Optional.of(saved));

        mvc.perform(get("/api/v1/orders/{id}", orderId)
                        .with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_STOCK"))
                .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Original product"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.5))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(25))
                .andExpect(jsonPath("$.totalAmount").value(25))
                .andExpect(jsonPath("$.currency").value("EUR"));
        verify(orderRepository).findByIdAndCustomerId(orderId, customerId);
        verifyNoMoreInteractions(orderRepository);
        verifyNoInteractions(cartClient, catalogClient);
    }

    @Test
    void missingOrderAndOtherCustomersOrderReturnSameNotFoundEvenForAdmin() throws Exception {
        var ownerId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var saved = Order.create(ownerId, UUID.randomUUID(), "EUR",
                new ShippingAddress("Owner", "Private street", "Lisbon", "1000-001", "PT"));
        when(orderRepository.findByIdAndCustomerId(orderId, ownerId)).thenReturn(Optional.of(saved));

        for (var requestedId : List.of(orderId, UUID.randomUUID())) {
            mvc.perform(get("/api/v1/orders/{id}", requestedId)
                            .with(jwt().jwt(token -> token.subject(callerId.toString()))
                                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("Order not found"))
                    .andExpect(jsonPath("$.items").doesNotExist());
            verify(orderRepository).findByIdAndCustomerId(requestedId, callerId);
        }
        verifyNoMoreInteractions(orderRepository);
        verifyNoInteractions(cartClient, catalogClient);
    }

    @Test
    void realUseCaseReturnsCreatedSnapshotAfterRepositoryWrite() throws Exception {
        var productId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        when(cartClient.getCustomerCart(anyString())).thenReturn(
                new CartResponse(UUID.randomUUID(), "ACTIVE", List.of(new CartItemResponse(productId, 2))));
        when(catalogClient.getProducts(List.of(productId))).thenReturn(
                List.of(new ProductResponse(productId, "Product", BigDecimal.TEN, "EUR", "ACTIVE")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", orderId);
            return order;
        });

        mvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("""
                                {"shippingAddress":{"recipient":"Customer","addressLine":"Street 1",
                                "city":"Lisbon","postalCode":"1000-001","countryCode":"PT"}}
                                """))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.totalAmount").value(20))
                .andExpect(jsonPath("$.status").value("PENDING_STOCK"));

        verify(cartClient).getCustomerCart(anyString());
        verify(catalogClient).getProducts(List.of(productId));
        var captured = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captured.capture());
        assertEquals(customerId, captured.getValue().getCustomerId());
        assertEquals(key.toString(), captured.getValue().getIdempotencyKey());
    }

    @Test
    void repositoryFailureDoesNotReturnCreationSuccess() throws Exception {
        var productId = UUID.randomUUID();
        when(cartClient.getCustomerCart(anyString())).thenReturn(
                new CartResponse(UUID.randomUUID(), "ACTIVE", List.of(new CartItemResponse(productId, 2))));
        when(catalogClient.getProducts(List.of(productId))).thenReturn(
                List.of(new ProductResponse(productId, "Product", BigDecimal.TEN, "EUR", "ACTIVE")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(new DataIntegrityViolationException("Write failed"));

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("""
                        {"shippingAddress":{"recipient":"Customer","addressLine":"Street 1",
                        "city":"Lisbon","postalCode":"1000-001","countryCode":"PT"}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void replayReturnsExistingLocationWithoutCallingDownstreamOrWriting() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var saved = Order.create(customerId, key, "EUR", new ShippingAddress("Customer", "Street 1", "Lisbon", "1000-001", "PT"));
        saved.addItem(UUID.randomUUID(), "Original product", BigDecimal.TEN, 2);
        ReflectionTestUtils.setField(saved, "id", orderId);
        when(orderRepository.findByCustomerIdAndIdempotencyKey(customerId, key.toString())).thenReturn(Optional.of(saved));

        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", key).contentType("application/json").content("""
                                {"shippingAddress":{"recipient":"Customer","addressLine":"Street 1",
                                "city":"Lisbon","postalCode":"1000-001","countryCode":"PT"}}
                                """))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.totalAmount").value(20));
        verifyNoInteractions(cartClient, catalogClient);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderRepository).findByCustomerIdAndIdempotencyKey(customerId, key.toString());
    }

    @Test
    void reusedKeyWithDifferentAddressReturnsConflictBeforeDownstreamCalls() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var saved = Order.create(customerId, key, "EUR", new ShippingAddress("Other recipient", "Street 1", "Lisbon", "1000-001", "PT"));
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        when(orderRepository.findByCustomerIdAndIdempotencyKey(customerId, key.toString())).thenReturn(Optional.of(saved));

        mvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .header("Idempotency-Key", key).contentType("application/json").content("""
                                {"shippingAddress":{"recipient":"Customer","addressLine":"Street 1",
                                "city":"Lisbon","postalCode":"1000-001","countryCode":"PT"}}
                                """))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(cartClient, catalogClient);
        verify(orderRepository, never()).saveAndFlush(any());
    }

}
