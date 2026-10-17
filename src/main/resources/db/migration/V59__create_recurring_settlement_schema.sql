-- Group recurring fixed-expense settlement schema.
--
-- A recurring settlement is a fixed snapshot taken from one DRAFT group settlement:
-- a MANUAL expense, the participant allocations, and an explicit receiving account.
-- It publishes a brand new Settlement occurrence on each due date and never charges
-- anything before first_run_date. Cancelling stops only future occurrences.
--
-- Ownership/consistency rules that span rows (source is a DRAFT with MANUAL expenses,
-- at least two distinct active group members including the creator, positive
-- allocations summing to total_amount, membership/account permission) are enforced by
-- the Domain and Application layers. SQL keeps only row-level CHECK, FK, UNIQUE, and
-- index constraints here.
--
-- next_run_at, failure state, and retry_at describe the next unpublished occurrence.
-- next_run_at advances only after a successful occurrence; a failure keeps next_run_at
-- and only sets retry_at. D-day, relative membership, and the viewer's own amount are
-- computed at read time and are deliberately not stored.
--
-- Timestamps are UTC LocalDateTime, matching the rest of the schema. Nothing here
-- duplicates the Spring Batch metadata created in V53.
--
-- History is preserved under the existing soft-delete conventions:
--   groups   are soft deleted (status/deleted_at),
--   users    are anonymized, never physically deleted,
--   accounts are copied into account_records before physical deletion (V17),
-- so every FK below uses ON DELETE RESTRICT and no historical row is cascade-deleted.

CREATE TABLE recurring_settlements (
    id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    source_settlement_id UUID NOT NULL,
    title VARCHAR(100) NOT NULL,
    total_amount BIGINT NOT NULL,
    category_code VARCHAR(50) NOT NULL,
    receiving_account_id BIGINT NOT NULL,
    frequency VARCHAR(10) NOT NULL,
    day_of_week VARCHAR(9),
    day_of_month INTEGER,
    first_run_date DATE NOT NULL,
    next_run_at TIMESTAMP(6) WITHOUT TIME ZONE,
    status VARCHAR(10) NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 0,
    retry_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_failure_code VARCHAR(64),
    last_failure_at TIMESTAMP(6) WITHOUT TIME ZONE,
    cancellation_reason VARCHAR(32),
    cancelled_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_recurring_settlements PRIMARY KEY (id),
    -- One recurring setup per original draft.
    CONSTRAINT uk_recurring_settlements_source_settlement UNIQUE (source_settlement_id),
    -- The original draft is soft deleted (deleted_at), so the row and this FK stay valid.
    CONSTRAINT fk_recurring_settlements_source_settlement
        FOREIGN KEY (source_settlement_id) REFERENCES settlements (id) ON DELETE RESTRICT,
    CONSTRAINT fk_recurring_settlements_group
        FOREIGN KEY (group_id) REFERENCES groups (id) ON DELETE RESTRICT,
    CONSTRAINT fk_recurring_settlements_creator
        FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT,
    -- Explicit receiving-account snapshot. account_records keeps the record after the
    -- live account is unlinked/deleted, so the setup never loses its receiving account.
    CONSTRAINT fk_recurring_settlements_receiving_account
        FOREIGN KEY (receiving_account_id) REFERENCES account_records (id) ON DELETE RESTRICT,
    CONSTRAINT ck_recurring_settlements_title_not_blank
        CHECK (CHAR_LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_recurring_settlements_total_amount_positive
        CHECK (total_amount > 0),
    -- Storable settlement-category code contract shared with V56 settlement_categories
    -- and SettlementCategory.isStorableCode. The selectable codes are the
    -- SETTLEMENT_CATEGORY common-code catalog (attr2 = 'Y'); the catalog itself is not
    -- duplicated here and is validated by the Application layer.
    CONSTRAINT ck_recurring_settlements_category_code
        CHECK (category_code ~ '^[A-Z][A-Z_]{0,49}$'),
    CONSTRAINT ck_recurring_settlements_frequency
        CHECK (frequency IN ('WEEKLY', 'MONTHLY')),
    -- PostgreSQL CHECK passes on UNKNOWN, so every branch spells out IS NULL/IS NOT NULL.
    CONSTRAINT ck_recurring_settlements_schedule CHECK (
        (frequency = 'WEEKLY'
            AND day_of_week IS NOT NULL
            AND day_of_week IN
                ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')
            AND day_of_month IS NULL)
        OR (frequency = 'MONTHLY'
            AND day_of_week IS NULL
            AND day_of_month IS NOT NULL
            AND day_of_month BETWEEN 1 AND 31)
    ),
    CONSTRAINT ck_recurring_settlements_status
        CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_recurring_settlements_failure_count
        CHECK (failure_count BETWEEN 0 AND 3),
    -- PostgreSQL CHECK passes on UNKNOWN, so the second branch explicitly requires a
    -- non-NULL code. Otherwise (code NULL, time set) stays UNKNOWN and is accepted.
    CONSTRAINT ck_recurring_settlements_failure CHECK (
        (last_failure_code IS NULL AND last_failure_at IS NULL)
        OR (last_failure_code IS NOT NULL
            AND last_failure_code IN ('RECURRING_TRANSIENT_DATABASE_FAILURE',
                'RECURRING_UNEXPECTED_EXECUTION_FAILURE',
                'RECURRING_MANUAL_EXECUTION_FAILURE')
            AND last_failure_at IS NOT NULL)
    ),
    -- An active setup always waits for its next occurrence; a cancelled one keeps the
    -- reason and time only and never publishes again.
    CONSTRAINT ck_recurring_settlements_cancellation CHECK (
        (status = 'ACTIVE'
            AND next_run_at IS NOT NULL
            AND cancellation_reason IS NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED'
            AND next_run_at IS NULL
            AND cancellation_reason IS NOT NULL AND cancelled_at IS NOT NULL)
    ),
    CONSTRAINT ck_recurring_settlements_cancellation_reason CHECK (
        cancellation_reason IS NULL OR cancellation_reason IN
            ('USER_CANCELLED', 'GROUP_UNAVAILABLE', 'CREATOR_UNAVAILABLE',
             'PARTICIPANT_UNAVAILABLE', 'ACCOUNT_UNAVAILABLE')
    ),
    -- Only a transient automatic failure below the retry budget schedules a retry.
    -- last_failure_code IS NOT NULL keeps a NULL code from making the branch UNKNOWN
    -- (which a CHECK would accept) instead of false.
    CONSTRAINT ck_recurring_settlements_retry CHECK (
        retry_at IS NULL OR (
            status = 'ACTIVE'
            AND failure_count BETWEEN 1 AND 2
            AND last_failure_code IS NOT NULL
            AND last_failure_code = 'RECURRING_TRANSIENT_DATABASE_FAILURE'
            AND last_failure_at IS NOT NULL)
    ),
    CONSTRAINT ck_recurring_settlements_times CHECK (created_at <= updated_at)
);

-- Due-candidate scan (status ACTIVE, below the automatic retry budget).
CREATE INDEX idx_recurring_settlements_due
    ON recurring_settlements (next_run_at, id)
    WHERE status = 'ACTIVE' AND failure_count < 3;

-- Group list page, newest first with a stable id tie-break.
CREATE INDEX idx_recurring_settlements_group
    ON recurring_settlements (group_id, created_at DESC, id DESC);

-- Fixed participant allocations. At least two distinct users including the creator and
-- a positive sum equal to total_amount are enforced by the Domain, not by row CHECKs.
CREATE TABLE recurring_settlement_participants (
    recurring_settlement_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT pk_recurring_settlement_participants
        PRIMARY KEY (recurring_settlement_id, user_id),
    CONSTRAINT uk_recurring_settlement_participants_order
        UNIQUE (recurring_settlement_id, display_order),
    CONSTRAINT fk_recurring_settlement_participants_settlement
        FOREIGN KEY (recurring_settlement_id) REFERENCES recurring_settlements (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_recurring_settlement_participants_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_recurring_settlement_participants_amount CHECK (amount > 0),
    CONSTRAINT ck_recurring_settlement_participants_order CHECK (display_order >= 0)
);

-- MANUAL expense snapshot copied once from the source draft.
CREATE TABLE recurring_settlement_expenses (
    id UUID NOT NULL,
    recurring_settlement_id UUID NOT NULL,
    description VARCHAR(500) NOT NULL,
    amount BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT pk_recurring_settlement_expenses PRIMARY KEY (id),
    CONSTRAINT uk_recurring_settlement_expenses_order
        UNIQUE (recurring_settlement_id, display_order),
    CONSTRAINT fk_recurring_settlement_expenses_settlement
        FOREIGN KEY (recurring_settlement_id) REFERENCES recurring_settlements (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_recurring_settlement_expenses_description
        CHECK (CHAR_LENGTH(BTRIM(description)) BETWEEN 1 AND 500),
    CONSTRAINT ck_recurring_settlement_expenses_amount CHECK (amount > 0),
    CONSTRAINT ck_recurring_settlement_expenses_order CHECK (display_order >= 0)
);

-- One row per actually published occurrence. The two UNIQUE constraints are the final
-- guard against duplicate publication for the same scheduled run.
CREATE TABLE recurring_settlement_occurrences (
    id UUID NOT NULL,
    recurring_settlement_id UUID NOT NULL,
    scheduled_for TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    settlement_id UUID NOT NULL,
    requested_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_recurring_settlement_occurrences PRIMARY KEY (id),
    CONSTRAINT uk_recurring_settlement_occurrences_schedule
        UNIQUE (recurring_settlement_id, scheduled_for),
    CONSTRAINT uk_recurring_settlement_occurrences_settlement UNIQUE (settlement_id),
    CONSTRAINT fk_recurring_settlement_occurrences_template
        FOREIGN KEY (recurring_settlement_id) REFERENCES recurring_settlements (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_recurring_settlement_occurrences_published_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE RESTRICT
);

CREATE INDEX idx_recurring_settlement_occurrences_history
    ON recurring_settlement_occurrences (recurring_settlement_id, scheduled_for DESC, id DESC);

COMMENT ON TABLE recurring_settlements IS
    'Group recurring fixed-expense setup. Publishes a new Settlement each due date; category_code is the template default propagated to occurrences.';
