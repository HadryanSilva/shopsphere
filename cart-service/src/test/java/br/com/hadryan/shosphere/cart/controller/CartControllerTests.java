package br.com.hadryan.shosphere.cart.controller;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;
import br.com.hadryan.shosphere.cart.mapper.response.AddedProductResponse;
import br.com.hadryan.shosphere.cart.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CartControllerTests {
    @Test
    void obtainsCustomerIdFromAuthenticatedJwtWhenUpdatingCart() {
        var updateCart = mock(UpdateCartUseCase.class);
        var controller = new CartController(mock(AddProductToCartUseCase.class), updateCart,
                mock(RemoveCartItemUseCase.class), mock(GetCartUseCase.class));
        var customerId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(customerId.toString()).build();
        var cartId = UUID.randomUUID();
        var request = new UpdateCartRequest(2);

        var response = controller.updateCart(jwt, cartId, request);

        verify(updateCart).execute(customerId, cartId, request);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void obtainsCustomerIdFromAuthenticatedJwtWhenRemovingItem() {
        var removeItem = mock(RemoveCartItemUseCase.class);
        var controller = new CartController(mock(AddProductToCartUseCase.class), mock(UpdateCartUseCase.class),
                removeItem, mock(GetCartUseCase.class));
        var customerId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(customerId.toString()).build();
        var cartId = UUID.randomUUID();
        var response = controller.removeItem(jwt, cartId);

        verify(removeItem).execute(customerId, cartId);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void obtainsCustomerIdFromAuthenticatedJwtWhenAddingProduct() {
        var addProduct = mock(AddProductToCartUseCase.class);
        var controller = new CartController(addProduct, mock(UpdateCartUseCase.class),
                mock(RemoveCartItemUseCase.class), mock(GetCartUseCase.class));
        var customerId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(customerId.toString()).build();
        var request = new AddProductRequest(UUID.randomUUID(), UUID.randomUUID(), 2);
        var expected = new AddedProductResponse(request.cartId(), request.productId(), 2, "Item added to cart");
        when(addProduct.execute(customerId, request)).thenReturn(expected);

        var response = controller.addProduct(jwt, request);

        verify(addProduct).execute(customerId, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
    }
}
