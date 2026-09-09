ALTER TABLE finance_account_refs
    ADD COLUMN sync_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_finance_account_refs_sync_version
        CHECK (sync_version >= 0);

ALTER TABLE account_transaction_sync_jobs
    ADD COLUMN sync_version BIGINT;

WITH ranked_jobs AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY account_id
            ORDER BY created_at, id
        ) AS sync_version
    FROM account_transaction_sync_jobs
)
UPDATE account_transaction_sync_jobs AS job
SET sync_version = ranked_job.sync_version
FROM ranked_jobs AS ranked_job
WHERE job.id = ranked_job.id;

ALTER TABLE account_transaction_sync_jobs
    ALTER COLUMN sync_version SET NOT NULL,
    ADD CONSTRAINT uk_account_transaction_sync_jobs_account_sync_version
        UNIQUE (account_id, sync_version),
    ADD CONSTRAINT ck_account_transaction_sync_jobs_sync_version
        CHECK (sync_version > 0);

UPDATE finance_account_refs AS account_ref
SET sync_version = COALESCE(
    (
        SELECT MAX(job.sync_version)
        FROM account_transaction_sync_jobs AS job
        WHERE job.account_id = account_ref.account_id
    ),
    0
);
