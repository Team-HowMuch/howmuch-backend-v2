-- Extend notification routing for financial SMS authentication while preserving
-- all existing settlement, group netting, invitation, and recap contracts.
ALTER TABLE user_notifications
    DROP CONSTRAINT ck_user_notifications_type,
    DROP CONSTRAINT ck_user_notifications_target_type,
    DROP CONSTRAINT ck_user_notifications_type_target,
    DROP CONSTRAINT ck_user_notifications_target_id;

ALTER TABLE user_notifications
    ADD CONSTRAINT ck_user_notifications_type CHECK (
        notification_type IN (
            'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER',
            'SETTLEMENT_PAYMENT_COMPLETED', 'SETTLEMENT_COMPLETED',
            'SETTLEMENT_DEPOSIT_CONFIRMED',
            'GROUP_INVITATION', 'GROUP_MEMBER_LEFT', 'MONTHLY_RECAP',
            'GROUP_NETTING_CONFIRMED', 'GROUP_NETTING_COMPLETED', 'NETTED_TRANSFER_REMINDER', 'FINANCE_AUTH_REQUIRED'
        )
    ),
    ADD CONSTRAINT ck_user_notifications_target_type CHECK (
        target_type IN (
            'SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL',
            'GROUP_INVITATION', 'GROUP_DETAIL', 'MONTHLY_RECAP',
            'GROUP_SETTLEMENT_NETTING', 'FINANCE_AUTHENTICATION'
        )
    ),
    ADD CONSTRAINT ck_user_notifications_type_target CHECK (
        (notification_type IN (
            'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER',
            'SETTLEMENT_PAYMENT_COMPLETED', 'SETTLEMENT_COMPLETED'
        ) AND target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL'))
        OR (notification_type = 'SETTLEMENT_DEPOSIT_CONFIRMED'
            AND target_type = 'SETTLEMENT_DETAIL')
        OR (notification_type = 'GROUP_INVITATION' AND target_type = 'GROUP_INVITATION')
        OR (notification_type = 'GROUP_MEMBER_LEFT' AND target_type = 'GROUP_DETAIL')
        OR (notification_type = 'FINANCE_AUTH_REQUIRED' AND target_type = 'FINANCE_AUTHENTICATION')
        OR (notification_type = 'MONTHLY_RECAP' AND target_type = 'MONTHLY_RECAP')
        OR (notification_type IN (
                'GROUP_NETTING_CONFIRMED', 'GROUP_NETTING_COMPLETED', 'NETTED_TRANSFER_REMINDER'
            )
            AND target_type = 'GROUP_SETTLEMENT_NETTING')
    ),
    ADD CONSTRAINT ck_user_notifications_target_id CHECK (
        (target_type IN ('SETTLEMENT_PARTICIPATION', 'SETTLEMENT_DETAIL')
            AND (
                (target_id IS NULL AND settlement_id IS NULL)
                OR (target_id IS NOT NULL
                    AND target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                    AND (settlement_id IS NULL OR LOWER(target_id) = settlement_id::TEXT))
            ))
        OR (target_type IN ('GROUP_INVITATION', 'GROUP_SETTLEMENT_NETTING', 'FINANCE_AUTHENTICATION')
            AND target_id IS NOT NULL
            AND target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
        OR (target_type = 'GROUP_DETAIL' AND target_id IS NOT NULL
            AND CASE WHEN target_id ~ '^[1-9][0-9]{0,18}$'
                THEN target_id::NUMERIC <= 9223372036854775807
                ELSE FALSE END)
        OR (target_type = 'MONTHLY_RECAP' AND target_id IS NOT NULL
            AND target_id ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
            AND LEFT(target_id, 4) <> '0000')
    );
