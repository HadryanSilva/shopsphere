package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.model.Category;

public interface CreateCategoryUseCase {

    Category execute(Category category);

}
