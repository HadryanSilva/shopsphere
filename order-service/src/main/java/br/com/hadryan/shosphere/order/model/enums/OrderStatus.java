package br.com.hadryan.shosphere.order.model.enums;

public enum OrderStatus {
    // Compatibilidade com pedidos persistidos pela V1; novos pedidos usam PENDING_STOCK.
    PENDING,
    COMPLETED,
    PENDING_STOCK,
    PENDING_PAYMENT,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    PAYMENT_FAILED
}
