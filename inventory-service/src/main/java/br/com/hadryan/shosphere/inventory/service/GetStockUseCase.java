package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;

import java.util.List;
import java.util.UUID;

public interface GetStockUseCase {
    StockResponse execute(UUID productId);
    List<StockResponse> executeBatch(List<UUID> productIds);
}
