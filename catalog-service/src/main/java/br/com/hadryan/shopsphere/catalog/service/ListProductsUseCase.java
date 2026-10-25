package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.mapper.response.ProductResponse;
import br.com.hadryan.shopsphere.catalog.model.Product;
import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.UUID;

public interface ListProductsUseCase {

    Page<ProductResponse> execute(String name,
                                  UUID categoryId,
                                  BigDecimal minPrice,
                                  BigDecimal maxPrice,
                                  ProductStatus status,
                                  Pageable pageable);

}
