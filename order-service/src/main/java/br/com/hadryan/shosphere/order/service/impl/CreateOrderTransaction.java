package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@RequiredArgsConstructor
public class CreateOrderTransaction {
    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResponse execute(Order order) {
        var saved = orderRepository.saveAndFlush(order);
        return OrderResponse.fromOrder(saved);
    }
}
