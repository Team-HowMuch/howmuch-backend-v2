ALTER TABLE settlement_expenses
    ADD CONSTRAINT uk_settlement_expenses_settlement_id_id
        UNIQUE (settlement_id, id);

CREATE TABLE settlement_items (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    expense_id UUID NOT NULL,
    name VARCHAR(500) NOT NULL,
    quantity INTEGER NOT NULL,
    amount BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_items PRIMARY KEY (id),
    CONSTRAINT fk_settlement_items_expense
        FOREIGN KEY (settlement_id, expense_id)
            REFERENCES settlement_expenses (settlement_id, id)
            ON DELETE CASCADE,
    CONSTRAINT ck_settlement_items_name_not_blank
        CHECK (CHAR_LENGTH(BTRIM(name)) BETWEEN 1 AND 500),
    CONSTRAINT ck_settlement_items_quantity_positive
        CHECK (quantity > 0),
    CONSTRAINT ck_settlement_items_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_settlement_items_display_order_nonnegative
        CHECK (display_order >= 0),
    CONSTRAINT uk_settlement_items_expense_display_order
        UNIQUE (expense_id, display_order),
    CONSTRAINT uk_settlement_items_settlement_id_id
        UNIQUE (settlement_id, id)
);

CREATE INDEX idx_settlement_items_settlement_display_order
    ON settlement_items (settlement_id, display_order, id);

CREATE TABLE settlement_item_selections (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    item_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    fraction VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_item_selections PRIMARY KEY (id),
    CONSTRAINT fk_settlement_item_selections_item
        FOREIGN KEY (settlement_id, item_id)
            REFERENCES settlement_items (settlement_id, id)
            ON DELETE CASCADE,
    CONSTRAINT fk_settlement_item_selections_participant
        FOREIGN KEY (settlement_id, participant_id)
            REFERENCES settlement_participants (settlement_id, id)
            ON DELETE CASCADE,
    CONSTRAINT ck_settlement_item_selections_fraction CHECK (
        fraction IN ('ONE', 'HALF', 'THIRD', 'QUARTER')
    ),
    CONSTRAINT uk_settlement_item_selections_item_participant
        UNIQUE (settlement_id, item_id, participant_id)
);

CREATE INDEX idx_settlement_item_selections_settlement_participant
    ON settlement_item_selections (settlement_id, participant_id, item_id);

CREATE TABLE settlement_item_submissions (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    submitted_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_item_submissions PRIMARY KEY (id),
    CONSTRAINT fk_settlement_item_submissions_participant
        FOREIGN KEY (settlement_id, participant_id)
            REFERENCES settlement_participants (settlement_id, id)
            ON DELETE CASCADE,
    CONSTRAINT uk_settlement_item_submissions_settlement_participant
        UNIQUE (settlement_id, participant_id)
);
