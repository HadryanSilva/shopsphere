
-- V1__create_initial_schema.sql

CREATE TABLE carts (
                       id UUID PRIMARY KEY,
                       customer_id UUID NOT NULL,
                       status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                       version BIGINT NOT NULL DEFAULT 0,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                       CONSTRAINT chk_carts_status
                           CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'ABANDONED'))
);

CREATE TABLE cart_items (
                            id UUID PRIMARY KEY,
                            cart_id UUID NOT NULL,
                            product_id UUID NOT NULL,
                            quantity INTEGER NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT fk_cart_items_cart
                                FOREIGN KEY (cart_id)
                                    REFERENCES carts (id),

                            CONSTRAINT chk_cart_items_quantity
                                CHECK (quantity > 0)
);

CREATE INDEX idx_cart_customer
    ON carts (customer_id);

CREATE INDEX idx_cart_items_cart
    ON cart_items (cart_id);

CREATE INDEX idx_cart_items_product
    ON cart_items (product_id);
