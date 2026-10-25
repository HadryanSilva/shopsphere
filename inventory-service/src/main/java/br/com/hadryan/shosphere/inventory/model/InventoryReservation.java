package br.com.hadryan.shosphere.inventory.model;

import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {
    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "service_client_id", nullable = false, updatable = false, length = 255)
    private String serviceClientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryReservationStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "inventory_reservation_items", joinColumns = @JoinColumn(name = "order_id"))
    private List<InventoryReservationItem> items = new ArrayList<>();

    protected InventoryReservation() {}

    public InventoryReservation(UUID orderId, String serviceClientId, Instant expiresAt,
                                List<InventoryReservationItem> items) {
        this.orderId = Objects.requireNonNull(orderId);
        if (serviceClientId == null || serviceClientId.isBlank() || serviceClientId.length() > 255) {
            throw new IllegalArgumentException("Invalid reservation service client");
        }
        this.serviceClientId = serviceClientId;
        this.expiresAt = Objects.requireNonNull(expiresAt).truncatedTo(ChronoUnit.MICROS);
        this.items = validatedItems(items);
        status = InventoryReservationStatus.ACTIVE;
    }

    public void initializeItems(List<InventoryReservationItem> items) {
        if (!this.items.isEmpty() || status != InventoryReservationStatus.ACTIVE) {
            throw new IllegalStateException("Reservation intent is already established");
        }
        this.items.addAll(validatedItems(items));
    }

    private static List<InventoryReservationItem> validatedItems(List<InventoryReservationItem> items) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("Reservation requires 1 to 100 distinct products");
        }
        var products = new HashSet<UUID>();
        for (var item : items) {
            if (item == null || !products.add(item.getProductId())) {
                throw new IllegalArgumentException("Reservation products must be non-null and distinct");
            }
        }
        return new ArrayList<>(items);
    }

    public List<InventoryReservationItem> getItems() {
        return List.copyOf(items);
    }

    public boolean confirm(Instant now) {
        Objects.requireNonNull(now);
        if (status == InventoryReservationStatus.CONFIRMED) return false;
        if (status != InventoryReservationStatus.ACTIVE || !now.isBefore(expiresAt)) {
            throw new ReservationStateConflictException();
        }
        status = InventoryReservationStatus.CONFIRMED;
        return true;
    }

    public boolean release(Instant now) {
        Objects.requireNonNull(now);
        if (status == InventoryReservationStatus.RELEASED || status == InventoryReservationStatus.EXPIRED) return false;
        if (status != InventoryReservationStatus.ACTIVE) throw new ReservationStateConflictException();
        status = now.isBefore(expiresAt) ? InventoryReservationStatus.RELEASED : InventoryReservationStatus.EXPIRED;
        return true;
    }

    public boolean expire(Instant now) {
        Objects.requireNonNull(now);
        if (status != InventoryReservationStatus.ACTIVE || now.isBefore(expiresAt)) return false;
        status = InventoryReservationStatus.EXPIRED;
        return true;
    }

    @PrePersist
    protected void onCreate() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Reservation expiry must be in the future");
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
