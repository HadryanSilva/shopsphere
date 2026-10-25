CREATE TABLE inventory_stock (
    product_id UUID NOT NULL,
    on_hand INTEGER NOT NULL DEFAULT 0,
    reserved INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_inventory_stock PRIMARY KEY (product_id),
    CONSTRAINT chk_inventory_stock_on_hand CHECK (on_hand >= 0),
    CONSTRAINT chk_inventory_stock_reserved CHECK (reserved >= 0),
    CONSTRAINT chk_inventory_stock_reserved_on_hand CHECK (reserved <= on_hand)
);
