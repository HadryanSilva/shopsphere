ALTER TABLE payment_work
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMPTZ,
    ADD COLUMN attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN last_warning_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_payment_work_claim CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL));

CREATE INDEX idx_payment_work_due ON payment_work(next_attempt_at, order_id)
    WHERE phase NOT IN ('DONE', 'CLOSED');
