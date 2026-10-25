package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;

import java.util.UUID;

public interface AdjustStockUseCase {
    AdjustmentResponse execute(String actor, UUID key, UUID productId, AdjustStockRequest request);
}
