-- Store the publication intent in the same transaction as a new recap snapshot.
-- The immutable snapshot supplies the user, month, count and event timestamp;
-- using its ID as the message ID keeps retries stable without copying payloads.
CREATE TABLE recap_publication_outbox (
    recap_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_token UUID,
    lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    published_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_recap_publication_outbox PRIMARY KEY (recap_id),
    CONSTRAINT fk_recap_publication_outbox_recap
        FOREIGN KEY (recap_id) REFERENCES monthly_recaps (id) ON DELETE CASCADE,
    CONSTRAINT ck_recap_publication_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_recap_publication_outbox_failure_code CHECK (
        last_failure_code IS NULL
        OR last_failure_code IN ('PUBLICATION_FAILED', 'LEASE_EXPIRED')
    ),
    CONSTRAINT ck_recap_publication_outbox_lifecycle CHECK (
        (
            status = 'PENDING'
            AND next_attempt_at IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL
            AND (
                (attempt_count = 0 AND last_failure_code IS NULL)
                OR (attempt_count > 0 AND last_failure_code IS NOT NULL)
            )
        )
        OR (
            status = 'PROCESSING' AND attempt_count > 0
            AND next_attempt_at IS NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND published_at IS NULL
        )
        OR (
            status = 'PUBLISHED' AND attempt_count > 0
            AND next_attempt_at IS NULL
            AND lease_token IS NULL AND lease_until IS NULL
            AND published_at IS NOT NULL AND last_failure_code IS NULL
        )
    )
);

CREATE INDEX idx_recap_publication_outbox_ready
    ON recap_publication_outbox (next_attempt_at, created_at, recap_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_recap_publication_outbox_lease
    ON recap_publication_outbox (lease_until, recap_id)
    WHERE status = 'PROCESSING';

COMMENT ON TABLE recap_publication_outbox IS
    'Durable RecapPublished publication intents. Enqueue only newly stored recaps; do not backfill historical notifications.';
COMMENT ON COLUMN recap_publication_outbox.recap_id IS
    'Immutable snapshot ID and stable Kafka message ID. Join monthly_recaps for the recipient, month, completed count and UTC generated_at.';
COMMENT ON COLUMN recap_publication_outbox.status IS
    'PUBLISHED means Kafka acknowledged the event, not that an inbox notification or push was delivered.';
