-- API-resident certificate provider storage; no plaintext credentials or message bodies.
CREATE TABLE certificate_upload_secrets (
    upload_ref UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    upload_capability_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    encrypted_payload BYTEA,
    wrapped_data_key BYTEA,
    encryption_key_id VARCHAR(255),
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    stored_at TIMESTAMP(6) WITHOUT TIME ZONE,
    cleared_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_certificate_upload_session FOREIGN KEY (session_id, upload_ref)
        REFERENCES certificate_registration_sessions (id, upload_ref) ON DELETE CASCADE,
    CONSTRAINT ck_certificate_upload_hash CHECK (upload_capability_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_certificate_upload_status CHECK (status IN ('ISSUED', 'STORED', 'CONSUMED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_certificate_upload_payload CHECK (
        (status = 'STORED' AND num_nonnulls(encrypted_payload, wrapped_data_key, encryption_key_id) = 3
            AND octet_length(encrypted_payload) BETWEEN 29 AND 524288
            AND octet_length(wrapped_data_key) BETWEEN 32 AND 4096 AND length(btrim(encryption_key_id)) > 0)
        OR (status <> 'STORED' AND num_nonnulls(encrypted_payload, wrapped_data_key, encryption_key_id) = 0)
    ),
    CONSTRAINT ck_certificate_upload_lifecycle CHECK (
        (status = 'ISSUED' AND stored_at IS NULL AND cleared_at IS NULL)
        OR (status = 'STORED' AND stored_at IS NOT NULL AND cleared_at IS NULL)
        OR (status = 'CONSUMED' AND stored_at IS NOT NULL AND cleared_at IS NOT NULL)
        OR (status IN ('EXPIRED', 'CANCELLED') AND cleared_at IS NOT NULL)
    ),
    CONSTRAINT ck_certificate_upload_timestamps CHECK (
        expires_at > created_at AND updated_at >= created_at
        AND (stored_at IS NULL OR (stored_at >= created_at AND stored_at < expires_at))
        AND (cleared_at IS NULL OR cleared_at >= coalesce(stored_at, created_at))
        AND (status <> 'EXPIRED' OR cleared_at >= expires_at)
    )
);
CREATE INDEX idx_certificate_upload_expiry ON certificate_upload_secrets (expires_at, upload_ref)
    WHERE status IN ('ISSUED', 'STORED');

CREATE FUNCTION guard_certificate_upload_secret() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.status <> 'ISSUED' THEN
        RAISE EXCEPTION 'Certificate upload must start as an unused capability' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.upload_ref, NEW.session_id, NEW.upload_capability_hash, NEW.expires_at, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.upload_ref, OLD.session_id, OLD.upload_capability_hash, OLD.expires_at, OLD.created_at)
            OR (OLD.status IN ('CONSUMED', 'EXPIRED', 'CANCELLED') AND NEW IS DISTINCT FROM OLD)
            OR (OLD.status = 'STORED' AND NEW.status = 'ISSUED')
            OR (NEW.status = 'CONSUMED' AND OLD.status <> 'STORED')
            OR (OLD.stored_at IS NOT NULL AND NEW.stored_at IS DISTINCT FROM OLD.stored_at)
            OR (OLD.status = 'STORED' AND NEW.status = 'STORED'
                AND ROW(NEW.encrypted_payload, NEW.wrapped_data_key, NEW.encryption_key_id)
                    IS DISTINCT FROM ROW(OLD.encrypted_payload, OLD.wrapped_data_key, OLD.encryption_key_id)) THEN
            RAISE EXCEPTION 'Certificate upload identity or consumed material is immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.expires_at > (SELECT expires_at FROM certificate_registration_sessions WHERE id = NEW.session_id) THEN
        RAISE EXCEPTION 'Certificate upload exceeds session deadline' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_upload_secret_guard BEFORE INSERT OR UPDATE ON certificate_upload_secrets
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_upload_secret();

-- A receipt is the durable job: acknowledge Kafka only after this row is committed.
-- No session/user FK: replay deduplication must survive session/user cleanup.
CREATE TABLE certificate_provider_command_inbox (
    message_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    session_version BIGINT NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    organization_code VARCHAR(4) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    upload_ref UUID,
    connection_ref UUID,
    approval_id UUID,
    claim_id UUID,
    claim_generation BIGINT,
    user_id BIGINT,
    ownership_verification_ref UUID,
    deadline_at TIMESTAMP(6) WITHOUT TIME ZONE,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_token UUID,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_expires_at TIMESTAMP(6) WITHOUT TIME ZONE,
    provider_request_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(40),
    received_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uk_certificate_provider_command UNIQUE (session_id, command_type, session_version),
    CONSTRAINT ck_certificate_provider_command_header CHECK (
        session_version > 0 AND organization_code ~ '^[0-9]{4}$' AND payload_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_certificate_provider_command_type CHECK (
        (command_type = 'REGISTER' AND upload_ref IS NOT NULL AND deadline_at IS NOT NULL
            AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 0)
        OR (command_type = 'BIND' AND upload_ref IS NULL AND deadline_at IS NULL
            AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 6
            AND claim_generation > 0 AND user_id > 0)
        OR (command_type = 'CANCEL' AND deadline_at IS NULL
            AND num_nonnulls(upload_ref, connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 0)
    ),
    CONSTRAINT ck_certificate_provider_command_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'IGNORED')
    ),
    CONSTRAINT ck_certificate_provider_command_attempt CHECK (
        attempt_count >= 0 AND lease_generation >= 0
        AND ((status IN ('PENDING', 'IGNORED') AND attempt_count = 0 AND lease_generation = 0)
            OR (status NOT IN ('PENDING', 'IGNORED') AND attempt_count > 0 AND lease_generation > 0))
    ),
    CONSTRAINT ck_certificate_provider_command_lifecycle CHECK (
        (status IN ('PENDING', 'RETRY') AND next_attempt_at IS NOT NULL AND provider_request_started_at IS NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, completed_at) = 0)
        OR (status = 'PROCESSING' AND next_attempt_at IS NULL AND completed_at IS NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at) = 3 AND lease_expires_at > processing_started_at)
        OR (status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'IGNORED') AND completed_at IS NOT NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, next_attempt_at) = 0)
    ),
    CONSTRAINT ck_certificate_provider_command_failure CHECK (
        (status IN ('PENDING', 'SUCCEEDED') AND last_failure_code IS NULL)
        OR (status IN ('RETRY', 'FAILED', 'UNKNOWN', 'IGNORED') AND last_failure_code IS NOT NULL)
        OR status = 'PROCESSING'
    ),
    CONSTRAINT ck_certificate_provider_command_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'PROVIDER_UNAVAILABLE', 'REGISTRATION_REJECTED', 'REGISTRATION_UNCERTAIN', 'UPLOAD_EXPIRED',
            'ADDITIONAL_AUTH_REQUIRED', 'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED',
            'BINDING_UNCERTAIN', 'CONTRACT_CONFLICT', 'SESSION_UNAVAILABLE', 'SESSION_CANCELLED', 'LEASE_EXPIRED'
        )
    ),
    CONSTRAINT ck_certificate_provider_command_request CHECK (
        (command_type <> 'BIND' OR provider_request_started_at IS NULL)
        AND (status <> 'UNKNOWN' OR command_type = 'BIND' OR provider_request_started_at IS NOT NULL)
    ),
    CONSTRAINT ck_certificate_provider_command_timestamps CHECK (
        updated_at >= received_at
        AND (next_attempt_at IS NULL OR next_attempt_at >= received_at)
        AND (processing_started_at IS NULL OR processing_started_at >= received_at)
        AND (provider_request_started_at IS NULL OR provider_request_started_at >= received_at)
        AND (completed_at IS NULL OR completed_at >= received_at)
    )
);
CREATE INDEX idx_certificate_provider_job_ready ON certificate_provider_command_inbox (next_attempt_at, message_id)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_certificate_provider_job_lease ON certificate_provider_command_inbox (lease_expires_at, message_id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_certificate_provider_command_retention ON certificate_provider_command_inbox (completed_at, message_id)
    WHERE status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'IGNORED');

CREATE FUNCTION guard_certificate_provider_command() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('PENDING', 'IGNORED') OR NEW.provider_request_started_at IS NOT NULL THEN
            RAISE EXCEPTION 'Certificate command must start as a receipt' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.message_id, NEW.session_id, NEW.session_version, NEW.command_type, NEW.organization_code,
           NEW.payload_hash, NEW.upload_ref, NEW.connection_ref, NEW.approval_id, NEW.claim_id, NEW.claim_generation,
           NEW.user_id, NEW.ownership_verification_ref, NEW.deadline_at, NEW.received_at)
        IS DISTINCT FROM
       ROW(OLD.message_id, OLD.session_id, OLD.session_version, OLD.command_type, OLD.organization_code,
           OLD.payload_hash, OLD.upload_ref, OLD.connection_ref, OLD.approval_id, OLD.claim_id, OLD.claim_generation,
           OLD.user_id, OLD.ownership_verification_ref, OLD.deadline_at, OLD.received_at)
        OR (OLD.provider_request_started_at IS NOT NULL
            AND NEW.provider_request_started_at IS DISTINCT FROM OLD.provider_request_started_at)
        OR NEW.lease_generation < OLD.lease_generation OR NEW.attempt_count < OLD.attempt_count
        OR (OLD.status IN ('SUCCEEDED', 'FAILED', 'IGNORED') AND NEW IS DISTINCT FROM OLD)
        OR (OLD.status = 'UNKNOWN' AND NEW.status NOT IN ('UNKNOWN', 'SUCCEEDED', 'FAILED')) THEN
        RAISE EXCEPTION 'Certificate command identity or provider request fact is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.provider_request_started_at IS NULL AND NEW.provider_request_started_at IS NOT NULL
        AND (OLD.status <> 'PROCESSING' OR NEW.status <> 'PROCESSING'
            OR NEW.provider_request_started_at < OLD.processing_started_at
            OR NEW.provider_request_started_at >= OLD.lease_expires_at) THEN
        RAISE EXCEPTION 'Certificate request start requires the current unexpired execution lease' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PROCESSING' AND OLD.status <> 'PROCESSING'
        AND (NEW.lease_generation <> OLD.lease_generation + 1 OR NEW.attempt_count <> OLD.attempt_count + 1) THEN
        RAISE EXCEPTION 'Certificate job claim must advance its fencing generation' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'PROCESSING' AND NEW.status = 'PROCESSING'
        AND ROW(NEW.lease_token, NEW.lease_generation, NEW.attempt_count, NEW.processing_started_at)
            IS DISTINCT FROM ROW(OLD.lease_token, OLD.lease_generation, OLD.attempt_count, OLD.processing_started_at) THEN
        RAISE EXCEPTION 'Certificate job lease cannot be replaced in place' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_provider_command_guard BEFORE INSERT OR UPDATE ON certificate_provider_command_inbox
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_provider_command();

-- The owner FK prevents a provider connection from referencing another user's legacy connection.
ALTER TABLE external_connections ADD CONSTRAINT uk_external_connections_id_user UNIQUE (id, user_id);
CREATE TABLE certificate_provider_connections (
    connection_ref UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    session_version BIGINT NOT NULL,
    organization_code VARCHAR(4) NOT NULL,
    registration_message_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    encrypted_payload BYTEA,
    wrapped_data_key BYTEA,
    encryption_key_id VARCHAR(255),
    approval_id UUID,
    owner_user_id BIGINT,
    ownership_verification_ref UUID,
    external_connection_id BIGINT UNIQUE,
    bound_at TIMESTAMP(6) WITHOUT TIME ZONE,
    cleared_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_certificate_provider_connection_session FOREIGN KEY (session_id)
        REFERENCES certificate_registration_sessions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_provider_connection_registration FOREIGN KEY (registration_message_id)
        REFERENCES certificate_provider_command_inbox (message_id) ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_provider_connection_approval FOREIGN KEY (session_id, approval_id, connection_ref, session_version)
        REFERENCES certificate_registration_sessions (id, approval_id, connection_ref, registration_version),
    CONSTRAINT fk_certificate_provider_connection_owner FOREIGN KEY (external_connection_id, owner_user_id)
        REFERENCES external_connections (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_certificate_provider_connection_scope CHECK (session_version > 0 AND organization_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_certificate_provider_connection_status CHECK (status IN ('READY', 'BOUND', 'REVOKED', 'DISCARDED', 'DELETE_UNKNOWN')),
    CONSTRAINT ck_certificate_provider_connection_secret CHECK (
        (status IN ('READY', 'BOUND', 'DELETE_UNKNOWN') AND num_nonnulls(encrypted_payload, wrapped_data_key, encryption_key_id) = 3
            AND octet_length(encrypted_payload) BETWEEN 29 AND 65536
            AND octet_length(wrapped_data_key) BETWEEN 32 AND 4096 AND length(btrim(encryption_key_id)) > 0 AND cleared_at IS NULL)
        OR (status IN ('REVOKED', 'DISCARDED') AND num_nonnulls(encrypted_payload, wrapped_data_key, encryption_key_id) = 0
            AND cleared_at IS NOT NULL)
    ),
    CONSTRAINT ck_certificate_provider_connection_binding CHECK (
        (status IN ('BOUND', 'REVOKED') AND num_nonnulls(approval_id, owner_user_id, ownership_verification_ref, external_connection_id, bound_at) = 5)
        OR (status NOT IN ('BOUND', 'REVOKED') AND num_nonnulls(approval_id, owner_user_id, ownership_verification_ref, external_connection_id, bound_at) = 0)
    ),
    CONSTRAINT ck_certificate_provider_connection_timestamps CHECK (
        updated_at >= created_at AND (bound_at IS NULL OR bound_at >= created_at)
        AND (cleared_at IS NULL OR cleared_at >= coalesce(bound_at, created_at))
    )
);
CREATE UNIQUE INDEX uk_certificate_provider_connection_active_owner ON certificate_provider_connections (owner_user_id) WHERE status = 'BOUND';

CREATE FUNCTION guard_certificate_provider_connection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    session certificate_registration_sessions%ROWTYPE;
    command certificate_provider_command_inbox%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.status <> 'READY' THEN
        RAISE EXCEPTION 'Certificate provider connection must start unbound' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO session FROM certificate_registration_sessions WHERE id = NEW.session_id FOR UPDATE;
    SELECT * INTO command FROM certificate_provider_command_inbox WHERE message_id = NEW.registration_message_id;
    IF command.command_type IS DISTINCT FROM 'REGISTER'
        OR command.status NOT IN ('PROCESSING', 'SUCCEEDED', 'UNKNOWN')
        OR command.provider_request_started_at IS NULL
        OR ROW(command.session_id, command.session_version, command.organization_code)
            IS DISTINCT FROM ROW(NEW.session_id, NEW.session_version, NEW.organization_code)
        OR session.organization_code IS DISTINCT FROM NEW.organization_code THEN
        RAISE EXCEPTION 'Certificate connection does not match its registration' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.connection_ref, NEW.session_id, NEW.session_version, NEW.organization_code, NEW.registration_message_id, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.connection_ref, OLD.session_id, OLD.session_version, OLD.organization_code, OLD.registration_message_id, OLD.created_at)
            OR (OLD.approval_id IS NOT NULL AND ROW(NEW.approval_id, NEW.owner_user_id, NEW.ownership_verification_ref, NEW.external_connection_id, NEW.bound_at)
                IS DISTINCT FROM ROW(OLD.approval_id, OLD.owner_user_id, OLD.ownership_verification_ref, OLD.external_connection_id, OLD.bound_at))
            OR (OLD.status IN ('REVOKED', 'DISCARDED') AND NEW IS DISTINCT FROM OLD)
            OR (OLD.status = 'BOUND' AND NEW.status NOT IN ('BOUND', 'REVOKED')) THEN
            RAISE EXCEPTION 'Certificate connection identity and binding are immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.approval_id IS NOT NULL AND ROW(NEW.approval_id, NEW.connection_ref, NEW.session_version, NEW.owner_user_id, NEW.ownership_verification_ref)
        IS DISTINCT FROM ROW(session.approval_id, session.connection_ref, session.registration_version, session.approved_user_id, session.ownership_verification_ref) THEN
        RAISE EXCEPTION 'Certificate binding does not match immutable approval' USING ERRCODE = '23514';
    END IF;
    IF NEW.bound_at IS NOT NULL AND NEW.bound_at < session.approved_at THEN
        RAISE EXCEPTION 'Certificate binding predates approval' USING ERRCODE = '23514';
    END IF;
    IF NEW.status IN ('DISCARDED', 'DELETE_UNKNOWN') AND session.approval_id IS NOT NULL THEN
        RAISE EXCEPTION 'Approved certificate connection cannot be orphan-cleaned' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_provider_connection_guard BEFORE INSERT OR UPDATE ON certificate_provider_connections
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_provider_connection();

CREATE TABLE certificate_provider_result_outbox (
    message_id UUID PRIMARY KEY,
    command_message_id UUID NOT NULL REFERENCES certificate_provider_command_inbox (message_id) ON DELETE RESTRICT,
    session_id UUID NOT NULL,
    session_version BIGINT NOT NULL,
    message_type VARCHAR(30) NOT NULL,
    connection_ref UUID,
    approval_id UUID,
    claim_id UUID,
    claim_generation BIGINT,
    user_id BIGINT,
    ownership_verification_ref UUID,
    event_failure_code VARCHAR(40),
    status VARCHAR(16) NOT NULL,
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
    CONSTRAINT uk_certificate_provider_result UNIQUE (command_message_id, message_type),
    CONSTRAINT ck_certificate_provider_result_version CHECK (session_version > 0),
    CONSTRAINT ck_certificate_provider_result_type CHECK (
        (message_type = 'REGISTERED' AND connection_ref IS NOT NULL AND event_failure_code IS NULL
            AND num_nonnulls(approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 0)
        OR (message_type IN ('REGISTRATION_FAILED', 'REGISTRATION_UNKNOWN') AND event_failure_code IS NOT NULL
            AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 0
            AND ((message_type = 'REGISTRATION_UNKNOWN' AND event_failure_code = 'REGISTRATION_UNCERTAIN')
                OR (message_type = 'REGISTRATION_FAILED' AND event_failure_code IN ('REGISTRATION_REJECTED',
                    'ADDITIONAL_AUTH_REQUIRED', 'UPLOAD_EXPIRED', 'PROVIDER_UNAVAILABLE', 'CONTRACT_CONFLICT'))))
        OR (message_type IN ('BOUND', 'BINDING_UNKNOWN') AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 6
            AND claim_generation > 0 AND user_id > 0
            AND ((message_type = 'BOUND' AND event_failure_code IS NULL)
                OR (message_type = 'BINDING_UNKNOWN' AND event_failure_code IS NOT NULL AND event_failure_code IN (
                    'BINDING_UNCERTAIN', 'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED', 'CONTRACT_CONFLICT', 'PROVIDER_UNAVAILABLE'))))
        OR (message_type = 'CANCELLED' AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref, event_failure_code) = 0)
    ),
    CONSTRAINT ck_certificate_provider_result_event_failure CHECK (
        event_failure_code IS NULL OR event_failure_code IN (
            'REGISTRATION_REJECTED', 'REGISTRATION_UNCERTAIN', 'ADDITIONAL_AUTH_REQUIRED', 'UPLOAD_EXPIRED',
            'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED', 'BINDING_UNCERTAIN', 'PROVIDER_UNAVAILABLE', 'CONTRACT_CONFLICT'
        )
    ),
    CONSTRAINT ck_certificate_provider_result_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_certificate_provider_result_attempt CHECK (
        attempt_count >= 0 AND lease_generation >= 0
        AND ((status = 'PENDING' AND attempt_count = 0 AND lease_generation = 0)
            OR (status <> 'PENDING' AND attempt_count > 0 AND lease_generation > 0))
    ),
    CONSTRAINT ck_certificate_provider_result_lifecycle CHECK (
        (status IN ('PENDING', 'RETRY') AND next_attempt_at IS NOT NULL AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, completed_at) = 0)
        OR (status = 'PROCESSING' AND next_attempt_at IS NULL AND completed_at IS NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at) = 3 AND lease_expires_at > processing_started_at)
        OR (status IN ('PUBLISHED', 'FAILED') AND completed_at IS NOT NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, next_attempt_at) = 0)
    ),
    CONSTRAINT ck_certificate_provider_result_failure CHECK (
        (status IN ('PENDING', 'PUBLISHED') AND last_failure_code IS NULL)
        OR (status IN ('RETRY', 'FAILED') AND last_failure_code IS NOT NULL) OR status = 'PROCESSING'
    ),
    CONSTRAINT ck_certificate_provider_result_publish_failure CHECK (
        last_failure_code IS NULL OR last_failure_code IN ('BROKER_UNAVAILABLE', 'PUBLISH_TIMEOUT', 'LEASE_EXPIRED', 'CONTRACT_INVALID', 'TARGET_UNAVAILABLE')
    ),
    CONSTRAINT ck_certificate_provider_result_timestamps CHECK (
        updated_at >= created_at AND (next_attempt_at IS NULL OR next_attempt_at >= created_at)
        AND (processing_started_at IS NULL OR processing_started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);
CREATE INDEX idx_certificate_provider_result_ready ON certificate_provider_result_outbox (next_attempt_at, message_id) WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_certificate_provider_result_lease ON certificate_provider_result_outbox (lease_expires_at, message_id) WHERE status = 'PROCESSING';

CREATE FUNCTION guard_certificate_provider_result() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    command certificate_provider_command_inbox%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Certificate result must start pending publication' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO command FROM certificate_provider_command_inbox WHERE message_id = NEW.command_message_id;
    IF NEW.created_at < command.received_at
        OR ROW(NEW.session_id, NEW.session_version) IS DISTINCT FROM ROW(command.session_id, command.session_version)
        OR (NEW.message_type IN ('REGISTERED', 'REGISTRATION_FAILED', 'REGISTRATION_UNKNOWN') AND command.command_type IS DISTINCT FROM 'REGISTER')
        OR (NEW.message_type = 'CANCELLED' AND command.command_type IS DISTINCT FROM 'CANCEL')
        OR (NEW.message_type IN ('BOUND', 'BINDING_UNKNOWN') AND
            (command.command_type IS DISTINCT FROM 'BIND' OR ROW(NEW.connection_ref, NEW.approval_id, NEW.claim_id, NEW.claim_generation, NEW.user_id, NEW.ownership_verification_ref)
                IS DISTINCT FROM ROW(command.connection_ref, command.approval_id, command.claim_id, command.claim_generation, command.user_id, command.ownership_verification_ref))) THEN
        RAISE EXCEPTION 'Certificate result does not match its command' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.message_type = 'REGISTERED' AND NOT EXISTS (
        SELECT 1 FROM certificate_provider_connections
        WHERE connection_ref = NEW.connection_ref AND registration_message_id = NEW.command_message_id
            AND session_id = NEW.session_id AND session_version = NEW.session_version AND status IN ('READY', 'BOUND', 'REVOKED')
    ) THEN
        RAISE EXCEPTION 'Registered result requires a confirmed provider connection' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.message_type = 'BOUND' AND NOT EXISTS (
        SELECT 1 FROM certificate_provider_connections
        WHERE connection_ref = NEW.connection_ref AND session_id = NEW.session_id AND session_version = NEW.session_version
            AND approval_id = NEW.approval_id AND owner_user_id = NEW.user_id
            AND ownership_verification_ref = NEW.ownership_verification_ref AND status IN ('BOUND', 'REVOKED')
    ) THEN
        RAISE EXCEPTION 'Bound result requires matching provider ownership' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.message_id, NEW.command_message_id, NEW.session_id, NEW.session_version, NEW.message_type,
               NEW.connection_ref, NEW.approval_id, NEW.claim_id, NEW.claim_generation, NEW.user_id, NEW.ownership_verification_ref, NEW.event_failure_code, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.message_id, OLD.command_message_id, OLD.session_id, OLD.session_version, OLD.message_type,
               OLD.connection_ref, OLD.approval_id, OLD.claim_id, OLD.claim_generation, OLD.user_id, OLD.ownership_verification_ref, OLD.event_failure_code, OLD.created_at)
            OR (OLD.status = 'PUBLISHED' AND NEW IS DISTINCT FROM OLD)
            OR (OLD.status = 'FAILED' AND NEW.status NOT IN ('FAILED', 'RETRY'))
            OR NEW.lease_generation < OLD.lease_generation OR NEW.attempt_count < OLD.attempt_count THEN
            RAISE EXCEPTION 'Certificate result facts are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'PROCESSING' AND OLD.status <> 'PROCESSING'
            AND (NEW.lease_generation <> OLD.lease_generation + 1 OR NEW.attempt_count <> OLD.attempt_count + 1) THEN
            RAISE EXCEPTION 'Certificate result claim must advance its fencing generation' USING ERRCODE = '23514';
        END IF;
        IF OLD.status = 'PROCESSING' AND NEW.status = 'PROCESSING'
            AND ROW(NEW.lease_token, NEW.lease_generation, NEW.attempt_count, NEW.processing_started_at)
                IS DISTINCT FROM ROW(OLD.lease_token, OLD.lease_generation, OLD.attempt_count, OLD.processing_started_at) THEN
            RAISE EXCEPTION 'Certificate result lease cannot be replaced in place' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_provider_result_guard BEFORE INSERT OR UPDATE ON certificate_provider_result_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_provider_result();

COMMENT ON TABLE certificate_upload_secrets IS 'Temporary AES-256-GCM envelope ciphertext; TTL and request-time expiry enforcement belong to the API.';
COMMENT ON TABLE certificate_provider_command_inbox IS 'Durable receipt plus execution job. Do not automatically repeat create after provider_request_started_at is set.';
COMMENT ON TABLE certificate_provider_connections IS 'Encrypted connectedId/account-query credentials only; never persist certificate DER/KEY/password in this long-lived payload.';
COMMENT ON TABLE certificate_provider_result_outbox IS 'Typed non-secret results; publication retry is independent of provider execution attempts.';
