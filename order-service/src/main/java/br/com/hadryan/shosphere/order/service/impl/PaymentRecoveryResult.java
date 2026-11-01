package br.com.hadryan.shosphere.order.service.impl;

public record PaymentRecoveryResult(PaymentWorkSnapshot work, long attempts, boolean warnOverdue) {}
