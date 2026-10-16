-- Existing authentication methods cannot be inferred from historical records; preserve UNKNOWN.
ALTER TABLE connected_institutions
 ADD COLUMN authentication_method VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 ADD COLUMN authentication_method_confirmed_at TIMESTAMP(6),
 ADD CONSTRAINT ck_institution_authentication_method CHECK(authentication_method IN ('CERTIFICATE','ID_PASSWORD','UNKNOWN')),
 ADD CONSTRAINT ck_institution_authentication_method_time CHECK(
   (authentication_method='UNKNOWN') = (authentication_method_confirmed_at IS NULL));
CREATE INDEX idx_connected_institution_certificate ON connected_institutions(external_connection_id,organization)
 WHERE authentication_method='CERTIFICATE' AND status='CONNECTED';

CREATE TABLE certificate_reference_operations (
 id UUID PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 external_connection_id BIGINT NOT NULL,
 source_organization VARCHAR(4) NOT NULL CHECK(source_organization ~ '^[0-9]{4}$'),
 organization VARCHAR(4) NOT NULL CHECK(organization ~ '^[0-9]{4}$'),
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 connection_hash VARCHAR(64) NOT NULL CHECK(connection_hash ~ '^[0-9a-f]{64}$'),
 status VARCHAR(24) NOT NULL CHECK(status IN ('QUEUED','RUNNING','CONNECTED','FAILED','UNKNOWN','ACTION_REQUIRED','EXPIRED')),
 already_connected BOOLEAN NOT NULL DEFAULT false,
 failure_code VARCHAR(80),
 encrypted_payload BYTEA,
 wrapped_data_key BYTEA,
 encryption_key_id VARCHAR(255),
 lease_generation BIGINT NOT NULL DEFAULT 0 CHECK(lease_generation >= 0),
 lease_token UUID,
 lease_expires_at TIMESTAMP(6),
 processing_started_at TIMESTAMP(6),
 provider_request_started_at TIMESTAMP(6),
 next_attempt_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 completed_at TIMESTAMP(6),
 UNIQUE(user_id,idempotency_key),
 FOREIGN KEY(external_connection_id,user_id) REFERENCES external_connections(id,user_id),
 CHECK(source_organization <> organization),
 CHECK(expires_at > created_at AND updated_at >= created_at),
 CHECK((encrypted_payload IS NULL AND wrapped_data_key IS NULL AND encryption_key_id IS NULL)
    OR (encrypted_payload IS NOT NULL AND octet_length(encrypted_payload) BETWEEN 29 AND 16384
      AND wrapped_data_key IS NOT NULL AND octet_length(wrapped_data_key) BETWEEN 32 AND 4096
      AND encryption_key_id IS NOT NULL AND length(btrim(encryption_key_id)) > 0)),
 CHECK((lease_token IS NULL AND lease_expires_at IS NULL) OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_generation > 0)),
 CHECK(status <> 'RUNNING' OR lease_token IS NOT NULL),
 CHECK(status NOT IN ('QUEUED','EXPIRED') OR provider_request_started_at IS NULL),
 CHECK(status NOT IN ('UNKNOWN','ACTION_REQUIRED') OR provider_request_started_at IS NOT NULL),
 CHECK((status IN ('CONNECTED','FAILED','EXPIRED')) = (completed_at IS NOT NULL)),
 CHECK(completed_at IS NULL OR (completed_at >= created_at AND encrypted_payload IS NULL)),
 CHECK((status IN ('FAILED','UNKNOWN','ACTION_REQUIRED','EXPIRED')) = (failure_code IS NOT NULL)),
 CHECK(failure_code IS NULL OR failure_code ~ '^([A-Z][A-Z0-9_]{0,79}|CF-[0-9]{5})$')
);
CREATE UNIQUE INDEX uk_certificate_reference_active_user ON certificate_reference_operations(user_id)
 WHERE status IN ('QUEUED','RUNNING','UNKNOWN','ACTION_REQUIRED');
CREATE INDEX idx_certificate_reference_work ON certificate_reference_operations(next_attempt_at,id)
 WHERE status IN ('QUEUED','UNKNOWN');

CREATE TABLE certificate_reference_command_outbox (
 message_id UUID PRIMARY KEY,
 operation_id UUID NOT NULL UNIQUE REFERENCES certificate_reference_operations(id),
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSING','PUBLISHED')),
 lease_generation BIGINT NOT NULL DEFAULT 0 CHECK(lease_generation >= 0),
 lease_token UUID,
 lease_expires_at TIMESTAMP(6),
 next_attempt_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 published_at TIMESTAMP(6),
 UNIQUE(message_id,operation_id),
 CHECK((status='PROCESSING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
 CHECK((status='PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX idx_certificate_reference_publication ON certificate_reference_command_outbox(next_attempt_at,message_id) WHERE status='PENDING';

CREATE TABLE certificate_reference_command_inbox (
 message_id UUID PRIMARY KEY,
 operation_id UUID NOT NULL UNIQUE,
 payload_hash VARCHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 received_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(message_id,operation_id) REFERENCES certificate_reference_command_outbox(message_id,operation_id)
);

CREATE FUNCTION guard_reference_connection_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.connected_id_ciphertext,NEW.active,NEW.configuration_fingerprint)
     IS NOT DISTINCT FROM ROW(OLD.connected_id_ciphertext,OLD.active,OLD.configuration_fingerprint) THEN RETURN NEW; END IF;
 IF EXISTS(SELECT 1 FROM certificate_reference_operations WHERE external_connection_id=OLD.id
   AND status IN ('QUEUED','RUNNING','UNKNOWN','ACTION_REQUIRED')) THEN
  RAISE EXCEPTION 'Unresolved reference authentication prevents connection mutation' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_reference_connection_mutation BEFORE UPDATE OR DELETE ON external_connections
 FOR EACH ROW EXECUTE FUNCTION guard_reference_connection_mutation();

CREATE FUNCTION guard_reference_user_deactivation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.active AND NOT NEW.active AND EXISTS(SELECT 1 FROM certificate_reference_operations WHERE user_id=OLD.id
   AND status IN ('QUEUED','RUNNING','UNKNOWN','ACTION_REQUIRED')) THEN
  RAISE EXCEPTION 'Unresolved reference authentication prevents user deactivation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_reference_user_deactivation BEFORE UPDATE ON users
 FOR EACH ROW EXECUTE FUNCTION guard_reference_user_deactivation();

CREATE FUNCTION guard_reference_operation_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.user_id,NEW.external_connection_id,NEW.source_organization,NEW.organization,
        NEW.idempotency_key,NEW.request_hash,NEW.connection_hash,NEW.created_at,NEW.expires_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.external_connection_id,OLD.source_organization,OLD.organization,
        OLD.idempotency_key,OLD.request_hash,OLD.connection_hash,OLD.created_at,OLD.expires_at) THEN
  RAISE EXCEPTION 'Reference operation identity is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.provider_request_started_at IS NOT NULL AND NEW.provider_request_started_at IS DISTINCT FROM OLD.provider_request_started_at THEN
  RAISE EXCEPTION 'Reference request-start marker is immutable' USING ERRCODE='23514';
 END IF;
 IF NEW.lease_generation<OLD.lease_generation OR (NEW.lease_token IS DISTINCT FROM OLD.lease_token
     AND NEW.lease_token IS NOT NULL AND NEW.lease_generation<=OLD.lease_generation) THEN
  RAISE EXCEPTION 'Reference execution fencing must advance' USING ERRCODE='23514';
 END IF;
 IF OLD.encrypted_payload IS NOT NULL AND NEW.encrypted_payload IS NOT NULL AND
   ROW(NEW.encrypted_payload,NEW.wrapped_data_key,NEW.encryption_key_id)
     IS DISTINCT FROM ROW(OLD.encrypted_payload,OLD.wrapped_data_key,OLD.encryption_key_id) THEN
  RAISE EXCEPTION 'Reference input envelope is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.encrypted_payload IS NULL AND NEW.encrypted_payload IS NOT NULL THEN
  RAISE EXCEPTION 'Cleared reference input cannot be restored' USING ERRCODE='23514';
 END IF;
 IF OLD.status IN ('CONNECTED','FAILED','EXPIRED') AND NEW IS DISTINCT FROM OLD THEN
  RAISE EXCEPTION 'Reference final result is immutable' USING ERRCODE='23514';
 END IF;
 IF NEW.status<>OLD.status AND NOT (
    (OLD.status='QUEUED' AND NEW.status IN ('RUNNING','EXPIRED','FAILED'))
    OR (OLD.status='RUNNING' AND NEW.status IN ('CONNECTED','FAILED','UNKNOWN','ACTION_REQUIRED','EXPIRED'))
    OR (OLD.status='RUNNING' AND NEW.status='QUEUED' AND OLD.provider_request_started_at IS NULL)
    OR (OLD.status='UNKNOWN' AND NEW.status='CONNECTED')) THEN
  RAISE EXCEPTION 'Invalid reference state transition' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_reference_operation_transition BEFORE UPDATE ON certificate_reference_operations
 FOR EACH ROW EXECUTE FUNCTION guard_reference_operation_transition();

COMMENT ON TABLE certificate_reference_operations IS 'Reference authentication metadata and short-lived encrypted extra inputs; never store DER/KEY or plaintext passwords/connectedId.';
