package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;


@Getter
@Setter
@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_order_customer", columnList = "customer_id"),
                @Index(name = "idx_order_status", columnList = "status")
        }
)
public class Order extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "source_cart_id", updatable = false)
    private UUID sourceCartId;

    public void assignSourceCart(UUID cartId) {
        Objects.requireNonNull(cartId);
        if (getId() != null && sourceCartId == null)
            throw new IllegalStateException("Persisted order origin cannot be assigned retrospectively");
        if (sourceCartId != null && !sourceCartId.equals(cartId))
            throw new IllegalStateException("Order already belongs to another cart");
        sourceCartId = cartId;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING_STOCK;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO.setScale(2);

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO.setScale(2);

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal shippingAmount = BigDecimal.ZERO.setScale(2);

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Embedded
    private ShippingAddress shippingAddress;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<OrderItem> items = new ArrayList<>();

    @Version
    private long version;

    protected Order() {}

    public static Order create(UUID customerId, UUID idempotencyKey, String currency, ShippingAddress shippingAddress) {
        var order = new Order();
        order.customerId = Objects.requireNonNull(customerId);
        order.idempotencyKey = Objects.requireNonNull(idempotencyKey).toString();
        order.currency = Objects.requireNonNull(currency);
        order.shippingAddress = Objects.requireNonNull(shippingAddress);
        return order;
    }

    public void addItem(
            UUID productId,
            String productName,
            BigDecimal unitPrice,
            int quantity
    ) {
        OrderItem item = new OrderItem(
                this, productId, productName, quantity, monetaryAmount(unitPrice)
        );

        monetaryAmount(item.getLineTotal());
        var newSubtotal = monetaryAmount(subtotal.add(item.getLineTotal()));
        var newTotal = monetaryAmount(newSubtotal.subtract(discountAmount).add(shippingAmount));
        items.add(item);
        subtotal = newSubtotal;
        totalAmount = newTotal;
    }

    private static BigDecimal monetaryAmount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Invalid monetary amount");
        }
        try {
            var amount = value.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.precision() > 19) {
                throw new IllegalArgumentException("Monetary amount exceeds NUMERIC(19,2)");
            }
            return amount;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Monetary amount requires rounding", exception);
        }
    }

    public void confirm() {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "O pedido não aguarda pagamento"
            );
        }
        status = OrderStatus.CONFIRMED;
    }

    public void cancel() {
        if (status == OrderStatus.SHIPPED
                || status == OrderStatus.DELIVERED
                || status == OrderStatus.COMPLETED
                || status == OrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    "O pedido não pode ser cancelado neste estado"
            );
        }
        status = OrderStatus.CANCELLED;
    }
}

