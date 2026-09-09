ALTER TABLE account_records
    ADD CONSTRAINT uk_account_records_id_user
        UNIQUE (id, user_id);

CREATE TABLE finance_connection_refs (
    external_connection_id BIGINT NOT NULL,
    connection_ref UUID NOT NULL,
    connection_version BIGINT NOT NULL DEFAULT 0,
    registered_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_finance_connection_refs PRIMARY KEY (external_connection_id),
    CONSTRAINT uk_finance_connection_refs_connection_ref UNIQUE (connection_ref),
    CONSTRAINT fk_finance_connection_refs_external_connection
        FOREIGN KEY (external_connection_id)
            REFERENCES external_connections (id)
            ON DELETE RESTRICT,
    CONSTRAINT ck_finance_connection_refs_version
        CHECK (connection_version >= 0),
    CONSTRAINT ck_finance_connection_refs_registration
        CHECK (
            (connection_version = 0 AND registered_at IS NULL)
            OR (connection_version > 0 AND registered_at IS NOT NULL)
        )
);

CREATE TABLE finance_account_refs (
    account_id BIGINT NOT NULL,
    connection_ref UUID NOT NULL,
    account_ref UUID NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_finance_account_refs PRIMARY KEY (account_id),
    CONSTRAINT uk_finance_account_refs_account_ref UNIQUE (account_ref),
    CONSTRAINT fk_finance_account_refs_account_record
        FOREIGN KEY (account_id)
            REFERENCES account_records (id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_finance_account_refs_connection_ref
        FOREIGN KEY (connection_ref)
            REFERENCES finance_connection_refs (connection_ref)
            ON DELETE RESTRICT
);

CREATE TABLE account_transaction_sync_jobs (
    id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    connection_version BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    expected_chunk_count INTEGER,
    expected_transaction_count INTEGER,
    applied_transaction_count INTEGER,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_synced_at TIMESTAMP(6) WITHOUT TIME ZONE,
    failure_code VARCHAR(64),
    safe_message VARCHAR(500),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_account_transaction_sync_jobs PRIMARY KEY (id),
    CONSTRAINT uk_account_transaction_sync_jobs_user_idempotency
        UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_account_transaction_sync_jobs_account_user
        FOREIGN KEY (account_id, user_id)
            REFERENCES account_records (id, user_id)
            ON DELETE RESTRICT,
    CONSTRAINT fk_account_transaction_sync_jobs_finance_account
        FOREIGN KEY (account_id)
            REFERENCES finance_account_refs (account_id)
            ON DELETE RESTRICT,
    CONSTRAINT ck_account_transaction_sync_jobs_connection_version
        CHECK (connection_version > 0),
    CONSTRAINT ck_account_transaction_sync_jobs_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_transaction_sync_jobs_date_range
        CHECK (from_date <= to_date AND to_date - from_date <= 30),
    CONSTRAINT ck_account_transaction_sync_jobs_status
        CHECK (
            status IN (
                'QUEUED',
                'COLLECTING',
                'APPLYING',
                'SUCCEEDED',
                'REAUTH_REQUIRED',
                'FAILED',
                'TIMED_OUT',
                'CANCELLED'
            )
        ),
    CONSTRAINT ck_account_transaction_sync_jobs_expected_counts
        CHECK (
            (
                expected_chunk_count IS NULL
                AND expected_transaction_count IS NULL
            )
            OR (
                expected_chunk_count IS NOT NULL
                AND expected_transaction_count IS NOT NULL
                AND expected_chunk_count >= 0
                AND expected_transaction_count >= 0
            )
        ),
    CONSTRAINT ck_account_transaction_sync_jobs_applied_count
        CHECK (
            applied_transaction_count IS NULL
            OR applied_transaction_count >= 0
        ),
    CONSTRAINT ck_account_transaction_sync_jobs_collection_complete
        CHECK (
            status NOT IN ('APPLYING', 'SUCCEEDED')
            OR (
                expected_chunk_count IS NOT NULL
                AND expected_transaction_count IS NOT NULL
            )
        ),
    CONSTRAINT ck_account_transaction_sync_jobs_completion
        CHECK (
            (
                status IN (
                    'SUCCEEDED',
                    'REAUTH_REQUIRED',
                    'FAILED',
                    'TIMED_OUT',
                    'CANCELLED'
                )
                AND completed_at IS NOT NULL
                AND completed_at >= created_at
            )
            OR (
                status IN ('QUEUED', 'COLLECTING', 'APPLYING')
                AND completed_at IS NULL
            )
        ),
    CONSTRAINT ck_account_transaction_sync_jobs_success
        CHECK (
            (
                status = 'SUCCEEDED'
                AND last_synced_at IS NOT NULL
                AND last_synced_at >= created_at
                AND failure_code IS NULL
                AND safe_message IS NULL
                AND applied_transaction_count IS NOT NULL
            )
            OR (
                status <> 'SUCCEEDED'
                AND last_synced_at IS NULL
            )
        ),
    CONSTRAINT ck_account_transaction_sync_jobs_failure
        CHECK (
            (
                status IN ('REAUTH_REQUIRED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                AND failure_code IS NOT NULL
                AND CHAR_LENGTH(BTRIM(failure_code)) > 0
                AND safe_message IS NOT NULL
                AND CHAR_LENGTH(BTRIM(safe_message)) > 0
            )
            OR (
                status IN ('QUEUED', 'COLLECTING', 'APPLYING', 'SUCCEEDED')
                AND failure_code IS NULL
                AND safe_message IS NULL
            )
        )
);

CREATE UNIQUE INDEX uk_account_transaction_sync_jobs_active_account
    ON account_transaction_sync_jobs (account_id)
    WHERE status IN ('QUEUED', 'COLLECTING', 'APPLYING');

CREATE INDEX idx_account_transaction_sync_jobs_active_deadline
    ON account_transaction_sync_jobs (created_at, id)
    WHERE status IN ('QUEUED', 'COLLECTING', 'APPLYING');

CREATE TABLE account_transaction_sync_outbox (
    id UUID NOT NULL,
    sync_job_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(64),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_account_transaction_sync_outbox PRIMARY KEY (id),
    CONSTRAINT uk_account_transaction_sync_outbox_job UNIQUE (sync_job_id),
    CONSTRAINT fk_account_transaction_sync_outbox_job
        FOREIGN KEY (sync_job_id)
            REFERENCES account_transaction_sync_jobs (id)
            ON DELETE CASCADE,
    CONSTRAINT ck_account_transaction_sync_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_account_transaction_sync_outbox_attempt_count
        CHECK (
            (status = 'PENDING' AND attempt_count = 0)
            OR (status <> 'PENDING' AND attempt_count > 0)
        ),
    CONSTRAINT ck_account_transaction_sync_outbox_lifecycle
        CHECK (
            (
                status IN ('PENDING', 'RETRY')
                AND next_attempt_at IS NOT NULL
                AND processing_started_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                status = 'PROCESSING'
                AND next_attempt_at IS NULL
                AND processing_started_at IS NOT NULL
                AND completed_at IS NULL
            )
            OR (
                status IN ('SUCCEEDED', 'FAILED')
                AND next_attempt_at IS NULL
                AND processing_started_at IS NULL
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_account_transaction_sync_outbox_failure
        CHECK (
            (status IN ('PENDING', 'SUCCEEDED') AND last_failure_code IS NULL)
            OR (status IN ('RETRY', 'FAILED') AND last_failure_code IS NOT NULL)
            OR status = 'PROCESSING'
        )
);

CREATE INDEX idx_account_transaction_sync_outbox_ready
    ON account_transaction_sync_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_account_transaction_sync_outbox_processing
    ON account_transaction_sync_outbox (processing_started_at, id)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_account_transaction_sync_outbox_completed
    ON account_transaction_sync_outbox (completed_at, id)
    WHERE status IN ('SUCCEEDED', 'FAILED');

CREATE TABLE account_transaction_sync_inbox (
    message_id UUID NOT NULL,
    sync_job_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    received_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_account_transaction_sync_inbox PRIMARY KEY (message_id),
    CONSTRAINT uk_account_transaction_sync_inbox_message_job
        UNIQUE (message_id, sync_job_id),
    CONSTRAINT fk_account_transaction_sync_inbox_job
        FOREIGN KEY (sync_job_id)
            REFERENCES account_transaction_sync_jobs (id)
            ON DELETE CASCADE,
    CONSTRAINT ck_account_transaction_sync_inbox_event_type
        CHECK (
            event_type IN (
                'SyncAccepted',
                'TransactionsChunkCollected',
                'TransactionsCollectionCompleted',
                'TransactionsSyncFailed'
            )
        ),
    CONSTRAINT ck_account_transaction_sync_inbox_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_account_transaction_sync_inbox_job_received
    ON account_transaction_sync_inbox (sync_job_id, received_at, message_id);

CREATE INDEX idx_account_transaction_sync_inbox_received
    ON account_transaction_sync_inbox (received_at, message_id);

CREATE TABLE account_transaction_sync_chunks (
    sync_job_id UUID NOT NULL,
    message_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    item_count INTEGER NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_account_transaction_sync_chunks
        PRIMARY KEY (sync_job_id, chunk_index),
    CONSTRAINT uk_account_transaction_sync_chunks_message UNIQUE (message_id),
    CONSTRAINT fk_account_transaction_sync_chunks_job
        FOREIGN KEY (sync_job_id)
            REFERENCES account_transaction_sync_jobs (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_account_transaction_sync_chunks_message
        FOREIGN KEY (message_id, sync_job_id)
            REFERENCES account_transaction_sync_inbox (message_id, sync_job_id)
            ON DELETE CASCADE,
    CONSTRAINT ck_account_transaction_sync_chunks_index
        CHECK (chunk_index >= 0),
    CONSTRAINT ck_account_transaction_sync_chunks_item_count
        CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT ck_account_transaction_sync_chunks_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_transaction_sync_chunks_payload
        CHECK (
            JSONB_TYPEOF(payload) = 'array'
            AND JSONB_ARRAY_LENGTH(payload) = item_count
        )
);
