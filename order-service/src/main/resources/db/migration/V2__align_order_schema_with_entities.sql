-- Preserva a V1 e os dados anteriores à introdução dos snapshots completos.
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN subtotal NUMERIC(19, 2),
    ADD COLUMN discount_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN shipping_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN shipping_recipient VARCHAR(150),
    ADD COLUMN shipping_address_line VARCHAR(200),
    ADD COLUMN shipping_address_line_2 VARCHAR(200),
    ADD COLUMN shipping_city VARCHAR(100),
    ADD COLUMN shipping_postal_code VARCHAR(20),
    ADD COLUMN shipping_country_code VARCHAR(2);

-- A V1 só registrava o total: preserva-o sem inventar descontos ou frete.
UPDATE orders SET subtotal = total_amount;
ALTER TABLE orders ALTER COLUMN subtotal SET NOT NULL;

ALTER TABLE orders DROP CONSTRAINT chk_orders_status;
ALTER TABLE orders ALTER COLUMN status SET DEFAULT 'PENDING_STOCK';
ALTER TABLE orders ADD CONSTRAINT chk_orders_status CHECK (status IN (
    'PENDING', 'COMPLETED',
    'PENDING_STOCK', 'PENDING_PAYMENT', 'CONFIRMED', 'PROCESSING',
    'SHIPPED', 'DELIVERED', 'CANCELLED', 'PAYMENT_FAILED'
));

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_subtotal CHECK (subtotal >= 0),
    ADD CONSTRAINT chk_orders_discount_amount CHECK (discount_amount >= 0),
    ADD CONSTRAINT chk_orders_shipping_amount CHECK (shipping_amount >= 0);

ALTER TABLE order_items RENAME COLUMN total_price TO line_total;
ALTER TABLE order_items RENAME CONSTRAINT chk_order_items_total_price TO chk_order_items_line_total;
