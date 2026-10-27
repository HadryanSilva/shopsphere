package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.CheckoutIntentRepository;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CheckoutIntentTransaction {
    private final CheckoutIntentRepository intents;
    private final OrderRepository orders;
    @PersistenceContext
    private EntityManager entityManager;

    public CheckoutIntentSnapshot start(UUID customerId, UUID key, ShippingAddress address) {
        var existing = intents.findWithLockByCustomerIdAndIdempotencyKey(customerId, key.toString());
        if (existing.isPresent()) {
            if (!existing.get().sameAddress(address)) throw conflict();
            return CheckoutIntentSnapshot.from(existing.get());
        }
        if (orders.findByCustomerIdAndIdempotencyKey(customerId, key.toString()).isPresent()) throw conflict();
        var intent = CheckoutIntent.start(customerId, key, address);
        intents.saveAndFlush(intent);
        return CheckoutIntentSnapshot.from(intent);
    }

    public CheckoutIntentSnapshot get(UUID id) {
        // Preparation adds the snapshot in a later transaction: read state and items under the same root lock.
        return CheckoutIntentSnapshot.from(lock(id));
    }

    public Optional<CheckoutIntentSnapshot> find(UUID customerId, UUID key) {
        return intents.findWithLockByCustomerIdAndIdempotencyKey(customerId, key.toString()).map(CheckoutIntentSnapshot::from);
    }

    public CheckoutIntentSnapshot prepared(UUID id, UUID cartId, List<CheckoutIntentItem> items) {
        var intent = lock(id);
        if (intent.getStatus() == CheckoutIntentStatus.PREPARING) intent.prepare(cartId, items);
        else if (intent.getStatus() != CheckoutIntentStatus.PREPARED || !intent.sameSnapshot(cartId, items)) throw conflict();
        intents.flush();
        return CheckoutIntentSnapshot.from(intent);
    }

    public OrderResponse persistOrder(UUID id, Order order) {
        var intent = lock(id);
        if (!intent.getCustomerId().equals(order.getCustomerId())
                || !intent.getIdempotencyKey().equals(order.getIdempotencyKey())
                || !intent.sameAddress(order.getShippingAddress())) throw conflict();
        if (intent.getStatus() == CheckoutIntentStatus.COMPLETION_PENDING || intent.getStatus() == CheckoutIntentStatus.COMPLETED)
            return OrderResponse.fromOrder(orders.findById(intent.getOrderId()).orElseThrow());
        if (intent.getStatus() != CheckoutIntentStatus.PREPARED || order.getId() != null) throw conflict();
        var items = order.getItems().stream()
                .map(item -> new CheckoutIntentItem(item.getProductId(), item.getQuantity())).toList();
        if (!intent.sameSnapshot(intent.getCartId(), items) || items.isEmpty()
                || order.getSourceCartId() != null && !order.getSourceCartId().equals(intent.getCartId())) throw conflict();
        order.assignSourceCart(intent.getCartId());
        var saved = orders.saveAndFlush(order);
        // Return the database representation, including its timestamp precision, on the first attempt too.
        entityManager.refresh(saved);
        intent.orderPersisted(saved.getId());
        intents.flush();
        return OrderResponse.fromOrder(saved);
    }

    public CheckoutIntentSnapshot requestAbort(UUID id, CheckoutFailureReason reason) {
        var intent = lock(id);
        if (intent.getStatus() == CheckoutIntentStatus.ABORT_PENDING || intent.getStatus() == CheckoutIntentStatus.ABORTED) {
            if (intent.getFailureReason() != reason) throw conflict();
        } else {
            if (intent.getStatus() != CheckoutIntentStatus.PREPARING && intent.getStatus() != CheckoutIntentStatus.PREPARED) throw conflict();
            intent.requestAbort(reason);
        }
        intents.flush();
        return CheckoutIntentSnapshot.from(intent);
    }

    public CheckoutIntentSnapshot completed(UUID id) {
        var intent = lock(id);
        if (intent.getStatus() == CheckoutIntentStatus.COMPLETION_PENDING) intent.completed();
        else if (intent.getStatus() != CheckoutIntentStatus.COMPLETED) throw conflict();
        intents.flush();
        return CheckoutIntentSnapshot.from(intent);
    }

    public CheckoutIntentSnapshot aborted(UUID id) {
        var intent = lock(id);
        if (intent.getStatus() == CheckoutIntentStatus.ABORT_PENDING) intent.aborted();
        else if (intent.getStatus() != CheckoutIntentStatus.ABORTED) throw conflict();
        intents.flush();
        return CheckoutIntentSnapshot.from(intent);
    }

    public CheckoutIntentSnapshot recordFailure(UUID id, CheckoutIntentStatus expected, CheckoutRetryCategory category, Instant nextAttemptAt) {
        var intent = lock(id);
        if (intent.getStatus() == expected && expected != CheckoutIntentStatus.COMPLETED && expected != CheckoutIntentStatus.ABORTED)
            intent.recordFailure(category, nextAttemptAt);
        intents.flush();
        return CheckoutIntentSnapshot.from(intent);
    }

    private CheckoutIntent lock(UUID id) {
        return intents.findWithLockByCheckoutId(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout intent not found"));
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Checkout intent conflicts with current state");
    }
}
