CREATE TABLE account_records (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    organization VARCHAR(10) NOT NULL,
    type VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    masked_number VARCHAR(64) NOT NULL,
    currency VARCHAR(10),
    linked_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    deleted_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_account_records PRIMARY KEY (id),
    CONSTRAINT fk_account_records_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_account_records_deleted_at
        CHECK (deleted_at IS NULL OR deleted_at >= linked_at)
);

INSERT INTO account_records (
    id,
    user_id,
    organization,
    type,
    name,
    masked_number,
    currency,
    linked_at,
    deleted_at
)
SELECT
    id,
    user_id,
    organization,
    type,
    name,
    masked_number,
    currency,
    created_at,
    NULL
FROM accounts;

CREATE FUNCTION sync_account_record_from_account()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        INSERT INTO account_records (
            id,
            user_id,
            organization,
            type,
            name,
            masked_number,
            currency,
            linked_at,
            deleted_at
        ) VALUES (
            OLD.id,
            OLD.user_id,
            OLD.organization,
            OLD.type,
            OLD.name,
            OLD.masked_number,
            OLD.currency,
            OLD.created_at,
            CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
        )
        ON CONFLICT (id) DO UPDATE SET
            user_id = EXCLUDED.user_id,
            organization = EXCLUDED.organization,
            type = EXCLUDED.type,
            name = EXCLUDED.name,
            masked_number = EXCLUDED.masked_number,
            currency = EXCLUDED.currency,
            linked_at = EXCLUDED.linked_at,
            deleted_at = EXCLUDED.deleted_at;
        RETURN OLD;
    END IF;

    INSERT INTO account_records (
        id,
        user_id,
        organization,
        type,
        name,
        masked_number,
        currency,
        linked_at,
        deleted_at
    ) VALUES (
        NEW.id,
        NEW.user_id,
        NEW.organization,
        NEW.type,
        NEW.name,
        NEW.masked_number,
        NEW.currency,
        NEW.created_at,
        NULL
    )
    ON CONFLICT (id) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        organization = EXCLUDED.organization,
        type = EXCLUDED.type,
        name = EXCLUDED.name,
        masked_number = EXCLUDED.masked_number,
        currency = EXCLUDED.currency,
        linked_at = EXCLUDED.linked_at,
        deleted_at = NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_accounts_sync_account_record
AFTER INSERT OR UPDATE ON accounts
FOR EACH ROW
EXECUTE FUNCTION sync_account_record_from_account();

CREATE TRIGGER trg_accounts_mark_account_record_deleted
BEFORE DELETE ON accounts
FOR EACH ROW
EXECUTE FUNCTION sync_account_record_from_account();

ALTER TABLE account_transactions
    DROP CONSTRAINT fk_account_transactions_account,
    ADD CONSTRAINT fk_account_transactions_account_record
        FOREIGN KEY (account_id) REFERENCES account_records (id) ON DELETE RESTRICT;

ALTER TABLE account_monthly_summaries
    DROP CONSTRAINT fk_account_monthly_summaries_account,
    ADD CONSTRAINT fk_account_monthly_summaries_account_record
        FOREIGN KEY (account_id) REFERENCES account_records (id) ON DELETE RESTRICT;

ALTER TABLE settlement_receiving_account_snapshots
    DROP CONSTRAINT fk_settlement_receiving_account_snapshots_selected_account,
    ADD CONSTRAINT fk_settlement_receiving_account_snapshots_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_settlement_receiving_account_snapshots_account_record
        FOREIGN KEY (source_account_id) REFERENCES account_records (id) ON DELETE RESTRICT;

ALTER TABLE settlements
    DROP CONSTRAINT uk_settlements_id_receiving_account;
