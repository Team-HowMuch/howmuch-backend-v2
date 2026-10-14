-- First group settlement netting schema for direct OWNER-triggered execution.
-- This migration intentionally has no user-facing preview/confirmation tables.
-- Existing original deposits remain the payment evidence and are backfilled into
-- the permanent cross-flow bank transaction claim ledger below.

ALTER TABLE settlements
    DROP CONSTRAINT ck_settlements_status,
    ADD CONSTRAINT ck_settlements_status CHECK (
        status IN (
            'DRAFT', 'REQUESTED', 'AWAITING_DEPOSIT', 'SUSPENDED',
            'NETTING_IN_PROGRESS', 'COMPLETED'
        )
    ),
    ADD CONSTRAINT uk_settlements_id_creator UNIQUE (id, creator_id);

ALTER TABLE account_records
    ADD CONSTRAINT uk_account_records_id_user_organization
        UNIQUE (id, user_id, organization);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM settlement_receiving_account_snapshots snapshot
        JOIN settlements settlement ON settlement.id = snapshot.settlement_id
        JOIN account_records account ON account.id = snapshot.source_account_id
        WHERE account.user_id <> settlement.creator_id
           OR account.organization <> snapshot.organization
    ) THEN
        RAISE EXCEPTION 'Settlement receiving snapshot account owner does not match settlement creator';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM settlement_deposits deposit
        JOIN settlements settlement ON settlement.id = deposit.settlement_id
        JOIN account_records account ON account.id = deposit.source_account_id
        JOIN settlement_receiving_account_snapshots snapshot
            ON snapshot.settlement_id = deposit.settlement_id
           AND snapshot.source_account_id = deposit.source_account_id
        JOIN account_transactions transaction
            ON transaction.id = deposit.account_transaction_id
           AND transaction.account_id = deposit.source_account_id
        WHERE account.user_id <> settlement.creator_id
           OR account.organization <> snapshot.organization
           OR transaction.transaction_type <> 'INCOME'
           OR transaction.amount <= 0
    ) THEN
        RAISE EXCEPTION 'Existing settlement deposit has invalid receiver, transaction type, or non-positive income';
    END IF;

    IF EXISTS (
        SELECT account.organization, transaction.external_transaction_key
        FROM settlement_deposits deposit
        JOIN account_records account ON account.id = deposit.source_account_id
        JOIN account_transactions transaction
            ON transaction.id = deposit.account_transaction_id
           AND transaction.account_id = deposit.source_account_id
        GROUP BY account.organization, transaction.external_transaction_key
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Existing settlement deposits contain duplicate organization/transaction keys';
    END IF;

END;
$$;

ALTER TABLE settlement_receiving_account_snapshots
    ADD COLUMN account_owner_id BIGINT;

UPDATE settlement_receiving_account_snapshots snapshot
SET account_owner_id = settlement.creator_id
FROM settlements settlement
WHERE settlement.id = snapshot.settlement_id;

ALTER TABLE settlement_receiving_account_snapshots
    ALTER COLUMN account_owner_id SET NOT NULL,
    ADD CONSTRAINT fk_settlement_receiving_snapshot_owner
        FOREIGN KEY (settlement_id, account_owner_id)
            REFERENCES settlements (id, creator_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_settlement_receiving_snapshot_account_owner
        FOREIGN KEY (source_account_id, account_owner_id)
            REFERENCES account_records (id, user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_settlement_receiving_snapshot_account_organization
        FOREIGN KEY (source_account_id, account_owner_id, organization)
            REFERENCES account_records (id, user_id, organization) ON DELETE RESTRICT;

CREATE FUNCTION populate_settlement_receiving_snapshot_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    settlement_creator BIGINT;
BEGIN
    SELECT creator_id INTO STRICT settlement_creator
    FROM settlements
    WHERE id = NEW.settlement_id;

    IF NEW.account_owner_id IS NULL THEN
        NEW.account_owner_id := settlement_creator;
    ELSIF NEW.account_owner_id <> settlement_creator THEN
        RAISE EXCEPTION 'Settlement receiving account snapshot owner must be the settlement creator'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_populate_settlement_receiving_snapshot_owner
    BEFORE INSERT ON settlement_receiving_account_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION populate_settlement_receiving_snapshot_owner();

CREATE TABLE group_netting_states (
    group_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_group_netting_states PRIMARY KEY (group_id),
    CONSTRAINT fk_group_netting_states_group
        FOREIGN KEY (group_id) REFERENCES groups (id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_states_version CHECK (version >= 0)
);

CREATE TABLE group_netting_executions (
    id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    target_netting_id UUID,
    status VARCHAR(20) NOT NULL,
    idempotency_key UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    worker_id UUID,
    lease_token UUID,
    lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    deadline TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    error_code VARCHAR(100),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    finished_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_group_netting_executions PRIMARY KEY (id),
    CONSTRAINT fk_group_netting_executions_group
        FOREIGN KEY (group_id) REFERENCES groups (id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_executions_actor
        FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_group_netting_execution_id_group UNIQUE (id, group_id),
    CONSTRAINT uk_group_netting_execution_id_group_kind
        UNIQUE (id, group_id, kind),
    CONSTRAINT uk_group_netting_execution_idempotency
        UNIQUE (group_id, actor_id, kind, idempotency_key),
    CONSTRAINT ck_group_netting_execution_kind CHECK (
        kind IN ('CREATE', 'RECONCILE', 'DETECT')
    ),
    CONSTRAINT ck_group_netting_execution_status CHECK (
        status IN ('QUEUED', 'SYNCING', 'RETRY', 'SUCCEEDED', 'STALE', 'FAILED')
    ),
    CONSTRAINT ck_group_netting_execution_attempts CHECK (
        max_attempts BETWEEN 1 AND 10
        AND attempt_count BETWEEN 0 AND max_attempts
        AND (status <> 'QUEUED' OR attempt_count = 0)
        AND (status <> 'SYNCING' OR attempt_count > 0)
        AND (status <> 'RETRY' OR (attempt_count > 0 AND attempt_count < max_attempts))
    ),
    CONSTRAINT ck_group_netting_execution_target CHECK (
        (kind = 'CREATE' AND target_netting_id IS NULL)
        OR (kind IN ('RECONCILE', 'DETECT') AND target_netting_id IS NOT NULL)
    ),
    CONSTRAINT ck_group_netting_execution_lifecycle CHECK (
        (status = 'QUEUED'
            AND attempt_count = 0 AND next_attempt_at IS NOT NULL
            AND worker_id IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND finished_at IS NULL)
        OR (status = 'SYNCING'
            AND next_attempt_at IS NULL
            AND worker_id IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND finished_at IS NULL)
        OR (status = 'RETRY'
            AND next_attempt_at IS NOT NULL
            AND worker_id IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'STALE', 'FAILED')
            AND next_attempt_at IS NULL
            AND worker_id IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND finished_at IS NOT NULL)
    ),
    CONSTRAINT ck_group_netting_execution_times CHECK (
        created_at <= updated_at
        AND deadline > created_at
        AND (finished_at IS NULL OR (finished_at >= created_at AND finished_at <= updated_at))
        AND (lease_until IS NULL OR lease_until > created_at)
    )
);

CREATE UNIQUE INDEX uk_group_netting_execution_live_create
    ON group_netting_executions (group_id)
    WHERE kind = 'CREATE' AND status IN ('QUEUED', 'SYNCING', 'RETRY');

CREATE INDEX idx_group_netting_execution_dispatch
    ON group_netting_executions (status, next_attempt_at, lease_until, created_at)
    WHERE status IN ('QUEUED', 'RETRY', 'SYNCING');

CREATE FUNCTION guard_group_netting_execution_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.group_id <> OLD.group_id
       OR NEW.actor_id <> OLD.actor_id
       OR NEW.kind <> OLD.kind
       OR NEW.target_netting_id IS DISTINCT FROM OLD.target_netting_id
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.max_attempts <> OLD.max_attempts
       OR NEW.deadline <> OLD.deadline
       OR NEW.created_at <> OLD.created_at
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'Netting execution identity is immutable and timestamps cannot move backward'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status IN ('SUCCEEDED', 'STALE', 'FAILED') THEN
        RAISE EXCEPTION 'A terminal netting execution is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF (OLD.status = 'QUEUED' AND NEW.status NOT IN ('QUEUED', 'SYNCING', 'STALE', 'FAILED'))
       OR (OLD.status = 'SYNCING' AND NEW.status NOT IN ('SYNCING', 'RETRY', 'SUCCEEDED', 'STALE', 'FAILED'))
       OR (OLD.status = 'RETRY' AND NEW.status NOT IN ('RETRY', 'SYNCING', 'STALE', 'FAILED')) THEN
        RAISE EXCEPTION 'Invalid netting execution lifecycle transition'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'SYNCING' AND OLD.status IN ('QUEUED', 'RETRY') THEN
        IF NEW.attempt_count <> OLD.attempt_count + 1 THEN
            RAISE EXCEPTION 'Claiming a netting execution must increment its attempt count once'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION 'Netting execution attempts change only when a worker claims the job'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_guard_group_netting_execution_lifecycle
    BEFORE UPDATE ON group_netting_executions
    FOR EACH ROW
    EXECUTE FUNCTION guard_group_netting_execution_lifecycle();

CREATE FUNCTION prevent_group_netting_execution_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Netting execution idempotency and audit records are permanent'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_prevent_group_netting_execution_delete
    BEFORE DELETE ON group_netting_executions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_group_netting_execution_delete();

CREATE TABLE group_netting_execution_snapshots (
    execution_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    execution_kind VARCHAR(20) NOT NULL DEFAULT 'CREATE',
    owner_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    input_json JSONB NOT NULL,
    captured_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_netting_execution_snapshots PRIMARY KEY (execution_id),
    CONSTRAINT fk_group_netting_execution_snapshots_execution
        FOREIGN KEY (execution_id, group_id, execution_kind)
            REFERENCES group_netting_executions (id, group_id, kind) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_execution_snapshots_owner
        FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_execution_snapshots_group_owner
        FOREIGN KEY (group_id, owner_id)
            REFERENCES group_members (group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT uk_group_netting_execution_snapshot_version UNIQUE (group_id, version),
    CONSTRAINT uk_group_netting_execution_snapshot_commit_ref
        UNIQUE (execution_id, group_id, version),
    CONSTRAINT ck_group_netting_execution_snapshot_version CHECK (version > 0),
    CONSTRAINT ck_group_netting_execution_snapshot_kind CHECK (execution_kind = 'CREATE'),
    CONSTRAINT ck_group_netting_execution_snapshot_fingerprint
        CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_group_netting_execution_snapshot_object
        CHECK (jsonb_typeof(input_json) = 'object')
);

CREATE TABLE group_settlement_nettings (
    id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    predecessor_netting_id UUID,
    execution_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    superseded_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_group_settlement_nettings PRIMARY KEY (id),
    CONSTRAINT fk_group_settlement_nettings_group
        FOREIGN KEY (group_id) REFERENCES groups (id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_settlement_nettings_predecessor
        FOREIGN KEY (predecessor_netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_settlement_nettings_snapshot
        FOREIGN KEY (execution_id, group_id, version)
            REFERENCES group_netting_execution_snapshots (execution_id, group_id, version)
            ON DELETE RESTRICT,
    CONSTRAINT uk_group_settlement_nettings_id_group UNIQUE (id, group_id),
    CONSTRAINT uk_group_settlement_nettings_successor_ref
        UNIQUE (id, group_id, predecessor_netting_id),
    CONSTRAINT uk_group_settlement_nettings_execution UNIQUE (execution_id),
    CONSTRAINT uk_group_settlement_nettings_group_version UNIQUE (group_id, version),
    CONSTRAINT uk_group_settlement_nettings_predecessor UNIQUE (predecessor_netting_id),
    CONSTRAINT ck_group_settlement_nettings_version CHECK (version > 0),
    CONSTRAINT ck_group_settlement_nettings_status CHECK (
        status IN ('BUILDING', 'ACTIVE', 'COMPLETED', 'SUPERSEDED')
    ),
    CONSTRAINT ck_group_settlement_nettings_terminal_time CHECK (
        (status IN ('BUILDING', 'ACTIVE') AND completed_at IS NULL AND superseded_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND superseded_at IS NULL)
        OR (status = 'SUPERSEDED' AND completed_at IS NULL AND superseded_at IS NOT NULL)
    ),
    CONSTRAINT ck_group_settlement_nettings_times CHECK (
        created_at <= COALESCE(completed_at, superseded_at, created_at)
    )
);

ALTER TABLE group_netting_executions
    ADD CONSTRAINT fk_group_netting_execution_target
        FOREIGN KEY (target_netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uk_group_settlement_nettings_active_group
    ON group_settlement_nettings (group_id)
    WHERE status IN ('BUILDING', 'ACTIVE');

CREATE INDEX idx_group_settlement_nettings_group_history
    ON group_settlement_nettings (group_id, version DESC);

CREATE FUNCTION guard_netting_snapshot_insert_phase()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_netting_id UUID;
    generation_status VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'group_netting_rollovers'
       OR TG_TABLE_NAME = 'group_netting_rollover_transfers'
       OR TG_TABLE_NAME = 'group_netting_rollover_obligations' THEN
        target_netting_id := NEW.successor_netting_id;
    ELSE
        target_netting_id := NEW.netting_id;
    END IF;

    SELECT status INTO STRICT generation_status
    FROM group_settlement_nettings
    WHERE id = target_netting_id;

    IF generation_status <> 'BUILDING' THEN
        RAISE EXCEPTION 'Netting snapshots can be inserted only during the atomic generation build transaction'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION require_building_netting_generation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'BUILDING' THEN
        RAISE EXCEPTION 'A netting generation must be assembled in the internal BUILDING phase'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_require_building_netting_generation
    BEFORE INSERT ON group_settlement_nettings
    FOR EACH ROW
    EXECUTE FUNCTION require_building_netting_generation();

CREATE FUNCTION assert_group_netting_execution_snapshot_committed()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM group_settlement_nettings netting
        JOIN group_netting_executions execution
          ON execution.id = netting.execution_id
        WHERE netting.execution_id = NEW.execution_id
          AND netting.group_id = NEW.group_id
          AND netting.version = NEW.version
          AND execution.status = 'SUCCEEDED'
    ) THEN
        RAISE EXCEPTION 'An internal execution snapshot commits only with its successful netting generation'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_group_netting_execution_snapshot_committed
    AFTER INSERT ON group_netting_execution_snapshots
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_execution_snapshot_committed();

CREATE TABLE group_netting_parties (
    netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT pk_group_netting_parties PRIMARY KEY (netting_id, user_id),
    CONSTRAINT uk_group_netting_parties_group_user UNIQUE (netting_id, group_id, user_id),
    CONSTRAINT fk_group_netting_parties_netting
        FOREIGN KEY (netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_parties_member
        FOREIGN KEY (group_id, user_id)
            REFERENCES group_members (group_id, user_id) ON DELETE RESTRICT
);

CREATE TABLE group_netting_sources (
    netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    settlement_id UUID NOT NULL,
    creator_id BIGINT NOT NULL,
    original_amount BIGINT NOT NULL,
    actual_deposit_amount BIGINT NOT NULL,
    replaced_amount BIGINT NOT NULL,
    captured_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_netting_sources PRIMARY KEY (netting_id, settlement_id),
    CONSTRAINT uk_group_netting_sources_settlement UNIQUE (settlement_id),
    CONSTRAINT uk_group_netting_sources_group_settlement
        UNIQUE (netting_id, group_id, settlement_id),
    CONSTRAINT fk_group_netting_sources_netting
        FOREIGN KEY (netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_sources_settlement_creator
        FOREIGN KEY (settlement_id, creator_id)
            REFERENCES settlements (id, creator_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_sources_creator_party
        FOREIGN KEY (netting_id, group_id, creator_id)
            REFERENCES group_netting_parties (netting_id, group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_sources_amounts CHECK (
        original_amount > 0
        AND actual_deposit_amount >= 0
        AND replaced_amount > 0
        AND original_amount = actual_deposit_amount + replaced_amount
    )
);

CREATE INDEX idx_group_netting_sources_netting
    ON group_netting_sources (netting_id, settlement_id);

ALTER TABLE settlement_participants
    ADD CONSTRAINT uk_settlement_participant_id_user
        UNIQUE (settlement_id, id, linked_user_id);

CREATE TABLE group_netting_source_shares (
    netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    settlement_id UUID NOT NULL,
    share_id UUID NOT NULL,
    payer_participant_id UUID NOT NULL,
    payer_user_id BIGINT NOT NULL,
    original_amount BIGINT NOT NULL,
    actual_deposit_amount BIGINT NOT NULL,
    replaced_amount BIGINT NOT NULL,
    CONSTRAINT pk_group_netting_source_shares
        PRIMARY KEY (netting_id, settlement_id, share_id),
    CONSTRAINT fk_group_netting_source_shares_source
        FOREIGN KEY (netting_id, group_id, settlement_id)
            REFERENCES group_netting_sources (netting_id, group_id, settlement_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_source_shares_share
        FOREIGN KEY (settlement_id, share_id)
            REFERENCES settlement_shares (settlement_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_source_shares_payer
        FOREIGN KEY (settlement_id, payer_participant_id, payer_user_id)
            REFERENCES settlement_participants (settlement_id, id, linked_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_source_shares_party
        FOREIGN KEY (netting_id, group_id, payer_user_id)
            REFERENCES group_netting_parties (netting_id, group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_source_shares_amounts CHECK (
        original_amount > 0
        AND actual_deposit_amount >= 0
        AND actual_deposit_amount <= original_amount
        AND replaced_amount = original_amount - actual_deposit_amount
    )
);

CREATE TABLE group_netting_monitored_accounts (
    netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    organization VARCHAR(10) NOT NULL,
    account_number_ciphertext VARCHAR(1024) NOT NULL,
    masked_number VARCHAR(64) NOT NULL,
    activated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_netting_monitored_accounts
        PRIMARY KEY (netting_id, user_id, account_id),
    CONSTRAINT uk_group_netting_monitored_account_owner
        UNIQUE (netting_id, group_id, user_id, account_id),
    CONSTRAINT fk_group_netting_monitored_party
        FOREIGN KEY (netting_id, group_id, user_id)
            REFERENCES group_netting_parties (netting_id, group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_monitored_account_owner
        FOREIGN KEY (account_id, user_id, organization)
            REFERENCES account_records (id, user_id, organization) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_monitored_connection_owner
        FOREIGN KEY (connection_id, user_id)
            REFERENCES external_connections (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_monitored_account_ciphertext
        CHECK (CHAR_LENGTH(BTRIM(account_number_ciphertext)) > 0),
    CONSTRAINT ck_group_netting_monitored_account_mask
        CHECK (CHAR_LENGTH(BTRIM(masked_number)) > 0)
);

CREATE INDEX idx_group_netting_monitored_accounts_account
    ON group_netting_monitored_accounts (account_id, activated_at);

CREATE FUNCTION validate_group_netting_monitored_account()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM account_records account
        JOIN external_connections connection
          ON connection.id = NEW.connection_id
         AND connection.user_id = account.user_id
        WHERE account.id = NEW.account_id
          AND account.user_id = NEW.user_id
          AND account.organization = NEW.organization
          AND account.deleted_at IS NULL
          AND connection.active
    ) THEN
        RAISE EXCEPTION 'A monitored account must be active and owned through its own active bank connection'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_group_netting_monitored_account
    BEFORE INSERT ON group_netting_monitored_accounts
    FOR EACH ROW
    EXECUTE FUNCTION validate_group_netting_monitored_account();

CREATE TABLE group_netting_receivers (
    netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    CONSTRAINT pk_group_netting_receivers PRIMARY KEY (netting_id, user_id),
    CONSTRAINT uk_group_netting_receivers_netting_group_user
        UNIQUE (netting_id, group_id, user_id),
    CONSTRAINT uk_group_netting_receivers_account
        UNIQUE (netting_id, group_id, user_id, account_id),
    CONSTRAINT fk_group_netting_receivers_party
        FOREIGN KEY (netting_id, group_id, user_id)
            REFERENCES group_netting_parties (netting_id, group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_receivers_monitored_account
        FOREIGN KEY (netting_id, group_id, user_id, account_id)
            REFERENCES group_netting_monitored_accounts (netting_id, group_id, user_id, account_id)
            ON DELETE RESTRICT
);

CREATE TABLE group_netted_transfers (
    id UUID NOT NULL,
    netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    payer_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    reference VARCHAR(8) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_netted_transfers PRIMARY KEY (id),
    CONSTRAINT uk_group_netted_transfers_reference UNIQUE (reference),
    CONSTRAINT uk_group_netted_transfers_pair UNIQUE (netting_id, payer_id, receiver_id),
    CONSTRAINT uk_group_netted_transfers_id_netting UNIQUE (id, netting_id),
    CONSTRAINT uk_group_netted_transfers_recipient UNIQUE (id, netting_id, receiver_id),
    CONSTRAINT fk_group_netted_transfers_netting
        FOREIGN KEY (netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netted_transfers_payer
        FOREIGN KEY (netting_id, group_id, payer_id)
            REFERENCES group_netting_parties (netting_id, group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netted_transfers_receiver
        FOREIGN KEY (netting_id, group_id, receiver_id)
            REFERENCES group_netting_receivers (netting_id, group_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netted_transfers_amount CHECK (amount > 0),
    CONSTRAINT ck_group_netted_transfers_distinct_parties CHECK (payer_id <> receiver_id),
    CONSTRAINT ck_group_netted_transfers_reference
        CHECK (reference ~ '^H[0-9A-F]{7}$')
);

CREATE INDEX idx_group_netted_transfers_netting_payer
    ON group_netted_transfers (netting_id, payer_id, receiver_id);

CREATE TABLE netted_transfer_reminders (
    transfer_id UUID NOT NULL,
    last_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    sequence BIGINT NOT NULL,
    CONSTRAINT pk_netted_transfer_reminders PRIMARY KEY (transfer_id),
    CONSTRAINT fk_netted_transfer_reminders_transfer
        FOREIGN KEY (transfer_id)
            REFERENCES group_netted_transfers (id) ON DELETE RESTRICT,
    CONSTRAINT ck_netted_transfer_reminders_sequence CHECK (sequence > 0)
);

CREATE TABLE group_netting_rollovers (
    successor_netting_id UUID NOT NULL,
    predecessor_netting_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_netting_rollovers PRIMARY KEY (successor_netting_id),
    CONSTRAINT uk_group_netting_rollovers_predecessor UNIQUE (predecessor_netting_id),
    CONSTRAINT uk_group_netting_rollovers_pair
        UNIQUE (successor_netting_id, predecessor_netting_id),
    CONSTRAINT fk_group_netting_rollovers_successor
        FOREIGN KEY (successor_netting_id, group_id, predecessor_netting_id)
            REFERENCES group_settlement_nettings (id, group_id, predecessor_netting_id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_rollovers_predecessor
        FOREIGN KEY (predecessor_netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_rollovers_distinct_generations
        CHECK (successor_netting_id <> predecessor_netting_id)
);

CREATE TABLE group_netting_rollover_transfers (
    successor_netting_id UUID NOT NULL,
    predecessor_netting_id UUID NOT NULL,
    previous_transfer_id UUID NOT NULL,
    payer_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    original_amount BIGINT NOT NULL,
    confirmed_amount BIGINT NOT NULL,
    carried_amount BIGINT NOT NULL,
    captured_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_netting_rollover_transfers
        PRIMARY KEY (successor_netting_id, previous_transfer_id),
    CONSTRAINT uk_group_netting_rollover_transfer_once UNIQUE (previous_transfer_id),
    CONSTRAINT fk_group_netting_rollover_transfer_rollover
        FOREIGN KEY (successor_netting_id, predecessor_netting_id)
            REFERENCES group_netting_rollovers (successor_netting_id, predecessor_netting_id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_rollover_transfer_previous
        FOREIGN KEY (previous_transfer_id, predecessor_netting_id)
            REFERENCES group_netted_transfers (id, netting_id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_netting_rollover_transfer_parties
        FOREIGN KEY (previous_transfer_id, predecessor_netting_id, receiver_id)
            REFERENCES group_netted_transfers (id, netting_id, receiver_id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_rollover_transfer_amounts CHECK (
        original_amount > 0
        AND confirmed_amount >= 0
        AND carried_amount >= 0
        AND original_amount = confirmed_amount + carried_amount
    )
);

CREATE TABLE group_netting_rollover_obligations (
    successor_netting_id UUID NOT NULL,
    previous_transfer_id UUID NOT NULL,
    payer_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    carried_amount BIGINT NOT NULL,
    CONSTRAINT pk_group_netting_rollover_obligations
        PRIMARY KEY (successor_netting_id, previous_transfer_id),
    CONSTRAINT fk_group_netting_rollover_obligation_snapshot
        FOREIGN KEY (successor_netting_id, previous_transfer_id)
            REFERENCES group_netting_rollover_transfers (successor_netting_id, previous_transfer_id)
            ON DELETE RESTRICT,
    CONSTRAINT ck_group_netting_rollover_obligation_amount
        CHECK (carried_amount > 0)
);

CREATE TRIGGER trg_group_netting_parties_build_phase
    BEFORE INSERT ON group_netting_parties
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_sources_build_phase
    BEFORE INSERT ON group_netting_sources
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_source_shares_build_phase
    BEFORE INSERT ON group_netting_source_shares
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_monitored_accounts_build_phase
    BEFORE INSERT ON group_netting_monitored_accounts
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_receivers_build_phase
    BEFORE INSERT ON group_netting_receivers
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netted_transfers_build_phase
    BEFORE INSERT ON group_netted_transfers
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_rollovers_build_phase
    BEFORE INSERT ON group_netting_rollovers
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_rollover_transfers_build_phase
    BEFORE INSERT ON group_netting_rollover_transfers
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();
CREATE TRIGGER trg_group_netting_rollover_obligations_build_phase
    BEFORE INSERT ON group_netting_rollover_obligations
    FOR EACH ROW EXECUTE FUNCTION guard_netting_snapshot_insert_phase();

CREATE TABLE netting_deposit_reviews (
    id UUID NOT NULL,
    claim_id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    netting_id UUID NOT NULL,
    receiver_id BIGINT NOT NULL,
    account_transaction_id BIGINT NOT NULL,
    source_account_id BIGINT NOT NULL,
    source_netting_id UUID,
    source_transfer_id UUID,
    reason VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    resolved_transfer_id UUID,
    resolved_by BIGINT,
    resolved_at TIMESTAMP(6) WITHOUT TIME ZONE,
    resolution_note VARCHAR(1000),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_netting_deposit_reviews PRIMARY KEY (id),
    CONSTRAINT uk_netting_deposit_reviews_transaction UNIQUE (account_transaction_id),
    CONSTRAINT uk_netting_deposit_reviews_claim_facts
        UNIQUE (id, account_transaction_id, source_account_id, receiver_id),
    CONSTRAINT uk_netting_deposit_reviews_transfer_resolution
        UNIQUE (id, netting_id, resolved_transfer_id, receiver_id),
    CONSTRAINT fk_netting_deposit_reviews_netting
        FOREIGN KEY (netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT fk_netting_deposit_reviews_receiver
        FOREIGN KEY (netting_id, group_id, receiver_id, source_account_id)
            REFERENCES group_netting_monitored_accounts (netting_id, group_id, user_id, account_id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_netting_deposit_reviews_source_netting
        FOREIGN KEY (source_netting_id, group_id)
            REFERENCES group_settlement_nettings (id, group_id) ON DELETE RESTRICT,
    CONSTRAINT fk_netting_deposit_reviews_source_transfer
        FOREIGN KEY (source_transfer_id, source_netting_id, receiver_id)
            REFERENCES group_netted_transfers (id, netting_id, receiver_id) ON DELETE RESTRICT,
    CONSTRAINT fk_netting_deposit_reviews_applied_transfer
        FOREIGN KEY (resolved_transfer_id, netting_id, receiver_id)
            REFERENCES group_netted_transfers (id, netting_id, receiver_id) ON DELETE RESTRICT,
    CONSTRAINT fk_netting_deposit_reviews_transaction
        FOREIGN KEY (account_transaction_id, source_account_id)
            REFERENCES account_transactions (id, account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_netting_deposit_reviews_resolver
        FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_netting_deposit_reviews_reason CHECK (
        reason IN (
            'AMBIGUOUS_MATCH', 'EXCESS_AMOUNT', 'STALE_REFERENCE',
            'UNLINKED_INCOME', 'DUPLICATE_REFERENCE', 'OTHER'
        )
    ),
    CONSTRAINT ck_netting_deposit_reviews_status CHECK (
        status IN ('OPEN', 'APPLIED_TO_NETTING', 'UNRELATED', 'RETURNED')
    ),
    CONSTRAINT ck_netting_deposit_reviews_source_transfer CHECK (
        (source_netting_id IS NULL AND source_transfer_id IS NULL)
        OR (source_netting_id IS NOT NULL AND source_transfer_id IS NOT NULL)
    ),
    CONSTRAINT ck_netting_deposit_reviews_stale_reference CHECK (
        reason <> 'STALE_REFERENCE' OR source_transfer_id IS NOT NULL
    ),
    CONSTRAINT ck_netting_deposit_reviews_claim_identity CHECK (claim_id = id),
    CONSTRAINT ck_netting_deposit_reviews_resolution CHECK (
        (status = 'OPEN'
            AND resolved_transfer_id IS NULL AND resolved_by IS NULL
            AND resolved_at IS NULL AND resolution_note IS NULL)
        OR (status = 'APPLIED_TO_NETTING'
            AND resolved_transfer_id IS NOT NULL AND resolved_by IS NOT NULL
            AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL
            AND CHAR_LENGTH(BTRIM(resolution_note)) > 0)
        OR (status IN ('UNRELATED', 'RETURNED')
            AND resolved_transfer_id IS NULL AND resolved_by IS NOT NULL
            AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL
            AND CHAR_LENGTH(BTRIM(resolution_note)) > 0)
    ),
    CONSTRAINT ck_netting_deposit_reviews_time CHECK (
        resolved_at IS NULL OR resolved_at >= created_at
    )
);

CREATE INDEX idx_netting_deposit_reviews_open
    ON netting_deposit_reviews (group_id, netting_id, created_at)
    WHERE status = 'OPEN';

CREATE TABLE netted_transfer_deposits (
    id UUID NOT NULL,
    netting_id UUID NOT NULL,
    receiver_id BIGINT NOT NULL,
    transfer_id UUID NOT NULL,
    source_account_id BIGINT NOT NULL,
    account_transaction_id BIGINT NOT NULL,
    claim_id UUID NOT NULL,
    matched_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_netted_transfer_deposits PRIMARY KEY (id),
    CONSTRAINT uk_netted_transfer_deposits_transaction UNIQUE (account_transaction_id),
    CONSTRAINT uk_netted_transfer_deposits_claim UNIQUE (claim_id),
    CONSTRAINT fk_netted_transfer_deposits_transfer
        FOREIGN KEY (transfer_id, netting_id, receiver_id)
            REFERENCES group_netted_transfers (id, netting_id, receiver_id) ON DELETE RESTRICT,
    CONSTRAINT fk_netted_transfer_deposits_monitored_account
        FOREIGN KEY (netting_id, receiver_id, source_account_id)
            REFERENCES group_netting_monitored_accounts (netting_id, user_id, account_id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_netted_transfer_deposits_transaction
        FOREIGN KEY (account_transaction_id, source_account_id)
            REFERENCES account_transactions (id, account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_netted_transfer_deposits_matched_at
        CHECK (matched_at >= TIMESTAMP '2000-01-01 00:00:00')
);

CREATE INDEX idx_netted_transfer_deposits_transfer
    ON netted_transfer_deposits (transfer_id, matched_at);

CREATE TABLE settlement_bank_transaction_claims (
    id UUID NOT NULL,
    receiver_user_id BIGINT NOT NULL,
    organization VARCHAR(10) NOT NULL,
    external_transaction_key VARCHAR(64) NOT NULL,
    source_account_id BIGINT NOT NULL,
    account_transaction_id BIGINT NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    settlement_id UUID,
    share_id UUID,
    netting_id UUID,
    transfer_id UUID,
    review_id UUID,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_bank_transaction_claims PRIMARY KEY (id),
    CONSTRAINT uk_settlement_bank_claims_id_share_ref
        UNIQUE (id, settlement_id, share_id, source_account_id, account_transaction_id),
    CONSTRAINT uk_settlement_bank_claims_id_transfer_ref
        UNIQUE (id, netting_id, transfer_id, source_account_id, account_transaction_id),
    CONSTRAINT uk_settlement_bank_claims_id_review_ref
        UNIQUE (id, review_id, source_account_id, account_transaction_id),
    CONSTRAINT fk_settlement_bank_claims_receiver_account
        FOREIGN KEY (source_account_id, receiver_user_id, organization)
            REFERENCES account_records (id, user_id, organization) ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_bank_claims_transaction
        FOREIGN KEY (account_transaction_id, source_account_id)
            REFERENCES account_transactions (id, account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_bank_claims_share_receiver
        FOREIGN KEY (settlement_id, receiver_user_id)
            REFERENCES settlements (id, creator_id) ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_bank_claims_share
        FOREIGN KEY (settlement_id, share_id)
            REFERENCES settlement_shares (settlement_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_bank_claims_transfer
        FOREIGN KEY (transfer_id, netting_id, receiver_user_id)
            REFERENCES group_netted_transfers (id, netting_id, receiver_id) ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_bank_claims_review
        FOREIGN KEY (review_id, account_transaction_id, source_account_id, receiver_user_id)
            REFERENCES netting_deposit_reviews (id, account_transaction_id, source_account_id, receiver_id)
            ON DELETE RESTRICT,
    CONSTRAINT ck_settlement_bank_claims_organization
        CHECK (organization ~ '^[0-9]{4}$'),
    CONSTRAINT ck_settlement_bank_claims_key
        CHECK (external_transaction_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_settlement_bank_claims_target_type CHECK (
        target_type IN ('SHARE', 'NETTED_TRANSFER', 'REVIEW')
    ),
    CONSTRAINT ck_settlement_bank_claims_target_ref CHECK (
        (target_type = 'SHARE'
            AND settlement_id IS NOT NULL AND share_id IS NOT NULL
            AND netting_id IS NULL AND transfer_id IS NULL AND review_id IS NULL)
        OR (target_type = 'NETTED_TRANSFER'
            AND settlement_id IS NULL AND share_id IS NULL
            AND netting_id IS NOT NULL AND transfer_id IS NOT NULL AND review_id IS NULL)
        OR (target_type = 'REVIEW'
            AND settlement_id IS NULL AND share_id IS NULL
            AND netting_id IS NULL AND transfer_id IS NULL AND review_id IS NOT NULL)
    )
);

-- Claims are permanent facts. Neither resolving a review nor rolling a netting
-- generation releases the bank transaction for attribution a second time.
CREATE UNIQUE INDEX uk_settlement_bank_claim_global_key
    ON settlement_bank_transaction_claims (organization, external_transaction_key);

CREATE UNIQUE INDEX uk_settlement_bank_claim_transaction
    ON settlement_bank_transaction_claims (account_transaction_id);

CREATE INDEX idx_settlement_bank_claims_receiver_created
    ON settlement_bank_transaction_claims (receiver_user_id, created_at DESC);

CREATE FUNCTION protect_claimed_bank_transaction_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM settlement_bank_transaction_claims claim
        WHERE claim.account_transaction_id = OLD.id
    ) AND (
        NEW.account_id <> OLD.account_id
        OR NEW.external_transaction_key <> OLD.external_transaction_key
        OR NEW.transaction_type <> OLD.transaction_type
        OR NEW.amount <> OLD.amount
        OR NEW.transacted_at <> OLD.transacted_at
    ) THEN
        RAISE EXCEPTION 'A claimed bank transaction cannot change its attribution identity or payment facts'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_claimed_bank_transaction_identity
    BEFORE UPDATE OF account_id, external_transaction_key, transaction_type, amount, transacted_at
    ON account_transactions
    FOR EACH ROW
    EXECUTE FUNCTION protect_claimed_bank_transaction_identity();

ALTER TABLE netting_deposit_reviews
    ADD CONSTRAINT fk_netting_deposit_reviews_claim
        FOREIGN KEY (claim_id, id, source_account_id, account_transaction_id)
            REFERENCES settlement_bank_transaction_claims (id, review_id, source_account_id, account_transaction_id)
            DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE netted_transfer_deposits
    ADD CONSTRAINT fk_netted_transfer_deposits_claim
        FOREIGN KEY (claim_id, netting_id, transfer_id, source_account_id, account_transaction_id)
            REFERENCES settlement_bank_transaction_claims (id, netting_id, transfer_id, source_account_id, account_transaction_id)
            DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE settlement_deposits
    ADD COLUMN claim_id UUID;

INSERT INTO settlement_bank_transaction_claims (
    id, receiver_user_id, organization, external_transaction_key,
    source_account_id, account_transaction_id, target_type,
    settlement_id, share_id, created_at
)
SELECT deposit.id,
       settlement.creator_id,
       account.organization,
       transaction.external_transaction_key,
       deposit.source_account_id,
       deposit.account_transaction_id,
       'SHARE',
       deposit.settlement_id,
       deposit.share_id,
       deposit.matched_at
FROM settlement_deposits deposit
JOIN settlements settlement ON settlement.id = deposit.settlement_id
JOIN account_records account ON account.id = deposit.source_account_id
JOIN account_transactions transaction
    ON transaction.id = deposit.account_transaction_id
   AND transaction.account_id = deposit.source_account_id;

UPDATE settlement_deposits
SET claim_id = id;

ALTER TABLE settlement_deposits
    ALTER COLUMN claim_id SET NOT NULL,
    ADD CONSTRAINT uk_settlement_deposits_claim UNIQUE (claim_id),
    ADD CONSTRAINT fk_settlement_deposits_bank_claim
        FOREIGN KEY (claim_id, settlement_id, share_id, source_account_id, account_transaction_id)
            REFERENCES settlement_bank_transaction_claims (
                id, settlement_id, share_id, source_account_id, account_transaction_id
            ) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION assert_netting_completion_ready(target_netting_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    target_group_id BIGINT;
BEGIN
    SELECT group_id INTO STRICT target_group_id
    FROM group_settlement_nettings
    WHERE id = target_netting_id;

    IF EXISTS (
        SELECT 1
        FROM group_netted_transfers transfer
        LEFT JOIN LATERAL (
            SELECT COALESCE(sum(transaction.amount), 0) AS paid
            FROM netted_transfer_deposits deposit
            JOIN account_transactions transaction
              ON transaction.id = deposit.account_transaction_id
             AND transaction.account_id = deposit.source_account_id
            WHERE deposit.transfer_id = transfer.id
        ) direct_deposits ON TRUE
        LEFT JOIN LATERAL (
            SELECT COALESCE(sum(transaction.amount), 0) AS paid
            FROM netting_deposit_reviews review
            JOIN account_transactions transaction
              ON transaction.id = review.account_transaction_id
             AND transaction.account_id = review.source_account_id
            WHERE review.status = 'APPLIED_TO_NETTING'
              AND review.resolved_transfer_id = transfer.id
        ) reviewed_deposits ON TRUE
        WHERE transfer.netting_id = target_netting_id
          AND direct_deposits.paid + reviewed_deposits.paid <> transfer.amount
    ) THEN
        RAISE EXCEPTION 'Netting cannot complete while a transfer has an unpaid balance'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM netting_deposit_reviews review
        WHERE review.group_id = target_group_id
          AND review.status = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'Netting cannot complete while the group has an open deposit review'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION guard_group_settlement_netting_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.group_id <> OLD.group_id
       OR NEW.version <> OLD.version
       OR NEW.predecessor_netting_id IS DISTINCT FROM OLD.predecessor_netting_id
       OR NEW.execution_id <> OLD.execution_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Netting generation identity and input reference are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'BUILDING' AND NEW.status = 'ACTIVE' THEN
        IF NEW.completed_at IS NOT NULL OR NEW.superseded_at IS NOT NULL THEN
            RAISE EXCEPTION 'A built generation becomes ACTIVE before any terminal transition'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'ACTIVE' OR NEW.status NOT IN ('COMPLETED', 'SUPERSEDED') THEN
        RAISE EXCEPTION 'Invalid or repeated netting state transition'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'COMPLETED' THEN
        IF NEW.completed_at IS NULL OR NEW.superseded_at IS NOT NULL THEN
            RAISE EXCEPTION 'Completed netting requires completion time only'
                USING ERRCODE = '23514';
        END IF;
        PERFORM assert_netting_completion_ready(NEW.id);
    ELSE
        IF NEW.superseded_at IS NULL OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Superseded netting requires supersession time only'
                USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT 1 FROM netting_deposit_reviews review
            WHERE review.group_id = NEW.group_id AND review.status = 'OPEN'
        ) THEN
            RAISE EXCEPTION 'Netting with an open deposit review cannot be rolled over'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_guard_group_settlement_netting_transition
    BEFORE UPDATE ON group_settlement_nettings
    FOR EACH ROW
    EXECUTE FUNCTION guard_group_settlement_netting_transition();

CREATE FUNCTION assert_group_netting_generation_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    checked_netting UUID;
    checked_group BIGINT;
    checked_predecessor UUID;
    checked_status VARCHAR(20);
    checked_execution UUID;
    execution_status VARCHAR(20);
    execution_actor BIGINT;
    snapshot_owner BIGINT;
    group_owner_id BIGINT;
    group_status VARCHAR(20);
    owner_is_active BOOLEAN;
    predecessor_status VARCHAR(20);
    predecessor_version BIGINT;
    successor_count BIGINT;
    transfer_count BIGINT;
    rollover_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'group_settlement_nettings' THEN
        checked_netting := NEW.id;
    ELSIF TG_TABLE_NAME = 'group_netting_rollovers' THEN
        checked_netting := NEW.successor_netting_id;
    ELSIF TG_TABLE_NAME IN ('group_netting_rollover_transfers', 'group_netting_rollover_obligations') THEN
        checked_netting := NEW.successor_netting_id;
    ELSE
        RAISE EXCEPTION 'Unexpected table for netting generation consistency trigger: %', TG_TABLE_NAME;
    END IF;

    SELECT group_id, predecessor_netting_id, status, execution_id
        INTO checked_group, checked_predecessor, checked_status, checked_execution
    FROM group_settlement_nettings
    WHERE id = checked_netting;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF checked_status = 'BUILDING' THEN
        RAISE EXCEPTION 'A generation cannot commit in its internal BUILDING phase'
            USING ERRCODE = '23514';
    END IF;

    SELECT owner_id, status INTO STRICT group_owner_id, group_status
    FROM groups
    WHERE id = checked_group;

    SELECT execution.status, execution.actor_id, snapshot.owner_id
        INTO STRICT execution_status, execution_actor, snapshot_owner
    FROM group_netting_executions execution
    JOIN group_netting_execution_snapshots snapshot
      ON snapshot.execution_id = execution.id
     AND snapshot.group_id = execution.group_id
    WHERE execution.id = checked_execution;

    SELECT EXISTS (
        SELECT 1 FROM group_members member
        WHERE member.group_id = checked_group
          AND member.user_id = execution_actor
          AND member.role = 'OWNER'
          AND member.status = 'ACTIVE'
    ) INTO owner_is_active;

    IF execution_status <> 'SUCCEEDED'
       OR execution_actor <> snapshot_owner THEN
        RAISE EXCEPTION 'Committed netting requires its successful execution and immutable owner snapshot'
            USING ERRCODE = '23514';
    END IF;

    IF checked_status = 'ACTIVE'
       AND (execution_actor <> group_owner_id
            OR group_status <> 'ACTIVE'
            OR NOT owner_is_active) THEN
        RAISE EXCEPTION 'A new active generation requires the current active group OWNER execution'
            USING ERRCODE = '23514';
    END IF;

    IF checked_status = 'ACTIVE' AND EXISTS (
        SELECT 1
        FROM group_netting_parties party
        JOIN group_members member
          ON member.group_id = party.group_id
         AND member.user_id = party.user_id
        WHERE party.netting_id = checked_netting
          AND member.status <> 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Committed netting parties must be active group members at the cutover transaction'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO successor_count
    FROM group_settlement_nettings
    WHERE predecessor_netting_id = checked_netting;

    IF checked_status = 'SUPERSEDED' THEN
        IF successor_count <> 1 OR NOT EXISTS (
            SELECT 1 FROM group_netting_rollovers rollover
            WHERE rollover.predecessor_netting_id = checked_netting
        ) THEN
            RAISE EXCEPTION 'Superseded generation requires one committed successor and rollover snapshot'
                USING ERRCODE = '23514';
        END IF;
    ELSIF successor_count > 0 THEN
        RAISE EXCEPTION 'A generation with a successor must be superseded'
            USING ERRCODE = '23514';
    END IF;

    IF checked_predecessor IS NOT NULL THEN
        SELECT status, version INTO STRICT predecessor_status, predecessor_version
        FROM group_settlement_nettings
        WHERE id = checked_predecessor;

        IF predecessor_status <> 'SUPERSEDED'
           OR (SELECT version FROM group_settlement_nettings WHERE id = checked_netting)
                <= predecessor_version
           OR NOT EXISTS (
               SELECT 1 FROM group_netting_rollovers rollover
               WHERE rollover.successor_netting_id = checked_netting
                 AND rollover.predecessor_netting_id = checked_predecessor
           ) THEN
            RAISE EXCEPTION 'Successor generation must point forward to one superseded same-group generation'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*) INTO transfer_count
        FROM group_netted_transfers transfer
        WHERE transfer.netting_id = checked_predecessor;

        SELECT count(*) INTO rollover_count
        FROM group_netting_rollover_transfers rollover_transfer
        WHERE rollover_transfer.successor_netting_id = checked_netting
          AND rollover_transfer.predecessor_netting_id = checked_predecessor;

        IF transfer_count <> rollover_count THEN
            RAISE EXCEPTION 'Every predecessor transfer must have one rollover balance snapshot'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM group_netting_rollover_transfers rollover_transfer
            LEFT JOIN group_netting_rollover_obligations obligation
              ON obligation.successor_netting_id = rollover_transfer.successor_netting_id
             AND obligation.previous_transfer_id = rollover_transfer.previous_transfer_id
            WHERE rollover_transfer.successor_netting_id = checked_netting
              AND (
                  (rollover_transfer.carried_amount = 0 AND obligation.previous_transfer_id IS NOT NULL)
                  OR (rollover_transfer.carried_amount > 0
                      AND (obligation.previous_transfer_id IS NULL
                           OR obligation.carried_amount <> rollover_transfer.carried_amount
                           OR obligation.payer_id <> rollover_transfer.payer_id
                           OR obligation.receiver_id <> rollover_transfer.receiver_id))
              )
        ) THEN
            RAISE EXCEPTION 'Only a positive predecessor residual may become a successor input obligation'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM group_netting_rollover_transfers rollover_transfer
            LEFT JOIN LATERAL (
                SELECT COALESCE(sum(transaction.amount), 0) AS paid
                FROM netted_transfer_deposits deposit
                JOIN account_transactions transaction
                  ON transaction.id = deposit.account_transaction_id
                 AND transaction.account_id = deposit.source_account_id
                WHERE deposit.transfer_id = rollover_transfer.previous_transfer_id
            ) direct_deposits ON TRUE
            LEFT JOIN LATERAL (
                SELECT COALESCE(sum(transaction.amount), 0) AS paid
                FROM netting_deposit_reviews review
                JOIN account_transactions transaction
                  ON transaction.id = review.account_transaction_id
                 AND transaction.account_id = review.source_account_id
                WHERE review.status = 'APPLIED_TO_NETTING'
                  AND review.resolved_transfer_id = rollover_transfer.previous_transfer_id
            ) reviewed_deposits ON TRUE
            WHERE rollover_transfer.successor_netting_id = checked_netting
              AND (rollover_transfer.confirmed_amount <> direct_deposits.paid + reviewed_deposits.paid
                   OR rollover_transfer.original_amount <> rollover_transfer.confirmed_amount
                                                       + rollover_transfer.carried_amount)
        ) THEN
            RAISE EXCEPTION 'Rollover balances must equal the authoritative predecessor transfer evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM group_netting_rollovers rollover
        WHERE rollover.successor_netting_id = checked_netting
    ) THEN
        RAISE EXCEPTION 'A root generation cannot have a rollover record'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM group_netting_source_shares source_share
        WHERE source_share.netting_id = checked_netting
          AND source_share.replaced_amount > 0
        UNION ALL
        SELECT 1 FROM group_netting_rollover_obligations obligation
        WHERE obligation.successor_netting_id = checked_netting
    ) THEN
        RAISE EXCEPTION 'A netting generation requires at least one positive unpaid obligation'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        WITH input_balances AS (
            SELECT obligation.user_id, sum(obligation.delta) AS balance
            FROM (
                SELECT source_share.payer_user_id AS user_id,
                       source_share.replaced_amount::NUMERIC AS delta
                FROM group_netting_source_shares source_share
                WHERE source_share.netting_id = checked_netting
                UNION ALL
                SELECT source.creator_id AS user_id,
                       -source_share.replaced_amount::NUMERIC AS delta
                FROM group_netting_source_shares source_share
                JOIN group_netting_sources source
                  ON source.netting_id = source_share.netting_id
                 AND source.settlement_id = source_share.settlement_id
                WHERE source_share.netting_id = checked_netting
                UNION ALL
                SELECT rollover.payer_id, rollover.carried_amount::NUMERIC
                FROM group_netting_rollover_obligations rollover
                WHERE rollover.successor_netting_id = checked_netting
                UNION ALL
                SELECT rollover.receiver_id, -rollover.carried_amount::NUMERIC
                FROM group_netting_rollover_obligations rollover
                WHERE rollover.successor_netting_id = checked_netting
            ) obligation
            GROUP BY obligation.user_id
        ),
        output_balances AS (
            SELECT transfer.user_id, sum(transfer.delta) AS balance
            FROM (
                SELECT net_transfer.payer_id AS user_id,
                       net_transfer.amount::NUMERIC AS delta
                FROM group_netted_transfers net_transfer
                WHERE net_transfer.netting_id = checked_netting
                UNION ALL
                SELECT net_transfer.receiver_id AS user_id,
                       -net_transfer.amount::NUMERIC AS delta
                FROM group_netted_transfers net_transfer
                WHERE net_transfer.netting_id = checked_netting
            ) transfer
            GROUP BY transfer.user_id
        )
        SELECT 1
        FROM input_balances input
        FULL OUTER JOIN output_balances output USING (user_id)
        WHERE COALESCE(input.balance, 0) IS DISTINCT FROM COALESCE(output.balance, 0)
    ) THEN
        RAISE EXCEPTION 'Netting transfers must preserve every member balance from source and carried obligations'
            USING ERRCODE = '23514';
    END IF;

    IF checked_status = 'ACTIVE' AND EXISTS (
        SELECT 1 FROM netting_deposit_reviews review
        WHERE review.group_id = checked_group AND review.status = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'A group with an open deposit review cannot activate a generation'
            USING ERRCODE = '23514';
    END IF;

    IF checked_status = 'COMPLETED' THEN
        IF EXISTS (
            WITH RECURSIVE lineage(id, predecessor_netting_id) AS (
                SELECT id, predecessor_netting_id
                FROM group_settlement_nettings
                WHERE id = checked_netting
                UNION ALL
                SELECT parent.id, parent.predecessor_netting_id
                FROM group_settlement_nettings parent
                JOIN lineage child ON child.predecessor_netting_id = parent.id
            )
            SELECT 1
            FROM group_netting_sources source
            JOIN lineage ON lineage.id = source.netting_id
            JOIN settlements settlement ON settlement.id = source.settlement_id
            WHERE settlement.status <> 'COMPLETED'
        ) THEN
            RAISE EXCEPTION 'A completed generation must complete every original source in its lineage'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_group_netting_generation_consistency
    AFTER INSERT OR UPDATE ON group_settlement_nettings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_generation_consistency();

CREATE CONSTRAINT TRIGGER ct_group_netting_rollover_header_consistency
    AFTER INSERT OR UPDATE ON group_netting_rollovers
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_generation_consistency();

CREATE CONSTRAINT TRIGGER ct_group_netting_rollover_transfer_consistency
    AFTER INSERT OR UPDATE ON group_netting_rollover_transfers
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_generation_consistency();

CREATE CONSTRAINT TRIGGER ct_group_netting_rollover_obligation_consistency
    AFTER INSERT OR UPDATE ON group_netting_rollover_obligations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_generation_consistency();

CREATE FUNCTION validate_netting_rollover_transfer_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_amount BIGINT;
    expected_payer BIGINT;
    expected_receiver BIGINT;
    paid_amount NUMERIC;
    review_paid_amount NUMERIC;
BEGIN
    SELECT amount, payer_id, receiver_id
        INTO STRICT expected_amount, expected_payer, expected_receiver
    FROM group_netted_transfers
    WHERE id = NEW.previous_transfer_id
      AND netting_id = NEW.predecessor_netting_id
    FOR UPDATE;

    SELECT COALESCE(sum(transaction.amount), 0) INTO paid_amount
    FROM netted_transfer_deposits deposit
    JOIN account_transactions transaction
      ON transaction.id = deposit.account_transaction_id
     AND transaction.account_id = deposit.source_account_id
    WHERE deposit.transfer_id = NEW.previous_transfer_id;

    SELECT COALESCE(sum(transaction.amount), 0) INTO review_paid_amount
    FROM netting_deposit_reviews review
    JOIN account_transactions transaction
      ON transaction.id = review.account_transaction_id
     AND transaction.account_id = review.source_account_id
    WHERE review.status = 'APPLIED_TO_NETTING'
      AND review.resolved_transfer_id = NEW.previous_transfer_id;

    paid_amount := paid_amount + review_paid_amount;

    IF NEW.original_amount <> expected_amount
       OR NEW.confirmed_amount <> paid_amount
       OR NEW.payer_id <> expected_payer
       OR NEW.receiver_id <> expected_receiver THEN
        RAISE EXCEPTION 'Rollover snapshot must preserve predecessor transfer amount, parties, and paid evidence'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_netting_rollover_transfer_snapshot
    BEFORE INSERT ON group_netting_rollover_transfers
    FOR EACH ROW
    EXECUTE FUNCTION validate_netting_rollover_transfer_snapshot();

CREATE FUNCTION validate_netted_transfer_deposit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transfer_amount BIGINT;
    transfer_receiver BIGINT;
    transfer_created_at TIMESTAMP(6) WITHOUT TIME ZONE;
    netting_status VARCHAR(20);
    account_owner BIGINT;
    account_organization VARCHAR(10);
    tx_type VARCHAR(20);
    tx_amount NUMERIC(19, 4);
    tx_time TIMESTAMP(6) WITHOUT TIME ZONE;
    direct_paid_amount NUMERIC;
    reviewed_paid_amount NUMERIC;
BEGIN
    SELECT transfer.amount, transfer.receiver_id, transfer.created_at, netting.status
        INTO STRICT transfer_amount, transfer_receiver, transfer_created_at, netting_status
    FROM group_netted_transfers transfer
    JOIN group_settlement_nettings netting ON netting.id = transfer.netting_id
    WHERE transfer.id = NEW.transfer_id
      AND transfer.netting_id = NEW.netting_id
    FOR UPDATE OF transfer, netting;

    SELECT user_id, organization INTO STRICT account_owner, account_organization
    FROM account_records WHERE id = NEW.source_account_id;

    SELECT transaction_type, amount, transacted_at
        INTO STRICT tx_type, tx_amount, tx_time
    FROM account_transactions
    WHERE id = NEW.account_transaction_id
      AND account_id = NEW.source_account_id
    FOR UPDATE;

    IF netting_status <> 'ACTIVE'
       OR transfer_receiver <> NEW.receiver_id
       OR account_owner <> NEW.receiver_id
       OR tx_type <> 'INCOME'
       OR tx_amount <= 0
       OR tx_amount <> trunc(tx_amount)
       OR tx_time < transfer_created_at THEN
        RAISE EXCEPTION 'Only an integral, post-instruction income to the active receiver may pay a netted transfer'
            USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(sum(transaction.amount), 0) INTO direct_paid_amount
    FROM netted_transfer_deposits deposit
    JOIN account_transactions transaction
      ON transaction.id = deposit.account_transaction_id
     AND transaction.account_id = deposit.source_account_id
    WHERE deposit.transfer_id = NEW.transfer_id;

    SELECT COALESCE(sum(transaction.amount), 0) INTO reviewed_paid_amount
    FROM netting_deposit_reviews review
    JOIN account_transactions transaction
      ON transaction.id = review.account_transaction_id
     AND transaction.account_id = review.source_account_id
    WHERE review.status = 'APPLIED_TO_NETTING'
      AND review.resolved_transfer_id = NEW.transfer_id;

    IF direct_paid_amount + reviewed_paid_amount + tx_amount > transfer_amount THEN
        RAISE EXCEPTION 'Netted transfer deposit exceeds its remaining balance; record it for review'
            USING ERRCODE = '23514';
    END IF;

    NEW.claim_id := NEW.id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_netted_transfer_deposit
    BEFORE INSERT ON netted_transfer_deposits
    FOR EACH ROW
    EXECUTE FUNCTION validate_netted_transfer_deposit();

CREATE FUNCTION create_claim_for_netted_transfer_deposit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    account_organization VARCHAR(10);
    transaction_key VARCHAR(64);
BEGIN
    SELECT organization INTO STRICT account_organization
    FROM account_records WHERE id = NEW.source_account_id;

    SELECT external_transaction_key INTO STRICT transaction_key
    FROM account_transactions
    WHERE id = NEW.account_transaction_id
      AND account_id = NEW.source_account_id
    FOR UPDATE;

    INSERT INTO settlement_bank_transaction_claims (
        id, receiver_user_id, organization, external_transaction_key,
        source_account_id, account_transaction_id, target_type,
        netting_id, transfer_id, created_at
    ) VALUES (
        NEW.claim_id, NEW.receiver_id, account_organization, transaction_key,
        NEW.source_account_id, NEW.account_transaction_id, 'NETTED_TRANSFER',
        NEW.netting_id, NEW.transfer_id, NEW.matched_at
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_create_claim_for_netted_transfer_deposit
    AFTER INSERT ON netted_transfer_deposits
    FOR EACH ROW
    EXECUTE FUNCTION create_claim_for_netted_transfer_deposit();

CREATE FUNCTION prevent_recorded_settlement_deposit_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Recorded settlement deposits are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_prevent_netted_transfer_deposit_rewrite
    BEFORE UPDATE OR DELETE ON netted_transfer_deposits
    FOR EACH ROW
    EXECUTE FUNCTION prevent_recorded_settlement_deposit_rewrite();

CREATE FUNCTION validate_netting_deposit_review_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    netting_status VARCHAR(20);
    tx_type VARCHAR(20);
    tx_amount NUMERIC(19, 4);
    tx_time TIMESTAMP(6) WITHOUT TIME ZONE;
    source_transfer_created_at TIMESTAMP(6) WITHOUT TIME ZONE;
    source_is_ancestor BOOLEAN;
BEGIN
    -- A review and a rollover both lock this generation row. Whichever obtains
    -- the lock first determines whether the review belongs to the still-current
    -- generation; a waiting insert rechecks the committed status after rollover.
    SELECT status INTO STRICT netting_status
    FROM group_settlement_nettings
    WHERE id = NEW.netting_id AND group_id = NEW.group_id
    FOR UPDATE;

    IF netting_status NOT IN ('ACTIVE', 'COMPLETED')
       OR NEW.status <> 'OPEN'
       OR NEW.claim_id <> NEW.id THEN
        RAISE EXCEPTION 'A deposit review must begin OPEN against a current or completed group generation'
            USING ERRCODE = '23514';
    END IF;

    SELECT transaction_type, amount, transacted_at
        INTO STRICT tx_type, tx_amount, tx_time
    FROM account_transactions
    WHERE id = NEW.account_transaction_id
      AND account_id = NEW.source_account_id
    FOR UPDATE;

    IF tx_type <> 'INCOME' OR tx_amount <= 0 THEN
        RAISE EXCEPTION 'Only a positive income transaction can be reviewed'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.reason = 'STALE_REFERENCE' THEN
        WITH RECURSIVE ancestors(id, predecessor_netting_id) AS (
            SELECT id, predecessor_netting_id
            FROM group_settlement_nettings
            WHERE id = NEW.netting_id
            UNION ALL
            SELECT parent.id, parent.predecessor_netting_id
            FROM group_settlement_nettings parent
            JOIN ancestors child ON child.predecessor_netting_id = parent.id
        )
        SELECT EXISTS (
            SELECT 1
            FROM ancestors
            JOIN group_settlement_nettings source_generation ON source_generation.id = ancestors.id
            WHERE ancestors.id = NEW.source_netting_id
              AND source_generation.status = 'SUPERSEDED'
        ) INTO source_is_ancestor;

        IF NOT source_is_ancestor THEN
            RAISE EXCEPTION 'A stale-reference review must identify a superseded ancestor transfer'
                USING ERRCODE = '23514';
        END IF;

        SELECT created_at INTO STRICT source_transfer_created_at
        FROM group_netted_transfers
        WHERE id = NEW.source_transfer_id
          AND netting_id = NEW.source_netting_id;

        IF tx_time < source_transfer_created_at THEN
            RAISE EXCEPTION 'A stale-reference review must be for an income after the original transfer instruction'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_netting_deposit_review_insert
    BEFORE INSERT ON netting_deposit_reviews
    FOR EACH ROW
    EXECUTE FUNCTION validate_netting_deposit_review_insert();

CREATE FUNCTION create_claim_for_netting_deposit_review()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    account_organization VARCHAR(10);
    transaction_key VARCHAR(64);
BEGIN
    SELECT organization INTO STRICT account_organization
    FROM account_records WHERE id = NEW.source_account_id;

    SELECT external_transaction_key INTO STRICT transaction_key
    FROM account_transactions
    WHERE id = NEW.account_transaction_id
      AND account_id = NEW.source_account_id
    FOR UPDATE;

    INSERT INTO settlement_bank_transaction_claims (
        id, receiver_user_id, organization, external_transaction_key,
        source_account_id, account_transaction_id, target_type, review_id, created_at
    ) VALUES (
        NEW.claim_id, NEW.receiver_id, account_organization, transaction_key,
        NEW.source_account_id, NEW.account_transaction_id, 'REVIEW', NEW.id, NEW.created_at
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_create_claim_for_netting_deposit_review
    AFTER INSERT ON netting_deposit_reviews
    FOR EACH ROW
    EXECUTE FUNCTION create_claim_for_netting_deposit_review();

CREATE FUNCTION validate_netting_deposit_review_resolution()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transfer_amount BIGINT;
    transfer_receiver BIGINT;
    transfer_created_at TIMESTAMP(6) WITHOUT TIME ZONE;
    netting_status VARCHAR(20);
    tx_amount NUMERIC(19, 4);
    tx_time TIMESTAMP(6) WITHOUT TIME ZONE;
    direct_paid_amount NUMERIC;
    prior_review_paid_amount NUMERIC;
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.claim_id <> OLD.claim_id
       OR NEW.group_id <> OLD.group_id
       OR NEW.netting_id <> OLD.netting_id
       OR NEW.receiver_id <> OLD.receiver_id
       OR NEW.account_transaction_id <> OLD.account_transaction_id
       OR NEW.source_account_id <> OLD.source_account_id
       OR NEW.source_netting_id IS DISTINCT FROM OLD.source_netting_id
       OR NEW.source_transfer_id IS DISTINCT FROM OLD.source_transfer_id
       OR NEW.reason <> OLD.reason
       OR NEW.created_at <> OLD.created_at
       OR OLD.status <> 'OPEN'
       OR NEW.status = 'OPEN' THEN
        RAISE EXCEPTION 'Deposit review evidence is immutable and can resolve only once'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'APPLIED_TO_NETTING' THEN
        SELECT transfer.amount, transfer.receiver_id, transfer.created_at, netting.status
            INTO STRICT transfer_amount, transfer_receiver, transfer_created_at, netting_status
        FROM group_netted_transfers transfer
        JOIN group_settlement_nettings netting ON netting.id = transfer.netting_id
        WHERE transfer.id = NEW.resolved_transfer_id
          AND transfer.netting_id = NEW.netting_id
        FOR UPDATE OF transfer, netting;

        SELECT amount, transacted_at INTO STRICT tx_amount, tx_time
        FROM account_transactions
        WHERE id = NEW.account_transaction_id
          AND account_id = NEW.source_account_id
        FOR UPDATE;

        IF netting_status <> 'ACTIVE'
           OR transfer_receiver <> NEW.receiver_id
           OR tx_time < transfer_created_at THEN
            RAISE EXCEPTION 'A reviewed deposit can be applied only to an active transfer for its receiver'
                USING ERRCODE = '23514';
        END IF;

        SELECT COALESCE(sum(transaction.amount), 0) INTO direct_paid_amount
        FROM netted_transfer_deposits deposit
        JOIN account_transactions transaction
          ON transaction.id = deposit.account_transaction_id
         AND transaction.account_id = deposit.source_account_id
        WHERE deposit.transfer_id = NEW.resolved_transfer_id;

        SELECT COALESCE(sum(transaction.amount), 0) INTO prior_review_paid_amount
        FROM netting_deposit_reviews review
        JOIN account_transactions transaction
          ON transaction.id = review.account_transaction_id
         AND transaction.account_id = review.source_account_id
        WHERE review.status = 'APPLIED_TO_NETTING'
          AND review.resolved_transfer_id = NEW.resolved_transfer_id
          AND review.id <> NEW.id;

        IF tx_amount <= 0 OR tx_amount <> trunc(tx_amount)
           OR tx_time < transfer_created_at
           OR direct_paid_amount + prior_review_paid_amount + tx_amount > transfer_amount THEN
            RAISE EXCEPTION 'Reviewed transaction cannot be applied beyond the transfer residual'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_netting_deposit_review_resolution
    BEFORE UPDATE ON netting_deposit_reviews
    FOR EACH ROW
    EXECUTE FUNCTION validate_netting_deposit_review_resolution();

CREATE FUNCTION prevent_netting_input_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Committed netting source snapshots are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_prevent_netting_deposit_review_delete
    BEFORE DELETE ON netting_deposit_reviews
    FOR EACH ROW
    EXECUTE FUNCTION prevent_netting_input_rewrite();

CREATE FUNCTION guard_group_netting_state_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Netting generation counters are permanent'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE'
       AND (NEW.group_id <> OLD.group_id OR NEW.version <> OLD.version + 1) THEN
        RAISE EXCEPTION 'Netting generation counter can only advance one version at a time'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_guard_group_netting_state_version
    BEFORE UPDATE OR DELETE ON group_netting_states
    FOR EACH ROW
    EXECUTE FUNCTION guard_group_netting_state_version();

CREATE FUNCTION assert_group_netting_state_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    checked_group BIGINT;
    checked_version BIGINT;
    latest_netting_version BIGINT;
BEGIN
    checked_group := NEW.group_id;
    SELECT version INTO STRICT checked_version
    FROM group_netting_states
    WHERE group_id = checked_group;

    SELECT COALESCE(max(version), 0) INTO latest_netting_version
    FROM group_settlement_nettings
    WHERE group_id = checked_group;

    IF checked_version <> latest_netting_version THEN
        RAISE EXCEPTION 'Group netting state version must match its committed generation history'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_group_netting_state_version
    AFTER INSERT OR UPDATE ON group_netting_states
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_state_version();

CREATE FUNCTION assert_group_netting_version_matches_state()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_group_version BIGINT;
BEGIN
    SELECT version INTO STRICT current_group_version
    FROM group_netting_states
    WHERE group_id = NEW.group_id;

    IF current_group_version <> NEW.version THEN
        RAISE EXCEPTION 'Netting version must match the locked group version in the final transaction'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_group_netting_netting_version
    AFTER INSERT ON group_settlement_nettings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_version_matches_state();

CREATE FUNCTION assert_original_source_completion(target_settlement_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    initial_netting UUID;
    leaf_netting UUID;
    leaf_status VARCHAR(20);
BEGIN
    SELECT netting_id INTO initial_netting
    FROM group_netting_sources
    WHERE settlement_id = target_settlement_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    WITH RECURSIVE descendants(id, predecessor_netting_id, version) AS (
        SELECT id, predecessor_netting_id, version
        FROM group_settlement_nettings
        WHERE id = initial_netting
        UNION ALL
        SELECT child.id, child.predecessor_netting_id, child.version
        FROM group_settlement_nettings child
        JOIN descendants parent ON child.predecessor_netting_id = parent.id
    )
    SELECT netting.id, netting.status INTO leaf_netting, leaf_status
    FROM descendants
    JOIN group_settlement_nettings netting ON netting.id = descendants.id
    ORDER BY descendants.version DESC
    LIMIT 1;

    IF leaf_status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'An original source can complete only with its terminal netting generation'
            USING ERRCODE = '23514';
    END IF;

    PERFORM assert_netting_completion_ready(leaf_netting);
    RETURN;
END;
$$;

CREATE FUNCTION protect_netted_original_settlement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM group_netting_sources source WHERE source.settlement_id = OLD.id
    ) THEN
        RETURN NEW;
    END IF;

    IF NEW.creator_id <> OLD.creator_id
       OR NEW.total_amount <> OLD.total_amount
       OR (NEW.receiving_account_id IS DISTINCT FROM OLD.receiving_account_id
           AND NOT (OLD.status = 'COMPLETED' AND NEW.receiving_account_id IS NULL))
       OR NEW.allocation_type IS DISTINCT FROM OLD.allocation_type THEN
        RAISE EXCEPTION 'A netted original settlement obligation is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'COMPLETED' AND NEW.status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'A completed netted original settlement cannot reopen'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status NOT IN ('NETTING_IN_PROGRESS', 'COMPLETED')
       AND NEW.status <> 'NETTING_IN_PROGRESS' THEN
        RAISE EXCEPTION 'A netting source can only enter the protected in-progress state'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'NETTING_IN_PROGRESS'
       AND NEW.status NOT IN ('NETTING_IN_PROGRESS', 'COMPLETED') THEN
        RAISE EXCEPTION 'A netted original settlement cannot return to ordinary payment flow'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'COMPLETED' THEN
        PERFORM assert_original_source_completion(NEW.id);
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_netted_original_settlement
    BEFORE UPDATE ON settlements
    FOR EACH ROW
    EXECUTE FUNCTION protect_netted_original_settlement();

CREATE FUNCTION assert_settlement_netting_state_has_source()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'NETTING_IN_PROGRESS'
       AND NOT EXISTS (
           SELECT 1 FROM group_netting_sources source
           WHERE source.settlement_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'An in-progress netting settlement must have an immutable source snapshot'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_settlement_netting_state_has_source
    AFTER INSERT OR UPDATE ON settlements
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_settlement_netting_state_has_source();

CREATE FUNCTION prevent_original_share_rewrite_after_netting()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    checked_settlement_id UUID;
    checked_share_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        checked_settlement_id := OLD.settlement_id;
        checked_share_id := OLD.id;
    ELSE
        checked_settlement_id := NEW.settlement_id;
        checked_share_id := OLD.id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM group_netting_sources source
        WHERE source.settlement_id = checked_settlement_id
    ) THEN
        RAISE EXCEPTION 'A netted original settlement share is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM settlement_deposits deposit
        WHERE deposit.settlement_id = checked_settlement_id
          AND deposit.share_id = checked_share_id
    ) THEN
        RAISE EXCEPTION 'A settlement share with recorded bank deposits is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prevent_netted_original_share_rewrite
    BEFORE UPDATE OR DELETE ON settlement_shares
    FOR EACH ROW
    EXECUTE FUNCTION prevent_original_share_rewrite_after_netting();

CREATE FUNCTION prevent_netting_receiver_snapshot_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM group_netting_sources source
        WHERE source.settlement_id = OLD.settlement_id
    ) THEN
        RAISE EXCEPTION 'A netted original receiving-account snapshot is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prevent_netted_original_receiving_snapshot_rewrite
    BEFORE UPDATE OR DELETE ON settlement_receiving_account_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION prevent_netting_receiver_snapshot_rewrite();

CREATE FUNCTION prevent_committed_netting_snapshot_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Committed netting snapshots, parties, accounts, transfers, and rollover records are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_prevent_group_netting_execution_snapshot_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_execution_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netting_party_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_parties
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netting_monitored_account_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_monitored_accounts
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netting_receiver_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_receivers
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netted_transfer_rewrite
    BEFORE UPDATE OR DELETE ON group_netted_transfers
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netting_rollover_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_rollovers
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netting_rollover_transfer_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_rollover_transfers
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();

CREATE TRIGGER trg_prevent_group_netting_rollover_obligation_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_rollover_obligations
    FOR EACH ROW
    EXECUTE FUNCTION prevent_committed_netting_snapshot_rewrite();




CREATE FUNCTION validate_settlement_bank_transaction_claim()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    receiver_account account_records%ROWTYPE;
    transaction_row account_transactions%ROWTYPE;
    settlement_creator BIGINT;
    expected_account_id BIGINT;
    review_owner BIGINT;
BEGIN
    SELECT * INTO STRICT receiver_account
    FROM account_records
    WHERE id = NEW.source_account_id;

    SELECT * INTO STRICT transaction_row
    FROM account_transactions
    WHERE id = NEW.account_transaction_id
    FOR UPDATE;

    IF receiver_account.user_id <> NEW.receiver_user_id
       OR receiver_account.organization <> NEW.organization
       OR transaction_row.account_id <> receiver_account.id
       OR transaction_row.external_transaction_key <> NEW.external_transaction_key
       OR transaction_row.transaction_type <> 'INCOME'
       OR transaction_row.amount <= 0 THEN
        RAISE EXCEPTION 'Invalid bank transaction claim facts'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.target_type = 'SHARE' THEN
        SELECT creator_id INTO STRICT settlement_creator
        FROM settlements
        WHERE id = NEW.settlement_id;

        IF settlement_creator <> NEW.receiver_user_id
           OR EXISTS (
               SELECT 1 FROM group_netting_sources source
               WHERE source.settlement_id = NEW.settlement_id
           ) THEN
            RAISE EXCEPTION 'Original share claim has wrong receiver or is already netted'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.target_type = 'NETTED_TRANSFER' THEN
        IF transaction_row.amount <> trunc(transaction_row.amount) THEN
            RAISE EXCEPTION 'Netted transfer deposit must be an integral KRW amount'
                USING ERRCODE = '23514';
        END IF;

        SELECT account_id INTO STRICT expected_account_id
        FROM group_netting_receivers
        WHERE netting_id = NEW.netting_id
          AND user_id = NEW.receiver_user_id;

        IF expected_account_id <> NEW.source_account_id THEN
            RAISE EXCEPTION 'Netted transfer claim account does not match receiver snapshot'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT receiver_id INTO STRICT review_owner
        FROM netting_deposit_reviews
        WHERE id = NEW.review_id
          AND account_transaction_id = NEW.account_transaction_id
          AND source_account_id = NEW.source_account_id;

        IF review_owner <> NEW.receiver_user_id THEN
            RAISE EXCEPTION 'Review claim receiver does not match review owner'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_settlement_bank_transaction_claim
    BEFORE INSERT ON settlement_bank_transaction_claims
    FOR EACH ROW
    EXECUTE FUNCTION validate_settlement_bank_transaction_claim();

CREATE FUNCTION keep_settlement_bank_transaction_claim_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Bank transaction claims are permanent attribution facts'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_keep_settlement_bank_transaction_claim_immutable
    BEFORE UPDATE OR DELETE ON settlement_bank_transaction_claims
    FOR EACH ROW
    EXECUTE FUNCTION keep_settlement_bank_transaction_claim_immutable();

CREATE FUNCTION create_claim_for_original_settlement_deposit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    settlement_creator BIGINT;
    account_owner BIGINT;
    account_organization VARCHAR(10);
    transaction_key VARCHAR(64);
    transaction_type VARCHAR(20);
    transaction_amount NUMERIC(19, 4);
    share_amount BIGINT;
BEGIN
    SELECT creator_id INTO STRICT settlement_creator
    FROM settlements
    WHERE id = NEW.settlement_id
    FOR UPDATE;

    SELECT user_id, organization INTO STRICT account_owner, account_organization
    FROM account_records
    WHERE id = NEW.source_account_id;

    SELECT bank_transaction.external_transaction_key,
           bank_transaction.transaction_type,
           bank_transaction.amount
        INTO STRICT transaction_key, transaction_type, transaction_amount
    FROM account_transactions bank_transaction
    WHERE bank_transaction.id = NEW.account_transaction_id
      AND bank_transaction.account_id = NEW.source_account_id
    FOR UPDATE;

    SELECT amount INTO STRICT share_amount
    FROM settlement_shares
    WHERE id = NEW.share_id AND settlement_id = NEW.settlement_id
    FOR UPDATE;

    IF account_owner <> settlement_creator
       OR transaction_type <> 'INCOME'
       OR transaction_amount <= 0
       OR EXISTS (
           SELECT 1 FROM group_netting_sources source
           WHERE source.settlement_id = NEW.settlement_id
       ) THEN
        RAISE EXCEPTION 'Original deposit does not belong to an unpaid original settlement'
            USING ERRCODE = '23514';
    END IF;

    NEW.claim_id := NEW.id;
    INSERT INTO settlement_bank_transaction_claims (
        id, receiver_user_id, organization, external_transaction_key,
        source_account_id, account_transaction_id, target_type,
        settlement_id, share_id, created_at
    ) VALUES (
        NEW.claim_id, settlement_creator, account_organization, transaction_key,
        NEW.source_account_id, NEW.account_transaction_id, 'SHARE',
        NEW.settlement_id, NEW.share_id, NEW.matched_at
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_create_claim_for_original_settlement_deposit
    BEFORE INSERT ON settlement_deposits
    FOR EACH ROW
    EXECUTE FUNCTION create_claim_for_original_settlement_deposit();

CREATE TRIGGER trg_prevent_original_settlement_deposit_rewrite
    BEFORE UPDATE OR DELETE ON settlement_deposits
    FOR EACH ROW
    EXECUTE FUNCTION prevent_recorded_settlement_deposit_rewrite();

CREATE FUNCTION validate_group_netting_source()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    settlement_row settlements%ROWTYPE;
    settlement_share_total BIGINT;
    payable_amount BIGINT;
    total_paid BIGINT;
BEGIN
    SELECT * INTO STRICT settlement_row
    FROM settlements
    WHERE id = NEW.settlement_id
    FOR UPDATE;

    SELECT COALESCE(sum(share.amount), 0)::BIGINT INTO payable_amount
    FROM settlement_shares share
    JOIN settlement_participants participant
      ON participant.settlement_id = share.settlement_id
     AND participant.id = share.payer_participant_id
    WHERE share.settlement_id = NEW.settlement_id
      AND participant.linked_user_id IS DISTINCT FROM NEW.creator_id;

    SELECT COALESCE(sum(share.amount), 0)::BIGINT INTO settlement_share_total
    FROM settlement_shares share
    WHERE share.settlement_id = NEW.settlement_id;

    IF EXISTS (
        SELECT 1
        FROM settlement_deposits deposit
        JOIN settlement_shares share
          ON share.settlement_id = deposit.settlement_id
         AND share.id = deposit.share_id
        JOIN settlement_participants participant
          ON participant.settlement_id = share.settlement_id
         AND participant.id = share.payer_participant_id
        JOIN account_transactions transaction
          ON transaction.id = deposit.account_transaction_id
         AND transaction.account_id = deposit.source_account_id
        WHERE deposit.settlement_id = NEW.settlement_id
          AND participant.linked_user_id IS DISTINCT FROM NEW.creator_id
          AND (transaction.amount <= 0 OR transaction.amount <> trunc(transaction.amount))
    ) THEN
        RAISE EXCEPTION 'Netting source contains a non-positive or non-integral original deposit'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM settlement_deposits deposit
        JOIN settlement_shares share
          ON share.settlement_id = deposit.settlement_id
         AND share.id = deposit.share_id
        JOIN settlement_participants participant
          ON participant.settlement_id = share.settlement_id
         AND participant.id = share.payer_participant_id
        JOIN account_transactions transaction
          ON transaction.id = deposit.account_transaction_id
         AND transaction.account_id = deposit.source_account_id
        WHERE deposit.settlement_id = NEW.settlement_id
          AND participant.linked_user_id IS DISTINCT FROM NEW.creator_id
        GROUP BY deposit.share_id, share.amount
        HAVING sum(transaction.amount) > share.amount
    ) THEN
        RAISE EXCEPTION 'Netting source contains an overpaid original share'
            USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(sum(transaction.amount), 0)::BIGINT INTO total_paid
    FROM settlement_deposits deposit
    JOIN settlement_shares share
      ON share.settlement_id = deposit.settlement_id
     AND share.id = deposit.share_id
    JOIN settlement_participants participant
      ON participant.settlement_id = share.settlement_id
     AND participant.id = share.payer_participant_id
    JOIN account_transactions transaction
      ON transaction.id = deposit.account_transaction_id
     AND transaction.account_id = deposit.source_account_id
    WHERE deposit.settlement_id = NEW.settlement_id
      AND participant.linked_user_id IS DISTINCT FROM NEW.creator_id;

    IF settlement_row.status <> 'AWAITING_DEPOSIT'
       OR settlement_row.creator_id <> NEW.creator_id
       OR settlement_row.total_amount <> settlement_share_total
       OR payable_amount <> NEW.original_amount
       OR total_paid <> NEW.actual_deposit_amount THEN
        RAISE EXCEPTION 'Netting source differs from authoritative settlement/deposit facts'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_group_netting_source
    BEFORE INSERT ON group_netting_sources
    FOR EACH ROW
    EXECUTE FUNCTION validate_group_netting_source();

CREATE FUNCTION validate_group_netting_source_share()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    share_amount BIGINT;
    paid_amount BIGINT;
    settlement_creator BIGINT;
BEGIN
    SELECT creator_id INTO STRICT settlement_creator
    FROM settlements
    WHERE id = NEW.settlement_id;

    SELECT amount INTO STRICT share_amount
    FROM settlement_shares
    WHERE settlement_id = NEW.settlement_id AND id = NEW.share_id;

    IF EXISTS (
        SELECT 1
        FROM settlement_deposits deposit
        JOIN account_transactions transaction
          ON transaction.id = deposit.account_transaction_id
         AND transaction.account_id = deposit.source_account_id
        WHERE deposit.settlement_id = NEW.settlement_id
          AND deposit.share_id = NEW.share_id
          AND (transaction.amount <= 0 OR transaction.amount <> trunc(transaction.amount))
    ) THEN
        RAISE EXCEPTION 'Netting source share contains a non-positive or non-integral deposit'
            USING ERRCODE = '23514';
    END IF;

    IF (
        SELECT COALESCE(sum(transaction.amount), 0)
        FROM settlement_deposits deposit
        JOIN account_transactions transaction
          ON transaction.id = deposit.account_transaction_id
         AND transaction.account_id = deposit.source_account_id
        WHERE deposit.settlement_id = NEW.settlement_id
          AND deposit.share_id = NEW.share_id
    ) > share_amount THEN
        RAISE EXCEPTION 'Netting source share is overpaid'
            USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(sum(transaction.amount), 0)::BIGINT INTO paid_amount
    FROM settlement_deposits deposit
    JOIN account_transactions transaction
      ON transaction.id = deposit.account_transaction_id
     AND transaction.account_id = deposit.source_account_id
    WHERE deposit.settlement_id = NEW.settlement_id
      AND deposit.share_id = NEW.share_id;

    IF NEW.payer_user_id = settlement_creator
       OR NEW.original_amount <> share_amount
       OR NEW.actual_deposit_amount <> paid_amount THEN
        RAISE EXCEPTION 'Netting source share differs from authoritative share/deposit facts'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM group_members member
        WHERE member.group_id = NEW.group_id
          AND member.user_id = NEW.payer_user_id
          AND member.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'A netting source share payer must be an active member of the source group'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_group_netting_source_share
    BEFORE INSERT ON group_netting_source_shares
    FOR EACH ROW
    EXECUTE FUNCTION validate_group_netting_source_share();

CREATE TRIGGER trg_prevent_group_netting_source_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_sources
    FOR EACH ROW
    EXECUTE FUNCTION prevent_netting_input_rewrite();

CREATE TRIGGER trg_prevent_group_netting_source_share_rewrite
    BEFORE UPDATE OR DELETE ON group_netting_source_shares
    FOR EACH ROW
    EXECUTE FUNCTION prevent_netting_input_rewrite();

CREATE FUNCTION assert_group_netting_source_snapshot_complete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_netting UUID;
    source_settlement UUID;
    expected_amount BIGINT;
    expected_paid BIGINT;
    expected_share_count BIGINT;
    actual_share_count BIGINT;
    settlement_state VARCHAR(30);
    total_amount BIGINT;
    total_paid BIGINT;
BEGIN
    source_netting := COALESCE(NEW.netting_id, OLD.netting_id);
    source_settlement := COALESCE(NEW.settlement_id, OLD.settlement_id);

    SELECT original_amount, actual_deposit_amount
        INTO expected_amount, expected_paid
    FROM group_netting_sources
    WHERE netting_id = source_netting AND settlement_id = source_settlement;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT status INTO STRICT settlement_state
    FROM settlements WHERE id = source_settlement;

    SELECT COALESCE(sum(original_amount), 0),
           COALESCE(sum(actual_deposit_amount), 0),
           count(*)
        INTO total_amount, total_paid, actual_share_count
    FROM group_netting_source_shares
    WHERE netting_id = source_netting AND settlement_id = source_settlement;

    SELECT count(*) INTO expected_share_count
    FROM settlement_shares share
    JOIN settlement_participants participant
      ON participant.settlement_id = share.settlement_id
     AND participant.id = share.payer_participant_id
    JOIN group_netting_sources source
      ON source.settlement_id = share.settlement_id
    WHERE source.netting_id = source_netting
      AND source.settlement_id = source_settlement
      AND participant.linked_user_id IS DISTINCT FROM source.creator_id;

    IF settlement_state NOT IN ('NETTING_IN_PROGRESS', 'COMPLETED')
       OR total_amount <> expected_amount
       OR total_paid <> expected_paid
       OR actual_share_count <> expected_share_count THEN
        RAISE EXCEPTION 'Netting source/share snapshot is incomplete or original state was not transitioned atomically'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_group_netting_source_snapshot_complete
    AFTER INSERT OR UPDATE ON group_netting_sources
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_source_snapshot_complete();

CREATE CONSTRAINT TRIGGER ct_group_netting_source_shares_complete
    AFTER INSERT OR UPDATE ON group_netting_source_shares
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_group_netting_source_snapshot_complete();

-- Direct execution has no preview or confirmation-request notifications.
-- Generation confirmation/completion and transfer reminders route to its UUID.
ALTER TABLE user_notifications
    DROP CONSTRAINT ck_user_notifications_type,
    DROP CONSTRAINT ck_user_notifications_target_type,
    DROP CONSTRAINT ck_user_notifications_type_target,
    DROP CONSTRAINT ck_user_notifications_target_id;

ALTER TABLE user_notifications
    ADD CONSTRAINT ck_user_notifications_type CHECK (
        notification_type IN (
            'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER',
            'SETTLEMENT_PAYMENT_COMPLETED', 'SETTLEMENT_COMPLETED',
            'SETTLEMENT_DEPOSIT_CONFIRMED',
            'GROUP_INVITATION', 'GROUP_MEMBER_LEFT', 'MONTHLY_RECAP',
            'GROUP_NETTING_CONFIRMED', 'GROUP_NETTING_COMPLETED', 'NETTED_TRANSFER_REMINDER'
        )
    ),
    ADD CONSTRAINT ck_user_notifications_target_type CHECK (
        target_type IN (
            'SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL',
            'GROUP_INVITATION', 'GROUP_DETAIL', 'MONTHLY_RECAP',
            'GROUP_SETTLEMENT_NETTING'
        )
    ),
    ADD CONSTRAINT ck_user_notifications_type_target CHECK (
        (notification_type IN (
            'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER',
            'SETTLEMENT_PAYMENT_COMPLETED', 'SETTLEMENT_COMPLETED'
        ) AND target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL'))
        OR (notification_type = 'SETTLEMENT_DEPOSIT_CONFIRMED'
            AND target_type = 'SETTLEMENT_DETAIL')
        OR (notification_type = 'GROUP_INVITATION' AND target_type = 'GROUP_INVITATION')
        OR (notification_type = 'GROUP_MEMBER_LEFT' AND target_type = 'GROUP_DETAIL')
        OR (notification_type = 'MONTHLY_RECAP' AND target_type = 'MONTHLY_RECAP')
        OR (notification_type IN (
                'GROUP_NETTING_CONFIRMED', 'GROUP_NETTING_COMPLETED', 'NETTED_TRANSFER_REMINDER'
            )
            AND target_type = 'GROUP_SETTLEMENT_NETTING')
    ),
    ADD CONSTRAINT ck_user_notifications_target_id CHECK (
        (target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL')
            AND (
                (target_id IS NULL AND settlement_id IS NULL)
                OR (target_id IS NOT NULL
                    AND target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                    AND (settlement_id IS NULL OR LOWER(target_id) = settlement_id::TEXT))
            ))
        OR (target_type IN ('GROUP_INVITATION', 'GROUP_SETTLEMENT_NETTING')
            AND target_id IS NOT NULL
            AND target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
        OR (target_type = 'GROUP_DETAIL' AND target_id IS NOT NULL
            AND CASE WHEN target_id ~ '^[1-9][0-9]{0,18}$'
                THEN target_id::NUMERIC <= 9223372036854775807
                ELSE FALSE END)
        OR (target_type = 'MONTHLY_RECAP' AND target_id IS NOT NULL
            AND target_id ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
            AND LEFT(target_id, 4) <> '0000')
    );

-- Spring Batch 6 JDBC metadata for durable asynchronous execution.
CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_NAME VARCHAR(100) NOT NULL,
    JOB_KEY VARCHAR(32) NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_INSTANCE_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE (JOB_INSTANCE_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL,
    PARAMETER_NAME VARCHAR(100) NOT NULL,
    PARAMETER_TYPE VARCHAR(100) NOT NULL,
    PARAMETER_VALUE VARCHAR(2500),
    IDENTIFYING CHAR(1) NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT NOT NULL,
    STEP_NAME VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    COMMIT_COUNT BIGINT,
    READ_COUNT BIGINT,
    FILTER_COUNT BIGINT,
    WRITE_COUNT BIGINT,
    READ_SKIP_COUNT BIGINT,
    WRITE_SKIP_COUNT BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT BIGINT,
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION (STEP_EXECUTION_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

COMMENT ON TABLE group_netting_execution_snapshots IS
    'Internal immutable calculation snapshot and lineage for an accepted OWNER execution; no public preview or participant confirmation state.';
COMMENT ON COLUMN group_settlement_nettings.status IS
    'BUILDING is transaction-local only; a rollover supersedes its predecessor before inserting the successor in the same transaction, and deferred constraints reject an unfinished commit.';
COMMENT ON COLUMN settlement_receiving_account_snapshots.account_owner_id IS
    'Owner snapshot backfilled from the settlement creator; an insert trigger preserves compatibility with pre-V53 writers.';
COMMENT ON TABLE settlement_bank_transaction_claims IS
    'Permanent global attribution of original settlement and netted transfer bank transactions; claims survive rollover and review resolution.';
COMMENT ON TABLE group_netting_rollover_obligations IS
    'Positive unpaid prior-transfer residuals carried exactly once into a successor generation; zero residuals are not re-created as debt.';
