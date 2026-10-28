package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.CheckoutFailureReason;
import br.com.hadryan.shosphere.order.model.enums.CheckoutIntentStatus;
import br.com.hadryan.shosphere.order.model.enums.CheckoutRetryCategory;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Getter
@Entity
@Table(name = "checkout_intents")
public class CheckoutIntent {
    @Id @Column(updatable = false)
    private UUID checkoutId;
    @Column(nullable = false, updatable = false)
    private UUID customerId;
    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private CheckoutIntentStatus status;
    @Embedded
    private ShippingAddress shippingAddress;
    private UUID cartId;
    private UUID orderId;
    @Enumerated(EnumType.STRING) @Column(length = 40)
    private CheckoutFailureReason failureReason;
    private Instant nextAttemptAt;
    @Column(nullable = false)
    private long attemptCount;
    @Enumerated(EnumType.STRING) @Column(length = 40)
    private CheckoutRetryCategory lastFailureCategory;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;
    @ElementCollection
    @CollectionTable(name = "checkout_intent_items", joinColumns = @JoinColumn(name = "checkout_id"))
    @OrderBy("productId ASC")
    private List<CheckoutIntentItem> items = new ArrayList<>();

    protected CheckoutIntent() {}

    public static CheckoutIntent start(UUID customerId, UUID key, ShippingAddress address) {
        Objects.requireNonNull(address);
        requireText(address.getRecipient(), 150);
        requireText(address.getAddressLine(), 200);
        requireText(address.getCity(), 100);
        requireText(address.getPostalCode(), 20);
        if (address.getAddressLine2() != null && address.getAddressLine2().length() > 200
                || address.getCountryCode() == null || !address.getCountryCode().matches("[A-Z]{2}"))
            throw new IllegalArgumentException("Invalid shipping address");
        var intent = new CheckoutIntent();
        intent.checkoutId = UUID.randomUUID();
        intent.customerId = Objects.requireNonNull(customerId);
        intent.idempotencyKey = Objects.requireNonNull(key).toString();
        intent.shippingAddress = new ShippingAddress(address.getRecipient(), address.getAddressLine(),
                address.getAddressLine2(), address.getCity(), address.getPostalCode(), address.getCountryCode());
        intent.status = CheckoutIntentStatus.PREPARING;
        intent.createdAt = now();
        intent.updatedAt = intent.createdAt;
        intent.nextAttemptAt = intent.createdAt;
        return intent;
    }

    public List<CheckoutIntentItem> getItems() { return Collections.unmodifiableList(items); }

    public boolean sameAddress(ShippingAddress address) {
        return address != null && Objects.equals(shippingAddress.getRecipient(), address.getRecipient())
                && Objects.equals(shippingAddress.getAddressLine(), address.getAddressLine())
                && Objects.equals(shippingAddress.getAddressLine2(), address.getAddressLine2())
                && Objects.equals(shippingAddress.getCity(), address.getCity())
                && Objects.equals(shippingAddress.getPostalCode(), address.getPostalCode())
                && Objects.equals(shippingAddress.getCountryCode(), address.getCountryCode());
    }

    public boolean sameSnapshot(UUID cartId, List<CheckoutIntentItem> snapshot) {
        return Objects.equals(this.cartId, cartId) && quantities(items).equals(quantities(snapshot));
    }

    public void prepare(UUID cartId, List<CheckoutIntentItem> snapshot) {
        requireState(CheckoutIntentStatus.PREPARING);
        Objects.requireNonNull(cartId);
        if (snapshot == null || snapshot.isEmpty()) throw new IllegalArgumentException("Empty checkout snapshot");
        quantities(snapshot);
        this.cartId = cartId;
        for (var item : snapshot) items.add(new CheckoutIntentItem(item.getProductId(), item.getQuantity()));
        status = CheckoutIntentStatus.PREPARED;
        progress();
    }

    public void orderPersisted(UUID orderId) {
        requireState(CheckoutIntentStatus.PREPARED);
        this.orderId = Objects.requireNonNull(orderId);
        status = CheckoutIntentStatus.COMPLETION_PENDING;
        progress();
    }

    public void requestAbort(CheckoutFailureReason reason) {
        Objects.requireNonNull(reason);
        if (status != CheckoutIntentStatus.PREPARING && status != CheckoutIntentStatus.PREPARED)
            throw new IllegalStateException("Checkout cannot be aborted");
        failureReason = reason;
        status = CheckoutIntentStatus.ABORT_PENDING;
        progress();
    }

    public void completed() {
        requireState(CheckoutIntentStatus.COMPLETION_PENDING);
        status = CheckoutIntentStatus.COMPLETED;
        updatedAt = now();
        nextAttemptAt = null;
    }

    public void aborted() {
        requireState(CheckoutIntentStatus.ABORT_PENDING);
        status = CheckoutIntentStatus.ABORTED;
        updatedAt = now();
        nextAttemptAt = null;
    }

    public void recordFailure(CheckoutRetryCategory category, Instant nextAttemptAt) {
        Objects.requireNonNull(category);
        Objects.requireNonNull(nextAttemptAt);
        if (status == CheckoutIntentStatus.COMPLETED || status == CheckoutIntentStatus.ABORTED)
            throw new IllegalStateException("Checkout is terminal");
        var attempts = Math.addExact(attemptCount, 1L);
        this.nextAttemptAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
        attemptCount = attempts;
        lastFailureCategory = category;
        updatedAt = now();
    }

    public void scheduleAttempt(Instant nextAttemptAt) {
        if (status == CheckoutIntentStatus.COMPLETED || status == CheckoutIntentStatus.ABORTED)
            throw new IllegalStateException("Checkout is terminal");
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt).truncatedTo(ChronoUnit.MICROS);
    }

    public static Map<UUID, Integer> quantities(List<CheckoutIntentItem> snapshot) {
        Objects.requireNonNull(snapshot);
        Map<UUID, Integer> quantities = new HashMap<>();
        for (var item : snapshot) {
            if (item == null || item.getProductId() == null || item.getQuantity() <= 0
                    || quantities.putIfAbsent(item.getProductId(), item.getQuantity()) != null)
                throw new IllegalArgumentException("Invalid checkout snapshot");
        }
        return quantities;
    }

    private void progress() {
        updatedAt = now();
        nextAttemptAt = updatedAt;
        lastFailureCategory = null;
    }

    private void requireState(CheckoutIntentStatus expected) {
        if (status != expected) throw new IllegalStateException("Invalid checkout transition");
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("Invalid shipping address");
    }
}
