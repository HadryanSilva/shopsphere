package br.com.hadryan.shosphere.cart.service;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.response.AddedProductResponse;

import java.util.UUID;

public interface AddProductToCartUseCase {

    AddedProductResponse execute(UUID customerId, AddProductRequest request);

}
