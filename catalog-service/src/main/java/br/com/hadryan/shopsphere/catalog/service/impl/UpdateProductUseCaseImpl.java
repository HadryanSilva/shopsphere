package br.com.hadryan.shopsphere.catalog.service.impl;

import br.com.hadryan.shopsphere.catalog.mapper.request.UpdateProductRequest;
import br.com.hadryan.shopsphere.catalog.repository.ProductRepository;
import br.com.hadryan.shopsphere.catalog.service.UpdateProductUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class UpdateProductUseCaseImpl implements UpdateProductUseCase {

    private final ProductRepository productRepository;

    @Transactional
    @Override
    public void execute(UUID id, UpdateProductRequest request) {
        var productToUpdate = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (request.name() != null) {
            productToUpdate.setName(request.name());
        }

        if (request.description() != null) {
            productToUpdate.setDescription(request.description());
        }

        if (request.imageUrl() != null) {
            productToUpdate.setImageUrl(request.imageUrl());
        }

        if (request.price() != null) {
            productToUpdate.setPrice(request.price());
        }

        if (request.status() != null) {
            productToUpdate.setStatus(request.status());
        }

        productRepository.save(productToUpdate);
    }
}
