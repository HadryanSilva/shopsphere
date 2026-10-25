package br.com.hadryan.shopsphere.catalog.mapper.response;

import br.com.hadryan.shopsphere.catalog.model.Product;
import br.com.hadryan.shopsphere.catalog.model.enums.Currency;
import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String description,
        UUID categoryId,
        BigDecimal price,
        Currency currency,
        String imageUrl,
        ProductStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse fromProduct(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getPrice(),
                Currency.EUR,
                product.getImageUrl(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
