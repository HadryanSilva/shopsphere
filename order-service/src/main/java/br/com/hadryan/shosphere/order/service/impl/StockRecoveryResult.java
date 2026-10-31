package br.com.hadryan.shosphere.order.service.impl;

public record StockRecoveryResult(StockWorkSnapshot work, boolean warnOverdue) {}
