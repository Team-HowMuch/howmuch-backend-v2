ALTER TABLE settlement_items
    ADD COLUMN discount BIGINT NOT NULL DEFAULT 0,
    DROP CONSTRAINT ck_settlement_items_amount_positive,
    ADD CONSTRAINT ck_settlement_items_amount_positive CHECK (amount >= 0),
    ADD CONSTRAINT ck_settlement_items_discount_range CHECK (
        discount >= 0 AND discount <= amount
    );

ALTER TABLE settlement_expenses
    ADD COLUMN discount BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_settlement_expenses_discount_nonnegative CHECK (discount >= 0);

CREATE TABLE settlement_item_options (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    item_id UUID NOT NULL,
    name VARCHAR(500) NOT NULL,
    price BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT pk_settlement_item_options PRIMARY KEY (id),
    CONSTRAINT fk_settlement_item_options_item
        FOREIGN KEY (settlement_id, item_id)
            REFERENCES settlement_items (settlement_id, id)
            ON DELETE CASCADE,
    CONSTRAINT ck_settlement_item_options_name_not_blank
        CHECK (CHAR_LENGTH(BTRIM(name)) BETWEEN 1 AND 500),
    CONSTRAINT ck_settlement_item_options_price_nonnegative CHECK (price >= 0),
    CONSTRAINT ck_settlement_item_options_display_order_nonnegative
        CHECK (display_order >= 0),
    CONSTRAINT uk_settlement_item_options_item_display_order
        UNIQUE (item_id, display_order)
);
