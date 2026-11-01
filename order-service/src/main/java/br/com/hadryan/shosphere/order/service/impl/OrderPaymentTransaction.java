package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderPaymentTransaction {
    private final OrderRepository orders;
    private final StockWorkRepository stock;
    private final PaymentWorkRepository payments;
    @PersistenceContext private EntityManager entityManager;

    public record Result(OrderResponse order, boolean terminal) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result request(UUID customerId, UUID orderId) {
        // A projection avoids caching an unlocked Order before taking locks in StockWork -> Order -> PaymentWork order.
        if (!orders.existsByIdAndCustomerId(orderId, customerId)) throw notFound();
        var work = stock.findWithLockByOrderId(orderId).orElseThrow(this::conflict);
        var order = entityManager.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !customerId.equals(order.getCustomerId())) throw notFound();
        var existing = payments.findWithLockByOrderId(orderId);
        if (existing.isPresent()) return new Result(OrderResponse.fromOrder(order), existing.get().getPhase().terminal());
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentStatus() != null
                || work.getPhase() != StockWorkPhase.RESERVED || work.isAbortRequested() || work.getReason() != null
                || work.getReservationStatus() != StockReservationStatus.ACTIVE) throw conflict();
        PaymentWork payment;
        try { payment = PaymentWork.start(order); }
        catch (IllegalArgumentException | ArithmeticException failure) { throw conflict(); }
        work.transferToPayment();
        order.startPayment();
        payments.saveAndFlush(payment);
        entityManager.flush();
        entityManager.refresh(order);
        return new Result(OrderResponse.fromOrder(order), false);
    }

    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"); }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Order is not eligible for payment"); }
}
