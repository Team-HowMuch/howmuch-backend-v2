ALTER TABLE settlement_links
    ADD COLUMN token_ciphertext VARCHAR(1024);

ALTER TABLE settlement_links
    ADD CONSTRAINT ck_settlement_links_token_ciphertext
        CHECK (
            token_ciphertext IS NULL
            OR CHAR_LENGTH(BTRIM(token_ciphertext)) > 0
        );
