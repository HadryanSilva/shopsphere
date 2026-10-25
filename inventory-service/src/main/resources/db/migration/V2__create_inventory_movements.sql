CREATE TABLE inventory_movements (
    id UUID PRIMARY KEY,
    movement_type VARCHAR(20) NOT NULL,
    product_id UUID NOT NULL REFERENCES inventory_stock(product_id),
    delta INTEGER NOT NULL,
    reason VARCHAR(500),
    actor_subject VARCHAR(255) NOT NULL,
    idempotency_key UUID NOT NULL,
    on_hand_after INTEGER NOT NULL,
    reserved_after INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inventory_movements_actor_type_key
        UNIQUE (actor_subject, movement_type, idempotency_key),
    CONSTRAINT chk_inventory_movements_type CHECK (movement_type IN ('INITIAL', 'ADJUSTMENT')),
    CONSTRAINT chk_inventory_movements_balances
        CHECK (on_hand_after >= 0 AND reserved_after >= 0 AND reserved_after <= on_hand_after),
    CONSTRAINT chk_inventory_movements_initial CHECK (movement_type <> 'INITIAL' OR
        (delta >= 0 AND on_hand_after = delta AND reserved_after = 0 AND reason IS NULL)),
    CONSTRAINT chk_inventory_movements_adjustment CHECK (movement_type <> 'ADJUSTMENT' OR
        (delta <> 0 AND reason IS NOT NULL AND length(btrim(reason)) > 0))
);
