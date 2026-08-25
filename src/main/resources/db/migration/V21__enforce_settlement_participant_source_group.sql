UPDATE settlement_participants
SET source = 'REGISTERED_USER'
WHERE source = 'GROUP_MEMBER'
  AND source_group_id IS NULL;

UPDATE settlement_participants
SET source_group_id = NULL
WHERE source <> 'GROUP_MEMBER'
  AND source_group_id IS NOT NULL;

ALTER TABLE settlement_participants
    ADD CONSTRAINT ck_settlement_participants_source_group
        CHECK (
            (source = 'GROUP_MEMBER' AND source_group_id IS NOT NULL)
            OR (source <> 'GROUP_MEMBER' AND source_group_id IS NULL)
        ) NOT VALID;

ALTER TABLE settlement_participants
    VALIDATE CONSTRAINT ck_settlement_participants_source_group;
