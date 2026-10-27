ALTER TABLE orders ADD COLUMN source_cart_id UUID;
CREATE UNIQUE INDEX uk_orders_source_cart ON orders(source_cart_id) WHERE source_cart_id IS NOT NULL;

CREATE TABLE checkout_intents (
    checkout_id UUID CONSTRAINT pk_checkout_intents PRIMARY KEY,
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    shipping_recipient VARCHAR(150) NOT NULL,
    shipping_address_line VARCHAR(200) NOT NULL,
    shipping_address_line_2 VARCHAR(200),
    shipping_city VARCHAR(100) NOT NULL,
    shipping_postal_code VARCHAR(20) NOT NULL,
    shipping_country_code VARCHAR(2) NOT NULL,
    cart_id UUID,
    order_id UUID UNIQUE REFERENCES orders(id),
    failure_reason VARCHAR(40),
    next_attempt_at TIMESTAMPTZ,
    attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_failure_category VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_checkout_intents_customer_key UNIQUE(customer_id, idempotency_key),
    CONSTRAINT chk_checkout_intents_state CHECK (
        (status = 'PREPARING' AND cart_id IS NULL AND order_id IS NULL AND failure_reason IS NULL)
        OR (status = 'PREPARED' AND cart_id IS NOT NULL AND order_id IS NULL AND failure_reason IS NULL)
        OR (status IN ('COMPLETION_PENDING', 'COMPLETED') AND cart_id IS NOT NULL
            AND order_id IS NOT NULL AND failure_reason IS NULL)
        OR (status IN ('ABORT_PENDING', 'ABORTED') AND order_id IS NULL AND failure_reason IS NOT NULL)
    ),
    CONSTRAINT chk_checkout_intents_failure CHECK (failure_reason IS NULL OR failure_reason IN (
        'CART_NOT_FOUND', 'CART_CONFLICT', 'PRODUCT_CONFLICT', 'MONETARY_CONFLICT', 'ORDER_CONFLICT')),
    CONSTRAINT chk_checkout_intents_retry_category CHECK (last_failure_category IS NULL OR last_failure_category IN (
        'UPSTREAM_UNAVAILABLE', 'UPSTREAM_REJECTED', 'INVALID_UPSTREAM_RESPONSE', 'PERSISTENCE_FAILURE')),
    CONSTRAINT chk_checkout_intents_schedule CHECK (
        (status IN ('COMPLETED', 'ABORTED') AND next_attempt_at IS NULL)
        OR (status IN ('PREPARING', 'PREPARED', 'COMPLETION_PENDING', 'ABORT_PENDING') AND next_attempt_at IS NOT NULL)
    )
);

CREATE INDEX idx_checkout_intents_due ON checkout_intents(next_attempt_at, checkout_id)
    WHERE status IN ('PREPARING', 'PREPARED', 'COMPLETION_PENDING', 'ABORT_PENDING');

CREATE TABLE checkout_intent_items (
    checkout_id UUID NOT NULL REFERENCES checkout_intents(checkout_id),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY(checkout_id, product_id)
);
