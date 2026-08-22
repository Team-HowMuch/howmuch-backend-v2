CREATE TABLE settlement_participants (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    source VARCHAR(30) NOT NULL,
    linked_user_id BIGINT,
    phone_hash VARCHAR(64),
    display_name VARCHAR(100) NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_participants PRIMARY KEY (id),
    CONSTRAINT fk_settlement_participants_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_participants_linked_user
        FOREIGN KEY (linked_user_id) REFERENCES users (id),
    CONSTRAINT ck_settlement_participants_source CHECK (
        source IN ('REGISTERED_USER', 'CONTACT', 'GROUP_MEMBER')
    ),
    CONSTRAINT ck_settlement_participants_identity CHECK (
        (
            source IN ('REGISTERED_USER', 'GROUP_MEMBER')
            AND linked_user_id IS NOT NULL
            AND phone_hash IS NULL
        )
        OR
        (
            source = 'CONTACT'
            AND (
                (linked_user_id IS NOT NULL AND phone_hash IS NULL)
                OR
                (linked_user_id IS NULL AND phone_hash ~ '^[0-9a-f]{64}$')
            )
        )
    ),
    CONSTRAINT ck_settlement_participants_display_name_not_blank
        CHECK (CHAR_LENGTH(BTRIM(display_name)) > 0),
    CONSTRAINT ck_settlement_participants_display_order_nonnegative
        CHECK (display_order >= 0),
    CONSTRAINT uk_settlement_participants_settlement_linked_user
        UNIQUE (settlement_id, linked_user_id),
    CONSTRAINT uk_settlement_participants_settlement_phone_hash
        UNIQUE (settlement_id, phone_hash),
    CONSTRAINT uk_settlement_participants_settlement_display_order
        UNIQUE (settlement_id, display_order)
);
