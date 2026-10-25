package br.com.hadryan.shopsphere.catalog.mapper.response;

import br.com.hadryan.shopsphere.catalog.model.Category;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String description,
        boolean active
) {

    public static CategoryResponse fromCategory(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.isActive()
        );
    }

}
