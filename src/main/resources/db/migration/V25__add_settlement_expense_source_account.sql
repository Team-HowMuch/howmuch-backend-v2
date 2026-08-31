ALTER TABLE settlement_expenses
    ADD COLUMN source_account_id BIGINT;

WITH inferred_source_accounts AS (
    SELECT expense.id AS expense_id,
           MIN(transaction.account_id) AS account_id
    FROM settlement_expenses expense
    JOIN settlements settlement
      ON settlement.id = expense.settlement_id
    JOIN account_transactions transaction
      ON transaction.external_transaction_key = expense.source_reference
    JOIN account_records account
      ON account.id = transaction.account_id
     AND account.user_id = settlement.creator_id
    WHERE expense.source = 'PAYMENT_HISTORY'
    GROUP BY expense.id
    HAVING COUNT(DISTINCT transaction.account_id) = 1
)
UPDATE settlement_expenses expense
SET source_account_id = inferred.account_id
FROM inferred_source_accounts inferred
WHERE expense.id = inferred.expense_id;

ALTER TABLE settlement_expenses
    ADD CONSTRAINT fk_settlement_expenses_source_account
        FOREIGN KEY (source_account_id) REFERENCES account_records (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_settlement_expenses_source_account CHECK (
        source_account_id IS NULL OR source = 'PAYMENT_HISTORY'
    );

CREATE INDEX idx_settlement_expenses_source_account_reference
    ON settlement_expenses (source_account_id, source_reference)
    WHERE source = 'PAYMENT_HISTORY' AND source_account_id IS NOT NULL;
