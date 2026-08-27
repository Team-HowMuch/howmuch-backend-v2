CREATE TABLE settlements (
    id UUID NOT NULL,
    creator_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    total_amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlements PRIMARY KEY (id),
    CONSTRAINT fk_settlements_creator FOREIGN KEY (creator_id) REFERENCES users (id),
    CONSTRAINT ck_settlements_title_not_blank CHECK (CHAR_LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_settlements_total_amount_positive CHECK (total_amount > 0),
    CONSTRAINT ck_settlements_status CHECK (
        status IN ('DRAFT', 'REQUESTED', 'AWAITING_DEPOSIT', 'SUSPENDED', 'COMPLETED')
    )
);

CREATE INDEX idx_settlements_creator_created_at
    ON settlements (creator_id, created_at DESC);
