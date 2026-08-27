ALTER TABLE settlements
    ADD CONSTRAINT uk_settlements_id_receiving_account
        UNIQUE (id, receiving_account_id);

CREATE TABLE settlement_receiving_account_snapshots (
    settlement_id UUID NOT NULL,
    source_account_id BIGINT NOT NULL,
    organization VARCHAR(10) NOT NULL,
    account_number_ciphertext VARCHAR(1024) NOT NULL,
    account_holder VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_receiving_account_snapshots
        PRIMARY KEY (settlement_id),
    CONSTRAINT fk_settlement_receiving_account_snapshots_selected_account
        FOREIGN KEY (settlement_id, source_account_id)
            REFERENCES settlements (id, receiving_account_id)
            ON DELETE CASCADE,
    CONSTRAINT ck_settlement_receiving_account_snapshots_organization
        CHECK (organization ~ '^[0-9]{4}$'),
    CONSTRAINT ck_settlement_receiving_account_snapshots_account_number
        CHECK (CHAR_LENGTH(BTRIM(account_number_ciphertext)) > 0),
    CONSTRAINT ck_settlement_receiving_account_snapshots_account_holder
        CHECK (CHAR_LENGTH(BTRIM(account_holder)) BETWEEN 1 AND 100)
);

CREATE INDEX idx_settlement_receiving_account_snapshots_source_account
    ON settlement_receiving_account_snapshots (source_account_id);

CREATE TABLE settlement_shares (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    payer_participant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_shares PRIMARY KEY (id),
    CONSTRAINT fk_settlement_shares_receiving_account_snapshot
        FOREIGN KEY (settlement_id)
            REFERENCES settlement_receiving_account_snapshots (settlement_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_settlement_shares_payer_participant
        FOREIGN KEY (settlement_id, payer_participant_id)
            REFERENCES settlement_participants (settlement_id, id)
            DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_settlement_shares_amount_positive
        CHECK (amount > 0),
    CONSTRAINT uk_settlement_shares_settlement_payer
        UNIQUE (settlement_id, payer_participant_id)
);

CREATE TABLE settlement_links (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_links PRIMARY KEY (id),
    CONSTRAINT fk_settlement_links_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT uk_settlement_links_settlement UNIQUE (settlement_id),
    CONSTRAINT uk_settlement_links_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_settlement_links_token_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_settlement_links_status
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_settlement_links_expiration
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_settlement_links_status_expires_at
    ON settlement_links (status, expires_at);
