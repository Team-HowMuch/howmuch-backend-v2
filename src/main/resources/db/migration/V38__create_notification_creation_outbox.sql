-- Persist notification creation intent in the settlement transaction. A consumer
-- creates the inbox entry and push outbox, then marks this row SUCCEEDED atomically.
-- Existing notifications and delivery attempts are intentionally not backfilled.
CREATE TABLE notification_creation_outbox (
    id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    settlement_id UUID NOT NULL,
    notification_type VARCHAR(30) NOT NULL,
    allocation_type VARCHAR(30) NOT NULL,
    requester_name VARCHAR(100) NOT NULL,
    title VARCHAR(100) NOT NULL,
    message VARCHAR(500) NOT NULL,
    deduplication_key VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_creation_outbox PRIMARY KEY (id),
    CONSTRAINT fk_notification_creation_outbox_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_creation_outbox_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_creation_outbox_recipient_deduplication
        UNIQUE (recipient_user_id, deduplication_key),
    CONSTRAINT ck_notification_creation_outbox_type
        CHECK (notification_type IN ('SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER')),
    CONSTRAINT ck_notification_creation_outbox_allocation_type
        CHECK (allocation_type IN ('DUTCH_PAY', 'ITEM_ALLOCATION')),
    CONSTRAINT ck_notification_creation_outbox_requester_name_not_blank
        CHECK (CHAR_LENGTH(BTRIM(requester_name)) > 0),
    CONSTRAINT ck_notification_creation_outbox_title_not_blank
        CHECK (CHAR_LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_notification_creation_outbox_message_not_blank
        CHECK (CHAR_LENGTH(BTRIM(message)) > 0),
    CONSTRAINT ck_notification_creation_outbox_deduplication_key_not_blank
        CHECK (CHAR_LENGTH(BTRIM(deduplication_key)) > 0),
    CONSTRAINT ck_notification_creation_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_notification_creation_outbox_attempt_count
        CHECK (
            (status = 'PENDING' AND attempt_count = 0)
            OR (status <> 'PENDING' AND attempt_count > 0)
        ),
    CONSTRAINT ck_notification_creation_outbox_failure_code
        CHECK (
            last_failure_code IS NULL
            OR last_failure_code IN (
                'PUBLICATION_ERROR',
                'PROCESSING_ERROR',
                'PROCESSING_TIMEOUT'
            )
        ),
    CONSTRAINT ck_notification_creation_outbox_lifecycle
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
    CONSTRAINT ck_notification_creation_outbox_failure_state
        CHECK (
            (status IN ('PENDING', 'SUCCEEDED') AND last_failure_code IS NULL)
            OR (status IN ('RETRY', 'FAILED') AND last_failure_code IS NOT NULL)
            OR status = 'PROCESSING'
        )
);

CREATE INDEX idx_notification_creation_outbox_ready
    ON notification_creation_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_notification_creation_outbox_processing
    ON notification_creation_outbox (processing_started_at, id)
    WHERE status = 'PROCESSING';

-- PostgreSQL does not index the referencing side of a foreign key automatically.
CREATE INDEX idx_notification_creation_outbox_settlement
    ON notification_creation_outbox (settlement_id);

COMMENT ON TABLE notification_creation_outbox IS
    'Durable settlement request/reminder notification creation intents. Display snapshots stay in PostgreSQL, not Kafka.';
COMMENT ON COLUMN notification_creation_outbox.id IS
    'Stable event ID, reused as the inbox notification ID when a new notification is created.';
COMMENT ON COLUMN notification_creation_outbox.deduplication_key IS
    'Same recipient-scoped business key as user_notifications. Includes manual reservation or automatic reminder cycle.';
COMMENT ON COLUMN notification_creation_outbox.status IS
    'PROCESSING covers publication and consumption. SUCCEEDED means inbox and push outbox committed, not push delivery.';
COMMENT ON COLUMN notification_creation_outbox.attempt_count IS
    'Incremented when claimed. Consumers must lock this row and verify PROCESSING plus the current attempt before creating a notification.';
