-- Preserve non-secret CODEF codes for definitively rejected certificate registrations.
-- Legacy failure values remain valid. CF-00000 is success, never a failure.
-- Deploy tolerant result consumers before enabling detailed-code producers.
ALTER TABLE certificate_registration_sessions
    DROP CONSTRAINT ck_certificate_sessions_failure_code,
    ADD CONSTRAINT ck_certificate_sessions_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'REGISTRATION_REJECTED', 'REGISTRATION_UNCERTAIN', 'ADDITIONAL_AUTH_REQUIRED',
            'UPLOAD_EXPIRED', 'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED',
            'BINDING_UNCERTAIN', 'PROVIDER_UNAVAILABLE', 'CONTRACT_CONFLICT'
        ) OR (status IN ('FAILED', 'CANCELLED', 'EXPIRED')
            AND length(last_failure_code) = 8 AND last_failure_code ~ '^CF-[0-9]{5}$'
            AND last_failure_code <> 'CF-00000')
    );

ALTER TABLE certificate_provider_command_inbox
    DROP CONSTRAINT ck_certificate_provider_command_failure_code,
    ADD CONSTRAINT ck_certificate_provider_command_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'PROVIDER_UNAVAILABLE', 'REGISTRATION_REJECTED', 'REGISTRATION_UNCERTAIN', 'UPLOAD_EXPIRED',
            'ADDITIONAL_AUTH_REQUIRED', 'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED',
            'BINDING_UNCERTAIN', 'CONTRACT_CONFLICT', 'SESSION_UNAVAILABLE', 'SESSION_CANCELLED', 'LEASE_EXPIRED'
        ) OR (command_type = 'REGISTER' AND status = 'FAILED'
            AND provider_request_started_at IS NOT NULL
            AND length(last_failure_code) = 8 AND last_failure_code ~ '^CF-[0-9]{5}$'
            AND last_failure_code <> 'CF-00000')
    );

ALTER TABLE certificate_provider_result_outbox
    DROP CONSTRAINT ck_certificate_provider_result_type,
    DROP CONSTRAINT ck_certificate_provider_result_event_failure,
    ADD CONSTRAINT ck_certificate_provider_result_type CHECK (
        (message_type = 'REGISTERED' AND connection_ref IS NOT NULL AND event_failure_code IS NULL
            AND num_nonnulls(approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 0)
        OR (message_type IN ('REGISTRATION_FAILED', 'REGISTRATION_UNKNOWN') AND event_failure_code IS NOT NULL
            AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 0
            AND ((message_type = 'REGISTRATION_UNKNOWN' AND event_failure_code = 'REGISTRATION_UNCERTAIN')
                OR (message_type = 'REGISTRATION_FAILED' AND (
                    event_failure_code IN ('REGISTRATION_REJECTED', 'ADDITIONAL_AUTH_REQUIRED',
                        'UPLOAD_EXPIRED', 'PROVIDER_UNAVAILABLE', 'CONTRACT_CONFLICT')
                    OR (length(event_failure_code) = 8 AND event_failure_code ~ '^CF-[0-9]{5}$'
                        AND event_failure_code <> 'CF-00000')))))
        OR (message_type IN ('BOUND', 'BINDING_UNKNOWN') AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref) = 6
            AND claim_generation > 0 AND user_id > 0
            AND ((message_type = 'BOUND' AND event_failure_code IS NULL)
                OR (message_type = 'BINDING_UNKNOWN' AND event_failure_code IS NOT NULL AND event_failure_code IN (
                    'BINDING_UNCERTAIN', 'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED', 'CONTRACT_CONFLICT', 'PROVIDER_UNAVAILABLE'))))
        OR (message_type = 'CANCELLED' AND num_nonnulls(connection_ref, approval_id, claim_id, claim_generation, user_id, ownership_verification_ref, event_failure_code) = 0)
    ),
    ADD CONSTRAINT ck_certificate_provider_result_event_failure CHECK (
        event_failure_code IS NULL OR event_failure_code IN (
            'REGISTRATION_REJECTED', 'REGISTRATION_UNCERTAIN', 'ADDITIONAL_AUTH_REQUIRED', 'UPLOAD_EXPIRED',
            'CONNECTION_CONFLICT', 'OWNER_VERIFICATION_REQUIRED', 'BINDING_UNCERTAIN', 'PROVIDER_UNAVAILABLE', 'CONTRACT_CONFLICT'
        ) OR (message_type = 'REGISTRATION_FAILED'
            AND length(event_failure_code) = 8 AND event_failure_code ~ '^CF-[0-9]{5}$'
            AND event_failure_code <> 'CF-00000')
    );
