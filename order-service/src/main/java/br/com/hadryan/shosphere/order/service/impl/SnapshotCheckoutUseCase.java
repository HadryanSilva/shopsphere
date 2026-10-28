package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.CartCheckoutClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.client.CheckoutIntegrationFailure;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import br.com.hadryan.shosphere.order.mapper.response.CartCheckoutResponse;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.order.model.CheckoutIntentItem;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.service.CreateOrderUseCase;
import feign.FeignException;
import feign.RetryableException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "checkout.snapshot.enabled", havingValue = "true")
public class SnapshotCheckoutUseCase implements CreateOrderUseCase {
    private final CheckoutIntentService intents;
    private final CheckoutIntentTransaction transaction;
    private final OrderIdempotency idempotency;
    private final CartCheckoutClient cart;
    private final CatalogClient catalog;
    private final Validator validator;

    @Override
    public OrderResponse execute(UUID customerId, UUID key, CreateOrderRequest request) {
        if (request == null || !validator.validate(request).isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid shipping address");
        var existing = idempotency.findExisting(customerId, key, request.shippingAddress());
        if (existing.isPresent()) {
            // A persisted order must remain available even if Cart or pending-work bookkeeping is unavailable.
            try { transaction.find(customerId, key).ifPresent(this::completeIfPending); }
            catch (DataAccessException failure) { /* The durable pending state remains for recovery. */ }
            return existing.get();
        }
        var address = request.shippingAddress();
        CheckoutIntentSnapshot intent;
        try {
            intent = intents.start(customerId, key, new ShippingAddress(address.recipient(), address.addressLine(),
                    address.addressLine2(), address.city(), address.postalCode(), address.countryCode()));
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            var raced = idempotency.findExisting(customerId, key, address);
            if (raced.isPresent()) return raced.get();
            throw conflict;
        }
        return resume(intent.checkoutId());
    }

    // Resume persisted work without a user JWT, including recovery after a restart.
    public OrderResponse resume(UUID checkoutId) {
        var intent = transaction.get(checkoutId);
        if (intent.orderId() != null) {
            var saved = savedOrder(intent);
            completeIfPending(intent);
            return saved;
        }
        if (intent.status() == CheckoutIntentStatus.ABORT_PENDING || intent.status() == CheckoutIntentStatus.ABORTED)
            return abortOrReplay(intent, intent.failureReason());
        if (intent.status() == CheckoutIntentStatus.PREPARING) {
            CartCheckoutResponse prepared;
            try {
                var preparing = intent;
                prepared = remote(preparing, () -> {
                    var result = cart.prepare(preparing.checkoutId(), preparing.customerId());
                    validatePrepared(result, preparing);
                    return result;
                });
            } catch (ResponseStatusException failure) {
                if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 409)
                    return abortOrReplay(intent, failure.getStatusCode().value() == 404
                            ? CheckoutFailureReason.CART_NOT_FOUND : CheckoutFailureReason.CART_CONFLICT);
                throw failure;
            }
            try {
                intent = transaction.prepared(intent.checkoutId(), prepared.cartId(), prepared.items().stream()
                        .map(item -> new CheckoutIntentItem(item.productId(), item.quantity())).toList());
            } catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode().value() != 409) throw conflict;
                var current = transaction.get(checkoutId);
                if (current.status() == CheckoutIntentStatus.PREPARED && !sameItems(prepared, current))
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid cart checkout response");
                if (current.status() == CheckoutIntentStatus.PREPARING) throw conflict;
                return resume(checkoutId);
            }
        }
        var ready = intent;
        List<ProductResponse> products;
        try {
            products = remote(ready, () -> fetchProducts(ready));
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 409) return abortOrReplay(ready, CheckoutFailureReason.PRODUCT_CONFLICT);
            throw failure;
        }
        var productsById = products.stream().collect(Collectors.toMap(ProductResponse::id, product -> product));
        Order order;
        try {
            order = Order.create(ready.customerId(), UUID.fromString(ready.idempotencyKey()), products.getFirst().currency(), ready.shippingAddress());
            for (var item : ready.items()) {
                var product = productsById.get(item.productId());
                order.addItem(product.id(), product.name(), product.price(), item.quantity());
            }
        } catch (IllegalArgumentException failure) {
            return abortOrReplay(ready, CheckoutFailureReason.MONETARY_CONFLICT);
        }
        OrderResponse saved;
        try { saved = transaction.persistOrder(checkoutId, order); }
        catch (DataIntegrityViolationException failure) {
            if (constraint(failure, "uk_orders_source_cart")) return abortOrReplay(ready, CheckoutFailureReason.ORDER_CONFLICT);
            recordFailure(ready, CheckoutRetryCategory.PERSISTENCE_FAILURE);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order persistence conflicts with stored data");
        } catch (DataAccessException failure) {
            recordFailure(ready, CheckoutRetryCategory.PERSISTENCE_FAILURE);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout persistence unavailable");
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            var current = transaction.get(checkoutId);
            if (current.status() == CheckoutIntentStatus.ABORT_PENDING || current.status() == CheckoutIntentStatus.ABORTED)
                return abortOrReplay(current, current.failureReason());
            throw conflict;
        }
        // Use the known persisted identity even if a subsequent bookkeeping read fails.
        try { completeIfPending(transaction.get(checkoutId)); }
        catch (DataAccessException failure) { /* Commit succeeded; recovery can finish the Cart. */ }
        return saved;
    }

    private OrderResponse savedOrder(CheckoutIntentSnapshot intent) {
        var address = intent.shippingAddress();
        return idempotency.findExisting(intent.customerId(), UUID.fromString(intent.idempotencyKey()),
                new ShippingAddressRequest(address.getRecipient(), address.getAddressLine(), address.getAddressLine2(),
                        address.getCity(), address.getPostalCode(), address.getCountryCode()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Persisted checkout order unavailable"));
    }

    private void completeIfPending(CheckoutIntentSnapshot intent) {
        if (intent.status() != CheckoutIntentStatus.COMPLETION_PENDING) return;
        try {
            var result = cart.complete(intent.checkoutId(), intent.orderId());
            validateIdentity(result, intent);
            if (!"COMPLETED".equals(result.status()) || !intent.orderId().equals(result.orderId())
                    || result.completedAt() == null || result.abortedAt() != null || result.preparedAt() == null
                    || !sameItems(result, intent)) throw invalidResponse();
            transaction.completed(intent.checkoutId());
        } catch (CheckoutIntegrationFailure failure) { recordFailure(intent, failure.category()); }
        catch (DataAccessException | ResponseStatusException failure) { recordFailure(intent, CheckoutRetryCategory.PERSISTENCE_FAILURE); }
    }

    private OrderResponse abortOrReplay(CheckoutIntentSnapshot original, CheckoutFailureReason reason) {
        CheckoutIntentSnapshot pending;
        try { pending = transaction.requestAbort(original.checkoutId(), reason); }
        catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            var current = transaction.get(original.checkoutId());
            if (current.orderId() != null) {
                var saved = savedOrder(current);
                completeIfPending(current);
                return saved;
            }
            if (current.failureReason() == null) throw conflict;
            pending = current;
        }
        if (pending.status() == CheckoutIntentStatus.ABORT_PENDING) {
            try {
                var result = cart.abort(pending.checkoutId(), pending.customerId());
                validateIdentity(result, pending);
                if (!"ABORTED".equals(result.status()) || result.abortedAt() == null
                        || result.orderId() != null || result.completedAt() != null) throw invalidResponse();
                if (pending.cartId() != null && (!sameItems(result, pending) || result.preparedAt() == null)) throw invalidResponse();
                if (result.cartId() == null && (result.preparedAt() != null || result.items() == null || !result.items().isEmpty())) throw invalidResponse();
                if (result.cartId() != null && (result.preparedAt() == null || !validItems(result.items()))) throw invalidResponse();
                transaction.aborted(pending.checkoutId());
            } catch (CheckoutIntegrationFailure failure) { recordFailure(pending, failure.category()); }
            catch (DataAccessException | ResponseStatusException failure) { recordFailure(pending, CheckoutRetryCategory.PERSISTENCE_FAILURE); }
        }
        throw new ResponseStatusException(HttpStatus.valueOf(pending.failureReason().httpStatus()),
                switch (pending.failureReason()) {
                    case CART_NOT_FOUND -> "Active cart not found";
                    case CART_CONFLICT -> "Cart checkout unavailable";
                    case PRODUCT_CONFLICT -> "Checkout products unavailable or incompatible";
                    case MONETARY_CONFLICT -> "Order monetary amounts are not supported";
                    case ORDER_CONFLICT -> "Cart already belongs to another order";
                });
    }

    private <T> T remote(CheckoutIntentSnapshot intent, Supplier<T> operation) {
        try { return operation.get(); }
        catch (CheckoutIntegrationFailure failure) {
            recordFailure(intent, failure.category());
            throw new ResponseStatusException(HttpStatus.valueOf(failure.status()), "Checkout upstream service unavailable");
        }
    }

    private void recordFailure(CheckoutIntentSnapshot intent, CheckoutRetryCategory category) {
        try { transaction.retry(intent.checkoutId(), intent.status(), category); }
        catch (DataAccessException | ResponseStatusException failure) { /* Preserve the existing durable state if bookkeeping is unavailable. */ }
    }

    private List<ProductResponse> fetchProducts(CheckoutIntentSnapshot intent) {
        List<ProductResponse> products;
        try { products = catalog.getProducts(intent.items().stream().map(CheckoutIntentSnapshot.Item::productId).toList()); }
        catch (RetryableException failure) { throw new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE); }
        catch (FeignException failure) { throw new CheckoutIntegrationFailure(502, CheckoutRetryCategory.UPSTREAM_REJECTED); }
        if (products == null) throw invalidResponse();
        Set<UUID> requested = intent.items().stream().map(CheckoutIntentSnapshot.Item::productId).collect(Collectors.toSet());
        Set<UUID> received = new HashSet<>();
        String currency = null;
        for (var product : products) {
            if (product == null || product.id() == null || !requested.contains(product.id()) || !received.add(product.id())
                    || product.name() == null || product.name().isBlank() || product.name().length() > 150 || product.currency() == null)
                throw invalidResponse();
            if (!"ACTIVE".equals(product.status()) || product.price() == null || product.price().signum() <= 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product unavailable or price invalid");
            try { Currency.getInstance(product.currency()); }
            catch (IllegalArgumentException failure) { throw invalidResponse(); }
            if (currency != null && !currency.equals(product.currency()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Products must use the same currency");
            currency = product.currency();
        }
        if (!received.equals(requested)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Some cart products no longer exist");
        return products;
    }

    private void validatePrepared(CartCheckoutResponse result, CheckoutIntentSnapshot intent) {
        validateIdentity(result, intent);
        if (!"PREPARED".equals(result.status()) || result.cartId() == null || result.preparedAt() == null
                || result.orderId() != null || result.completedAt() != null || result.abortedAt() != null
                || !validItems(result.items())) throw invalidResponse();
    }

    private void validateIdentity(CartCheckoutResponse result, CheckoutIntentSnapshot intent) {
        if (result == null || !intent.checkoutId().equals(result.checkoutId()) || !intent.customerId().equals(result.customerId()))
            throw invalidResponse();
    }

    private boolean sameItems(CartCheckoutResponse result, CheckoutIntentSnapshot intent) {
        if (!Objects.equals(result.cartId(), intent.cartId()) || !validItems(result.items())) return false;
        var remote = result.items().stream().collect(Collectors.toMap(CartCheckoutResponse.Item::productId, CartCheckoutResponse.Item::quantity));
        return remote.equals(intent.items().stream().collect(Collectors.toMap(CheckoutIntentSnapshot.Item::productId, CheckoutIntentSnapshot.Item::quantity)));
    }

    private boolean validItems(List<CartCheckoutResponse.Item> items) {
        if (items == null || items.isEmpty()) return false;
        Set<UUID> ids = new HashSet<>();
        for (var item : items) if (item == null || item.productId() == null || item.quantity() == null
                || item.quantity() <= 0 || !ids.add(item.productId())) return false;
        return true;
    }

    private CheckoutIntegrationFailure invalidResponse() {
        return new CheckoutIntegrationFailure(502, CheckoutRetryCategory.INVALID_UPSTREAM_RESPONSE);
    }

    private boolean constraint(Throwable failure, String name) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof ConstraintViolationException violation && name.equals(violation.getConstraintName())) return true;
        return false;
    }
}
