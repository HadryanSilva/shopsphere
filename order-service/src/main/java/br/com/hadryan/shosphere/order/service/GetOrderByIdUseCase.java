package br.com.hadryan.shosphere.order.service;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;

import java.util.UUID;

public interface GetOrderByIdUseCase {
    OrderResponse execute(UUID customerId, UUID orderId);
}
