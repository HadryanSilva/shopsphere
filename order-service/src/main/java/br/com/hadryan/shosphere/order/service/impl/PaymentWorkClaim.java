package br.com.hadryan.shosphere.order.service.impl;

import java.util.UUID;

public record PaymentWorkClaim(UUID token, PaymentWorkSnapshot work, long attempts) {}
