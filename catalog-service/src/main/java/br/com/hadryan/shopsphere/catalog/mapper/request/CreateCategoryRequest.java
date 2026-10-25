package br.com.hadryan.shopsphere.catalog.mapper.request;

import br.com.hadryan.shopsphere.catalog.model.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 500) String description
) {

    public Category toCategory() {
        Category category = new Category();
        category.setName(name);
        category.setDescription(description);
        return category;
    }

}
