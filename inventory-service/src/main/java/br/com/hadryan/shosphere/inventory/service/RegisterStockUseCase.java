package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;

import java.util.UUID;

public interface RegisterStockUseCase {
    StockResponse execute(String actorSubject, UUID idempotencyKey, RegisterStockRequest request);
}
