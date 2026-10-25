CREATE INDEX idx_inventory_reservations_expiration
    ON inventory_reservations (status, expires_at, order_id);
