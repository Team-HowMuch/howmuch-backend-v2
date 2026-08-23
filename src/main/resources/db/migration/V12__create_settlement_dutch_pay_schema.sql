ALTER TABLE settlements
    ADD COLUMN allocation_type VARCHAR(30);

ALTER TABLE settlements
    ADD CONSTRAINT ck_settlements_allocation_type CHECK (
        allocation_type IS NULL
        OR allocation_type IN ('DUTCH_PAY', 'ITEM_ALLOCATION')
    );

ALTER TABLE settlement_participants
    ADD CONSTRAINT uk_settlement_participants_settlement_id_id
        UNIQUE (settlement_id, id);

CREATE TABLE settlement_dutch_pay_allocations (
    id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_settlement_dutch_pay_allocations PRIMARY KEY (id),
    CONSTRAINT fk_settlement_dutch_pay_allocations_participant
        FOREIGN KEY (settlement_id, participant_id)
            REFERENCES settlement_participants (settlement_id, id)
            ON DELETE CASCADE,
    CONSTRAINT ck_settlement_dutch_pay_allocations_amount_nonnegative
        CHECK (amount >= 0),
    CONSTRAINT uk_settlement_dutch_pay_allocations_settlement_participant
        UNIQUE (settlement_id, participant_id)
);
