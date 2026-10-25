package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.response.AddedProductResponse;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.service.AddProductToCartUseCase;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class AddProductToCartUseCaseImpl implements AddProductToCartUseCase {

    private final CartRepository cartRepository;
    private final CatalogClient catalogClient;
    private final AddCartItemTransaction addCartItemTransaction;

    @Override
    public AddedProductResponse execute(UUID customerId, AddProductRequest request) {
        if (request.cartId() != null && !cartRepository.existsByIdAndCustomerIdAndStatus(
                request.cartId(), customerId, CartStatus.ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found");
        }

        var product = catalogClient.getProductById(request.productId());
        if (!product.status().equals("ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is not active or is out of stock");
        }

        var cartId = addCartItemTransaction.execute(customerId, request);

        return new AddedProductResponse(
                cartId,
                product.id(),
                request.quantity(),
                "Item added to cart"
        );
    }

}
