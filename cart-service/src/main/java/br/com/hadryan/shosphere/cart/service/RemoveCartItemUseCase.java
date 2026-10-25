package br.com.hadryan.shosphere.cart.service;

import java.util.UUID;

public interface RemoveCartItemUseCase {

    void execute(UUID customerId, UUID productId);

}
