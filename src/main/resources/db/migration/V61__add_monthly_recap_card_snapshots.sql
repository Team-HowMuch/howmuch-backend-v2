-- Keep card values in the same immutable row as the original recap summary.
-- No defaults or backfill: previously generated recaps retain absent cards.
ALTER TABLE monthly_recaps
    ADD COLUMN categories_snapshot JSONB,
    ADD COLUMN record_snapshot JSONB,
    ADD COLUMN benefits_snapshot JSONB;

-- SQL NULL represents an absent card; a JSON null literal is not a card value.
-- CASE protects array-length checks from non-array inputs. Nested field validity
-- and aggregation rules belong to the application snapshot contracts.
ALTER TABLE monthly_recaps
    ADD CONSTRAINT ck_monthly_recaps_categories_snapshot CHECK (
        categories_snapshot IS NULL
        OR CASE WHEN jsonb_typeof(categories_snapshot) = 'array'
            THEN jsonb_array_length(categories_snapshot) <= 3
            ELSE FALSE
        END
    ),
    ADD CONSTRAINT ck_monthly_recaps_record_snapshot CHECK (
        record_snapshot IS NULL OR jsonb_typeof(record_snapshot) = 'object'
    ),
    ADD CONSTRAINT ck_monthly_recaps_benefits_snapshot CHECK (
        benefits_snapshot IS NULL
        OR CASE WHEN jsonb_typeof(benefits_snapshot) = 'array'
            THEN jsonb_array_length(benefits_snapshot) <= 2
            ELSE FALSE
        END
    );

-- The existing guard_monthly_recap_snapshot trigger compares the complete row,
-- so it also prevents changes to these columns without replacing the trigger.
COMMENT ON COLUMN monthly_recaps.categories_snapshot IS
    'Category ranking snapshot: rank, category (code, name, icon), totalOwnAmount. Copy display values; do not resolve live category data on reads. SQL NULL means no card.';
COMMENT ON COLUMN monthly_recaps.record_snapshot IS
    'Record snapshot with nullable busiestWeekday, frequentCategory, largestSettlement and fastestSettlement. Copy category display values and settlement title. SQL NULL means no card.';
COMMENT ON COLUMN monthly_recaps.benefits_snapshot IS
    'Benefit snapshot: title, reason, expiresAt, url and optional logoUrl. SQL NULL when partner data is unavailable; do not generate placeholder offers.';
