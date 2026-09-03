CREATE TABLE settlement_notification_outbox (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_notification_outbox PRIMARY KEY (id),
    CONSTRAINT fk_settlement_notification_outbox_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_notification_outbox_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_settlement_notification_outbox_settlement_recipient
        UNIQUE (settlement_id, recipient_user_id),
    CONSTRAINT ck_settlement_notification_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_settlement_notification_outbox_attempt_count
        CHECK (
            (status = 'PENDING' AND attempt_count = 0)
            OR (status <> 'PENDING' AND attempt_count > 0)
        ),
    CONSTRAINT ck_settlement_notification_outbox_failure_code
        CHECK (
            last_failure_code IS NULL
            OR last_failure_code IN (
                'TARGET_UNAVAILABLE',
                'LINK_UNAVAILABLE',
                'INVALID_TARGET',
                'DELIVERY_ERROR',
                'PROCESSING_TIMEOUT'
            )
        ),
    CONSTRAINT ck_settlement_notification_outbox_lifecycle
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
    CONSTRAINT ck_settlement_notification_outbox_failure_state
        CHECK (
            (status IN ('PENDING', 'SUCCEEDED') AND last_failure_code IS NULL)
            OR (status IN ('RETRY', 'FAILED') AND last_failure_code IS NOT NULL)
            OR status = 'PROCESSING'
        )
);

CREATE INDEX idx_settlement_notification_outbox_ready
    ON settlement_notification_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_settlement_notification_outbox_processing
    ON settlement_notification_outbox (processing_started_at, id)
    WHERE status = 'PROCESSING';
