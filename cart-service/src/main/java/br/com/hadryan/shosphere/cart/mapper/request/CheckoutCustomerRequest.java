package br.com.hadryan.shosphere.cart.mapper.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CheckoutCustomerRequest(@NotNull UUID customerId) {}
