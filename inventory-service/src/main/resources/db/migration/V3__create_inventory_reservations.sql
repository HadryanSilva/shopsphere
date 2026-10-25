CREATE TABLE inventory_reservations (
    order_id UUID NOT NULL,
    service_client_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_inventory_reservations PRIMARY KEY (order_id),
    CONSTRAINT chk_inventory_reservations_client CHECK (length(btrim(service_client_id)) > 0),
    CONSTRAINT chk_inventory_reservations_status
        CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_inventory_reservations_expiry CHECK (expires_at > created_at)
);

CREATE TABLE inventory_reservation_items (
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT pk_inventory_reservation_items PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_inventory_reservation_items_reservation
        FOREIGN KEY (order_id) REFERENCES inventory_reservations(order_id),
    CONSTRAINT fk_inventory_reservation_items_stock
        FOREIGN KEY (product_id) REFERENCES inventory_stock(product_id),
    CONSTRAINT chk_inventory_reservation_items_quantity CHECK (quantity > 0)
);
