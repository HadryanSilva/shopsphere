package br.com.hadryan.shopsphere.catalog.controller;

import br.com.hadryan.shopsphere.catalog.mapper.request.CreateProductRequest;
import br.com.hadryan.shopsphere.catalog.mapper.request.SearchProductsRequest;
import br.com.hadryan.shopsphere.catalog.mapper.request.UpdateProductRequest;
import br.com.hadryan.shopsphere.catalog.mapper.response.PagedResponse;
import br.com.hadryan.shopsphere.catalog.mapper.response.ProductResponse;
import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;
import br.com.hadryan.shopsphere.catalog.service.CreateProductUseCase;
import br.com.hadryan.shopsphere.catalog.service.GetProductByIdUseCase;
import br.com.hadryan.shopsphere.catalog.service.ListProductsUseCase;
import br.com.hadryan.shopsphere.catalog.service.UpdateProductUseCase;
import br.com.hadryan.shopsphere.catalog.service.SearchProductsUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CreateProductUseCase createProductUseCase;
    private final ListProductsUseCase  listProductsUseCase;
    private final GetProductByIdUseCase getProductByIdUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final SearchProductsUseCase searchProductsUseCase;

    @GetMapping
    public PagedResponse<ProductResponse> searchProducts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) ProductStatus status,
            @ParameterObject @PageableDefault(
                    size = 20,
                    sort = "name",
                    direction = Sort.Direction.ASC
            ) Pageable pageable) {

        Page<ProductResponse> result = listProductsUseCase.execute(
                name, categoryId, minPrice, maxPrice, status, pageable
        );

        return PagedResponse.from(result);
    }

    @GetMapping("/batch")
    public ResponseEntity<List<ProductResponse>> searchProducts(@NotEmpty @RequestParam List<@NotNull UUID> productIds) {
        return ResponseEntity.ok(searchProductsUseCase.execute(new SearchProductsRequest(productIds)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> findProductById(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .body(ProductResponse.fromProduct(getProductByIdUseCase.execute(id)));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        var product = createProductUseCase.execute(request.toProduct());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductResponse.fromProduct(product));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> updateProduct(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        updateProductUseCase.execute(id, request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

}
