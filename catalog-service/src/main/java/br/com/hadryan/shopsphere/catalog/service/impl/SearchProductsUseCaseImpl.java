package br.com.hadryan.shopsphere.catalog.service.impl;

import br.com.hadryan.shopsphere.catalog.mapper.request.SearchProductsRequest;
import br.com.hadryan.shopsphere.catalog.mapper.response.ProductResponse;
import br.com.hadryan.shopsphere.catalog.repository.ProductRepository;
import br.com.hadryan.shopsphere.catalog.service.SearchProductsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@RequiredArgsConstructor
@Service
public class SearchProductsUseCaseImpl implements SearchProductsUseCase {

    private final ProductRepository productRepository;

    @Override
    public List<ProductResponse> execute(SearchProductsRequest request) {
        return productRepository.findAllById(request.productIds())
                .stream()
                .map(ProductResponse::fromProduct)
                .toList();
    }

}
