package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PaymentWorkTransaction {
    private final StockWorkRepository stock;
    private final PaymentWorkRepository payments;
    @PersistenceContext private EntityManager entityManager;
    private record Locked(StockWork stock, Order order, PaymentWork payment) {}

    public PaymentWorkSnapshot get(UUID id) { return snapshot(lock(id)); }
    public PaymentWorkSnapshot resolution(UUID id, PaymentWorkPhase expected) {
        var state = lock(id);
        if (eligible(state, expected)) state.payment.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
        return snapshot(state);
    }
    public PaymentWorkSnapshot payment(UUID id, PaymentWorkPhase expected, PaymentObservation observation) {
        var state = lock(id); var work = state.payment;
        if (!eligible(state, expected)) return snapshot(state);
        try { work.remember(observation); }
        catch (IllegalArgumentException failure) { return review(state, PaymentFailureCategory.INCONSISTENT_PROOF); }
        var status = work.getPaymentStatus();
        if (work.getPhase() == PaymentWorkPhase.CAPTURE_PENDING) {
            if (status == OrderPaymentStatus.CAPTURED) work.moveTo(PaymentWorkPhase.CONFIRM_PENDING);
            else if (status != OrderPaymentStatus.PROCESSING) work.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
        } else if (work.getPhase() == PaymentWorkPhase.CONFIRM_PENDING && status != OrderPaymentStatus.CAPTURED)
            work.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
        if (work.getPhase() == PaymentWorkPhase.CANCEL_PAYMENT_PENDING
                && (status == OrderPaymentStatus.DECLINED || status == OrderPaymentStatus.CANCELLED || status == OrderPaymentStatus.REFUNDED)) {
            state.order.observePayment(status); state.order.cancel();
            state.stock.completePayment(false, work.isStockAbsent()); work.moveTo(PaymentWorkPhase.CLOSED);
        } else if (state.stock.getReservationStatus() == StockReservationStatus.CONFIRMED) {
            if (status == OrderPaymentStatus.CAPTURED && !work.isStockSafe()) complete(state);
            else if (status != OrderPaymentStatus.PROCESSING) return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        } else updatePublic(state);
        return snapshot(state);
    }
    public PaymentWorkSnapshot inventory(UUID id, PaymentWorkPhase expected, StockReservationObservation observation) {
        var state = lock(id);
        if ((state.payment.getPhase() == PaymentWorkPhase.CANCEL_PAYMENT_PENDING || state.payment.getPhase() == PaymentWorkPhase.CLOSED)
                && state.payment.isStockSafe()
                && observation != null && observation.status() == StockReservationStatus.CONFIRMED && valid(state, observation))
            return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        if (!eligible(state, expected)) return snapshot(state);
        if (!valid(state, observation)) return review(state, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE);
        if (state.payment.isStockSafe()) {
            if (observation.status() == StockReservationStatus.CONFIRMED) return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
            return snapshot(state);
        }
        try { state.stock.paymentObserved(observation.status(), observation.createdAt(), observation.expiresAt(), observation.updatedAt()); }
        catch (IllegalArgumentException failure) { return review(state, PaymentFailureCategory.INCONSISTENT_PROOF); }
        var status = state.stock.getReservationStatus();
        if (status == StockReservationStatus.CONFIRMED) {
            if (state.payment.getPaymentStatus() == OrderPaymentStatus.CAPTURED) complete(state);
            else if (state.payment.getPaymentStatus() == null || state.payment.getPaymentStatus() == OrderPaymentStatus.PROCESSING)
                state.payment.moveTo(PaymentWorkPhase.RESOLUTION_PENDING);
            else return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        } else if (status == StockReservationStatus.RELEASED || status == StockReservationStatus.EXPIRED) safeStock(state, false);
        return snapshot(state);
    }
    public PaymentWorkSnapshot aborted(UUID id, UUID proofId, StockReservationObservation reservation) {
        if (reservation != null) {
            if (reservation.status() != StockReservationStatus.RELEASED && reservation.status() != StockReservationStatus.EXPIRED)
                return failure(id, PaymentWorkPhase.RESOLUTION_PENDING, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE);
            if (!id.equals(proofId)) return failure(id, PaymentWorkPhase.RESOLUTION_PENDING, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE);
            return inventory(id, PaymentWorkPhase.RESOLUTION_PENDING, reservation);
        }
        var state = lock(id);
        if (!eligible(state, PaymentWorkPhase.RESOLUTION_PENDING)) return snapshot(state);
        if (!id.equals(proofId) || state.stock.getReservationStatus() == StockReservationStatus.CONFIRMED)
            return review(state, PaymentFailureCategory.INCONSISTENT_PROOF);
        safeStock(state, true); return snapshot(state);
    }
    public PaymentWorkSnapshot failure(UUID id, PaymentWorkPhase expected, PaymentFailureCategory category) {
        var state = lock(id);
        if (eligible(state, expected)) {
            if (category == PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE || category == PaymentFailureCategory.INCONSISTENT_PROOF)
                return review(state, category);
            state.payment.failure(category);
        }
        return snapshot(state);
    }
    private void safeStock(Locked state, boolean absent) {
        state.payment.proveStockSafe(absent); state.payment.moveTo(PaymentWorkPhase.CANCEL_PAYMENT_PENDING); updatePublic(state);
    }
    private void complete(Locked state) {
        if (state.payment.isStockSafe() || state.payment.getPaymentStatus() != OrderPaymentStatus.CAPTURED
                || state.stock.getReservationStatus() != StockReservationStatus.CONFIRMED) throw new IllegalStateException("Missing completion proofs");
        state.order.observePayment(OrderPaymentStatus.CAPTURED); state.order.confirm();
        state.stock.completePayment(true, false); state.payment.moveTo(PaymentWorkPhase.DONE);
    }
    private void updatePublic(Locked state) {
        var work = state.payment;
        state.order.observePayment(work.getCapturedAt() == null ? OrderPaymentStatus.PROCESSING
                : work.isStockSafe() || work.getPaymentStatus() == OrderPaymentStatus.REFUND_PENDING || work.getPaymentStatus() == OrderPaymentStatus.REFUNDED
                    ? OrderPaymentStatus.REFUND_PENDING : OrderPaymentStatus.CAPTURED);
    }
    private PaymentWorkSnapshot review(Locked state, PaymentFailureCategory category) {
        state.payment.moveTo(PaymentWorkPhase.REVIEW_REQUIRED); state.payment.failure(category);
        state.order.reviewPayment(); return snapshot(state);
    }
    private boolean eligible(Locked state, PaymentWorkPhase expected) {
        if (state.payment.getPhase() != expected || expected.terminal() || expected == PaymentWorkPhase.REVIEW_REQUIRED) return false;
        if (state.stock.getPhase() != StockWorkPhase.PAYMENT_PENDING || state.order.getStatus() != OrderStatus.PENDING_PAYMENT
                || state.order.getTotalAmount().compareTo(state.payment.getAmount()) != 0
                || !state.order.getCurrency().equals(state.payment.getCurrency())) throw new IllegalStateException("Payment handoff is inconsistent");
        return true;
    }
    private boolean valid(Locked state, StockReservationObservation proof) {
        if (proof == null || !state.order.getId().equals(proof.orderId()) || proof.status() == null || proof.createdAt() == null
                || proof.expiresAt() == null || proof.updatedAt() == null || !proof.createdAt().isBefore(proof.expiresAt())
                || proof.updatedAt().isBefore(proof.createdAt()) || proof.items() == null) return false;
        var expected = new HashMap<UUID, Integer>(); var actual = new HashMap<UUID, Integer>();
        state.order.getItems().forEach(item -> expected.put(item.getProductId(), item.getQuantity()));
        for (var item : proof.items())
            if (item == null || item.productId() == null || item.quantity() <= 0 || actual.putIfAbsent(item.productId(), item.quantity()) != null) return false;
        return !expected.isEmpty() && expected.equals(actual);
    }
    private Locked lock(UUID id) {
        var reservation = stock.findWithLockByOrderId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock work not found"));
        var order = Objects.requireNonNull(entityManager.find(Order.class, id, LockModeType.PESSIMISTIC_WRITE));
        var payment = payments.findWithLockByOrderId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment work not found"));
        return new Locked(reservation, order, payment);
    }
    private PaymentWorkSnapshot snapshot(Locked state) { payments.flush(); return PaymentWorkSnapshot.from(state.payment, state.stock, state.order); }
}
