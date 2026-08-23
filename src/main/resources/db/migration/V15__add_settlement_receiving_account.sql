ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_user_id_id UNIQUE (user_id, id);

ALTER TABLE settlements
    ADD COLUMN receiving_account_id BIGINT,
    ADD CONSTRAINT fk_settlements_receiving_account_owner
        FOREIGN KEY (creator_id, receiving_account_id)
            REFERENCES accounts (user_id, id);

CREATE INDEX idx_settlements_receiving_account_id
    ON settlements (receiving_account_id)
    WHERE receiving_account_id IS NOT NULL;
