-- The mobile-selected bank exists before a registration session is created.
-- NULL keeps pre-claim pairings and legacy writers compatible during rollout.
ALTER TABLE certificate_pc_pairings
    ADD COLUMN organization_code VARCHAR(4),
    ADD CONSTRAINT ck_certificate_pairing_organization CHECK (
        organization_code IS NULL OR organization_code ~ '^[0-9]{4}$'
    );

-- Keep this guard separate from the existing ownership and failed-retry guard.
CREATE FUNCTION guard_certificate_pairing_organization() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.organization_code IS NOT NULL
        AND NEW.organization_code IS DISTINCT FROM OLD.organization_code THEN
        RAISE EXCEPTION 'Certificate pairing organization is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_pairing_organization_guard
    BEFORE UPDATE ON certificate_pc_pairings
    FOR EACH ROW EXECUTE FUNCTION guard_certificate_pairing_organization();

-- Only linked, non-cancelled legacy sessions provide a mutable known-bank row.
-- Cancelled pairings are immutable; never rewrite them or guess for unlinked rows.
UPDATE certificate_pc_pairings p
SET organization_code = s.organization_code
FROM certificate_registration_sessions s
WHERE s.id = p.registration_session_id AND p.cancelled_at IS NULL;

