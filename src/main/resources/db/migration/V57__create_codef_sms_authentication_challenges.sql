-- Temporary, owner-bound CODEF continuation state. Never store plaintext SMS codes or credentials.
-- Existing certificate sessions remain REGISTERING while their provider job is WAITING_AUTH.
ALTER TABLE certificate_provider_command_inbox
    DROP CONSTRAINT ck_certificate_provider_command_status,
    DROP CONSTRAINT ck_certificate_provider_command_lifecycle,
    DROP CONSTRAINT ck_certificate_provider_command_failure;

ALTER TABLE certificate_provider_command_inbox
    ADD CONSTRAINT ck_certificate_provider_command_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY', 'WAITING_AUTH', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'IGNORED')
    ),
    ADD CONSTRAINT ck_certificate_provider_command_lifecycle CHECK (
        (status IN ('PENDING', 'RETRY') AND next_attempt_at IS NOT NULL AND provider_request_started_at IS NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, completed_at) = 0)
        OR (status = 'PROCESSING' AND next_attempt_at IS NULL AND completed_at IS NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at) = 3
            AND lease_expires_at > processing_started_at)
        OR (status = 'WAITING_AUTH' AND command_type = 'REGISTER' AND provider_request_started_at IS NOT NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, next_attempt_at, completed_at) = 0)
        OR (status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'IGNORED') AND completed_at IS NOT NULL
            AND num_nonnulls(lease_token, processing_started_at, lease_expires_at, next_attempt_at) = 0)
    ),
    ADD CONSTRAINT ck_certificate_provider_command_failure CHECK (
        (status IN ('PENDING', 'SUCCEEDED') AND last_failure_code IS NULL)
        OR (status IN ('RETRY', 'FAILED', 'UNKNOWN', 'IGNORED') AND last_failure_code IS NOT NULL)
        OR (status = 'WAITING_AUTH' AND last_failure_code IS NOT NULL
            AND last_failure_code = 'ADDITIONAL_AUTH_REQUIRED')
        OR status = 'PROCESSING'
    ),
    ADD CONSTRAINT uk_certificate_provider_command_auth_target
        UNIQUE (message_id, session_id, organization_code);

CREATE INDEX idx_certificate_provider_waiting_auth
    ON certificate_provider_command_inbox (deadline_at, message_id) WHERE status = 'WAITING_AUTH';

CREATE TABLE codef_authentication_challenges (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    operation_type VARCHAR(24) NOT NULL,
    operation_id UUID NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1,
    organization_code VARCHAR(4) NOT NULL,
    certificate_command_id UUID,
    connection_id BIGINT,
    method VARCHAR(16) NOT NULL,
    masked_destination VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(80),
    encrypted_payload BYTEA,
    wrapped_data_key BYTEA,
    encryption_key_id VARCHAR(255),
    encrypted_result BYTEA,
    wrapped_result_key BYTEA,
    result_key_id VARCHAR(255),
    result_expires_at TIMESTAMP(6) WITHOUT TIME ZONE,
    submission_id UUID UNIQUE,
    submitted_at TIMESTAMP(6) WITHOUT TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_token UUID,
    processing_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lease_expires_at TIMESTAMP(6) WITHOUT TIME ZONE,
    provider_request_started_at TIMESTAMP(6) WITHOUT TIME ZONE,
    cancel_requested_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    cleared_at TIMESTAMP(6) WITHOUT TIME ZONE,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uk_codef_auth_operation_generation UNIQUE (operation_type, operation_id, generation),
    CONSTRAINT fk_codef_auth_certificate_command
        FOREIGN KEY (certificate_command_id, operation_id, organization_code)
        REFERENCES certificate_provider_command_inbox (message_id, session_id, organization_code)
        ON DELETE RESTRICT,
    CONSTRAINT fk_codef_auth_owned_connection
        FOREIGN KEY (connection_id, user_id) REFERENCES external_connections (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_codef_auth_operation CHECK (
        (operation_type = 'CERTIFICATE_REGISTER' AND certificate_command_id IS NOT NULL)
        OR (operation_type = 'BANK_CONNECT' AND certificate_command_id IS NULL)
        OR (operation_type IN ('ACCOUNT_LIST', 'TRANSACTION_LIST')
            AND certificate_command_id IS NULL AND connection_id IS NOT NULL)
    ),
    CONSTRAINT ck_codef_auth_identity CHECK (generation > 0 AND organization_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_codef_auth_method CHECK (method = 'SMS'),
    CONSTRAINT ck_codef_auth_destination CHECK (
        masked_destination IS NULL OR (masked_destination ~ '^[0-9*+ -]{4,64}$'
            AND position('*' IN masked_destination) > 0)
    ),
    CONSTRAINT ck_codef_auth_status CHECK (
        status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_codef_auth_failure CHECK (
        (status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING', 'SUCCEEDED') AND failure_code IS NULL)
        OR (status IN ('FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED') AND failure_code IS NOT NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{1,79}$')
    ),
    CONSTRAINT ck_codef_auth_payload CHECK (
        (status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING') AND cancel_requested_at IS NULL
            AND cleared_at IS NULL AND num_nonnulls(encrypted_payload, wrapped_data_key, encryption_key_id) = 3
            AND octet_length(encrypted_payload) BETWEEN 29 AND 1048576
            AND octet_length(wrapped_data_key) BETWEEN 32 AND 4096 AND length(btrim(encryption_key_id)) > 0)
        OR ((status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED') OR cancel_requested_at IS NOT NULL)
            AND cleared_at IS NOT NULL AND num_nonnulls(encrypted_payload, wrapped_data_key, encryption_key_id) = 0)
    ),
    CONSTRAINT ck_codef_auth_result CHECK (
        num_nonnulls(encrypted_result, wrapped_result_key, result_key_id, result_expires_at) = 0
        OR (status = 'SUCCEEDED' AND cancel_requested_at IS NULL
            AND num_nonnulls(encrypted_result, wrapped_result_key, result_key_id, result_expires_at) = 4
            AND octet_length(encrypted_result) BETWEEN 29 AND 1048576
            AND octet_length(wrapped_result_key) BETWEEN 32 AND 4096 AND length(btrim(result_key_id)) > 0
            AND result_expires_at > completed_at AND result_expires_at <= completed_at + INTERVAL '10 minutes')
    ),
    CONSTRAINT ck_codef_auth_submission CHECK (
        num_nonnulls(submission_id, submitted_at) IN (0, 2)
        AND (status NOT IN ('SUBMITTED', 'PROCESSING', 'SUCCEEDED', 'UNKNOWN') OR submitted_at IS NOT NULL)
        AND (status <> 'WAITING_INPUT' OR submitted_at IS NULL)
    ),
    CONSTRAINT ck_codef_auth_lease CHECK (
        attempt_count >= 0 AND lease_generation >= 0
        AND ((status = 'PROCESSING' AND num_nonnulls(lease_token, processing_started_at, lease_expires_at) = 3
                AND lease_expires_at > processing_started_at AND lease_expires_at <= expires_at
                AND attempt_count > 0 AND lease_generation > 0)
            OR (status <> 'PROCESSING' AND num_nonnulls(lease_token, processing_started_at, lease_expires_at) = 0))
        AND (status NOT IN ('SUCCEEDED', 'UNKNOWN') OR provider_request_started_at IS NOT NULL)
        AND (status NOT IN ('WAITING_INPUT', 'SUBMITTED') OR provider_request_started_at IS NULL)
    ),
    CONSTRAINT ck_codef_auth_completion CHECK (
        (status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING') AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_codef_auth_timestamps CHECK (
        expires_at > created_at AND expires_at <= created_at + INTERVAL '10 minutes'
        AND updated_at >= created_at
        AND (submitted_at IS NULL OR (submitted_at >= created_at AND submitted_at < expires_at))
        AND (processing_started_at IS NULL OR (processing_started_at >= submitted_at AND processing_started_at < expires_at))
        AND (provider_request_started_at IS NULL OR (provider_request_started_at >= submitted_at AND provider_request_started_at < expires_at))
        AND (cancel_requested_at IS NULL OR cancel_requested_at >= created_at)
        AND (cleared_at IS NULL OR cleared_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= coalesce(submitted_at, created_at))
        AND (completed_at IS NULL OR completed_at >= coalesce(provider_request_started_at, created_at))
        AND updated_at >= coalesce(submitted_at, created_at)
        AND updated_at >= coalesce(processing_started_at, created_at)
        AND updated_at >= coalesce(provider_request_started_at, created_at)
        AND updated_at >= coalesce(cancel_requested_at, created_at)
        AND updated_at >= coalesce(completed_at, created_at)
        AND updated_at >= coalesce(cleared_at, created_at)
        AND (status <> 'EXPIRED' OR completed_at >= expires_at)
        AND (status <> 'SUCCEEDED' OR completed_at < expires_at)
    )
);

CREATE UNIQUE INDEX uk_codef_auth_active_operation
    ON codef_authentication_challenges (operation_type, operation_id)
    WHERE status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING');
CREATE UNIQUE INDEX uk_codef_auth_active_user_institution
    ON codef_authentication_challenges (user_id, organization_code)
    WHERE status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING');
CREATE INDEX idx_codef_auth_ready ON codef_authentication_challenges (submitted_at, id)
    WHERE status = 'SUBMITTED' AND cancel_requested_at IS NULL;
CREATE INDEX idx_codef_auth_lease ON codef_authentication_challenges (lease_expires_at, id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_codef_auth_expiry ON codef_authentication_challenges (expires_at, id)
    WHERE status IN ('WAITING_INPUT', 'SUBMITTED', 'PROCESSING');
CREATE INDEX idx_codef_auth_result_expiry ON codef_authentication_challenges (result_expires_at, id)
    WHERE result_expires_at IS NOT NULL;
CREATE INDEX idx_codef_auth_owner ON codef_authentication_challenges (user_id, created_at, id);
CREATE INDEX idx_codef_auth_certificate ON codef_authentication_challenges (certificate_command_id)
    WHERE certificate_command_id IS NOT NULL;

CREATE FUNCTION guard_codef_authentication_challenge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'WAITING_INPUT' OR NEW.attempt_count <> 0 OR NEW.lease_generation <> 0
            OR NEW.provider_request_started_at IS NOT NULL OR NEW.cancel_requested_at IS NOT NULL THEN
            RAISE EXCEPTION 'CODEF authentication must start awaiting user input' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.user_id, NEW.operation_type, NEW.operation_id, NEW.generation, NEW.organization_code,
           NEW.certificate_command_id, NEW.connection_id, NEW.method, NEW.masked_destination, NEW.expires_at, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.user_id, OLD.operation_type, OLD.operation_id, OLD.generation, OLD.organization_code,
           OLD.certificate_command_id, OLD.connection_id, OLD.method, OLD.masked_destination, OLD.expires_at, OLD.created_at)
        OR NEW.updated_at < OLD.updated_at OR NEW.attempt_count < OLD.attempt_count OR NEW.lease_generation < OLD.lease_generation
        OR (OLD.submission_id IS NOT NULL AND ROW(NEW.submission_id, NEW.submitted_at) IS DISTINCT FROM ROW(OLD.submission_id, OLD.submitted_at))
        OR (OLD.provider_request_started_at IS NOT NULL AND NEW.provider_request_started_at IS DISTINCT FROM OLD.provider_request_started_at)
        OR (OLD.cancel_requested_at IS NOT NULL AND NEW.cancel_requested_at IS DISTINCT FROM OLD.cancel_requested_at)
        OR (OLD.cleared_at IS NOT NULL AND NEW.cleared_at IS DISTINCT FROM OLD.cleared_at) THEN
        RAISE EXCEPTION 'CODEF authentication identity and accepted actions are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED') THEN
        IF NEW IS DISTINCT FROM OLD AND NOT (
            num_nonnulls(NEW.encrypted_result, NEW.wrapped_result_key, NEW.result_key_id, NEW.result_expires_at) = 0
            AND (to_jsonb(NEW) - ARRAY['encrypted_result', 'wrapped_result_key', 'result_key_id', 'result_expires_at', 'updated_at'])
                IS NOT DISTINCT FROM (to_jsonb(OLD) - ARRAY['encrypted_result', 'wrapped_result_key', 'result_key_id', 'result_expires_at', 'updated_at'])
        ) THEN
            RAISE EXCEPTION 'Completed CODEF authentication cannot be resumed or replaced' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'WAITING_INPUT' AND NEW.status IN ('SUBMITTED', 'CANCELLED', 'EXPIRED'))
        OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('PROCESSING', 'FAILED', 'CANCELLED', 'EXPIRED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status = 'SUBMITTED'
            AND OLD.provider_request_started_at IS NULL AND OLD.cancel_requested_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'Invalid CODEF authentication transition' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PROCESSING' AND OLD.status <> 'PROCESSING'
        AND (NEW.lease_generation <> OLD.lease_generation + 1 OR NEW.attempt_count <> OLD.attempt_count + 1) THEN
        RAISE EXCEPTION 'CODEF authentication claim requires a new fencing generation' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'PROCESSING' AND NEW.status = 'PROCESSING'
        AND ROW(NEW.lease_token, NEW.lease_generation, NEW.attempt_count, NEW.processing_started_at)
            IS DISTINCT FROM ROW(OLD.lease_token, OLD.lease_generation, OLD.attempt_count, OLD.processing_started_at) THEN
        RAISE EXCEPTION 'CODEF authentication lease cannot be replaced in place' USING ERRCODE = '23514';
    END IF;
    IF OLD.provider_request_started_at IS NULL AND NEW.provider_request_started_at IS NOT NULL
        AND (OLD.status <> 'PROCESSING' OR NEW.status <> 'PROCESSING' OR OLD.cancel_requested_at IS NOT NULL
            OR NEW.cancel_requested_at IS NOT NULL OR NEW.provider_request_started_at < OLD.processing_started_at
            OR NEW.provider_request_started_at >= OLD.lease_expires_at) THEN
        RAISE EXCEPTION 'CODEF continuation requires the current unexpired lease and no cancellation' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'SUCCEEDED' AND NEW.cancel_requested_at IS NOT NULL THEN
        RAISE EXCEPTION 'Cancelled CODEF authentication cannot succeed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_codef_authentication_guard BEFORE INSERT OR UPDATE ON codef_authentication_challenges
    FOR EACH ROW EXECUTE FUNCTION guard_codef_authentication_challenge();

COMMENT ON TABLE codef_authentication_challenges IS
    'Owner-bound SMS continuation queue; encrypted context and SMS are erased at termination. No automatic replay after provider request start.';
COMMENT ON COLUMN codef_authentication_challenges.operation_id IS
    'Certificate session ID for CERTIFICATE_REGISTER; server-created operation UUID for connection/account/transaction requests.';
COMMENT ON COLUMN codef_authentication_challenges.encrypted_payload IS
    'Envelope ciphertext for original allowlisted request, two-way context and accepted SMS; AAD binds challenge/owner/operation/generation.';
COMMENT ON COLUMN codef_authentication_challenges.encrypted_result IS
    'Optional encrypted application result snapshot. Must not retain original credentials, SMS or continuation tokens.';
COMMENT ON COLUMN codef_authentication_challenges.submission_id IS
    'Client idempotency UUID; duplicate submissions return existing state without another provider call.';
COMMENT ON COLUMN codef_authentication_challenges.cancel_requested_at IS
    'Monotonic cancellation intent; an in-flight provider response must not attach a connection or publish a successful application result.';
