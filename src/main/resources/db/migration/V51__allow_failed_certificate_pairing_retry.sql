-- Keep the authenticated mobile owner and PC/QR capabilities immutable. Only a
-- definitive, unapproved failure may be replaced by a fresh CREATED attempt.
CREATE OR REPLACE FUNCTION guard_certificate_pc_pairing() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    retry_allowed BOOLEAN := FALSE;
    previous_session certificate_registration_sessions%ROWTYPE;
    next_session certificate_registration_sessions%ROWTYPE;
BEGIN
    IF OLD.registration_session_id IS NOT NULL
        AND NEW.registration_session_id IS DISTINCT FROM OLD.registration_session_id THEN
        SELECT * INTO previous_session FROM certificate_registration_sessions
            WHERE id = OLD.registration_session_id FOR UPDATE;
        SELECT * INTO next_session FROM certificate_registration_sessions
            WHERE id = NEW.registration_session_id FOR UPDATE;
        retry_allowed := COALESCE(
            OLD.paired_user_id IS NOT NULL
            AND OLD.cancelled_at IS NULL AND NEW.cancelled_at IS NULL
            AND previous_session.status = 'FAILED'
            AND previous_session.approval_id IS NULL
            AND previous_session.current_claim_id IS NULL
            AND previous_session.approved_user_id IS NULL
            AND NOT EXISTS (
                SELECT 1 FROM certificate_registration_consents
                WHERE session_id = OLD.registration_session_id
            )
            AND next_session.id IS NOT NULL
            AND next_session.status = 'CREATED'
            AND next_session.approval_id IS NULL
            AND next_session.current_claim_id IS NULL
            AND next_session.approved_user_id IS NULL
            AND next_session.pc_capability_hash = NEW.registration_pc_capability_hash
            AND NOT NEW.authentication_prepared
            AND NEW.existing_connection_id IS NULL,
            FALSE
        );
    END IF;
    IF ROW(NEW.id, NEW.pc_capability_hash, NEW.qr_token_hash, NEW.qr_expires_at, NEW.expires_at, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.pc_capability_hash, OLD.qr_token_hash, OLD.qr_expires_at, OLD.expires_at, OLD.created_at)
        OR (OLD.paired_user_id IS NOT NULL AND ROW(NEW.paired_user_id, NEW.paired_at) IS DISTINCT FROM ROW(OLD.paired_user_id, OLD.paired_at))
        OR (OLD.registration_session_id IS NOT NULL
            AND ROW(NEW.registration_session_id, NEW.registration_pc_capability_hash)
                IS DISTINCT FROM ROW(OLD.registration_session_id, OLD.registration_pc_capability_hash)
            AND NOT retry_allowed)
        OR (OLD.authentication_prepared
            AND ROW(NEW.authentication_prepared, NEW.existing_connection_id)
                IS DISTINCT FROM ROW(OLD.authentication_prepared, OLD.existing_connection_id)
            AND NOT retry_allowed)
        OR (OLD.cancelled_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Certificate pairing ownership and authentication context are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
