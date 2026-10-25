package br.com.hadryan.shosphere.order.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;


@Getter
@Setter
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 150)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false,
            precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false,
            precision = 19, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {}

    public OrderItem(
            Order order,
            UUID productId,
            String productName,
            int quantity,
            BigDecimal unitPrice) {

        if (quantity <= 0 || unitPrice == null
                || unitPrice.signum() < 0) {
            throw new IllegalArgumentException(
                    "Quantidade ou preço inválido"
            );
        }

        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = unitPrice.multiply(
                BigDecimal.valueOf(quantity)
        );
    }
}

