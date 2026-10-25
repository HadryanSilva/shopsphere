package br.com.hadryan.shosphere.cart.controller;

import br.com.hadryan.shosphere.cart.config.SecurityConfig;
import br.com.hadryan.shosphere.cart.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;
import br.com.hadryan.shosphere.cart.mapper.response.AddedProductResponse;
import br.com.hadryan.shosphere.cart.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CartController.class)
@ContextConfiguration(classes = {CartController.class, SecurityConfig.class, ApiExceptionHandler.class})
@Import(SecurityConfig.class)
class CartAddProductHttpTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private AddProductToCartUseCase addProduct;
    @MockitoBean private UpdateCartUseCase updateCart;
    @MockitoBean private RemoveCartItemUseCase removeItem;
    @MockitoBean private GetCartUseCase getCart;
    @MockitoBean private JwtDecoder decoder;

    @Test
    void acceptsFirstAdditionWithoutCartIdUsingJwtIdentity() throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var cartId = UUID.randomUUID();
        var request = new AddProductRequest(null, productId, 2);
        when(addProduct.execute(customerId, request)).thenReturn(
                new AddedProductResponse(cartId, productId, 2, "Item added to cart"));

        mvc.perform(post("/api/v1/carts/me/items").with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cartId").value(cartId.toString()));
        verify(addProduct).execute(customerId, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":2}",
            "{\"productId\":\"20000000-0000-0000-0000-000000000001\"}",
            "{\"productId\":\"20000000-0000-0000-0000-000000000001\",\"quantity\":0}",
            "{\"productId\":\"20000000-0000-0000-0000-000000000001\",\"quantity\":-1}"})
    void rejectsMissingOrInvalidProductAndQuantity(String body) throws Exception {
        mvc.perform(post("/api/v1/carts/me/items").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(addProduct);
    }

    @Test
    void patchUsesProductFromPathAndQuantityFromBody() throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        mvc.perform(patch("/api/v1/carts/me/items/{productId}", productId)
                        .with(jwt().jwt(token -> token.subject(customerId.toString())))
                        .contentType("application/json").content("{\"quantity\":3}"))
                .andExpect(status().isNoContent());
        verify(updateCart).execute(customerId, productId, new UpdateCartRequest(3));
    }

    @Test
    void deleteUsesProductFromPathWithoutRequestBody() throws Exception {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        mvc.perform(delete("/api/v1/carts/me/items/{productId}", productId)
                        .with(jwt().jwt(token -> token.subject(customerId.toString()))))
                .andExpect(status().isNoContent());
        verify(removeItem).execute(customerId, productId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":null}", "{\"quantity\":0}", "{\"quantity\":-1}"})
    void patchRejectsInvalidQuantity(String body) throws Exception {
        mvc.perform(patch("/api/v1/carts/me/items/{productId}", UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(updateCart);
    }
}
