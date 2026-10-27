package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.CartCheckout;
import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartCheckoutRepository;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CartCheckoutTransaction {
    private final CartCheckoutRepository checkouts;
    private final CartRepository carts;

    public Preparation prepare(UUID id, UUID customerId, String serviceClient) {
        var existing = checkouts.findWithLockByCheckoutId(id);
        if (existing.isPresent()) {
            return replay(existing.get(), customerId, serviceClient);
        }
        var active = carts.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE);
        // Another prepare may have committed while this transaction waited for the cart.
        // Do not acquire a checkout lock after the cart lock: transitions use the opposite order.
        var raced = checkouts.findById(id);
        if (raced.isPresent()) return replay(raced.get(), customerId, serviceClient);
        var cart = active.orElseThrow(CartCheckoutTransaction::notFound);
        if (cart.getItems().isEmpty() || carts.isCheckoutPrepared(cart.getId())) throw conflict();
        var checkout = CartCheckout.prepare(id, customerId, serviceClient, cart);
        checkouts.saveAndFlush(checkout);
        return new Preparation(CartCheckoutSnapshot.from(checkout), true);
    }

    public CartCheckoutSnapshot get(UUID id, String serviceClient) {
        var checkout = checkouts.findById(id).orElseThrow(CartCheckoutTransaction::notFound);
        verifyService(checkout, serviceClient);
        return CartCheckoutSnapshot.from(checkout);
    }

    public CartCheckoutSnapshot complete(UUID id, UUID orderId, String serviceClient) {
        var checkout = checkouts.findWithLockByCheckoutId(id).orElseThrow(CartCheckoutTransaction::notFound);
        verifyService(checkout, serviceClient);
        if (checkout.getStatus() == CartCheckoutStatus.COMPLETED) {
            if (!orderId.equals(checkout.getOrderId())) throw conflict();
        } else {
            if (checkout.getStatus() != CartCheckoutStatus.PREPARED) throw conflict();
            var cart = lockCart(checkout);
            checkout.complete(orderId);
            cart.setStatus(CartStatus.CHECKED_OUT);
            carts.saveAndFlush(cart);
            checkouts.flush();
        }
        return CartCheckoutSnapshot.from(checkout);
    }

    public CartCheckoutSnapshot abort(UUID id, UUID customerId, String serviceClient) {
        var existing = checkouts.findWithLockByCheckoutId(id);
        if (existing.isEmpty()) {
            var checkout = CartCheckout.aborted(id, customerId, serviceClient);
            checkouts.saveAndFlush(checkout);
            return CartCheckoutSnapshot.from(checkout);
        }
        var checkout = existing.get();
        verifyIdentity(checkout, customerId, serviceClient);
        if (checkout.getStatus() == CartCheckoutStatus.COMPLETED) throw conflict();
        if (checkout.getStatus() == CartCheckoutStatus.PREPARED) {
            lockCart(checkout);
            checkout.abort();
            checkouts.flush();
        }
        return CartCheckoutSnapshot.from(checkout);
    }

    private Cart lockCart(CartCheckout checkout) {
        var cart = carts.findWithLockById(checkout.getCartId()).orElseThrow(CartCheckoutTransaction::notFound);
        if (cart.getStatus() != CartStatus.ACTIVE || !cart.getCustomerId().equals(checkout.getCustomerId())) throw conflict();
        return cart;
    }

    private Preparation replay(CartCheckout checkout, UUID customerId, String serviceClient) {
        verifyIdentity(checkout, customerId, serviceClient);
        if (checkout.getStatus() == CartCheckoutStatus.ABORTED) throw conflict();
        return new Preparation(CartCheckoutSnapshot.from(checkout), false);
    }

    private void verifyIdentity(CartCheckout checkout, UUID customerId, String serviceClient) {
        verifyService(checkout, serviceClient);
        if (!customerId.equals(checkout.getCustomerId())) throw conflict();
    }

    private void verifyService(CartCheckout checkout, String serviceClient) {
        if (!checkout.getServiceClient().equals(serviceClient)) throw notFound();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Cart checkout conflicts with current state");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart checkout or active cart not found");
    }

    public record Preparation(CartCheckoutSnapshot snapshot, boolean created) {}
}
