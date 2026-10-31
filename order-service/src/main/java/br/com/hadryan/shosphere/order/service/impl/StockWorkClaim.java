package br.com.hadryan.shosphere.order.service.impl;

import java.util.UUID;

public record StockWorkClaim(UUID token, StockWorkSnapshot work) {}
