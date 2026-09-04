LOCK TABLE settlements IN SHARE MODE;
LOCK TABLE settlement_items IN SHARE MODE;
LOCK TABLE settlement_item_selections IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE settlement_item_submissions IN SHARE MODE;
LOCK TABLE settlement_shares IN SHARE MODE;

DO $migration$
DECLARE
    v27_installed_at TIMESTAMP WITH TIME ZONE;
    v29_installed_at TIMESTAMP WITH TIME ZONE;
    migration_started_at TIMESTAMP WITH TIME ZONE := clock_timestamp();
    -- #230 배포에서 기존 컨테이너 제거와 새 컨테이너 시작 사이의 무쓰기 구간이다.
    semantic_cutover CONSTANT TIMESTAMP WITH TIME ZONE :=
        TIMESTAMPTZ '2026-09-01 07:59:07.500+00';
    legacy_cutoff TIMESTAMP WITH TIME ZONE;
    is_fresh_migration_chain BOOLEAN;
BEGIN
    -- Flyway 시각은 DB 세션 시간대이고 애플리케이션의 updated_at은 UTC다.
    SELECT installed_on AT TIME ZONE current_setting('TimeZone')
    INTO v27_installed_at
    FROM flyway_schema_history
    WHERE version = '27'
      AND success
    ORDER BY installed_rank DESC
    LIMIT 1;

    IF v27_installed_at IS NULL THEN
        RAISE EXCEPTION 'Successful Flyway V27 history is required';
    END IF;

    SELECT installed_on AT TIME ZONE current_setting('TimeZone')
    INTO v29_installed_at
    FROM flyway_schema_history
    WHERE version = '29'
      AND success
    ORDER BY installed_rank DESC
    LIMIT 1;

    IF v29_installed_at IS NULL THEN
        RAISE EXCEPTION 'Successful Flyway V29 history is required';
    END IF;

    -- V27~V30을 함께 적용하는 DB에서는 V27 적용 전 행 전체가 레거시다.
    -- 설치 시각만으로 같은 Flyway 실행임을 증명할 수 없으므로, V27~V29와 V30 시작이
    -- 모두 1분 안에 이어진 경우만 무중간실행 경로로 인정한다. 나머지는 혼합 이력이다.
    legacy_cutoff := GREATEST(semantic_cutover, v27_installed_at);
    is_fresh_migration_chain :=
        v27_installed_at >= semantic_cutover
        AND v29_installed_at >= v27_installed_at
        AND migration_started_at >= v29_installed_at
        AND migration_started_at - v27_installed_at <= INTERVAL '1 minute';

    -- 같은 fraction/units를 새 의미로 다시 저장하면 Hibernate가 UPDATE를 하지 않아
    -- updated_at도 바뀌지 않는다. 신·구 코드가 모두 실행된 DB에서는 컷오버 전 제출로
    -- 이후 편집이 막힌 행만 확실한 레거시다. 나머지는 SQL만으로 의도를 복원할 수 없다.
    IF NOT is_fresh_migration_chain AND EXISTS (
        SELECT 1
        FROM settlement_item_selections selection
        JOIN settlement_items item
          ON item.id = selection.item_id
         AND item.settlement_id = selection.settlement_id
        JOIN settlements settlement
          ON settlement.id = selection.settlement_id
        LEFT JOIN settlement_item_submissions submission
          ON submission.settlement_id = selection.settlement_id
         AND submission.participant_id = selection.participant_id
        WHERE item.quantity > 1
          AND (
              settlement.allocation_type = 'ITEM_ALLOCATION'
              OR settlement.status = 'DRAFT'
          )
          AND selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff
          AND (
              submission.id IS NULL
              OR submission.submitted_at AT TIME ZONE 'UTC' >= legacy_cutoff
              OR selection.updated_at > submission.submitted_at
          )
    ) THEN
        RAISE EXCEPTION
            'Ambiguous settlement item selection history requires manual audit';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM settlement_item_selections selection
        JOIN settlement_items item
          ON item.id = selection.item_id
         AND item.settlement_id = selection.settlement_id
        JOIN settlements settlement
          ON settlement.id = selection.settlement_id
        WHERE selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff
          AND (
              settlement.allocation_type = 'ITEM_ALLOCATION'
              OR settlement.status = 'DRAFT'
          )
          AND selection.units::BIGINT * item.quantity > 2147483647
    ) THEN
        RAISE EXCEPTION
            'Legacy settlement item selection units exceed INTEGER range';
    END IF;

    -- 구·신 의미의 선택이 섞여 보정 후 과할당되면 임의로 데이터를 고치지 않는다.
    IF EXISTS (
        SELECT item.id
        FROM settlement_items item
        JOIN settlement_item_selections selection
          ON selection.item_id = item.id
         AND selection.settlement_id = item.settlement_id
        JOIN settlements settlement
          ON settlement.id = selection.settlement_id
        WHERE settlement.allocation_type = 'ITEM_ALLOCATION'
           OR settlement.status = 'DRAFT'
        GROUP BY item.id, item.quantity
        HAVING SUM(
            (
                CASE selection.fraction
                    WHEN 'ONE' THEN 12
                    WHEN 'HALF' THEN 6
                    WHEN 'THIRD' THEN 4
                    WHEN 'QUARTER' THEN 3
                END
            )::BIGINT
            *
            CASE
                WHEN selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff
                    THEN selection.units::BIGINT * item.quantity
                ELSE selection.units::BIGINT
            END
        ) > item.quantity::BIGINT * 12
    ) THEN
        RAISE EXCEPTION
            'Legacy settlement item selection backfill would overallocate an item';
    END IF;

    UPDATE settlement_item_selections selection
    SET units = (selection.units::BIGINT * item.quantity)::INTEGER
    FROM settlement_items item
    JOIN settlements settlement
      ON settlement.id = item.settlement_id
    WHERE item.id = selection.item_id
      AND item.settlement_id = selection.settlement_id
      AND item.quantity > 1
      AND (
          settlement.allocation_type = 'ITEM_ALLOCATION'
          OR settlement.status = 'DRAFT'
      )
      AND selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff;

    -- Share는 이미 송금 안내나 입금 대사의 기준으로 사용됐을 수 있어 자동 수정하지 않는다.
    -- 백필된 선택으로 현재 계산식을 다시 적용한 금액이 기존 Share와 다르면 전체를 되돌린다.
    IF EXISTS (
        WITH affected_payers AS (
            SELECT DISTINCT
                selection.settlement_id,
                selection.participant_id
            FROM settlement_item_selections selection
            JOIN settlement_items item
              ON item.id = selection.item_id
             AND item.settlement_id = selection.settlement_id
            JOIN settlements settlement
              ON settlement.id = selection.settlement_id
            WHERE item.quantity > 1
              AND (
                  settlement.allocation_type = 'ITEM_ALLOCATION'
                  OR settlement.status = 'DRAFT'
              )
              AND selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff
        ),
        recalculated_shares AS (
            SELECT
                selection.settlement_id,
                selection.participant_id,
                SUM(FLOOR(
                    item.amount::NUMERIC
                    * CASE selection.fraction
                        WHEN 'ONE' THEN 12
                        WHEN 'HALF' THEN 6
                        WHEN 'THIRD' THEN 4
                        WHEN 'QUARTER' THEN 3
                    END
                    * selection.units::NUMERIC
                    / (item.quantity::NUMERIC * 12)
                )) AS amount
            FROM settlement_item_selections selection
            JOIN settlement_items item
              ON item.id = selection.item_id
             AND item.settlement_id = selection.settlement_id
            JOIN affected_payers payer
              ON payer.settlement_id = selection.settlement_id
             AND payer.participant_id = selection.participant_id
            GROUP BY selection.settlement_id, selection.participant_id
        )
        SELECT 1
        FROM settlement_shares share
        JOIN affected_payers payer
          ON payer.settlement_id = share.settlement_id
         AND payer.participant_id = share.payer_participant_id
        JOIN recalculated_shares recalculated
          ON recalculated.settlement_id = share.settlement_id
         AND recalculated.participant_id = share.payer_participant_id
        WHERE share.amount::NUMERIC <> recalculated.amount
    ) THEN
        RAISE EXCEPTION
            'Settlement shares diverge from backfilled item selections';
    END IF;
END
$migration$;
