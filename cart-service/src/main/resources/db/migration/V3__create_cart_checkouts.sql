CREATE TABLE cart_checkouts (
    checkout_id UUID CONSTRAINT pk_cart_checkouts PRIMARY KEY,
    customer_id UUID NOT NULL,
    service_client VARCHAR(255) NOT NULL CHECK (length(trim(service_client)) > 0),
    cart_id UUID REFERENCES carts(id),
    status VARCHAR(20) NOT NULL,
    order_id UUID,
    prepared_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    aborted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_cart_checkouts_state CHECK (
        (status = 'PREPARED' AND cart_id IS NOT NULL AND prepared_at IS NOT NULL
            AND order_id IS NULL AND completed_at IS NULL AND aborted_at IS NULL)
        OR (status = 'COMPLETED' AND cart_id IS NOT NULL AND prepared_at IS NOT NULL
            AND order_id IS NOT NULL AND completed_at IS NOT NULL AND aborted_at IS NULL)
        OR (status = 'ABORTED' AND order_id IS NULL AND completed_at IS NULL
            AND aborted_at IS NOT NULL AND ((cart_id IS NULL AND prepared_at IS NULL)
                OR (cart_id IS NOT NULL AND prepared_at IS NOT NULL)))
    )
);

CREATE UNIQUE INDEX uk_cart_checkouts_cart_claim
    ON cart_checkouts(cart_id) WHERE status IN ('PREPARED', 'COMPLETED');

CREATE TABLE cart_checkout_items (
    checkout_id UUID NOT NULL REFERENCES cart_checkouts(checkout_id),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (checkout_id, product_id)
);
