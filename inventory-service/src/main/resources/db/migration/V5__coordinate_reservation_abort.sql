CREATE TABLE inventory_reservation_coordination (
    order_id UUID CONSTRAINT pk_inventory_reservation_coordination PRIMARY KEY,
    service_client_id VARCHAR(255) NOT NULL,
    aborted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_reservation_coordination_client CHECK (length(btrim(service_client_id)) > 0),
    CONSTRAINT chk_reservation_coordination_abort CHECK (aborted_at IS NULL OR aborted_at >= created_at),
    CONSTRAINT chk_reservation_coordination_version CHECK (version >= 0)
);

CREATE TABLE inventory_reservation_coordination_items (
    order_id UUID NOT NULL REFERENCES inventory_reservation_coordination(order_id),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    CONSTRAINT pk_inventory_reservation_coordination_items PRIMARY KEY (order_id, product_id)
);
