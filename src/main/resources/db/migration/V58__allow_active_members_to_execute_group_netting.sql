-- Permit every active group member to request compression and recompression.
-- Keep captured group-owner metadata separate from the immutable execution requester.

CREATE OR REPLACE FUNCTION assert_group_netting_generation_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    checked_netting UUID;
    checked_group BIGINT;
    checked_predecessor UUID;
    checked_status VARCHAR(20);
    checked_execution UUID;
    execution_status VARCHAR(20);
    execution_actor BIGINT;
    snapshot_owner BIGINT;
    group_owner_id BIGINT;
    group_status VARCHAR(20);
    actor_is_active BOOLEAN;
    predecessor_status VARCHAR(20);
    predecessor_version BIGINT;
    successor_count BIGINT;
    transfer_count BIGINT;
    rollover_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'group_settlement_nettings' THEN
        checked_netting := NEW.id;
    ELSIF TG_TABLE_NAME = 'group_netting_rollovers' THEN
        checked_netting := NEW.successor_netting_id;
    ELSIF TG_TABLE_NAME IN ('group_netting_rollover_transfers', 'group_netting_rollover_obligations') THEN
        checked_netting := NEW.successor_netting_id;
    ELSE
        RAISE EXCEPTION 'Unexpected table for netting generation consistency trigger: %', TG_TABLE_NAME;
    END IF;

    SELECT group_id, predecessor_netting_id, status, execution_id
        INTO checked_group, checked_predecessor, checked_status, checked_execution
    FROM group_settlement_nettings
    WHERE id = checked_netting;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF checked_status = 'BUILDING' THEN
        RAISE EXCEPTION 'A generation cannot commit in its internal BUILDING phase'
            USING ERRCODE = '23514';
    END IF;

    SELECT owner_id, status INTO STRICT group_owner_id, group_status
    FROM groups
    WHERE id = checked_group;

    SELECT execution.status, execution.actor_id, snapshot.owner_id
        INTO STRICT execution_status, execution_actor, snapshot_owner
    FROM group_netting_executions execution
    JOIN group_netting_execution_snapshots snapshot
      ON snapshot.execution_id = execution.id
     AND snapshot.group_id = execution.group_id
    WHERE execution.id = checked_execution;

    SELECT EXISTS (
        SELECT 1
        FROM group_members member
        JOIN users requester ON requester.id = member.user_id AND requester.active
        WHERE member.group_id = checked_group
          AND member.user_id = execution_actor
          AND member.status = 'ACTIVE'
    ) INTO actor_is_active;

    IF execution_status <> 'SUCCEEDED' THEN
        RAISE EXCEPTION 'Committed netting requires its successful execution and immutable snapshot'
            USING ERRCODE = '23514';
    END IF;

    -- owner_id records the group owner at capture; actor_id records the requester.
    -- Authorize every initial cutover, including a zero-net COMPLETED generation.
    -- Historical completion/rollover must not depend on the requester still belonging to the group.
    IF TG_TABLE_NAME = 'group_settlement_nettings' THEN
        IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND OLD.status = 'BUILDING') THEN
            IF snapshot_owner <> group_owner_id
               OR group_status <> 'ACTIVE'
               OR NOT actor_is_active THEN
                RAISE EXCEPTION 'A new generation requires an active group member execution and the current owner snapshot'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;

    IF checked_status = 'ACTIVE' AND EXISTS (
        SELECT 1
        FROM group_netting_parties party
        JOIN group_members member
          ON member.group_id = party.group_id
         AND member.user_id = party.user_id
        WHERE party.netting_id = checked_netting
          AND member.status <> 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Committed netting parties must be active group members at the cutover transaction'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO successor_count
    FROM group_settlement_nettings
    WHERE predecessor_netting_id = checked_netting;

    IF checked_status = 'SUPERSEDED' THEN
        IF successor_count <> 1 OR NOT EXISTS (
            SELECT 1 FROM group_netting_rollovers rollover
            WHERE rollover.predecessor_netting_id = checked_netting
        ) THEN
            RAISE EXCEPTION 'Superseded generation requires one committed successor and rollover snapshot'
                USING ERRCODE = '23514';
        END IF;
    ELSIF successor_count > 0 THEN
        RAISE EXCEPTION 'A generation with a successor must be superseded'
            USING ERRCODE = '23514';
    END IF;

    IF checked_predecessor IS NOT NULL THEN
        SELECT status, version INTO STRICT predecessor_status, predecessor_version
        FROM group_settlement_nettings
        WHERE id = checked_predecessor;

        IF predecessor_status <> 'SUPERSEDED'
           OR (SELECT version FROM group_settlement_nettings WHERE id = checked_netting)
                <= predecessor_version
           OR NOT EXISTS (
               SELECT 1 FROM group_netting_rollovers rollover
               WHERE rollover.successor_netting_id = checked_netting
                 AND rollover.predecessor_netting_id = checked_predecessor
           ) THEN
            RAISE EXCEPTION 'Successor generation must point forward to one superseded same-group generation'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*) INTO transfer_count
        FROM group_netted_transfers transfer
        WHERE transfer.netting_id = checked_predecessor;

        SELECT count(*) INTO rollover_count
        FROM group_netting_rollover_transfers rollover_transfer
        WHERE rollover_transfer.successor_netting_id = checked_netting
          AND rollover_transfer.predecessor_netting_id = checked_predecessor;

        IF transfer_count <> rollover_count THEN
            RAISE EXCEPTION 'Every predecessor transfer must have one rollover balance snapshot'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM group_netting_rollover_transfers rollover_transfer
            LEFT JOIN group_netting_rollover_obligations obligation
              ON obligation.successor_netting_id = rollover_transfer.successor_netting_id
             AND obligation.previous_transfer_id = rollover_transfer.previous_transfer_id
            WHERE rollover_transfer.successor_netting_id = checked_netting
              AND (
                  (rollover_transfer.carried_amount = 0 AND obligation.previous_transfer_id IS NOT NULL)
                  OR (rollover_transfer.carried_amount > 0
                      AND (obligation.previous_transfer_id IS NULL
                           OR obligation.carried_amount <> rollover_transfer.carried_amount
                           OR obligation.payer_id <> rollover_transfer.payer_id
                           OR obligation.receiver_id <> rollover_transfer.receiver_id))
              )
        ) THEN
            RAISE EXCEPTION 'Only a positive predecessor residual may become a successor input obligation'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM group_netting_rollover_transfers rollover_transfer
            LEFT JOIN LATERAL (
                SELECT COALESCE(sum(transaction.amount), 0) AS paid
                FROM netted_transfer_deposits deposit
                JOIN account_transactions transaction
                  ON transaction.id = deposit.account_transaction_id
                 AND transaction.account_id = deposit.source_account_id
                WHERE deposit.transfer_id = rollover_transfer.previous_transfer_id
            ) direct_deposits ON TRUE
            LEFT JOIN LATERAL (
                SELECT COALESCE(sum(transaction.amount), 0) AS paid
                FROM netting_deposit_reviews review
                JOIN account_transactions transaction
                  ON transaction.id = review.account_transaction_id
                 AND transaction.account_id = review.source_account_id
                WHERE review.status = 'APPLIED_TO_NETTING'
                  AND review.resolved_transfer_id = rollover_transfer.previous_transfer_id
            ) reviewed_deposits ON TRUE
            WHERE rollover_transfer.successor_netting_id = checked_netting
              AND (rollover_transfer.confirmed_amount <> direct_deposits.paid + reviewed_deposits.paid
                   OR rollover_transfer.original_amount <> rollover_transfer.confirmed_amount
                                                       + rollover_transfer.carried_amount)
        ) THEN
            RAISE EXCEPTION 'Rollover balances must equal the authoritative predecessor transfer evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM group_netting_rollovers rollover
        WHERE rollover.successor_netting_id = checked_netting
    ) THEN
        RAISE EXCEPTION 'A root generation cannot have a rollover record'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM group_netting_source_shares source_share
        WHERE source_share.netting_id = checked_netting
          AND source_share.replaced_amount > 0
        UNION ALL
        SELECT 1 FROM group_netting_rollover_obligations obligation
        WHERE obligation.successor_netting_id = checked_netting
    ) THEN
        RAISE EXCEPTION 'A netting generation requires at least one positive unpaid obligation'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        WITH input_balances AS (
            SELECT obligation.user_id, sum(obligation.delta) AS balance
            FROM (
                SELECT source_share.payer_user_id AS user_id,
                       source_share.replaced_amount::NUMERIC AS delta
                FROM group_netting_source_shares source_share
                WHERE source_share.netting_id = checked_netting
                UNION ALL
                SELECT source.creator_id AS user_id,
                       -source_share.replaced_amount::NUMERIC AS delta
                FROM group_netting_source_shares source_share
                JOIN group_netting_sources source
                  ON source.netting_id = source_share.netting_id
                 AND source.settlement_id = source_share.settlement_id
                WHERE source_share.netting_id = checked_netting
                UNION ALL
                SELECT rollover.payer_id, rollover.carried_amount::NUMERIC
                FROM group_netting_rollover_obligations rollover
                WHERE rollover.successor_netting_id = checked_netting
                UNION ALL
                SELECT rollover.receiver_id, -rollover.carried_amount::NUMERIC
                FROM group_netting_rollover_obligations rollover
                WHERE rollover.successor_netting_id = checked_netting
            ) obligation
            GROUP BY obligation.user_id
        ),
        output_balances AS (
            SELECT transfer.user_id, sum(transfer.delta) AS balance
            FROM (
                SELECT net_transfer.payer_id AS user_id,
                       net_transfer.amount::NUMERIC AS delta
                FROM group_netted_transfers net_transfer
                WHERE net_transfer.netting_id = checked_netting
                UNION ALL
                SELECT net_transfer.receiver_id AS user_id,
                       -net_transfer.amount::NUMERIC AS delta
                FROM group_netted_transfers net_transfer
                WHERE net_transfer.netting_id = checked_netting
            ) transfer
            GROUP BY transfer.user_id
        )
        SELECT 1
        FROM input_balances input
        FULL OUTER JOIN output_balances output USING (user_id)
        WHERE COALESCE(input.balance, 0) IS DISTINCT FROM COALESCE(output.balance, 0)
    ) THEN
        RAISE EXCEPTION 'Netting transfers must preserve every member balance from source and carried obligations'
            USING ERRCODE = '23514';
    END IF;

    IF checked_status = 'ACTIVE' AND EXISTS (
        SELECT 1 FROM netting_deposit_reviews review
        WHERE review.group_id = checked_group AND review.status = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'A group with an open deposit review cannot activate a generation'
            USING ERRCODE = '23514';
    END IF;

    IF checked_status = 'COMPLETED' THEN
        IF EXISTS (
            WITH RECURSIVE lineage(id, predecessor_netting_id) AS (
                SELECT id, predecessor_netting_id
                FROM group_settlement_nettings
                WHERE id = checked_netting
                UNION ALL
                SELECT parent.id, parent.predecessor_netting_id
                FROM group_settlement_nettings parent
                JOIN lineage child ON child.predecessor_netting_id = parent.id
            )
            SELECT 1
            FROM group_netting_sources source
            JOIN lineage ON lineage.id = source.netting_id
            JOIN settlements settlement ON settlement.id = source.settlement_id
            WHERE settlement.status <> 'COMPLETED'
        ) THEN
            RAISE EXCEPTION 'A completed generation must complete every original source in its lineage'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

COMMENT ON TABLE group_netting_execution_snapshots IS
    'Internal immutable calculation snapshot and lineage for an accepted active-member execution; owner_id captures the group owner, while group_netting_executions.actor_id identifies the requester.';
