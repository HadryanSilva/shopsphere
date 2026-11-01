package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "checkout.snapshot.enabled", havingValue = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentFlow {
    private final PaymentWorkTransaction transaction;
    private final PaymentClient payment;
    private final InventoryReservationClient inventory;

    // One durable phase per invocation. Public acceptance never waits for this flow; scheduling is added in step 61.
    public PaymentWorkSnapshot resume(UUID id) {
        var work = transaction.get(id);
        try {
            return switch (work.phase()) {
                case CAPTURE_PENDING -> capture(work);
                case CONFIRM_PENDING -> confirm(work);
                case RESOLUTION_PENDING -> resolve(work);
                case CANCEL_PAYMENT_PENDING -> cancel(work);
                case DONE, CLOSED, REVIEW_REQUIRED -> work;
            };
        } catch (PaymentIntegrationFailure failure) {
            return transaction.failure(id, work.phase(), failure.category());
        } catch (StockIntegrationFailure failure) {
            var category = switch (failure.category()) {
                case AUTHENTICATION_CONFIGURATION -> PaymentFailureCategory.AUTHENTICATION_CONFIGURATION;
                case UPSTREAM_UNAVAILABLE -> PaymentFailureCategory.UPSTREAM_UNAVAILABLE;
                case PERSISTENCE_FAILURE -> PaymentFailureCategory.PERSISTENCE_FAILURE;
                default -> PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE;
            };
            return transaction.failure(id, work.phase(), category);
        } catch (DataAccessException failure) {
            return transaction.failure(id, work.phase(), PaymentFailureCategory.PERSISTENCE_FAILURE);
        }
    }

    private PaymentWorkSnapshot capture(PaymentWorkSnapshot work) {
        try { work = transaction.inventory(work.orderId(), work.phase(), inventory.get(work.stock())); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404) throw failure;
            return transaction.resolution(work.orderId(), work.phase());
        }
        if (work.phase() != PaymentWorkPhase.CAPTURE_PENDING) return work;
        try {
            work = transaction.payment(work.orderId(), work.phase(), payment.get(work));
            if (work.phase() != PaymentWorkPhase.CAPTURE_PENDING) return work;
        }
        catch (PaymentIntegrationFailure failure) { if (failure.status() != 404) throw failure; }
        var current = transaction.get(work.orderId());
        if (current.phase() != PaymentWorkPhase.CAPTURE_PENDING) return current;
        try { return transaction.payment(current.orderId(), current.phase(), payment.capture(current)); }
        catch (PaymentIntegrationFailure failure) {
            if (failure.status() != 409) throw failure;
            return transaction.payment(current.orderId(), current.phase(), payment.get(current));
        }
    }

    private PaymentWorkSnapshot confirm(PaymentWorkSnapshot work) {
        work = transaction.payment(work.orderId(), work.phase(), payment.get(work));
        if (work.phase() != PaymentWorkPhase.CONFIRM_PENDING) return work;
        try { work = transaction.inventory(work.orderId(), work.phase(), inventory.get(work.stock())); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404) throw failure;
            return transaction.resolution(work.orderId(), work.phase());
        }
        if (work.phase() != PaymentWorkPhase.CONFIRM_PENDING) return work;
        var current = transaction.get(work.orderId());
        if (current.phase() != PaymentWorkPhase.CONFIRM_PENDING) return current;
        try { return transaction.inventory(current.orderId(), current.phase(), inventory.confirm(current.stock())); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 409 && failure.status() != 404) throw failure;
            return transaction.resolution(current.orderId(), current.phase());
        }
    }

    private PaymentWorkSnapshot resolve(PaymentWorkSnapshot work) {
        try { work = transaction.inventory(work.orderId(), work.phase(), inventory.get(work.stock())); }
        catch (StockIntegrationFailure failure) { if (failure.status() != 404) throw failure; }
        if (work.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return work;
        if (work.stock().reservationStatus() == StockReservationStatus.CONFIRMED) {
            try { return transaction.payment(work.orderId(), work.phase(), payment.get(work)); }
            catch (PaymentIntegrationFailure failure) {
                if (failure.status() != 404) throw failure;
                return transaction.failure(work.orderId(), work.phase(), PaymentFailureCategory.INCONSISTENT_PROOF);
            }
        }
        var current = transaction.get(work.orderId());
        if (current.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return current;
        try {
            var proof = inventory.abort(current.stock());
            return transaction.aborted(current.orderId(), proof.orderId(), proof.reservation());
        } catch (StockIntegrationFailure failure) {
            if (failure.status() != 409) throw failure;
            var reconciled = transaction.inventory(current.orderId(), current.phase(), inventory.get(current.stock()));
            if (reconciled.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return reconciled;
            if (reconciled.stock().reservationStatus() == StockReservationStatus.CONFIRMED)
                return transaction.payment(reconciled.orderId(), reconciled.phase(), payment.get(reconciled));
            return transaction.failure(current.orderId(), current.phase(), PaymentFailureCategory.INCONSISTENT_PROOF);
        }
    }

    private PaymentWorkSnapshot cancel(PaymentWorkSnapshot work) {
        var current = transaction.get(work.orderId());
        if (current.phase() != PaymentWorkPhase.CANCEL_PAYMENT_PENDING || !current.stockSafe()) return current;
        return transaction.payment(current.orderId(), current.phase(), payment.cancel(current));
    }
}
