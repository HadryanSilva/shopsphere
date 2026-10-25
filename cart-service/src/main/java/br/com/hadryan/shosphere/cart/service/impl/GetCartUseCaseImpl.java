package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.response.CartItemResponse;
import br.com.hadryan.shosphere.cart.mapper.response.CartResponse;
import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.cart.model.CartItem;
import br.com.hadryan.shosphere.cart.model.enums.Currency;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.service.GetCartUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

@RequiredArgsConstructor
@Service
public class GetCartUseCaseImpl implements GetCartUseCase {

    private final CartRepository cartRepository;
    private final CatalogClient catalogClient;

    @Override
    public CartResponse execute(UUID customerId) {
        var cart = cartRepository.findByCustomerIdAndStatus(customerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));

        var items = cart.getItems()
                .stream()
                .toList();

        var productsById = fetchProducts(items.stream().map(CartItem::getProductId).toList());
        List<CartItemResponse> cartItemResponses = new ArrayList<>();
        var total = BigDecimal.ZERO;
        for (CartItem item : items) {
            var product = productsById.get(item.getProductId());
            if (product == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found.");
            }
            BigDecimal subtotal = BigDecimal.ZERO;
            subtotal = subtotal.add(product.price().multiply(BigDecimal.valueOf(item.getQuantity())));
            var cartItem = new CartItemResponse(
                    item.getProductId(),
                    product.name(),
                    item.getQuantity(),
                    product.price(),
                    subtotal
            );
            cartItemResponses.add(cartItem);
            total = total.add(subtotal);
        }

        return new CartResponse(
                cart.getId(),
                cart.getStatus(),
                cartItemResponses,
                cart.getItems().size(),
                total,
                Currency.EUR,
                cart.getUpdatedAt()
        );

    }

    private Map<UUID, ProductResponse> fetchProducts(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        var products = catalogClient.getProducts(productIds);
        if (products == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog service response");
        }
        Map<UUID, ProductResponse> productsById = new HashMap<>();
        for (var product : products) {
            if (product == null || product.id() == null || product.price() == null
                    || productsById.putIfAbsent(product.id(), product) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog service response");
            }
        }
        return productsById;
    }
}
