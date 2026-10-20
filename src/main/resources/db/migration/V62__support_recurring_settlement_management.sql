-- Direct form registration has no source draft. Preserve the existing FK and UNIQUE:
-- non-NULL sources must still exist and can be converted only once, while PostgreSQL's
-- default NULLS DISTINCT behavior allows multiple directly registered templates.
ALTER TABLE recurring_settlements
    ALTER COLUMN source_settlement_id DROP NOT NULL;

-- Hide a setup without deleting the template, its participant/expense snapshot, or
-- any published occurrence. Existing rows remain visible (deleted_at IS NULL).
ALTER TABLE recurring_settlements
    ADD COLUMN deleted_at TIMESTAMP(6) WITHOUT TIME ZONE;

-- Deletion also stops publication. The existing cancellation/retry constraints ensure
-- CANCELLED rows have no next_run_at or retry_at and retain their cancellation reason.
-- An already-cancelled setup may be deleted later without changing cancelled_at.
ALTER TABLE recurring_settlements
    ADD CONSTRAINT ck_recurring_settlements_deletion CHECK (
        deleted_at IS NULL OR (
            status = 'CANCELLED'
            AND cancelled_at IS NOT NULL
            AND created_at <= cancelled_at
            AND cancelled_at <= deleted_at
            AND deleted_at <= updated_at
        )
    );

COMMENT ON COLUMN recurring_settlements.source_settlement_id IS
    'Original converted DRAFT settlement; NULL for direct form registration. Non-NULL sources remain unique.';

COMMENT ON COLUMN recurring_settlements.deleted_at IS
    'UTC soft-deletion time. Deleted setups are CANCELLED; published settlements and occurrence history are preserved. Application reads must exclude deleted setups.';
