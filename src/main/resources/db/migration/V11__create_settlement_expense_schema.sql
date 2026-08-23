CREATE TABLE settlement_expenses (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    source VARCHAR(30) NOT NULL,
    description VARCHAR(500) NOT NULL,
    amount BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_expenses PRIMARY KEY (id),
    CONSTRAINT fk_settlement_expenses_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT ck_settlement_expenses_source CHECK (
        source IN ('MANUAL', 'PAYMENT_HISTORY', 'RECEIPT_OCR')
    ),
    CONSTRAINT ck_settlement_expenses_description_not_blank
        CHECK (CHAR_LENGTH(BTRIM(description)) BETWEEN 1 AND 500),
    CONSTRAINT ck_settlement_expenses_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_settlement_expenses_display_order_nonnegative
        CHECK (display_order >= 0),
    CONSTRAINT uk_settlement_expenses_settlement_display_order
        UNIQUE (settlement_id, display_order)
);
