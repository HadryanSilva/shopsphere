package br.com.hadryan.shopsphere.catalog.controller;

import br.com.hadryan.shopsphere.catalog.mapper.request.CreateCategoryRequest;
import br.com.hadryan.shopsphere.catalog.mapper.response.CategoryResponse;
import br.com.hadryan.shopsphere.catalog.service.CreateCategoryUseCase;
import br.com.hadryan.shopsphere.catalog.service.GetCategoryByIdUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CreateCategoryUseCase createCategoryUseCase;
    private final GetCategoryByIdUseCase getCategoryByIdUseCase;

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> findCategoryById(@PathVariable UUID id) {
        return ResponseEntity.ok(CategoryResponse.fromCategory(getCategoryByIdUseCase.execute(id)));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        var category = createCategoryUseCase.execute(request.toCategory());
        return ResponseEntity.created(URI.create("/api/v1/categories/" + category.getId()))
                .body(CategoryResponse.fromCategory(category));
    }

}
