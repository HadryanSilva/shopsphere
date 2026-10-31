-- Only newly committed snapshot checkouts create work. Existing orders are not enrolled.
CREATE TABLE stock_work (
    order_id UUID CONSTRAINT pk_stock_work PRIMARY KEY REFERENCES orders(id),
    checkout_id UUID NOT NULL UNIQUE REFERENCES checkout_intents(checkout_id),
    phase VARCHAR(30) NOT NULL,
    abort_requested BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(40),
    reservation_status VARCHAR(30),
    reservation_created_at TIMESTAMPTZ,
    reservation_expires_at TIMESTAMPTZ,
    reservation_updated_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_failure_category VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_stock_work_phase CHECK (phase IN (
        'WAITING_CART', 'RESERVE_PENDING', 'RESERVED', 'ABORT_PENDING', 'CLOSED', 'REVIEW_REQUIRED')),
    CONSTRAINT chk_stock_work_reason CHECK (reason IS NULL OR reason IN (
        'RESERVATION_REJECTED', 'RESERVATION_EXPIRED', 'RESERVATION_RELEASED')),
    CONSTRAINT chk_stock_work_category CHECK (last_failure_category IS NULL OR last_failure_category IN (
        'UPSTREAM_UNAVAILABLE', 'AUTHENTICATION_CONFIGURATION', 'INVALID_UPSTREAM_RESPONSE',
        'UNEXPECTED_CONFIRMED', 'PERSISTENCE_FAILURE')),
    CONSTRAINT chk_stock_work_decision CHECK (
        (NOT abort_requested OR reason IS NOT NULL)
        AND (phase <> 'ABORT_PENDING' OR abort_requested)
        AND (phase NOT IN ('WAITING_CART', 'RESERVE_PENDING', 'RESERVED') OR NOT abort_requested)),
    CONSTRAINT chk_stock_work_observation CHECK (
        (reservation_status IS NULL AND reservation_created_at IS NULL
            AND reservation_expires_at IS NULL AND reservation_updated_at IS NULL)
        OR (reservation_status IS NOT NULL AND reservation_status IN ('ACTIVE', 'RELEASED', 'EXPIRED')
            AND reservation_created_at IS NOT NULL AND reservation_expires_at IS NOT NULL
            AND reservation_updated_at IS NOT NULL AND reservation_created_at < reservation_expires_at
            AND reservation_updated_at >= reservation_created_at)),
    CONSTRAINT chk_stock_work_reserved CHECK (phase <> 'RESERVED'
        OR (reservation_status IS NOT NULL AND reservation_status = 'ACTIVE')),
    CONSTRAINT chk_stock_work_closed CHECK (phase <> 'CLOSED' OR (reason IS NOT NULL
        AND ((reservation_status IS NOT NULL AND reservation_status IN ('RELEASED', 'EXPIRED'))
            OR (abort_requested AND reservation_status IS NULL)))),
    CONSTRAINT chk_stock_work_schedule CHECK (
        (phase = 'CLOSED' AND next_attempt_at IS NULL)
        OR (phase <> 'CLOSED' AND next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_stock_work_dates CHECK (updated_at >= created_at)
);

CREATE INDEX idx_stock_work_due ON stock_work(next_attempt_at, order_id) WHERE phase <> 'CLOSED';
