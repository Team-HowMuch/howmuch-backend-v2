ALTER TABLE settlement_item_selections
    ADD COLUMN units INTEGER NOT NULL DEFAULT 1;

ALTER TABLE settlement_item_selections
    ADD CONSTRAINT ck_settlement_item_selections_units CHECK (units >= 1);
