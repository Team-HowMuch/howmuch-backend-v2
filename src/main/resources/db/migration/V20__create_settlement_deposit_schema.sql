ALTER TABLE settlement_shares
    ADD CONSTRAINT uk_settlement_shares_settlement_id_id
        UNIQUE (settlement_id, id);

ALTER TABLE settlement_receiving_account_snapshots
    ADD CONSTRAINT uk_settlement_receiving_snapshots_settlement_account
        UNIQUE (settlement_id, source_account_id);

ALTER TABLE account_transactions
    ADD CONSTRAINT uk_account_transactions_id_account
        UNIQUE (id, account_id);

CREATE TABLE settlement_deposits (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    share_id UUID NOT NULL,
    source_account_id BIGINT NOT NULL,
    account_transaction_id BIGINT NOT NULL,
    matched_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_deposits PRIMARY KEY (id),
    CONSTRAINT fk_settlement_deposits_share
        FOREIGN KEY (settlement_id, share_id)
            REFERENCES settlement_shares (settlement_id, id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_deposits_receiving_account
        FOREIGN KEY (settlement_id, source_account_id)
            REFERENCES settlement_receiving_account_snapshots (
                settlement_id,
                source_account_id
            )
            ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_deposits_account_transaction
        FOREIGN KEY (account_transaction_id, source_account_id)
            REFERENCES account_transactions (id, account_id)
            ON DELETE RESTRICT,
    CONSTRAINT uk_settlement_deposits_account_transaction
        UNIQUE (account_transaction_id)
);

CREATE INDEX idx_settlement_deposits_settlement_share
    ON settlement_deposits (settlement_id, share_id);

CREATE INDEX idx_settlement_deposits_settlement_matched_at
    ON settlement_deposits (settlement_id, matched_at);
