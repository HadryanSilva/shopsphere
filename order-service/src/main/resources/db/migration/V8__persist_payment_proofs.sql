ALTER TABLE payment_work
    ADD COLUMN payment_status VARCHAR(30),
    ADD COLUMN payment_created_at TIMESTAMPTZ,
    ADD COLUMN payment_updated_at TIMESTAMPTZ,
    ADD COLUMN captured_at TIMESTAMPTZ,
    ADD COLUMN refunded_at TIMESTAMPTZ,
    ADD COLUMN stock_safe BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stock_absent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_failure_category VARCHAR(40),
    ADD CONSTRAINT chk_payment_work_category CHECK (last_failure_category IS NULL OR last_failure_category IN (
        'UPSTREAM_UNAVAILABLE','AUTHENTICATION_CONFIGURATION','INVALID_UPSTREAM_RESPONSE','PERSISTENCE_FAILURE','INCONSISTENT_PROOF')),
    ADD CONSTRAINT chk_payment_work_proof CHECK (
        (payment_status IS NULL AND payment_created_at IS NULL AND payment_updated_at IS NULL AND captured_at IS NULL AND refunded_at IS NULL)
        OR (payment_status IS NOT NULL AND payment_created_at IS NOT NULL AND payment_updated_at IS NOT NULL AND payment_updated_at >= payment_created_at
            AND ((payment_status IN ('PROCESSING','DECLINED','CANCELLED') AND captured_at IS NULL AND refunded_at IS NULL)
                OR (payment_status IN ('CAPTURED','REFUND_PENDING') AND captured_at IS NOT NULL AND refunded_at IS NULL)
                OR (payment_status = 'REFUNDED' AND captured_at IS NOT NULL AND refunded_at IS NOT NULL))
            AND (captured_at IS NULL OR captured_at >= payment_created_at AND payment_updated_at >= captured_at)
            AND (refunded_at IS NULL OR refunded_at >= captured_at AND payment_updated_at >= refunded_at))),
    ADD CONSTRAINT chk_payment_work_resolution CHECK (
        (NOT stock_absent OR stock_safe)
        AND (phase <> 'CANCEL_PAYMENT_PENDING' OR stock_safe)
        AND (phase <> 'CLOSED' OR stock_safe AND payment_status IS NOT NULL AND payment_status IN ('DECLINED','CANCELLED','REFUNDED'))
        AND (phase <> 'DONE' OR NOT stock_safe AND payment_status IS NOT NULL AND payment_status = 'CAPTURED'));

ALTER TABLE stock_work DROP CONSTRAINT chk_stock_work_phase,
    DROP CONSTRAINT chk_stock_work_schedule,
    DROP CONSTRAINT chk_stock_work_observation,
    DROP CONSTRAINT chk_stock_work_payment_handoff,
    ADD CONSTRAINT chk_stock_work_phase CHECK (phase IN (
        'WAITING_CART','RESERVE_PENDING','RESERVED','ABORT_PENDING','CLOSED','REVIEW_REQUIRED','PAYMENT_PENDING','SETTLED')),
    ADD CONSTRAINT chk_stock_work_schedule CHECK (
        (phase IN ('CLOSED','PAYMENT_PENDING','SETTLED') AND next_attempt_at IS NULL)
        OR (phase NOT IN ('CLOSED','PAYMENT_PENDING','SETTLED') AND next_attempt_at IS NOT NULL)),
    ADD CONSTRAINT chk_stock_work_observation CHECK (
        (reservation_status IS NULL AND reservation_created_at IS NULL AND reservation_expires_at IS NULL AND reservation_updated_at IS NULL)
        OR (reservation_status IS NOT NULL AND reservation_status IN ('ACTIVE','CONFIRMED','RELEASED','EXPIRED')
            AND reservation_created_at IS NOT NULL AND reservation_expires_at IS NOT NULL AND reservation_updated_at IS NOT NULL
            AND reservation_created_at < reservation_expires_at AND reservation_updated_at >= reservation_created_at)),
    ADD CONSTRAINT chk_stock_work_payment_handoff CHECK (phase NOT IN ('PAYMENT_PENDING','SETTLED') OR (
        NOT abort_requested AND reason IS NULL AND claim_token IS NULL AND claimed_until IS NULL)),
    ADD CONSTRAINT chk_stock_work_settled CHECK (phase <> 'SETTLED' OR reservation_status IS NOT NULL AND reservation_status = 'CONFIRMED');
