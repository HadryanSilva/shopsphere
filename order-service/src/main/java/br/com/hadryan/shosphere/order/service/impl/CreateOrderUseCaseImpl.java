package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.CartClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.mapper.response.CartItemResponse;
import br.com.hadryan.shosphere.order.mapper.response.CartResponse;
import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.service.CreateOrderUseCase;
import br.com.hadryan.shosphere.order.util.CurrentUserTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import feign.FeignException;
import feign.RetryableException;
import jakarta.validation.Validator;

import java.util.UUID;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.Currency;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "checkout.snapshot.enabled", havingValue = "false", matchIfMissing = true)
public class CreateOrderUseCaseImpl implements CreateOrderUseCase {

    private final CurrentUserTokenProvider currentUserTokenProvider;
    private final CartClient cartClient;
    private final CatalogClient catalogClient;
    private final CreateOrderTransaction createOrderTransaction;
    private final OrderIdempotency orderIdempotency;
    private final Validator validator;

    @Override
    public OrderResponse execute(UUID customerId, UUID idempotencyKey, CreateOrderRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid shipping address");
        }
        var existing = orderIdempotency.findExisting(customerId, idempotencyKey, request.shippingAddress());
        if (existing.isPresent()) {
            return existing.get();
        }
        var authorization = currentUserTokenProvider.getCurrentUserToken();
        var cart = fetchCart(authorization);
        var productIds = validateCart(cart);
        var products = fetchProducts(cart.items().stream().map(CartItemResponse::productId).toList());
        validateProducts(products, productIds);

        var address = request.shippingAddress();
        var order = Order.create(customerId, idempotencyKey, products.getFirst().currency(),
                new ShippingAddress(address.recipient(), address.addressLine(), address.addressLine2(),
                        address.city(), address.postalCode(), address.countryCode()));
        var productsById = products.stream().collect(Collectors.toMap(ProductResponse::id, Function.identity()));
        try {
            for (var item : cart.items()) {
                var product = productsById.get(item.productId());
                order.addItem(product.id(), product.name(), product.price(), item.quantity());
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order monetary amounts are not supported", exception);
        }
        try {
            return createOrderTransaction.execute(order);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyConflict(exception)) {
                throw exception;
            }
            // A tentativa de criação já foi revertida pelo proxy transacional.
            return orderIdempotency.findExisting(customerId, idempotencyKey, request.shippingAddress())
                    .orElseThrow(() -> exception);
        }
    }

    private boolean isIdempotencyConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_orders_customer_idempotency".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private CartResponse fetchCart(String authorization) {
        try {
            return cartClient.getCustomerCart(authorization);
        } catch (FeignException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active cart not found", exception);
        } catch (RetryableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cart service unavailable", exception);
        } catch (FeignException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cart service request failed", exception);
        }
    }

    private List<ProductResponse> fetchProducts(List<UUID> productIds) {
        try {
            return catalogClient.getProducts(productIds);
        } catch (RetryableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service unavailable", exception);
        } catch (FeignException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Catalog service request failed", exception);
        }
    }

    private Set<UUID> validateCart(CartResponse cart) {
        if (cart == null || cart.id() == null || cart.status() == null || cart.items() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid cart service response");
        }
        if (!"ACTIVE".equals(cart.status()) || cart.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart must be active and nonempty");
        }
        Set<UUID> productIds = new HashSet<>();
        for (var item : cart.items()) {
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0
                    || !productIds.add(item.productId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid or duplicate cart item");
            }
        }
        return productIds;
    }

    private void validateProducts(List<ProductResponse> products, Set<UUID> requestedIds) {
        if (products == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog service response");
        }
        Set<UUID> receivedIds = new HashSet<>();
        String currency = null;
        for (var product : products) {
            if (product == null || product.id() == null || !requestedIds.contains(product.id())
                    || !receivedIds.add(product.id()) || product.name() == null || product.name().isBlank()
                    || product.name().length() > 150 || product.currency() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog product");
            }
            if (!"ACTIVE".equals(product.status()) || product.price() == null || product.price().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product unavailable or price invalid");
            }
            try {
                Currency.getInstance(product.currency());
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid product currency", exception);
            }
            if (currency != null && !currency.equals(product.currency())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Products must use the same currency");
            }
            currency = product.currency();
        }
        if (!receivedIds.equals(requestedIds)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Some cart products no longer exist");
        }
    }
}
