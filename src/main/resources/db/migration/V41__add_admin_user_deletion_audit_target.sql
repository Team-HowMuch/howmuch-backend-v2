ALTER TABLE auth_audit_logs
    ADD COLUMN target_user_id BIGINT,
    ADD CONSTRAINT chk_auth_audit_logs_admin_user_deletion_target
        CHECK (event_type <> 'ADMIN_USER_DELETION' OR target_user_id IS NOT NULL);
