ALTER TABLE settlement_participants
    DROP CONSTRAINT ck_settlement_participants_identity;

ALTER TABLE settlement_participants
    ADD CONSTRAINT ck_settlement_participants_identity CHECK (
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
                (
                    linked_user_id IS NULL
                    AND phone_hash IS NOT NULL
                    AND phone_hash ~ '^[0-9a-f]{64}$'
                )
                OR
                (linked_user_id IS NULL AND phone_hash IS NULL)
            )
        )
    );
