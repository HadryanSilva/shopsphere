ALTER TABLE stock_work
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMPTZ,
    ADD COLUMN last_warning_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_stock_work_claim CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL));
