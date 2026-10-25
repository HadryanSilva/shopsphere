package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.GetStockUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetStockUseCaseImpl implements GetStockUseCase {
    private final InventoryStockRepository stocks;

    @Override
    public StockResponse execute(UUID productId) {
        return stocks.findById(productId).map(StockResponse::fromStock)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found"));
    }

    @Override
    public List<StockResponse> executeBatch(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 100
                || productIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(productIds).size() != productIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch requires 1 to 100 distinct product IDs");
        }
        return stocks.findAllById(productIds).stream().map(StockResponse::fromStock).toList();
    }
}
