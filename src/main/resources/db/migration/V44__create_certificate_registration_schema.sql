-- PC-first certificate registration metadata only. Secrets belong to the Connector.
CREATE TABLE certificate_registration_sessions (
    id UUID NOT NULL,
    registration_version BIGINT NOT NULL DEFAULT 1,
    organization_code VARCHAR(4) NOT NULL,
    status VARCHAR(30) NOT NULL,
    pc_capability_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    upload_ref UUID,
    connection_ref UUID,
    claim_generation BIGINT NOT NULL DEFAULT 0,
    qr_token_hash VARCHAR(64),
    qr_expires_at TIMESTAMP(6) WITHOUT TIME ZONE,
    current_claim_id UUID,
    approval_id UUID,
    approved_claim_id UUID,
    approved_user_id BIGINT,
    ownership_verification_ref UUID,
    approved_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_certificate_registration_sessions PRIMARY KEY (id),
    CONSTRAINT uk_certificate_sessions_pc_capability UNIQUE (pc_capability_hash),
    CONSTRAINT uk_certificate_sessions_upload UNIQUE (upload_ref),
    CONSTRAINT uk_certificate_sessions_connection UNIQUE (connection_ref),
    CONSTRAINT uk_certificate_sessions_qr UNIQUE (qr_token_hash),
    CONSTRAINT uk_certificate_sessions_approval UNIQUE (approval_id),
    CONSTRAINT uk_certificate_sessions_approval_target
        UNIQUE (id, approval_id, connection_ref, registration_version),
    CONSTRAINT uk_certificate_sessions_upload_target UNIQUE (id, upload_ref),
    CONSTRAINT fk_certificate_sessions_approved_user
        FOREIGN KEY (approved_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_certificate_sessions_version
        CHECK (registration_version > 0 AND claim_generation >= 0),
    CONSTRAINT ck_certificate_sessions_organization CHECK (organization_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_certificate_sessions_pc_hash CHECK (pc_capability_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_certificate_sessions_status CHECK (status IN (
        'CREATED', 'SUBMITTED', 'REGISTERING', 'READY_FOR_CLAIM', 'CLAIM_RESERVED',
        'APPROVED_BINDING', 'BOUND', 'FAILED', 'UNKNOWN', 'BINDING_UNKNOWN', 'EXPIRED', 'CANCELLED'
    )),
    CONSTRAINT ck_certificate_sessions_upload_state CHECK (
        status NOT IN ('SUBMITTED', 'REGISTERING', 'READY_FOR_CLAIM', 'CLAIM_RESERVED',
                       'APPROVED_BINDING', 'BOUND', 'UNKNOWN', 'BINDING_UNKNOWN')
        OR upload_ref IS NOT NULL
    ),
    CONSTRAINT ck_certificate_sessions_connection_state CHECK (
        status NOT IN ('READY_FOR_CLAIM', 'CLAIM_RESERVED', 'APPROVED_BINDING', 'BOUND', 'BINDING_UNKNOWN')
        OR connection_ref IS NOT NULL
    ),
    CONSTRAINT ck_certificate_sessions_qr CHECK (
        (status = 'READY_FOR_CLAIM' AND qr_token_hash IS NOT NULL
            AND qr_token_hash ~ '^[0-9a-f]{64}$' AND qr_expires_at IS NOT NULL
            AND qr_expires_at > created_at AND qr_expires_at <= expires_at AND claim_generation > 0)
        OR (status <> 'READY_FOR_CLAIM' AND qr_token_hash IS NULL AND qr_expires_at IS NULL)
    ),
    CONSTRAINT ck_certificate_sessions_current_claim CHECK (
        (status = 'CLAIM_RESERVED' AND current_claim_id IS NOT NULL AND claim_generation > 0)
        OR (status <> 'CLAIM_RESERVED' AND current_claim_id IS NULL)
    ),
    CONSTRAINT ck_certificate_sessions_approval CHECK (
        (status IN ('APPROVED_BINDING', 'BOUND', 'BINDING_UNKNOWN')
            AND num_nonnulls(approval_id, approved_claim_id, approved_user_id,
                            ownership_verification_ref, approved_at) = 5
            AND claim_generation > 0 AND approved_at >= created_at AND approved_at < expires_at)
        OR (status NOT IN ('APPROVED_BINDING', 'BOUND', 'BINDING_UNKNOWN')
            AND num_nonnulls(approval_id, approved_claim_id, approved_user_id,
                            ownership_verification_ref, approved_at) = 0)
    ),
    CONSTRAINT ck_certificate_sessions_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'REGISTRATION_REJECTED', 'REGISTRATION_UNCERTAIN', 'ADDITIONAL_AUTH_REQUIRED',
            'UPLOAD_EXPIRED', 'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED',
            'BINDING_UNCERTAIN', 'PROVIDER_UNAVAILABLE', 'CONTRACT_CONFLICT'
        )
    ),
    CONSTRAINT ck_certificate_sessions_timestamps
        CHECK (expires_at > created_at AND updated_at >= created_at)
);

CREATE UNIQUE INDEX uk_certificate_sessions_user_binding
    ON certificate_registration_sessions (approved_user_id)
    WHERE status IN ('APPROVED_BINDING', 'BINDING_UNKNOWN', 'BOUND');
CREATE INDEX idx_certificate_sessions_expiry
    ON certificate_registration_sessions (expires_at, id)
    WHERE status NOT IN ('APPROVED_BINDING', 'BINDING_UNKNOWN', 'BOUND');

CREATE TABLE certificate_registration_claims (
    id UUID NOT NULL,
    session_id UUID NOT NULL,
    claimant_user_id BIGINT NOT NULL,
    claim_generation BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    verification_code_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    finished_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_certificate_registration_claims PRIMARY KEY (id),
    CONSTRAINT fk_certificate_claims_session
        FOREIGN KEY (session_id) REFERENCES certificate_registration_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_certificate_claims_user
        FOREIGN KEY (claimant_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_certificate_claims_idempotency UNIQUE (session_id, claimant_user_id, idempotency_key),
    CONSTRAINT uk_certificate_claims_generation UNIQUE (session_id, claim_generation),
    CONSTRAINT uk_certificate_claims_target UNIQUE (session_id, id, claim_generation),
    CONSTRAINT uk_certificate_claims_approval_target UNIQUE (session_id, id, claimant_user_id, claim_generation),
    CONSTRAINT ck_certificate_claims_generation CHECK (claim_generation > 0),
    CONSTRAINT ck_certificate_claims_code_hash CHECK (verification_code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_certificate_claims_status
        CHECK (status IN ('RESERVED', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_certificate_claims_lifecycle CHECK (
        expires_at > created_at
        AND ((status = 'RESERVED' AND finished_at IS NULL)
            OR (status <> 'RESERVED' AND finished_at IS NOT NULL AND finished_at >= created_at))
        AND (status <> 'APPROVED' OR finished_at < expires_at)
    )
);

CREATE UNIQUE INDEX uk_certificate_claims_reserved_session
    ON certificate_registration_claims (session_id) WHERE status = 'RESERVED';
CREATE INDEX idx_certificate_claims_reserved_expiry
    ON certificate_registration_claims (expires_at, session_id) WHERE status = 'RESERVED';
CREATE INDEX idx_certificate_claims_user ON certificate_registration_claims (claimant_user_id, created_at DESC);

CREATE FUNCTION guard_certificate_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.id, NEW.session_id, NEW.claimant_user_id, NEW.claim_generation, NEW.idempotency_key,
               NEW.verification_code_hash, NEW.created_at, NEW.expires_at)
            IS DISTINCT FROM
           ROW(OLD.id, OLD.session_id, OLD.claimant_user_id, OLD.claim_generation, OLD.idempotency_key,
               OLD.verification_code_hash, OLD.created_at, OLD.expires_at)
           OR (OLD.status = 'APPROVED' AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'Certificate claim identity and approved facts are immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.expires_at > (SELECT expires_at FROM certificate_registration_sessions WHERE id = NEW.session_id) THEN
        RAISE EXCEPTION 'Certificate claim exceeds session deadline' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_claim_guard
    BEFORE INSERT OR UPDATE ON certificate_registration_claims
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_claim();

-- Deferred to allow session and claim transitions in the same transaction.
ALTER TABLE certificate_registration_sessions
    ADD CONSTRAINT fk_certificate_sessions_current_claim
        FOREIGN KEY (id, current_claim_id, claim_generation)
        REFERENCES certificate_registration_claims (session_id, id, claim_generation)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_certificate_sessions_approved_claim
        FOREIGN KEY (id, approved_claim_id, approved_user_id, claim_generation)
        REFERENCES certificate_registration_claims (session_id, id, claimant_user_id, claim_generation)
        DEFERRABLE INITIALLY DEFERRED;

-- Validate the final state from either side of the relationship, not a queued row snapshot.
CREATE FUNCTION validate_certificate_current_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target_session_id UUID;
    target_claim_id UUID;
    current_claim_status VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'certificate_registration_sessions' THEN
        target_session_id := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        target_session_id := OLD.session_id;
    ELSE
        target_session_id := NEW.session_id;
    END IF;

    -- Serialize cross-table checks; callers should acquire locks session-first.
    SELECT session.current_claim_id INTO target_claim_id
    FROM certificate_registration_sessions AS session
    WHERE session.id = target_session_id
    FOR UPDATE;

    -- A deleted session or a completed transition no longer has a current claim.
    IF target_claim_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT claim.status INTO current_claim_status
    FROM certificate_registration_claims AS claim
    WHERE claim.id = target_claim_id AND claim.session_id = target_session_id
    FOR UPDATE;

    IF current_claim_status IS DISTINCT FROM 'RESERVED' THEN
        RAISE EXCEPTION 'Certificate current claim must remain reserved' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_certificate_current_claim_session
    AFTER INSERT OR UPDATE ON certificate_registration_sessions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_current_claim();
CREATE CONSTRAINT TRIGGER trg_certificate_current_claim_change
    AFTER INSERT OR UPDATE OR DELETE ON certificate_registration_claims
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_current_claim();

CREATE FUNCTION guard_certificate_approval() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.approval_id IS NOT NULL AND ROW(
        NEW.approval_id, NEW.approved_claim_id, NEW.approved_user_id, NEW.ownership_verification_ref,
        NEW.approved_at, NEW.registration_version, NEW.claim_generation, NEW.connection_ref, NEW.organization_code
    ) IS DISTINCT FROM ROW(
        OLD.approval_id, OLD.approved_claim_id, OLD.approved_user_id, OLD.ownership_verification_ref,
        OLD.approved_at, OLD.registration_version, OLD.claim_generation, OLD.connection_ref, OLD.organization_code
    ) THEN
        RAISE EXCEPTION 'Certificate approval facts are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'BOUND' AND NEW.status <> 'BOUND' THEN
        RAISE EXCEPTION 'A bound certificate session cannot be reopened' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_approval_immutable
    BEFORE UPDATE ON certificate_registration_sessions
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_approval();

-- Check the final transaction state, allowing either session/claim update order.
CREATE FUNCTION validate_certificate_approval_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM certificate_registration_sessions AS session
        JOIN certificate_registration_claims AS claim ON claim.id = session.approved_claim_id
        WHERE session.id = NEW.id AND session.approval_id IS NOT NULL
            AND (claim.status <> 'APPROVED' OR claim.finished_at IS DISTINCT FROM session.approved_at
                 OR claim.expires_at > session.expires_at)
    ) THEN
        RAISE EXCEPTION 'Certificate approval requires the matching completed claim' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_certificate_approval_claim
    AFTER INSERT OR UPDATE ON certificate_registration_sessions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_approval_claim();

CREATE TABLE certificate_command_outbox (
    message_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_version BIGINT NOT NULL,
    command_type VARCHAR(30) NOT NULL,
    upload_ref UUID,
    approval_id UUID,
    connection_ref UUID,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_token UUID,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_expires_at TIMESTAMP(6) WITHOUT TIME ZONE,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(30),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_certificate_command_outbox PRIMARY KEY (message_id),
    CONSTRAINT fk_certificate_outbox_session
        FOREIGN KEY (session_id) REFERENCES certificate_registration_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_certificate_outbox_upload
        FOREIGN KEY (session_id, upload_ref)
        REFERENCES certificate_registration_sessions (id, upload_ref),
    CONSTRAINT fk_certificate_outbox_approval
        FOREIGN KEY (session_id, approval_id, connection_ref, session_version)
        REFERENCES certificate_registration_sessions (id, approval_id, connection_ref, registration_version),
    CONSTRAINT uk_certificate_outbox_command UNIQUE (session_id, command_type, session_version),
    CONSTRAINT ck_certificate_outbox_command CHECK (
        (command_type = 'REGISTER' AND upload_ref IS NOT NULL AND approval_id IS NULL AND connection_ref IS NULL)
        OR (command_type = 'BIND' AND upload_ref IS NULL AND approval_id IS NOT NULL AND connection_ref IS NOT NULL)
        OR (command_type = 'CANCEL' AND upload_ref IS NULL AND approval_id IS NULL AND connection_ref IS NULL)
    ),
    CONSTRAINT ck_certificate_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_certificate_outbox_attempt CHECK (
        session_version > 0 AND attempt_count >= 0 AND lease_generation >= 0
        AND ((status = 'PENDING' AND attempt_count = 0 AND lease_generation = 0)
            OR (status <> 'PENDING' AND attempt_count > 0 AND lease_generation > 0))
    ),
    CONSTRAINT ck_certificate_outbox_lifecycle CHECK (
        (status IN ('PENDING', 'RETRY') AND next_attempt_at IS NOT NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, completed_at) = 0)
        OR (status = 'PROCESSING' AND next_attempt_at IS NULL AND completed_at IS NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at) = 3
            AND lease_expires_at > processing_started_at)
        OR (status IN ('PUBLISHED', 'FAILED') AND completed_at IS NOT NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, next_attempt_at) = 0)
    ),
    CONSTRAINT ck_certificate_outbox_failure CHECK (
        (status IN ('PENDING', 'PUBLISHED') AND last_failure_code IS NULL)
        OR (status IN ('RETRY', 'FAILED') AND last_failure_code IS NOT NULL)
        OR status = 'PROCESSING'
    ),
    CONSTRAINT ck_certificate_outbox_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'BROKER_UNAVAILABLE', 'PUBLISH_TIMEOUT', 'LEASE_EXPIRED', 'CONTRACT_INVALID', 'TARGET_UNAVAILABLE'
        )
    ),
    CONSTRAINT ck_certificate_outbox_timestamps CHECK (
        updated_at >= created_at
        AND (processing_started_at IS NULL OR processing_started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX idx_certificate_outbox_ready
    ON certificate_command_outbox (next_attempt_at, created_at, message_id) WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_certificate_outbox_processing
    ON certificate_command_outbox (lease_expires_at, message_id) WHERE status = 'PROCESSING';

-- Retained independently so a cleaned session does not erase replay deduplication.
CREATE TABLE certificate_result_inbox (
    message_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_version BIGINT NOT NULL,
    message_type VARCHAR(30) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    disposition VARCHAR(20) NOT NULL,
    received_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    processed_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_certificate_result_inbox PRIMARY KEY (message_id),
    CONSTRAINT ck_certificate_inbox_version CHECK (session_version > 0),
    CONSTRAINT ck_certificate_inbox_type CHECK (message_type IN (
        'REGISTERED', 'REGISTRATION_FAILED', 'REGISTRATION_UNKNOWN', 'BOUND', 'BINDING_UNKNOWN', 'CANCELLED'
    )),
    CONSTRAINT ck_certificate_inbox_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_certificate_inbox_disposition CHECK (disposition IN ('APPLIED', 'IGNORED')),
    CONSTRAINT ck_certificate_inbox_timestamps CHECK (processed_at >= received_at)
);
CREATE INDEX idx_certificate_inbox_session ON certificate_result_inbox (session_id, session_version);
CREATE INDEX idx_certificate_inbox_retention ON certificate_result_inbox (processed_at, message_id);

COMMENT ON TABLE certificate_registration_sessions IS
    'Pending PC registration and immutable approval facts; no certificates, passwords, account numbers or connectedId.';
COMMENT ON COLUMN certificate_registration_sessions.ownership_verification_ref IS
    'Reference to independently verified financial ownership; PC consent or self-entered name/date of birth is insufficient.';
COMMENT ON COLUMN certificate_registration_sessions.expires_at IS
    'Deadline before approval only. Never expire or orphan-clean APPROVED_BINDING, BINDING_UNKNOWN or BOUND by this TTL.';
COMMENT ON COLUMN certificate_registration_sessions.pc_capability_hash IS
    'SHA-256 of a separate PC-only capability. Never include the capability in the QR.';
COMMENT ON COLUMN certificate_registration_sessions.upload_ref IS
    'Opaque Connector upload reference; keep reference after Connector deletes its short-lived encrypted secret.';
COMMENT ON TABLE certificate_command_outbox IS
    'Typed references only. Relay must fence completion with message_id, lease_token and lease_generation.';
COMMENT ON TABLE certificate_result_inbox IS
    'Message deduplication after session cleanup; same message_id with a different payload_hash is a contract conflict.';
