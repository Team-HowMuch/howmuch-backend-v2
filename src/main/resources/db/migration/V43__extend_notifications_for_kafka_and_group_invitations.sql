-- Keep existing inbox records and delivery IDs. The physical delivery table name
-- stays stable while settlement-specific rows and generic rows coexist.
ALTER TABLE user_notifications
    ADD COLUMN target_type VARCHAR(40),
    ADD COLUMN target_id VARCHAR(64),
    ALTER COLUMN allocation_type DROP NOT NULL,
    DROP CONSTRAINT ck_user_notifications_type,
    DROP CONSTRAINT ck_user_notifications_allocation_type;

UPDATE user_notifications
SET target_type = CASE
        WHEN notification_type = 'SETTLEMENT_REQUEST' THEN 'SETTLEMENT_PARTICIPATION'
        ELSE 'SETTLEMENT_DETAIL'
    END,
    target_id = settlement_id::TEXT;

ALTER TABLE user_notifications
    ALTER COLUMN target_type SET NOT NULL,
    ADD CONSTRAINT ck_user_notifications_type
        CHECK (notification_type IN (
            'SETTLEMENT_REQUEST',
            'SETTLEMENT_REMINDER',
            'SETTLEMENT_PAYMENT_COMPLETED',
            'SETTLEMENT_COMPLETED',
            'GROUP_INVITATION',
            'GROUP_MEMBER_LEFT',
            'MONTHLY_RECAP'
        )),
    ADD CONSTRAINT ck_user_notifications_target_type
        CHECK (target_type IN (
            'SETTLEMENT_PARTICIPATION',
            'SETTLEMENT_DETAIL',
            'GROUP_INVITATION',
            'GROUP_DETAIL',
            'MONTHLY_RECAP'
        )),
    ADD CONSTRAINT ck_user_notifications_type_target
        CHECK (
            (notification_type IN (
                'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER',
                'SETTLEMENT_PAYMENT_COMPLETED', 'SETTLEMENT_COMPLETED'
            ) AND target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL'))
            OR (notification_type = 'GROUP_INVITATION' AND target_type = 'GROUP_INVITATION')
            OR (notification_type = 'GROUP_MEMBER_LEFT' AND target_type = 'GROUP_DETAIL')
            OR (notification_type = 'MONTHLY_RECAP' AND target_type = 'MONTHLY_RECAP')
        ),
    ADD CONSTRAINT ck_user_notifications_allocation_type
        CHECK (
            (target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL')
                AND allocation_type IS NOT NULL
                AND allocation_type IN ('DUTCH_PAY', 'ITEM_ALLOCATION'))
            OR (target_type NOT IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL')
                AND allocation_type IS NULL AND settlement_id IS NULL)
        ),
    ADD CONSTRAINT ck_user_notifications_target_id
        CHECK (
            (target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL')
                AND (
                    (target_id IS NULL AND settlement_id IS NULL)
                    OR (target_id IS NOT NULL
                        AND target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                        AND (settlement_id IS NULL OR LOWER(target_id) = settlement_id::TEXT))
                ))
            OR (target_type = 'GROUP_INVITATION' AND target_id IS NOT NULL
                AND target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
            OR (target_type = 'GROUP_DETAIL' AND target_id IS NOT NULL
                AND target_id ~ '^[1-9][0-9]{0,18}$')
            OR (target_type = 'MONTHLY_RECAP' AND target_id IS NOT NULL
                AND target_id ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
        ),
    ADD CONSTRAINT uk_user_notifications_delivery_recipient
        UNIQUE (id, recipient_user_id);

COMMENT ON COLUMN user_notifications.target_type IS
    'Typed application route. Target existence and current-actor authorization are checked before opening it.';
COMMENT ON COLUMN user_notifications.target_id IS
    'UUID for settlement/invitation, decimal group ID, or YYYY-MM recap month. NULL preserves old notifications whose settlement was physically deleted.';

ALTER TABLE settlement_notification_outbox
    ALTER COLUMN settlement_id DROP NOT NULL,
    ADD COLUMN delivery_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN delivery_message_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN publication_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN publication_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_publication_at TIMESTAMP(6) WITHOUT TIME ZONE,
    ADD COLUMN publication_lease_token UUID,
    ADD COLUMN publication_lease_until TIMESTAMP(6) WITHOUT TIME ZONE,
    ADD COLUMN published_at TIMESTAMP(6) WITHOUT TIME ZONE,
    ADD CONSTRAINT fk_settlement_notification_outbox_notification_recipient
        FOREIGN KEY (notification_id, recipient_user_id)
            REFERENCES user_notifications (id, recipient_user_id)
            ON DELETE CASCADE,
    ADD CONSTRAINT ck_settlement_notification_outbox_reference
        CHECK (notification_id IS NOT NULL OR settlement_id IS NOT NULL),
    ADD CONSTRAINT ck_settlement_notification_outbox_generation
        CHECK (delivery_generation >= 0),
    ADD CONSTRAINT uk_settlement_notification_outbox_message_id
        UNIQUE (delivery_message_id),
    ADD CONSTRAINT ck_settlement_notification_outbox_publication_status
        CHECK (publication_status IN ('PENDING', 'PROCESSING', 'PUBLISHED')),
    ADD CONSTRAINT ck_settlement_notification_outbox_publication_attempt
        CHECK (publication_attempt_count >= 0
            AND (publication_status = 'PENDING' OR publication_attempt_count > 0)),
    ADD CONSTRAINT ck_settlement_notification_outbox_publication_lease
        CHECK (
            (publication_status = 'PROCESSING'
                AND publication_lease_token IS NOT NULL AND publication_lease_until IS NOT NULL)
            OR (publication_status <> 'PROCESSING'
                AND publication_lease_token IS NULL AND publication_lease_until IS NULL)
        ),
    ADD CONSTRAINT ck_settlement_notification_outbox_publication_completed
        CHECK (
            (publication_status = 'PUBLISHED' AND published_at IS NOT NULL)
            OR (publication_status <> 'PUBLISHED' AND published_at IS NULL)
        );

-- The old (notification, recipient, settlement) FK remains: it still rejects an
-- inconsistent non-null settlement. The new pair FK independently checks a
-- notification's recipient when a generic delivery has no settlement.
-- Legacy notification_id=NULL rows retain their original settlement reference.
CREATE INDEX idx_settlement_notification_outbox_publication_ready
    ON settlement_notification_outbox
        (COALESCE(next_publication_at, next_attempt_at), created_at, id)
    WHERE publication_status = 'PENDING' AND status IN ('PENDING', 'RETRY');

CREATE INDEX idx_settlement_notification_outbox_publication_lease
    ON settlement_notification_outbox (publication_lease_until, id)
    WHERE publication_status = 'PROCESSING';

COMMENT ON COLUMN settlement_notification_outbox.status IS
    'Provider delivery state only. Kafka publication has its independent publication_status and lease.';
COMMENT ON COLUMN settlement_notification_outbox.delivery_generation IS
    'Provider retry/recovery generation fence. Rotate delivery_message_id and reset publication state when advancing it.';
COMMENT ON COLUMN settlement_notification_outbox.delivery_message_id IS
    'Kafka event identity for the current provider generation; retained across broker publication retries.';
COMMENT ON COLUMN settlement_notification_outbox.publication_status IS
    'Independent relay state. Provider consumption may finish while publication is PROCESSING, before the broker ACK is recorded.';
COMMENT ON COLUMN settlement_notification_outbox.next_publication_at IS
    'A NULL value uses provider next_attempt_at for pre-migration rows. Terminal provider rows are not publication candidates.';

CREATE TABLE group_invitations (
    id UUID NOT NULL,
    group_id BIGINT NOT NULL,
    inviter_user_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_group_invitations PRIMARY KEY (id),
    CONSTRAINT fk_group_invitations_group FOREIGN KEY (group_id) REFERENCES groups (id),
    CONSTRAINT fk_group_invitations_inviter FOREIGN KEY (inviter_user_id) REFERENCES users (id),
    CONSTRAINT fk_group_invitations_recipient FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT ck_group_invitations_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_group_invitations_not_self
        CHECK (inviter_user_id <> recipient_user_id),
    CONSTRAINT ck_group_invitations_expiry
        CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uk_group_invitations_pending_recipient
    ON group_invitations (group_id, recipient_user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_group_invitations_recipient_status_expiry
    ON group_invitations (recipient_user_id, status, expires_at, id);

CREATE INDEX idx_group_invitations_pending_expiry
    ON group_invitations (expires_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_group_invitations_inviter
    ON group_invitations (inviter_user_id);

COMMENT ON TABLE group_invitations IS
    'Recipient-addressed group invitations. Expired PENDING rows transition to EXPIRED before replacement; membership changes revoke stale PENDING grants through CANCELLED.';
COMMENT ON COLUMN group_invitations.expires_at IS
    'UTC LocalDateTime represented as timestamp without time zone, consistent with other application storage.';
