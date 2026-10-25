package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.model.Product;

import java.util.UUID;

public interface GetProductByIdUseCase {

    Product execute(UUID id);

}
