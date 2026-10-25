package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AddCartItemTransaction {
    private final CartRepository cartRepository;

    @Transactional
    public UUID execute(UUID customerId, AddProductRequest request) {
        if (request.cartId() == null) {
            // O índice parcial resolve a corrida de criação sem abortar a transação perdedora.
            cartRepository.createActiveCartIfAbsent(UUID.randomUUID(), customerId);
        }

        // Serializa adições inclusive quando apenas os filhos do agregado são alterados.
        var cart = (request.cartId() == null
                ? cartRepository.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)
                : cartRepository.findWithLockByIdAndCustomerIdAndStatus(request.cartId(), customerId, CartStatus.ACTIVE))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));

        try {
            cart.addItem(request.productId(), request.quantity());
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart item quantity exceeds supported limit", exception);
        }
        cartRepository.saveAndFlush(cart);
        return cart.getId();
    }
}
