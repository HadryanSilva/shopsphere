package br.com.hadryan.shosphere.cart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.repository.CartItemRepository;
import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.CartItem;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import feign.FeignException;
import feign.RetryableException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CartServiceApplicationTests {
    @Autowired private MockMvc mvc;

    @Test
    void accumulatedQuantityOverflowReturnsConflictWithoutChangingOrSavingItem() throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var cart = mock(Cart.class, CALLS_REAL_METHODS);
        var item = new CartItem(cart, productId, Integer.MAX_VALUE);
        cart.setItems(new java.util.ArrayList<>(List.of(item)));
        when(cartRepository.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(catalogClient.getProductById(productId)).thenReturn(
                new ProductResponse(productId, "Product", BigDecimal.TEN, "ACTIVE"));

        mvc.perform(post("/api/v1/carts/me/items")
                        .with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Cart item quantity exceeds supported limit"));
        org.junit.jupiter.api.Assertions.assertEquals(Integer.MAX_VALUE, item.getQuantity());
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingActiveCartReturnsNotFoundProblem() throws Exception {
        mvc.perform(get("/api/v1/carts/me").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Cart not found"));
    }

    @Test
    void inactiveProductReturnsConflictProblem() throws Exception {
        var productId = UUID.randomUUID();
        when(catalogClient.getProductById(productId)).thenReturn(
                new ProductResponse(productId, "Product", BigDecimal.TEN, "INACTIVE"));
        mvc.perform(post("/api/v1/carts/me/items").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                        .contentType("application/json").content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @ParameterizedTest
    @MethodSource("catalogFailures")
    void catalogFailureUsesStandardProblem(FeignException failure, int status) throws Exception {
        var productId = UUID.randomUUID();
        when(catalogClient.getProductById(productId)).thenThrow(failure);
        mvc.perform(post("/api/v1/carts/me/items").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                        .contentType("application/json").content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.status").value(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    static Stream<Arguments> catalogFailures() {
        var missing = mock(FeignException.NotFound.class);
        when(missing.status()).thenReturn(404);
        return Stream.of(Arguments.of(missing, 404), Arguments.of(mock(FeignException.InternalServerError.class), 502),
                Arguments.of(mock(RetryableException.class), 503));
    }

    @ParameterizedTest
    @MethodSource("catalogFailures")
    void batchReadFailurePreservesIntegrationStatus(FeignException failure, int status) throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var cart = mock(Cart.class);
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, productId, 2)));
        when(cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(catalogClient.getProducts(List.of(productId))).thenThrow(failure);

        mvc.perform(get("/api/v1/carts/me").with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(catalogClient).getProducts(List.of(productId));
        verifyNoMoreInteractions(catalogClient);
    }

    @Test
    void missingBatchProductReturnsNotFoundInsteadOfPartialCart() throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var cart = mock(Cart.class);
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, productId, 2)));
        when(cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(catalogClient.getProducts(List.of(productId))).thenReturn(List.of());

        mvc.perform(get("/api/v1/carts/me").with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Product not found."))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(catalogClient).getProducts(List.of(productId));
        verifyNoMoreInteractions(catalogClient);
    }

    @Test
    void nullBatchResponseReturnsBadGatewayInsteadOfInternalError() throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var cart = mock(Cart.class);
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, productId, 2)));
        when(cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(catalogClient.getProducts(List.of(productId))).thenReturn(null);

        mvc.perform(get("/api/v1/carts/me").with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @MockitoBean private CartRepository cartRepository;
    @MockitoBean private CartItemRepository cartItemRepository;
    @MockitoBean private CatalogClient catalogClient;
    @MockitoBean private JwtDecoder jwtDecoder;

	@Test
	void contextLoads() {
	}

}
