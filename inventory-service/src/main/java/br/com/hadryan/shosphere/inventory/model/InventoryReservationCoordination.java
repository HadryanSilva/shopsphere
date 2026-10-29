package br.com.hadryan.shosphere.inventory.model;

import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Getter
@Entity
@Table(name = "inventory_reservation_coordination")
public class InventoryReservationCoordination {
    @Id @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Column(name = "service_client_id", nullable = false, updatable = false, length = 255)
    private String serviceClientId;
    @Column(name = "aborted_at")
    private Instant abortedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Version @Column(nullable = false)
    private Long version;
    @ElementCollection
    @CollectionTable(name = "inventory_reservation_coordination_items", joinColumns = @JoinColumn(name = "order_id"))
    private List<InventoryReservationItem> items = new ArrayList<>();

    protected InventoryReservationCoordination() { }

    public InventoryReservationCoordination(UUID orderId, String serviceClientId, List<InventoryReservationItem> items) {
        this.orderId = Objects.requireNonNull(orderId);
        if (serviceClientId == null || serviceClientId.isBlank() || serviceClientId.length() > 255)
            throw new IllegalArgumentException("Invalid reservation service client");
        this.serviceClientId = serviceClientId;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        initializeItems(items);
    }

    public List<InventoryReservationItem> getItems() { return List.copyOf(items); }

    public void initializeItems(List<InventoryReservationItem> snapshot) {
        if (!items.isEmpty() || abortedAt != null) throw new IllegalStateException("Reservation intent is already established");
        quantities(snapshot);
        snapshot.forEach(item -> items.add(new InventoryReservationItem(item.getProductId(), item.getQuantity())));
    }

    public boolean sameItems(List<InventoryReservationItem> snapshot) { return quantities(items).equals(quantities(snapshot)); }

    public boolean abort(Instant now) {
        Objects.requireNonNull(now);
        if (abortedAt != null) return false;
        if (now.isBefore(createdAt)) throw new IllegalArgumentException("Abort precedes reservation coordination");
        abortedAt = now.truncatedTo(ChronoUnit.MICROS);
        return true;
    }

    private static Map<UUID, Integer> quantities(List<InventoryReservationItem> snapshot) {
        if (snapshot == null || snapshot.isEmpty() || snapshot.size() > 100)
            throw new IllegalArgumentException("Reservation requires 1 to 100 distinct products");
        var quantities = new HashMap<UUID, Integer>();
        for (var item : snapshot)
            if (item == null || item.getProductId() == null || item.getQuantity() <= 0
                    || quantities.putIfAbsent(item.getProductId(), item.getQuantity()) != null)
                throw new IllegalArgumentException("Invalid or duplicate reservation item");
        return quantities;
    }
}
