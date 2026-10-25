-- Chaves nulas continuam permitidas para pedidos legados.
-- Duplicatas existentes precisam de revisão antes da aplicação desta migration.
CREATE UNIQUE INDEX uk_orders_customer_idempotency
    ON orders (customer_id, idempotency_key);
