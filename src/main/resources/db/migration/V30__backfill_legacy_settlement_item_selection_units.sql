LOCK TABLE settlement_items IN SHARE MODE;
LOCK TABLE settlement_item_selections IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
DECLARE
    v27_installed_at TIMESTAMP WITH TIME ZONE;
    -- #230 배포에서 기존 컨테이너 제거와 새 컨테이너 시작 사이의 무쓰기 구간이다.
    legacy_cutoff TIMESTAMP WITH TIME ZONE :=
        TIMESTAMPTZ '2026-09-01 07:59:07.500+00';
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

    -- V27~V30을 함께 적용하는 DB에서는 V27 적용 전 행 전체가 레거시다.
    -- 기존 development에서는 품목 한 개 기준이 배포되기 전 행만 레거시다.
    legacy_cutoff := GREATEST(legacy_cutoff, v27_installed_at);

    IF EXISTS (
        SELECT 1
        FROM settlement_item_selections selection
        JOIN settlement_items item
          ON item.id = selection.item_id
         AND item.settlement_id = selection.settlement_id
        WHERE selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff
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
    WHERE item.id = selection.item_id
      AND item.settlement_id = selection.settlement_id
      AND item.quantity > 1
      AND selection.updated_at AT TIME ZONE 'UTC' < legacy_cutoff;
END
$migration$;
