package br.com.hadryan.shosphere.cart.service.impl;

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
public class UpdateCartItemTransaction {
    private final CartRepository cartRepository;

    @Transactional
    public void execute(UUID customerId, UUID productId, int quantity) {
        var cart = cartRepository.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));
        if (cartRepository.isCheckoutPrepared(cart.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart checkout is in progress");
        }
        var item = cart.getItems().stream().filter(candidate -> candidate.getProductId().equals(productId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart item not found"));
        item.changeQuantity(quantity);
        cartRepository.saveAndFlush(cart);
    }
}
