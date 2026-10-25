package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.service.UpdateCartUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class UpdateCartUseCaseImpl implements UpdateCartUseCase {

    private final CartRepository cartRepository;
    private final CatalogClient catalogClient;
    private final UpdateCartItemTransaction updateCartItemTransaction;

    @Override
    public void execute(UUID customerId, UUID productId, UpdateCartRequest request) {
        var cart = cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));
        if (cart.getItems().stream().noneMatch(item -> item.getProductId().equals(productId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart item not found");
        }

        var product = catalogClient.getProductById(productId);
        if (!product.status().equals("ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is not active or is out of stock");
        }

        updateCartItemTransaction.execute(customerId, productId, request.quantity());
    }

}
