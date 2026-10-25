package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.mapper.request.UpdateProductRequest;

import java.util.UUID;

public interface UpdateProductUseCase {

    void execute(UUID id, UpdateProductRequest request);

}
