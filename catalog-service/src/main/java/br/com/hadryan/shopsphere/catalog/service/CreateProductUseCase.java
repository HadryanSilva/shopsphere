package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.model.Product;

public interface CreateProductUseCase
{
    Product execute(Product product);
}
