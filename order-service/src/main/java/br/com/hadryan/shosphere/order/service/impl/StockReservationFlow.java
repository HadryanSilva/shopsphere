package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.InventoryReservationClient;
import br.com.hadryan.shosphere.order.client.StockIntegrationFailure;
import br.com.hadryan.shosphere.order.model.enums.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "checkout.snapshot.enabled", havingValue = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class StockReservationFlow {
    private final StockWorkTransaction transaction;
    private final InventoryReservationClient inventory;

    // Explicitly resumed by the scheduler in the next delivery; never on the initial checkout request.
    public StockWorkSnapshot resume(UUID orderId) {
        var work = transaction.cartCompleted(orderId);
        try {
            return switch (work.phase()) {
                case WAITING_CART, CLOSED -> work;
                case ABORT_PENDING -> abort(work);
                case RESERVE_PENDING -> reserve(work);
                case RESERVED -> observe(work, inventory.get(work));
                case REVIEW_REQUIRED -> {
                    inventory.get(work); // Read-only reconciliation; review does not authorize mutations.
                    yield transaction.get(orderId);
                }
            };
        } catch (StockIntegrationFailure failure) {
            if (failure.category() == StockFailureCategory.INVALID_UPSTREAM_RESPONSE)
                return transaction.review(orderId, failure.category());
            return transaction.recordFailure(orderId, work.phase(), failure.category(), nextAttempt());
        } catch (DataAccessException failure) {
            // If even this bookkeeping is unavailable, the caller retains the durable work for another pass.
            return transaction.recordFailure(orderId, work.phase(), StockFailureCategory.PERSISTENCE_FAILURE, nextAttempt());
        }
    }

    private StockWorkSnapshot reserve(StockWorkSnapshot work) {
        try { return observe(work, inventory.get(work)); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404) throw failure;
        }
        var current = transaction.get(work.orderId());
        if (current.phase() != StockWorkPhase.RESERVE_PENDING || current.abortRequested()) return current;
        try { return observe(current, inventory.reserve(current)); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404 && failure.status() != 409) throw failure;
        }
        try { return observe(current, inventory.get(current)); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404) throw failure;
        }
        return abort(transaction.requestAbort(work.orderId(), StockWorkReason.RESERVATION_REJECTED));
    }

    private StockWorkSnapshot observe(StockWorkSnapshot work, StockReservationObservation observation) {
        var current = transaction.observe(work.orderId(), observation, nextAttempt());
        if (current.phase() == StockWorkPhase.ABORT_PENDING) return abort(current);
        if (current.phase() == StockWorkPhase.RESERVED && !observation.expiresAt().isAfter(Instant.now()))
            return abort(transaction.requestAbort(work.orderId(), StockWorkReason.RESERVATION_EXPIRED));
        return current;
    }

    private StockWorkSnapshot abort(StockWorkSnapshot work) {
        var current = transaction.get(work.orderId());
        if (current.phase() != StockWorkPhase.ABORT_PENDING) return current;
        try {
            var proof = inventory.abort(current);
            return transaction.aborted(current.orderId(), proof.orderId(), proof.reservation());
        } catch (StockIntegrationFailure failure) {
            if (failure.category() == StockFailureCategory.INVALID_UPSTREAM_RESPONSE)
                return transaction.review(current.orderId(), failure.category());
            return transaction.recordFailure(current.orderId(), StockWorkPhase.ABORT_PENDING, failure.category(), nextAttempt());
        }
    }

    private Instant nextAttempt() { return Instant.now().plusSeconds(60); }
}
