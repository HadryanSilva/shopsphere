package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import br.com.hadryan.shosphere.order.service.GetOrderByIdUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetOrderByIdUseCaseImpl implements GetOrderByIdUseCase {
    private final OrderRepository orderRepository;

    @Override
    @Transactional(readOnly = true)
    public OrderResponse execute(UUID customerId, UUID orderId) {
        var order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return OrderResponse.fromOrder(order);
    }
}
