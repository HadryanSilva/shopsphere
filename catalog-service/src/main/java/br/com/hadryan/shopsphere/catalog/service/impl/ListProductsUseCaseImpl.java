package br.com.hadryan.shopsphere.catalog.service.impl;

import br.com.hadryan.shopsphere.catalog.mapper.response.ProductResponse;
import br.com.hadryan.shopsphere.catalog.model.Product;
import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;
import br.com.hadryan.shopsphere.catalog.repository.ProductRepository;
import br.com.hadryan.shopsphere.catalog.repository.spec.ProductSpecifications;
import br.com.hadryan.shopsphere.catalog.service.ListProductsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class ListProductsUseCaseImpl implements ListProductsUseCase {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "name", "price", "createdAt"
    );

    private final ProductRepository productRepository;

    public Page<ProductResponse> execute(String name,
                                         UUID categoryId,
                                         BigDecimal minPrice,
                                         BigDecimal maxPrice,
                                         ProductStatus status,
                                         Pageable pageable) {
        if (minPrice != null
                && maxPrice != null
                && minPrice.compareTo(maxPrice) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minPrice must be less than or equal to maxPrice"
            );
        }

        validateSort(pageable);

        Specification<Product> specification =
                Specification.where(
                                ProductSpecifications.nameContains(name)
                        )
                        .and(ProductSpecifications.hasCategory(categoryId))
                        .and(ProductSpecifications.priceGreaterThanOrEqual(minPrice))
                        .and(ProductSpecifications.priceLessThanOrEqual(maxPrice))
                        .and(ProductSpecifications.hasStatus(status));

        return productRepository
                .findAll(specification, pageable)
                .map(ProductResponse::fromProduct);
    }

    private void validateSort(Pageable pageable) {
        boolean invalidSort = pageable.getSort().stream()
                .anyMatch(order ->
                        !ALLOWED_SORT_FIELDS.contains(order.getProperty())
                );

        if (invalidSort) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported sort field"
            );
        }
    }

}
