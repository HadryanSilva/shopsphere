package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.service.RemoveCartItemUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class RemoveCartItemUseCaseImpl implements RemoveCartItemUseCase {

    private final CartRepository cartRepository;

    @Override
    @Transactional
    public void execute(UUID customerId, UUID productId) {
        var cart = cartRepository.findWithLockByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));

        cart.removeItem(productId);
        cartRepository.saveAndFlush(cart);
    }
}
