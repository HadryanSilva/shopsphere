ALTER TABLE orders ADD COLUMN payment_status VARCHAR(30),
    ADD CONSTRAINT chk_order_payment_status CHECK (payment_status IS NULL OR payment_status IN (
        'PROCESSING', 'CAPTURED', 'DECLINED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED', 'REVIEW_REQUIRED'));

CREATE TABLE payment_work (
    order_id UUID CONSTRAINT pk_payment_work PRIMARY KEY REFERENCES orders(id),
    phase VARCHAR(30) NOT NULL CHECK (phase IN (
        'CAPTURE_PENDING', 'CONFIRM_PENDING', 'RESOLUTION_PENDING', 'CANCEL_PAYMENT_PENDING', 'DONE', 'CLOSED', 'REVIEW_REQUIRED')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::NUMERIC),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'EUR'),
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_payment_work_schedule CHECK (
        (phase IN ('DONE', 'CLOSED') AND next_attempt_at IS NULL)
        OR (phase NOT IN ('DONE', 'CLOSED') AND next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_payment_work_dates CHECK (updated_at >= created_at)
);

ALTER TABLE stock_work DROP CONSTRAINT chk_stock_work_phase,
    DROP CONSTRAINT chk_stock_work_schedule,
    ADD CONSTRAINT chk_stock_work_phase CHECK (phase IN (
        'WAITING_CART', 'RESERVE_PENDING', 'RESERVED', 'ABORT_PENDING', 'CLOSED', 'REVIEW_REQUIRED', 'PAYMENT_PENDING')),
    ADD CONSTRAINT chk_stock_work_schedule CHECK (
        (phase IN ('CLOSED', 'PAYMENT_PENDING') AND next_attempt_at IS NULL)
        OR (phase NOT IN ('CLOSED', 'PAYMENT_PENDING') AND next_attempt_at IS NOT NULL)),
    ADD CONSTRAINT chk_stock_work_payment_handoff CHECK (phase <> 'PAYMENT_PENDING' OR (
        NOT abort_requested AND reason IS NULL AND reservation_status IS NOT NULL AND reservation_status = 'ACTIVE'
        AND claim_token IS NULL AND claimed_until IS NULL));
