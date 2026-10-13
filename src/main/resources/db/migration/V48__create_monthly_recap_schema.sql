-- Snapshot values are independent of later settlement, account and profile changes.
-- Only the owner is a foreign key: a companion's withdrawal must not erase another owner's recap.
CREATE TABLE monthly_recaps (
    id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    month_start DATE NOT NULL,
    owner_name VARCHAR(100) NOT NULL,
    completed_settlement_count INTEGER NOT NULL,
    total_own_amount BIGINT NOT NULL,
    comparison_status VARCHAR(20) NOT NULL,
    previous_own_amount BIGINT,
    amount_difference BIGINT,
    change_rate_percent NUMERIC(24, 1),
    companion_kind VARCHAR(20),
    companion_identity_key TEXT,
    companion_display_name VARCHAR(100),
    companion_share_display_name VARCHAR(100),
    companion_avatar_file_name VARCHAR(100),
    companion_completed_settlement_count INTEGER,
    companion_total_own_amount BIGINT,
    generated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_monthly_recaps PRIMARY KEY (id),
    CONSTRAINT fk_monthly_recaps_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_monthly_recaps_owner_month UNIQUE (owner_user_id, month_start),
    CONSTRAINT ck_monthly_recaps_month CHECK (
        EXTRACT(DAY FROM month_start) = 1
        AND EXTRACT(YEAR FROM month_start) BETWEEN 1 AND 9999
    ),
    CONSTRAINT ck_monthly_recaps_owner_name CHECK (CHAR_LENGTH(BTRIM(owner_name)) > 0),
    CONSTRAINT ck_monthly_recaps_summary CHECK (
        completed_settlement_count > 0 AND total_own_amount >= 0
    ),
    CONSTRAINT ck_monthly_recaps_generated_at CHECK (
        generated_at >= month_start::TIMESTAMP + INTERVAL '1 month' - INTERVAL '9 hours'
    ),
    -- Explicit IS NOT NULL guards are required: PostgreSQL CHECK also accepts UNKNOWN.
    CONSTRAINT ck_monthly_recaps_comparison CHECK (
        (
            comparison_status = 'NO_PREVIOUS'
            AND previous_own_amount IS NULL
            AND amount_difference IS NULL
            AND change_rate_percent IS NULL
        )
        OR (
            previous_own_amount IS NOT NULL AND previous_own_amount >= 0
            AND amount_difference IS NOT NULL
            AND amount_difference = total_own_amount - previous_own_amount
            AND (
                (
                    comparison_status = 'PREVIOUS_ZERO'
                    AND previous_own_amount = 0 AND total_own_amount > 0
                    AND change_rate_percent IS NULL
                )
                OR (
                    comparison_status = 'UNCHANGED'
                    AND total_own_amount = previous_own_amount
                    AND change_rate_percent IS NOT NULL AND change_rate_percent = 0
                )
                OR (
                    comparison_status IN ('INCREASED', 'DECREASED')
                    AND previous_own_amount > 0
                    AND change_rate_percent IS NOT NULL
                    -- Preserve near-half boundaries across the full BIGINT range.
                    AND change_rate_percent = ROUND(
                        (total_own_amount::NUMERIC - previous_own_amount) * 100
                            / NULLIF(previous_own_amount::NUMERIC(50, 30), 0), 1
                    )
                    AND (
                        (comparison_status = 'INCREASED' AND total_own_amount > previous_own_amount)
                        OR (comparison_status = 'DECREASED' AND total_own_amount < previous_own_amount)
                    )
                )
            )
        )
    ),
    CONSTRAINT ck_monthly_recaps_companion CHECK (
        (
            companion_kind IS NULL
            AND companion_identity_key IS NULL
            AND companion_display_name IS NULL
            AND companion_share_display_name IS NULL
            AND companion_avatar_file_name IS NULL
            AND companion_completed_settlement_count IS NULL
            AND companion_total_own_amount IS NULL
        )
        OR (
            companion_kind IS NOT NULL
            AND companion_kind IN ('MEMBER', 'GUEST', 'WITHDRAWN_MEMBER')
            AND companion_identity_key IS NOT NULL
            AND CHAR_LENGTH(BTRIM(companion_identity_key)) > 0
            AND companion_display_name IS NOT NULL
            AND CHAR_LENGTH(BTRIM(companion_display_name)) > 0
            AND companion_share_display_name IS NOT NULL
            AND CHAR_LENGTH(BTRIM(companion_share_display_name)) > 0
            AND companion_completed_settlement_count IS NOT NULL
            AND companion_completed_settlement_count BETWEEN 1 AND completed_settlement_count
            AND companion_total_own_amount IS NOT NULL
            AND companion_total_own_amount BETWEEN 0 AND total_own_amount
            AND (
                (
                    companion_kind = 'MEMBER'
                    AND companion_identity_key ~ '^MEMBER:[1-9][0-9]{0,18}$'
                    AND companion_share_display_name ~ '^[a-z0-9._]{4,20}$'
                )
                OR (
                    companion_kind = 'GUEST'
                    AND companion_identity_key LIKE 'GUEST:%'
                    AND CHAR_LENGTH(companion_identity_key) > 6
                    AND companion_avatar_file_name IS NULL
                )
                OR (
                    companion_kind = 'WITHDRAWN_MEMBER'
                    AND companion_identity_key ~ '^MEMBER:[1-9][0-9]{0,18}$'
                    AND companion_display_name = '탈퇴한 회원'
                    AND companion_share_display_name = '탈퇴한 회원'
                    AND companion_avatar_file_name IS NULL
                )
            )
        )
    ),
    CONSTRAINT ck_monthly_recaps_avatar CHECK (
        companion_avatar_file_name IS NULL
        OR companion_avatar_file_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
    )
);

CREATE FUNCTION guard_monthly_recap_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Monthly recap snapshots are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_monthly_recap_snapshot_immutable
    BEFORE UPDATE ON monthly_recaps
    FOR EACH ROW EXECUTE FUNCTION guard_monthly_recap_snapshot();

-- Inserted by the first enabled generator, not by deployment-time CURRENT_DATE.
CREATE TABLE recap_generation_state (
    id SMALLINT NOT NULL DEFAULT 1,
    first_month DATE NOT NULL,
    registered_through_month DATE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_recap_generation_state PRIMARY KEY (id),
    CONSTRAINT ck_recap_generation_state_singleton CHECK (id = 1),
    CONSTRAINT ck_recap_generation_state_first_month CHECK (
        EXTRACT(DAY FROM first_month) = 1
        AND EXTRACT(YEAR FROM first_month) BETWEEN 1 AND 9999
    ),
    CONSTRAINT ck_recap_generation_state_registered_month CHECK (
        registered_through_month IS NULL
        OR (
            registered_through_month >= first_month
            AND EXTRACT(DAY FROM registered_through_month) = 1
            AND EXTRACT(YEAR FROM registered_through_month) BETWEEN 1 AND 9999
        )
    )
);

CREATE FUNCTION guard_recap_generation_scope()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Recap generation scope cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.first_month IS DISTINCT FROM OLD.first_month
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (OLD.registered_through_month IS NOT NULL AND (
            NEW.registered_through_month IS NULL
            OR NEW.registered_through_month < OLD.registered_through_month
        )) THEN
        RAISE EXCEPTION 'Recap generation scope cannot be reset' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_recap_generation_scope_guard
    BEFORE UPDATE OR DELETE ON recap_generation_state
    FOR EACH ROW EXECUTE FUNCTION guard_recap_generation_scope();

CREATE TABLE recap_generation_jobs (
    id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    month_start DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_token UUID,
    lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    reserved_image_file_name VARCHAR(100),
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(64),
    skip_reason VARCHAR(40),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_recap_generation_jobs PRIMARY KEY (id),
    CONSTRAINT fk_recap_generation_jobs_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_recap_generation_jobs_owner_month UNIQUE (owner_user_id, month_start),
    CONSTRAINT ck_recap_generation_jobs_month CHECK (
        EXTRACT(DAY FROM month_start) = 1
        AND EXTRACT(YEAR FROM month_start) BETWEEN 1 AND 9999
    ),
    CONSTRAINT ck_recap_generation_jobs_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_recap_generation_jobs_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_recap_generation_jobs_skip_reason CHECK (
        skip_reason IS NULL OR skip_reason IN ('NO_COMPLETED_SETTLEMENTS', 'OWNER_INACTIVE')
    ),
    CONSTRAINT ck_recap_generation_jobs_reserved_image CHECK (
        reserved_image_file_name IS NULL
        OR reserved_image_file_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
    ),
    CONSTRAINT ck_recap_generation_jobs_lifecycle CHECK (
        (
            status = 'PROCESSING' AND attempt_count > 0
            AND next_attempt_at IS NULL AND processing_started_at IS NOT NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND lease_until > processing_started_at
            AND completed_at IS NULL AND skip_reason IS NULL
        )
        OR (
            status IN ('PENDING', 'RETRY', 'COMPLETED', 'SKIPPED')
            AND processing_started_at IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND reserved_image_file_name IS NULL
            AND (
                (
                    status = 'PENDING' AND attempt_count = 0 AND next_attempt_at IS NOT NULL
                    AND completed_at IS NULL AND last_failure_code IS NULL AND skip_reason IS NULL
                )
                OR (
                    status = 'RETRY' AND attempt_count > 0 AND next_attempt_at IS NOT NULL
                    AND completed_at IS NULL AND last_failure_code IS NOT NULL AND skip_reason IS NULL
                )
                OR (
                    status = 'COMPLETED' AND attempt_count > 0 AND next_attempt_at IS NULL
                    AND completed_at IS NOT NULL AND last_failure_code IS NULL AND skip_reason IS NULL
                )
                OR (
                    status = 'SKIPPED' AND attempt_count > 0 AND next_attempt_at IS NULL
                    AND completed_at IS NOT NULL AND last_failure_code IS NULL AND skip_reason IS NOT NULL
                )
            )
        )
    )
);

CREATE INDEX idx_recap_generation_jobs_ready
    ON recap_generation_jobs (month_start, next_attempt_at, owner_user_id, id)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_recap_generation_jobs_processing
    ON recap_generation_jobs (lease_until, id) WHERE status = 'PROCESSING';
CREATE INDEX idx_recap_generation_jobs_owner_unfinished
    ON recap_generation_jobs (owner_user_id, month_start)
    WHERE status IN ('PENDING', 'RETRY', 'PROCESSING');
CREATE UNIQUE INDEX uk_recap_generation_jobs_reserved_image
    ON recap_generation_jobs (reserved_image_file_name)
    WHERE reserved_image_file_name IS NOT NULL;

-- No owner FK: cleanup must survive deleting the owner, snapshot or generation job.
CREATE TABLE recap_image_cleanup_tasks (
    id UUID NOT NULL,
    file_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_token UUID,
    lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(64),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_recap_image_cleanup_tasks PRIMARY KEY (id),
    CONSTRAINT uk_recap_image_cleanup_tasks_file UNIQUE (file_name),
    CONSTRAINT ck_recap_image_cleanup_tasks_file CHECK (
        file_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
    ),
    CONSTRAINT ck_recap_image_cleanup_tasks_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_recap_image_cleanup_tasks_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_recap_image_cleanup_tasks_lifecycle CHECK (
        (
            status = 'PROCESSING' AND attempt_count > 0
            AND next_attempt_at IS NULL AND processing_started_at IS NOT NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND lease_until > processing_started_at
        )
        OR (
            status IN ('PENDING', 'RETRY') AND next_attempt_at IS NOT NULL
            AND processing_started_at IS NULL AND lease_token IS NULL AND lease_until IS NULL
            AND (
                (status = 'PENDING' AND attempt_count = 0 AND last_failure_code IS NULL)
                OR (status = 'RETRY' AND attempt_count > 0 AND last_failure_code IS NOT NULL)
            )
        )
    )
);

CREATE INDEX idx_recap_image_cleanup_tasks_ready
    ON recap_image_cleanup_tasks (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_recap_image_cleanup_tasks_processing
    ON recap_image_cleanup_tasks (lease_until, id) WHERE status = 'PROCESSING';
CREATE UNIQUE INDEX uk_monthly_recaps_avatar
    ON monthly_recaps (companion_avatar_file_name) WHERE companion_avatar_file_name IS NOT NULL;

CREATE INDEX idx_settlements_recap_completed_month
    ON settlements (updated_at, id) WHERE status = 'COMPLETED' AND deleted_at IS NULL;
CREATE INDEX idx_settlement_participants_recap_user
    ON settlement_participants (linked_user_id, settlement_id) WHERE linked_user_id IS NOT NULL;

COMMENT ON TABLE monthly_recaps IS
    'Immutable owner/month recap. DELETE is reserved for owner withdrawal; original data never refreshes a snapshot.';
COMMENT ON COLUMN monthly_recaps.month_start IS 'First calendar day of the recap month in Asia/Seoul.';
COMMENT ON COLUMN monthly_recaps.generated_at IS 'UTC LocalDateTime; the target KST month has already ended.';
COMMENT ON COLUMN monthly_recaps.companion_identity_key IS
    'MEMBER:<saved user ID> or GUEST:<normalized name>; internal identity, never an API field.';
COMMENT ON COLUMN monthly_recaps.companion_share_display_name IS
    'Fixed publicId for a member, masked guest name, or withdrawal label. No raw name in the sharing response.';
COMMENT ON TABLE recap_generation_state IS 'Persistent first eligible month and fully registered month cursor.';
COMMENT ON COLUMN recap_generation_jobs.lease_token IS
    'Claim fencing token. Renew and finish by CAS; snapshot INSERT and COMPLETED transition share one transaction.';
COMMENT ON COLUMN recap_generation_jobs.reserved_image_file_name IS
    'Claim-owned unique copy path; enqueue cleanup before releasing a failed/expired claim or deleting an owner.';
COMMENT ON TABLE recap_image_cleanup_tasks IS
    'Durable file deletion intent. Check snapshot and active claim references before deletion; remove the task after success.';
