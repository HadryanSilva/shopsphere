CREATE TABLE payments (
    order_id UUID CONSTRAINT pk_payments PRIMARY KEY,
    service_client_id VARCHAR(255) NOT NULL CHECK (length(trim(service_client_id)) > 0),
    status VARCHAR(30) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::NUMERIC),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'EUR'),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    captured_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uk_payments_terms UNIQUE (order_id, amount, currency),
    CONSTRAINT chk_payments_state CHECK (
        (status IN ('PROCESSING', 'DECLINED') AND NOT cancel_requested AND captured_at IS NULL AND refunded_at IS NULL)
        OR (status = 'CAPTURED' AND NOT cancel_requested AND captured_at IS NOT NULL AND refunded_at IS NULL)
        OR (status = 'CANCELLED' AND cancel_requested AND captured_at IS NULL AND refunded_at IS NULL)
        OR (status = 'REFUND_PENDING' AND cancel_requested AND captured_at IS NOT NULL AND refunded_at IS NULL)
        OR (status = 'REFUNDED' AND cancel_requested AND captured_at IS NOT NULL AND refunded_at IS NOT NULL)
    ),
    CONSTRAINT chk_payments_dates CHECK (
        updated_at >= created_at
        AND (captured_at IS NULL OR (captured_at >= created_at AND updated_at >= captured_at))
        AND (refunded_at IS NULL OR (refunded_at >= captured_at AND updated_at >= refunded_at))
    )
);

CREATE TABLE payment_movements (
    order_id UUID NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('CAPTURE', 'REFUND')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::NUMERIC),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'EUR'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_payment_movements PRIMARY KEY (order_id, kind),
    CONSTRAINT fk_payment_movements_terms FOREIGN KEY (order_id, amount, currency)
        REFERENCES payments (order_id, amount, currency)
);
