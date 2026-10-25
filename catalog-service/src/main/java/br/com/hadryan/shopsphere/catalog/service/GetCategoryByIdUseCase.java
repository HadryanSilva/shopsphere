package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.model.Category;

import java.util.UUID;

public interface GetCategoryByIdUseCase {

    Category execute(UUID id);

}
