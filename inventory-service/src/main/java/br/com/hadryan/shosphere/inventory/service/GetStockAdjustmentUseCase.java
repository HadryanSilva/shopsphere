package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;

import java.util.UUID;

public interface GetStockAdjustmentUseCase {
    AdjustmentResponse execute(UUID productId, UUID id);
}
