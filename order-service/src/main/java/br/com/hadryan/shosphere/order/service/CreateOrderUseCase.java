package br.com.hadryan.shosphere.order.service;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;

import java.util.UUID;

public interface CreateOrderUseCase {

    OrderResponse execute(UUID customerId, UUID idempotencyKey, CreateOrderRequest request);


}
