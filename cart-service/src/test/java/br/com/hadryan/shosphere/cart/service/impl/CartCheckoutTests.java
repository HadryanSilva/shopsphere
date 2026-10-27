package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.CartCheckout;
import br.com.hadryan.shosphere.cart.model.CartItem;
import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartCheckoutRepository;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartCheckoutTests {
    private final CartRepository carts = mock(CartRepository.class);
    private final CartCheckoutRepository checkouts = mock(CartCheckoutRepository.class);
    private final CartCheckoutTransaction transaction = new CartCheckoutTransaction(checkouts, carts);
    private final UUID id = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final String client = "shopsphere-order";

    private Cart cart() {
        var cart = mock(Cart.class, CALLS_REAL_METHODS);
        when(cart.getId()).thenReturn(cartId);
        cart.setCustomerId(customerId);
        cart.setStatus(CartStatus.ACTIVE);
        cart.setItems(new ArrayList<>(List.of(new CartItem(cart, productId, 2))));
        return cart;
    }

    @Test
    void snapshotIsIndependentAndCompletionReplayPreservesTimestamps() {
        var cart = cart();
        var checkout = CartCheckout.prepare(id, customerId, client, cart);
        cart.getItems().getFirst().changeQuantity(9);
        assertEquals(2, checkout.getItems().getFirst().getQuantity());
        assertThrows(UnsupportedOperationException.class, () -> checkout.getItems().clear());
        var orderId = UUID.randomUUID();
        checkout.complete(orderId);
        var completed = checkout.getCompletedAt();
        checkout.complete(orderId);
        assertEquals(completed, checkout.getCompletedAt());
        assertEquals(CartCheckoutStatus.COMPLETED, checkout.getStatus());
        assertThrows(IllegalStateException.class, checkout::abort);
        assertThrows(IllegalStateException.class, () -> checkout.complete(UUID.randomUUID()));
    }

    @Test
    void earlyAbortHasNoCartAndReplayDoesNotChangeTimestamp() {
        var checkout = CartCheckout.aborted(id, customerId, client);
        var aborted = checkout.getAbortedAt();
        checkout.abort();
        assertEquals(aborted, checkout.getAbortedAt());
        assertNull(checkout.getCartId());
        assertNull(checkout.getPreparedAt());
        assertTrue(checkout.getItems().isEmpty());
        assertThrows(IllegalStateException.class, () -> checkout.complete(UUID.randomUUID()));
    }

    @Test
    void replayFoundAfterWaitingForCartDoesNotCreateAnotherPreparation() {
        var checkout = CartCheckout.prepare(id, customerId, client, cart());
        when(checkouts.findById(id)).thenReturn(Optional.of(checkout));
        var replay = transaction.prepare(id, customerId, client);
        assertFalse(replay.created());
        assertEquals(cartId, replay.snapshot().cartId());
        verify(checkouts, never()).saveAndFlush(any());
    }

    @Test
    void abortedIntentRejectsLatePreparationAndAnotherServiceCannotReadIt() {
        var checkout = CartCheckout.aborted(id, customerId, client);
        when(checkouts.findWithLockByCheckoutId(id)).thenReturn(Optional.of(checkout));
        when(checkouts.findById(id)).thenReturn(Optional.of(checkout));
        assertStatus(409, () -> transaction.prepare(id, customerId, client));
        assertStatus(409, () -> transaction.abort(id, UUID.randomUUID(), client));
        assertStatus(404, () -> transaction.get(id, "another-service"));
        verifyNoInteractions(carts);
    }

    @Test
    void onlyPrimaryKeyRaceIsRereadAfterFailedTransaction() {
        var proxy = mock(CartCheckoutTransaction.class);
        var service = new CartCheckoutService(proxy);
        var constraint = new ConstraintViolationException("duplicate", null, "pk_cart_checkouts");
        var failure = new DataIntegrityViolationException("duplicate", constraint);
        var result = new CartCheckoutTransaction.Preparation(
                CartCheckoutSnapshot.from(CartCheckout.prepare(id, customerId, client, cart())), false);
        when(proxy.prepare(id, customerId, client)).thenThrow(failure).thenReturn(result);
        assertSame(result, service.prepare(id, customerId, client));
        verify(proxy, times(2)).prepare(id, customerId, client);
        var otherFailure = new DataIntegrityViolationException("different constraint");
        when(proxy.abort(id, customerId, client)).thenThrow(otherFailure);
        assertSame(otherFailure, assertThrows(DataIntegrityViolationException.class,
                () -> service.abort(id, customerId, client)));
        verify(proxy).abort(id, customerId, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "explicitAdd", "update", "remove"})
    void preparedCartRejectsEveryWriteBeforeChangingChildren(String operation) {
        var cart = cart();
        when(carts.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(carts.findWithLockByIdAndCustomerIdAndStatus(cartId, customerId, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(carts.isCheckoutPrepared(cartId)).thenReturn(true);
        assertStatus(409, () -> {
            switch (operation) {
                case "add", "explicitAdd" -> new AddCartItemTransaction(carts).execute(customerId,
                        new AddProductRequest(operation.equals("add") ? null : cartId, productId, 1));
                case "update" -> new UpdateCartItemTransaction(carts).execute(customerId, productId, 8);
                case "remove" -> new RemoveCartItemUseCaseImpl(carts).execute(customerId, productId);
                default -> fail("Unexpected operation");
            }
        });
        assertEquals(2, cart.getItems().getFirst().getQuantity());
        verify(carts, never()).saveAndFlush(any());
        var order = inOrder(carts);
        if (operation.equals("explicitAdd")) order.verify(carts)
                .findWithLockByIdAndCustomerIdAndStatus(cartId, customerId, CartStatus.ACTIVE);
        else {
            if (operation.equals("add")) order.verify(carts).createActiveCartIfAbsent(any(), eq(customerId));
            order.verify(carts).findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
        }
        order.verify(carts).isCheckoutPrepared(cartId);
    }

    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
