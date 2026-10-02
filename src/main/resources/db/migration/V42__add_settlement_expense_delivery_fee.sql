ALTER TABLE settlement_expenses
    ADD COLUMN delivery_fee BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_settlement_expenses_delivery_fee_nonnegative
        CHECK (delivery_fee >= 0);
