ALTER TABLE settlement_expenses
    ADD COLUMN source_reference VARCHAR(255);

ALTER TABLE settlement_expenses
    ADD CONSTRAINT ck_settlement_expenses_source_reference CHECK (
        (
            source = 'MANUAL'
            AND source_reference IS NULL
        )
        OR (
            source IN ('PAYMENT_HISTORY', 'RECEIPT_OCR')
            AND source_reference IS NOT NULL
            AND source_reference = BTRIM(source_reference)
            AND CHAR_LENGTH(source_reference) BETWEEN 1 AND 255
        )
    );

ALTER TABLE settlement_expenses
    ADD CONSTRAINT uk_settlement_expenses_settlement_source_reference
        UNIQUE (settlement_id, source, source_reference);
