
-- V1__create_initial_schema.sql

CREATE TABLE orders (
                        id UUID PRIMARY KEY,
                        customer_id UUID NOT NULL,

                        status VARCHAR(30) NOT NULL DEFAULT 'PENDING',

                        total_amount NUMERIC(19, 2) NOT NULL,
                        currency VARCHAR(3) NOT NULL DEFAULT 'EUR',

                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                        version BIGINT NOT NULL DEFAULT 0,

                        CONSTRAINT chk_orders_status
                            CHECK (status IN (
                                              'PENDING',
                                              'CONFIRMED',
                                              'CANCELLED',
                                              'COMPLETED'
                                )),

                        CONSTRAINT chk_orders_total_amount
                            CHECK (total_amount >= 0),

                        CONSTRAINT chk_orders_currency
                            CHECK (currency ~ '^[A-Z]{3}$')
    );

CREATE TABLE order_items (
                             id UUID PRIMARY KEY,
                             order_id UUID NOT NULL,
                             product_id UUID NOT NULL,

                             product_name VARCHAR(150) NOT NULL,

                             quantity INTEGER NOT NULL,
                             unit_price NUMERIC(19, 2) NOT NULL,
                             total_price NUMERIC(19, 2) NOT NULL,

                             created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                             CONSTRAINT fk_order_items_order
                                 FOREIGN KEY (order_id)
                                     REFERENCES orders (id),

                             CONSTRAINT chk_order_items_quantity
                                 CHECK (quantity > 0),

                             CONSTRAINT chk_order_items_unit_price
                                 CHECK (unit_price >= 0),

                             CONSTRAINT chk_order_items_total_price
                                 CHECK (total_price >= 0),

                             CONSTRAINT uk_order_items_order_product
                                 UNIQUE (order_id, product_id)
);

CREATE INDEX idx_orders_customer_id
    ON orders (customer_id);

CREATE INDEX idx_orders_status
    ON orders (status);

CREATE INDEX idx_orders_customer_created_at
    ON orders (customer_id, created_at DESC);

CREATE INDEX idx_order_items_order_id
    ON order_items (order_id);

CREATE INDEX idx_order_items_product_id
    ON order_items (product_id);
