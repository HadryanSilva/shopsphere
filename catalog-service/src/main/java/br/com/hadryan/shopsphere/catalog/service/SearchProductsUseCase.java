package br.com.hadryan.shopsphere.catalog.service;

import br.com.hadryan.shopsphere.catalog.mapper.request.SearchProductsRequest;
import br.com.hadryan.shopsphere.catalog.mapper.response.ProductResponse;

import java.util.List;

public interface SearchProductsUseCase {
    List<ProductResponse> execute(SearchProductsRequest request);
}
