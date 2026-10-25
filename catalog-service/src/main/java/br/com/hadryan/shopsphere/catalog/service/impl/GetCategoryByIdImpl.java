package br.com.hadryan.shopsphere.catalog.service.impl;

import br.com.hadryan.shopsphere.catalog.model.Category;
import br.com.hadryan.shopsphere.catalog.repository.CategoryRepository;
import br.com.hadryan.shopsphere.catalog.service.GetCategoryByIdUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class GetCategoryByIdImpl implements GetCategoryByIdUseCase {

    private final CategoryRepository categoryRepository;

    @Override
    public Category execute(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
    }
}
