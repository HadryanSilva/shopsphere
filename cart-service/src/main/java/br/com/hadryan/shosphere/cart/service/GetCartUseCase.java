package br.com.hadryan.shosphere.cart.service;

import br.com.hadryan.shosphere.cart.mapper.response.CartResponse;

import java.util.UUID;

public interface GetCartUseCase {

    CartResponse execute(UUID id);

}
