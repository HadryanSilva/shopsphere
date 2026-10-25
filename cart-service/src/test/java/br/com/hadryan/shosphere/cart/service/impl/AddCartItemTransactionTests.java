package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AddCartItemTransactionTests {
    private final CartRepository repository = mock(CartRepository.class);
    private final AddCartItemTransaction transaction = new AddCartItemTransaction(repository);
    private final UUID customerId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void createsOrReusesActiveCartAndAddsItemUnderLock() {
        var cart = mock(Cart.class);
        when(repository.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cart.getId()).thenReturn(cartId);
        assertEquals(cartId, transaction.execute(customerId, new AddProductRequest(null, productId, 2)));
        var order = inOrder(repository, cart);
        order.verify(repository).createActiveCartIfAbsent(any(UUID.class), eq(customerId));
        order.verify(repository).findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
        order.verify(cart).addItem(productId, 2);
        order.verify(repository).saveAndFlush(cart);
    }

    @Test
    void rechecksOwnershipAndActiveStatusForExplicitCartId() {
        var request = new AddProductRequest(cartId, productId, 2);
        assertThrows(ResponseStatusException.class, () -> transaction.execute(customerId, request));
        verify(repository).findWithLockByIdAndCustomerIdAndStatus(cartId, customerId, CartStatus.ACTIVE);
        verify(repository, never()).createActiveCartIfAbsent(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }
}
