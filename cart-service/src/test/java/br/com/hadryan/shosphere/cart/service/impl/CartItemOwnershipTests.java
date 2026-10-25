package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;
import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.CartItem;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartItemOwnershipTests {
    enum Operation { UPDATE, REMOVE }

    private final CartRepository repository = mock(CartRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final UpdateCartItemTransaction transaction = mock(UpdateCartItemTransaction.class);
    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UpdateCartRequest request = new UpdateCartRequest(2);

    @ParameterizedTest
    @EnumSource(Operation.class)
    void allowsOwnerToModifyCart(Operation operation) {
        var cart = mock(Cart.class);
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(repository.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, productId, 5)));
        if (operation == Operation.UPDATE) {
            when(catalog.getProductById(productId)).thenReturn(
                    new ProductResponse(productId, "Product", BigDecimal.TEN, "ACTIVE"));
        }

        execute(operation);

        if (operation == Operation.UPDATE) {
            verify(repository).findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
            verify(transaction).execute(customerId, productId, request.quantity());
        } else {
            verify(repository).findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
            verify(cart).removeItem(productId);
            verify(repository).saveAndFlush(cart);
            verifyNoInteractions(catalog);
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void rejectsAnotherCustomersCartWithoutSideEffects(Operation operation) {
        var cart = mock(Cart.class);
        var ownerId = UUID.randomUUID();
        when(repository.findByCustomerIdAndStatus(ownerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(repository.findWithLockByCustomerIdAndStatus(ownerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        var exception = assertThrows(ResponseStatusException.class, () -> execute(operation));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(repository, never()).findById(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(cart, catalog, transaction);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void returnsNotFoundForMissingCartWithoutSideEffects(Operation operation) {
        var exception = assertThrows(ResponseStatusException.class, () -> execute(operation));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(catalog, transaction);
    }

    private void execute(Operation operation) {
        if (operation == Operation.UPDATE) {
            new UpdateCartUseCaseImpl(repository, catalog, transaction).execute(customerId, productId, request);
        } else {
            new RemoveCartItemUseCaseImpl(repository).execute(customerId, productId);
        }
    }

    @Test
    void updateMissingProductDoesNotCreateItemOrCallCatalog() {
        var cart = mock(Cart.class);
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getItems()).thenReturn(List.of());

        var exception = assertThrows(ResponseStatusException.class, () -> execute(Operation.UPDATE));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(catalog, transaction);
    }

    @Test
    void inactiveProductDoesNotWriteQuantity() {
        var cart = mock(Cart.class);
        when(repository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getItems()).thenReturn(List.of(new CartItem(cart, productId, 5)));
        when(catalog.getProductById(productId)).thenReturn(
                new ProductResponse(productId, "Product", BigDecimal.TEN, "INACTIVE"));

        assertThrows(RuntimeException.class, () -> execute(Operation.UPDATE));

        verifyNoInteractions(transaction);
    }
}
