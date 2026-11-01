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

    // One durable phase per invocation. Public acceptance never waits for the worker's HTTP calls.
    public PaymentWorkSnapshot resume(UUID id) {
        return resume(id, null);
    }
    public PaymentWorkSnapshot resume(PaymentWorkClaim claim) { return resume(claim.work().orderId(), claim.token()); }
    private PaymentWorkSnapshot resume(UUID id, UUID token) {
        try { return execute(id, token); }
        catch (PaymentClaimLostException lost) { return transaction.get(id); }
    }
    private PaymentWorkSnapshot execute(UUID id, UUID token) {
        var work = transaction.get(id, token);
        try {
            return switch (work.phase()) {
                case CAPTURE_PENDING -> capture(work, token);
                case CONFIRM_PENDING -> confirm(work, token);
                case RESOLUTION_PENDING -> resolve(work, token);
                case CANCEL_PAYMENT_PENDING -> cancel(work, token);
                case REVIEW_REQUIRED -> review(work, token);
                case DONE, CLOSED -> work;
            };
        } catch (PaymentIntegrationFailure failure) {
            return transaction.failure(id, work.phase(), failure.category(), token);
        } catch (StockIntegrationFailure failure) {
            var category = switch (failure.category()) {
                case AUTHENTICATION_CONFIGURATION -> PaymentFailureCategory.AUTHENTICATION_CONFIGURATION;
                case UPSTREAM_UNAVAILABLE -> PaymentFailureCategory.UPSTREAM_UNAVAILABLE;
                case PERSISTENCE_FAILURE -> PaymentFailureCategory.PERSISTENCE_FAILURE;
                default -> PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE;
            };
            return transaction.failure(id, work.phase(), category, token);
        } catch (DataAccessException failure) {
            return transaction.failure(id, work.phase(), PaymentFailureCategory.PERSISTENCE_FAILURE, token);
        }
    }

    private PaymentWorkSnapshot capture(PaymentWorkSnapshot work, UUID token) {
        try { work = transaction.inventory(work.orderId(), work.phase(), inventory.get(work.stock()), token); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404) throw failure;
            return transaction.resolution(work.orderId(), work.phase(), token);
        }
        if (work.phase() != PaymentWorkPhase.CAPTURE_PENDING) return work;
        try {
            work = transaction.get(work.orderId(), token);
            if (work.phase() != PaymentWorkPhase.CAPTURE_PENDING) return work;
            work = transaction.payment(work.orderId(), work.phase(), payment.get(work), token);
            if (work.phase() != PaymentWorkPhase.CAPTURE_PENDING) return work;
        }
        catch (PaymentIntegrationFailure failure) { if (failure.status() != 404) throw failure; }
        var current = transaction.get(work.orderId(), token);
        if (current.phase() != PaymentWorkPhase.CAPTURE_PENDING) return current;
        try { return transaction.payment(current.orderId(), current.phase(), payment.capture(current), token); }
        catch (PaymentIntegrationFailure failure) {
            if (failure.status() != 409) throw failure;
            current = transaction.get(current.orderId(), token);
            if (current.phase() != PaymentWorkPhase.CAPTURE_PENDING) return current;
            return transaction.payment(current.orderId(), current.phase(), payment.get(current), token);
        }
    }

    private PaymentWorkSnapshot confirm(PaymentWorkSnapshot work, UUID token) {
        work = transaction.payment(work.orderId(), work.phase(), payment.get(work), token);
        if (work.phase() != PaymentWorkPhase.CONFIRM_PENDING) return work;
        work = transaction.get(work.orderId(), token);
        if (work.phase() != PaymentWorkPhase.CONFIRM_PENDING) return work;
        try { work = transaction.inventory(work.orderId(), work.phase(), inventory.get(work.stock()), token); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 404) throw failure;
            return transaction.resolution(work.orderId(), work.phase(), token);
        }
        if (work.phase() != PaymentWorkPhase.CONFIRM_PENDING) return work;
        var current = transaction.get(work.orderId(), token);
        if (current.phase() != PaymentWorkPhase.CONFIRM_PENDING) return current;
        try { return transaction.inventory(current.orderId(), current.phase(), inventory.confirm(current.stock()), token); }
        catch (StockIntegrationFailure failure) {
            if (failure.status() != 409 && failure.status() != 404) throw failure;
            return transaction.resolution(current.orderId(), current.phase(), token);
        }
    }

    private PaymentWorkSnapshot resolve(PaymentWorkSnapshot work, UUID token) {
        try { work = transaction.inventory(work.orderId(), work.phase(), inventory.get(work.stock()), token); }
        catch (StockIntegrationFailure failure) { if (failure.status() != 404) throw failure; }
        if (work.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return work;
        if (work.stock().reservationStatus() == StockReservationStatus.CONFIRMED) {
            work = transaction.get(work.orderId(), token);
            if (work.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return work;
            try { return transaction.payment(work.orderId(), work.phase(), payment.get(work), token); }
            catch (PaymentIntegrationFailure failure) {
                if (failure.status() != 404) throw failure;
                return transaction.failure(work.orderId(), work.phase(), PaymentFailureCategory.INCONSISTENT_PROOF, token);
            }
        }
        var current = transaction.get(work.orderId(), token);
        if (current.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return current;
        try {
            var proof = inventory.abort(current.stock());
            return transaction.aborted(current.orderId(), proof.orderId(), proof.reservation(), token);
        } catch (StockIntegrationFailure failure) {
            if (failure.status() != 409) throw failure;
            current = transaction.get(current.orderId(), token);
            if (current.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return current;
            var reconciled = transaction.inventory(current.orderId(), current.phase(), inventory.get(current.stock()), token);
            if (reconciled.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return reconciled;
            if (reconciled.stock().reservationStatus() == StockReservationStatus.CONFIRMED) {
                reconciled = transaction.get(reconciled.orderId(), token);
                if (reconciled.phase() != PaymentWorkPhase.RESOLUTION_PENDING) return reconciled;
                return transaction.payment(reconciled.orderId(), reconciled.phase(), payment.get(reconciled), token);
            }
            return transaction.failure(current.orderId(), current.phase(), PaymentFailureCategory.INCONSISTENT_PROOF, token);
        }
    }

    private PaymentWorkSnapshot cancel(PaymentWorkSnapshot work, UUID token) {
        var current = transaction.get(work.orderId(), token);
        if (current.phase() != PaymentWorkPhase.CANCEL_PAYMENT_PENDING || !current.stockSafe()) return current;
        return transaction.payment(current.orderId(), current.phase(), payment.cancel(current), token);
    }
    private PaymentWorkSnapshot review(PaymentWorkSnapshot work, UUID token) {
        if (token == null) return work; // Unscheduled diagnostic reads must not bypass the review interval.
        var current = transaction.get(work.orderId(), token);
        try { payment.get(current); } catch (PaymentIntegrationFailure ignored) { }
        current = transaction.get(work.orderId(), token);
        try { inventory.get(current.stock()); } catch (StockIntegrationFailure ignored) { }
        return transaction.get(work.orderId(), token);
    }
}
