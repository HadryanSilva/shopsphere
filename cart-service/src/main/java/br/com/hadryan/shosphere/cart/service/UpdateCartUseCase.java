package br.com.hadryan.shosphere.cart.service;

import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;

import java.util.UUID;

public interface UpdateCartUseCase {

    void execute(UUID customerId, UUID productId, UpdateCartRequest request);

}
