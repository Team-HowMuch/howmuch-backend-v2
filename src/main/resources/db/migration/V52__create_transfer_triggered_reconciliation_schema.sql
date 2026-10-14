-- One reusable reconciliation cycle per settlement. A button tap is only a
-- scheduling signal; settlement_deposits remains the source of payment evidence.
CREATE TABLE settlement_deposit_reconciliation_jobs (
    settlement_id UUID NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_token UUID,
    lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    first_signaled_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    last_signaled_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_deposit_reconciliation_jobs PRIMARY KEY (settlement_id),
    CONSTRAINT fk_settlement_deposit_reconciliation_jobs_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT ck_deposit_reconciliation_jobs_generation CHECK (generation > 0),
    CONSTRAINT ck_deposit_reconciliation_jobs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY', 'SUCCEEDED', 'EXHAUSTED', 'CANCELLED')
    ),
    CONSTRAINT ck_deposit_reconciliation_jobs_attempts CHECK (
        max_attempts BETWEEN 1 AND 3
        AND attempt_count BETWEEN 0 AND max_attempts
        AND (status <> 'PENDING' OR attempt_count = 0)
        AND (status <> 'PROCESSING' OR attempt_count > 0)
        AND (status <> 'RETRY' OR (attempt_count > 0 AND attempt_count < max_attempts))
    ),
    CONSTRAINT ck_deposit_reconciliation_jobs_lifecycle CHECK (
        (status IN ('PENDING', 'RETRY')
            AND next_attempt_at IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL AND completed_at IS NULL)
        OR (status = 'PROCESSING'
            AND next_attempt_at IS NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'EXHAUSTED', 'CANCELLED')
            AND next_attempt_at IS NULL
            AND lease_token IS NULL AND lease_until IS NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_deposit_reconciliation_jobs_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'NO_NEW_DEPOSIT', 'PROVIDER_ERROR', 'PROCESSING_TIMEOUT',
            'QUOTA_EXHAUSTED', 'TARGET_UNAVAILABLE', 'WINDOW_EXPIRED'
        )
    ),
    CONSTRAINT ck_deposit_reconciliation_jobs_failure_state CHECK (
        (status IN ('PENDING', 'SUCCEEDED') AND last_failure_code IS NULL)
        OR (status IN ('RETRY', 'EXHAUSTED', 'CANCELLED') AND last_failure_code IS NOT NULL)
        OR status = 'PROCESSING'
    ),
    CONSTRAINT ck_deposit_reconciliation_jobs_times CHECK (
        created_at <= first_signaled_at
        AND first_signaled_at <= last_signaled_at
        AND last_signaled_at <= updated_at
        AND expires_at > first_signaled_at
        AND expires_at <= first_signaled_at + INTERVAL '10 minutes'
        AND (next_attempt_at IS NULL
            OR (next_attempt_at >= first_signaled_at AND next_attempt_at < expires_at))
        AND (lease_until IS NULL OR lease_until > first_signaled_at)
        AND (completed_at IS NULL
            OR (completed_at >= first_signaled_at AND completed_at <= updated_at))
    )
);

CREATE INDEX idx_deposit_reconciliation_jobs_due
    ON settlement_deposit_reconciliation_jobs (next_attempt_at, settlement_id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_deposit_reconciliation_jobs_lease
    ON settlement_deposit_reconciliation_jobs (lease_until, settlement_id)
    WHERE status = 'PROCESSING';

COMMENT ON TABLE settlement_deposit_reconciliation_jobs IS
    'Durable tap-triggered reconciliation cycles; at most one active cycle per settlement. No payment assertion or participant capability is stored.';
COMMENT ON COLUMN settlement_deposit_reconciliation_jobs.generation IS
    'Increment only when replacing a terminal cycle after application cooldown checks. Results require the current generation and lease_token.';
COMMENT ON COLUMN settlement_deposit_reconciliation_jobs.attempt_count IS
    'Increment atomically on claim, including claims recovered after timeout. Never reset or increase max_attempts for taps during an active cycle.';
COMMENT ON COLUMN settlement_deposit_reconciliation_jobs.last_signaled_at IS
    'Coalesced taps update this field only; they must not postpone next_attempt_at or extend expires_at.';
COMMENT ON COLUMN settlement_deposit_reconciliation_jobs.lease_token IS
    'Random claim fence. Claim due rows with SKIP LOCKED; verify generation, token and unexpired lease before committing results. Bank I/O runs outside database transactions.';
COMMENT ON COLUMN settlement_deposit_reconciliation_jobs.expires_at IS
    'UTC cycle deadline, at most ten minutes after first_signaled_at. Application terminates expired cycles without another bank request.';

-- Partial deposits need their own event type. Existing completion/reminder types
-- and recipient-scoped deduplication remain unchanged. Deploy readers that know
-- the new enum before enabling producers of this type.
ALTER TABLE user_notifications
    DROP CONSTRAINT ck_user_notifications_type,
    DROP CONSTRAINT ck_user_notifications_type_target;

ALTER TABLE user_notifications
    ADD CONSTRAINT ck_user_notifications_type CHECK (
        notification_type IN (
            'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER',
            'SETTLEMENT_PAYMENT_COMPLETED', 'SETTLEMENT_COMPLETED',
            'SETTLEMENT_DEPOSIT_CONFIRMED',
            'GROUP_INVITATION', 'GROUP_MEMBER_LEFT', 'MONTHLY_RECAP'
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
    );
