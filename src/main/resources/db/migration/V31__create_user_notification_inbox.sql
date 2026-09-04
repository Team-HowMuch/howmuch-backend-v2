CREATE TABLE user_notifications (
    id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    settlement_id UUID,
    notification_type VARCHAR(30) NOT NULL,
    allocation_type VARCHAR(30) NOT NULL,
    requester_name VARCHAR(100) NOT NULL,
    title VARCHAR(100) NOT NULL,
    message VARCHAR(500) NOT NULL,
    deduplication_key VARCHAR(200) NOT NULL,
    read_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_user_notifications PRIMARY KEY (id),
    CONSTRAINT fk_user_notifications_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_notifications_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id) ON DELETE SET NULL,
    CONSTRAINT uk_user_notifications_recipient_deduplication
        UNIQUE (recipient_user_id, deduplication_key),
    -- A linked delivery must belong to the same recipient and settlement.
    CONSTRAINT uk_user_notifications_delivery_target
        UNIQUE (id, recipient_user_id, settlement_id),
    CONSTRAINT ck_user_notifications_type
        CHECK (notification_type IN ('SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER')),
    CONSTRAINT ck_user_notifications_allocation_type
        CHECK (allocation_type IN ('DUTCH_PAY', 'ITEM_ALLOCATION')),
    CONSTRAINT ck_user_notifications_requester_name_not_blank
        CHECK (CHAR_LENGTH(BTRIM(requester_name)) > 0),
    CONSTRAINT ck_user_notifications_title_not_blank
        CHECK (CHAR_LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_user_notifications_message_not_blank
        CHECK (CHAR_LENGTH(BTRIM(message)) > 0),
    CONSTRAINT ck_user_notifications_deduplication_key_not_blank
        CHECK (CHAR_LENGTH(BTRIM(deduplication_key)) > 0)
);

CREATE INDEX idx_user_notifications_recipient_created
    ON user_notifications (recipient_user_id, created_at DESC, id DESC);

CREATE INDEX idx_user_notifications_recipient_unread
    ON user_notifications (recipient_user_id)
    WHERE read_at IS NULL;

-- Supports settlement deletion and the automatic reminder's request-existence lookup.
CREATE INDEX idx_user_notifications_settlement_recipient_type
    ON user_notifications (settlement_id, recipient_user_id, notification_type);

COMMENT ON COLUMN user_notifications.deduplication_key IS
    'Unique per recipient: SETTLEMENT_REQUEST:<settlementId>, MANUAL_REMINDER:<reservationId>, AUTO_REMINDER:<shareId>.';
COMMENT ON COLUMN user_notifications.settlement_id IS
    'Cleared on settlement deletion; display snapshots and read state remain in the inbox.';

ALTER TABLE settlement_notification_outbox
    ADD COLUMN notification_id UUID,
    ADD CONSTRAINT fk_settlement_notification_outbox_notification
        FOREIGN KEY (notification_id, recipient_user_id, settlement_id)
            REFERENCES user_notifications (id, recipient_user_id, settlement_id)
            ON DELETE CASCADE;

-- NULL keeps existing rows and old producers compatible without creating historical inbox entries.
-- Deploy the new producer/worker together: old workers only understand participation requests.
COMMENT ON COLUMN settlement_notification_outbox.notification_id IS
    'NULL means a legacy participation request. Non-NULL deliveries use the linked inbox notification type.';

ALTER TABLE settlement_notification_outbox
    DROP CONSTRAINT uk_settlement_notification_outbox_settlement_recipient;

CREATE UNIQUE INDEX uk_settlement_notification_outbox_legacy_recipient
    ON settlement_notification_outbox (settlement_id, recipient_user_id)
    WHERE notification_id IS NULL;

CREATE UNIQUE INDEX uk_settlement_notification_outbox_notification
    ON settlement_notification_outbox (notification_id)
    WHERE notification_id IS NOT NULL;
