package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.CartItem;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetCartUseCaseImplTests {
    private final CartRepository repository = mock(CartRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final GetCartUseCaseImpl useCase = new GetCartUseCaseImpl(repository, catalog);
    private final UUID customerId = UUID.randomUUID();

    @Test
    void enrichesEachItemByProductIdInsteadOfCartItemIdAndCalculatesTotals() {
        var cart = mock(Cart.class);
        var cartId = UUID.randomUUID();
        var firstProductId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var secondProductId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var firstItemId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        var secondItemId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        var firstItem = spy(new CartItem(cart, firstProductId, 2));
        var secondItem = spy(new CartItem(cart, secondProductId, 3));
        doReturn(firstItemId).when(firstItem).getId();
        doReturn(secondItemId).when(secondItem).getId();
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getId()).thenReturn(cartId);
        when(cart.getStatus()).thenReturn(CartStatus.ACTIVE);
        when(cart.getItems()).thenReturn(List.of(firstItem, secondItem));
        when(catalog.getProducts(List.of(firstProductId, secondProductId))).thenReturn(List.of(
                new ProductResponse(secondProductId, "Mouse", new BigDecimal("7.30"), "OUT_OF_STOCK"),
                new ProductResponse(firstProductId, "Keyboard", new BigDecimal("12.50"), "ACTIVE")));

        var response = useCase.execute(customerId);

        verify(repository).findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
        verify(catalog).getProducts(List.of(firstProductId, secondProductId));
        verify(catalog, never()).getProductById(firstItemId);
        verify(catalog, never()).getProductById(secondItemId);
        verifyNoMoreInteractions(catalog);
        assertEquals(cartId, response.id());
        assertEquals(CartStatus.ACTIVE, response.status());
        assertEquals(2, response.items().size());
        var first = response.items().getFirst();
        assertEquals(firstProductId, first.productId());
        assertEquals("Keyboard", first.productName());
        assertEquals(2, first.quantity());
        assertEquals(new BigDecimal("12.50"), first.unitPrice());
        assertEquals(new BigDecimal("25.00"), first.subTotal());
        var second = response.items().getLast();
        assertEquals(secondProductId, second.productId());
        assertEquals("Mouse", second.productName());
        assertEquals(3, second.quantity());
        assertEquals(new BigDecimal("7.30"), second.unitPrice());
        assertEquals(new BigDecimal("21.90"), second.subTotal());
        assertEquals(new BigDecimal("46.90"), response.estimatedTotal());
    }

    @Test
    void missingProductInBatchPreservesNotFoundInsteadOfReturningPartialCart() {
        var cart = mock(Cart.class);
        var firstId = UUID.randomUUID();
        var missingId = UUID.randomUUID();
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, firstId, 2), new CartItem(cart, missingId, 1)));
        when(catalog.getProducts(List.of(firstId, missingId))).thenReturn(
                List.of(new ProductResponse(firstId, "Product", BigDecimal.TEN, "ACTIVE")));

        var exception = assertThrows(ResponseStatusException.class, () -> useCase.execute(customerId));

        assertEquals(404, exception.getStatusCode().value());
        assertEquals("Product not found.", exception.getReason());
        verify(catalog).getProducts(List.of(firstId, missingId));
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void emptyCartDoesNotCallCatalog() {
        var cart = mock(Cart.class);
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getItems()).thenReturn(List.of());

        var response = useCase.execute(customerId);

        assertTrue(response.items().isEmpty());
        assertEquals(0, response.totalItems());
        assertEquals(BigDecimal.ZERO, response.estimatedTotal());
        verifyNoInteractions(catalog);
    }

    @Test
    void duplicateBatchProductIsAnUpstreamFailure() {
        var cart = mock(Cart.class);
        var productId = UUID.randomUUID();
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, productId, 2)));
        var product = new ProductResponse(productId, "Product", BigDecimal.TEN, "ACTIVE");
        when(catalog.getProducts(List.of(productId))).thenReturn(List.of(product, product));

        var exception = assertThrows(ResponseStatusException.class, () -> useCase.execute(customerId));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void missingActiveCartKeepsExistingErrorAndDoesNotCallCatalog() {
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.empty());

        var exception = assertThrows(ResponseStatusException.class, () -> useCase.execute(customerId));

        assertEquals("Cart not found", exception.getReason());
        assertEquals(404, exception.getStatusCode().value());
        verify(repository).findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
        verifyNoInteractions(catalog);
    }
}
