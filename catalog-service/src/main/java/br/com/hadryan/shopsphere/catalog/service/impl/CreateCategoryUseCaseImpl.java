package br.com.hadryan.shopsphere.catalog.service.impl;

import br.com.hadryan.shopsphere.catalog.model.Category;
import br.com.hadryan.shopsphere.catalog.repository.CategoryRepository;
import br.com.hadryan.shopsphere.catalog.service.CreateCategoryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class CreateCategoryUseCaseImpl implements CreateCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public Category execute(Category category)
    {
        return categoryRepository.save(category);
    }

}
