package br.com.hadryan.shopsphere.catalog.service.impl;

import br.com.hadryan.shopsphere.catalog.model.Product;
import br.com.hadryan.shopsphere.catalog.repository.ProductRepository;
import br.com.hadryan.shopsphere.catalog.service.GetProductByIdUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class GetProductByIdUseCaseImpl implements GetProductByIdUseCase {

    private final ProductRepository productRepository;

    @Override
    public Product execute(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }
}
