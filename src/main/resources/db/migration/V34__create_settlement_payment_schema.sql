CREATE TABLE settlement_payments (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    share_id UUID NOT NULL,
    payer_account_id BIGINT NOT NULL,
    account_transaction_id BIGINT NOT NULL,
    matched_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_payments PRIMARY KEY (id),
    CONSTRAINT fk_settlement_payments_share
        FOREIGN KEY (settlement_id, share_id)
            REFERENCES settlement_shares (settlement_id, id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_payments_payer_account
        FOREIGN KEY (payer_account_id)
            REFERENCES account_records (id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_payments_account_transaction
        FOREIGN KEY (account_transaction_id, payer_account_id)
            REFERENCES account_transactions (id, account_id)
            ON DELETE RESTRICT,
    CONSTRAINT uk_settlement_payments_account_transaction
        UNIQUE (account_transaction_id)
);

CREATE INDEX idx_settlement_payments_payer_account
    ON settlement_payments (payer_account_id);

CREATE INDEX idx_settlement_payments_settlement_share
    ON settlement_payments (settlement_id, share_id);
