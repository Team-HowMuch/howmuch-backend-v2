-- Early PC/mobile pairing is separate from final financial-connection consent.
ALTER TABLE certificate_registration_sessions
    ADD CONSTRAINT uk_certificate_session_pc_capability UNIQUE (id, pc_capability_hash);

CREATE TABLE certificate_pc_pairings (
    id UUID PRIMARY KEY,
    pc_capability_hash VARCHAR(64) NOT NULL UNIQUE,
    qr_token_hash VARCHAR(64) NOT NULL UNIQUE,
    qr_expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    paired_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    paired_at TIMESTAMP(6) WITHOUT TIME ZONE,
    registration_session_id UUID UNIQUE,
    registration_pc_capability_hash VARCHAR(64),
    authentication_prepared BOOLEAN NOT NULL DEFAULT FALSE,
    existing_connection_id BIGINT,
    cancelled_at TIMESTAMP(6) WITHOUT TIME ZONE,
    FOREIGN KEY (registration_session_id, registration_pc_capability_hash)
        REFERENCES certificate_registration_sessions(id, pc_capability_hash) ON DELETE RESTRICT,
    CHECK (num_nonnulls(registration_session_id, registration_pc_capability_hash) IN (0, 2)),
    CHECK (registration_pc_capability_hash IS NULL OR registration_pc_capability_hash ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (existing_connection_id, paired_user_id) REFERENCES external_connections(id, user_id),
    CHECK (pc_capability_hash ~ '^[0-9a-f]{64}$' AND qr_token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at > created_at AND qr_expires_at > created_at AND qr_expires_at <= expires_at),
    CHECK (num_nonnulls(paired_user_id, paired_at) IN (0, 2)),
    CHECK (paired_at IS NULL OR (paired_at >= created_at AND paired_at < qr_expires_at)),
    CHECK (registration_session_id IS NULL OR paired_user_id IS NOT NULL),
    CHECK (NOT authentication_prepared OR registration_session_id IS NOT NULL),
    CHECK (existing_connection_id IS NULL OR authentication_prepared),
    CHECK (cancelled_at IS NULL OR cancelled_at >= created_at)
);
CREATE INDEX idx_certificate_pc_pairing_expiry ON certificate_pc_pairings(expires_at)
    WHERE cancelled_at IS NULL;
CREATE FUNCTION guard_certificate_pc_pairing() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.pc_capability_hash, NEW.qr_token_hash, NEW.qr_expires_at, NEW.expires_at, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.pc_capability_hash, OLD.qr_token_hash, OLD.qr_expires_at, OLD.expires_at, OLD.created_at)
        OR (OLD.paired_user_id IS NOT NULL AND ROW(NEW.paired_user_id, NEW.paired_at) IS DISTINCT FROM ROW(OLD.paired_user_id, OLD.paired_at))
        OR (OLD.registration_session_id IS NOT NULL AND ROW(NEW.registration_session_id, NEW.registration_pc_capability_hash) IS DISTINCT FROM ROW(OLD.registration_session_id, OLD.registration_pc_capability_hash))
        OR (OLD.authentication_prepared AND ROW(NEW.authentication_prepared, NEW.existing_connection_id) IS DISTINCT FROM ROW(OLD.authentication_prepared, OLD.existing_connection_id))
        OR (OLD.cancelled_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Certificate pairing ownership and authentication context are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_pc_pairing_guard BEFORE UPDATE ON certificate_pc_pairings
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_pc_pairing();

-- Pairing and registration capabilities are distinct. The composite FK proves the
-- linked session's capability; deferred checks also preserve the mobile owner.
CREATE FUNCTION validate_certificate_pairing_owner() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target_session_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'certificate_pc_pairings' THEN
        target_session_id := NEW.registration_session_id;
    ELSIF TG_TABLE_NAME = 'certificate_registration_sessions' THEN
        target_session_id := NEW.id;
    ELSE
        target_session_id := NEW.session_id;
    END IF;
    -- Serialize deferred validation with concurrent claim/approval changes.
    PERFORM 1 FROM certificate_registration_sessions WHERE id = target_session_id FOR UPDATE;
    IF EXISTS (
        SELECT 1 FROM certificate_pc_pairings p
        JOIN certificate_registration_sessions s ON s.id = p.registration_session_id
        LEFT JOIN certificate_registration_claims c ON c.id = s.current_claim_id
        LEFT JOIN certificate_registration_consents a ON a.session_id = s.id
        WHERE s.id = target_session_id
          AND ((s.approved_user_id IS NOT NULL AND s.approved_user_id <> p.paired_user_id)
            OR (c.claimant_user_id IS NOT NULL AND c.claimant_user_id <> p.paired_user_id)
            OR (a.user_id IS NOT NULL AND a.user_id <> p.paired_user_id))
    ) THEN
        RAISE EXCEPTION 'Certificate pairing owner must match claim and approval owner' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_certificate_pairing_owner_link
    AFTER INSERT OR UPDATE ON certificate_pc_pairings DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_pairing_owner();
CREATE CONSTRAINT TRIGGER trg_certificate_pairing_owner_session
    AFTER INSERT OR UPDATE ON certificate_registration_sessions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_pairing_owner();
CREATE CONSTRAINT TRIGGER trg_certificate_pairing_owner_claim
    AFTER INSERT OR UPDATE ON certificate_registration_claims DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_pairing_owner();
CREATE CONSTRAINT TRIGGER trg_certificate_pairing_owner_consent
    AFTER INSERT OR UPDATE ON certificate_registration_consents DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_certificate_pairing_owner();
