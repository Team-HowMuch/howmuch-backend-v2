-- Mobile consent precedes the provider mutation. One user's connection can have many registration receipts.
ALTER TABLE certificate_registration_sessions DROP CONSTRAINT ck_certificate_sessions_connection_state;
ALTER TABLE certificate_registration_sessions ADD CONSTRAINT ck_certificate_sessions_connection_state
 CHECK (status NOT IN ('APPROVED_BINDING','BOUND','BINDING_UNKNOWN') OR connection_ref IS NOT NULL);
DROP INDEX uk_certificate_sessions_user_binding;
ALTER TABLE certificate_provider_connections DROP CONSTRAINT certificate_provider_connections_external_connection_id_key;
DROP INDEX uk_certificate_provider_connection_active_owner;

CREATE TABLE certificate_registration_consents (
 id UUID PRIMARY KEY,
 session_id UUID NOT NULL UNIQUE REFERENCES certificate_registration_sessions(id) ON DELETE RESTRICT,
 claim_id UUID NOT NULL UNIQUE,
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 claim_generation BIGINT NOT NULL CHECK (claim_generation > 0),
 approved_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
 external_connection_id BIGINT,
 completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
 FOREIGN KEY (session_id,claim_id,user_id,claim_generation)
  REFERENCES certificate_registration_claims(session_id,id,claimant_user_id,claim_generation),
 FOREIGN KEY (external_connection_id,user_id) REFERENCES external_connections(id,user_id),
 CHECK ((external_connection_id IS NULL AND completed_at IS NULL)
  OR (external_connection_id IS NOT NULL AND completed_at >= approved_at))
);
CREATE FUNCTION guard_certificate_consent() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN
  RAISE EXCEPTION 'Certificate consent cannot be deleted' USING ERRCODE = '23514';
 END IF;
 IF TG_OP = 'UPDATE' AND (ROW(NEW.id,NEW.session_id,NEW.claim_id,NEW.user_id,NEW.claim_generation,NEW.approved_at)
  IS DISTINCT FROM ROW(OLD.id,OLD.session_id,OLD.claim_id,OLD.user_id,OLD.claim_generation,OLD.approved_at)
  OR (OLD.completed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD)) THEN
  RAISE EXCEPTION 'Certificate consent is immutable' USING ERRCODE = '23514';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM certificate_registration_claims c
  WHERE c.id=NEW.claim_id AND c.session_id=NEW.session_id AND c.claimant_user_id=NEW.user_id
   AND c.claim_generation=NEW.claim_generation AND c.status='APPROVED' AND c.finished_at=NEW.approved_at) THEN
  RAISE EXCEPTION 'Certificate consent requires the approved claim' USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_certificate_consent_guard BEFORE INSERT OR UPDATE OR DELETE ON certificate_registration_consents
 FOR EACH ROW EXECUTE FUNCTION guard_certificate_consent();
