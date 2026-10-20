# 그룹 반복 정산 후속 구현 운영·검증 메모

> 2026-10-20 후속: 폼 직접 생성·전체 수정·삭제와 편집용 표시 응답은 [정기 정산 관리 API](../completed/recurring-settlement-management.md)를 우선 참고한다. 아래 초기 구현 기록의 범위 제외·마스킹 정보 제외는 #574 당시 기준이다.

- Last updated: 2026-10-18
- Related Feature Plan: [그룹 반복 정산](group-recurring-settlement.md)
- 범위: 격리 워크트리에서의 로컬 구현 활성화·검증·제한된 수동 복구 절차. 운영 배포 기록이 아니다.

## 활성화와 실행 경계

기존 PostgreSQL·Redis·인증·계좌 암호화 설정을 사용한다. 스키마는 최신 `develop`에 병합된 **V59**를 그대로 쓰고, Batch 메타데이터는 **V53**의 `BATCH_*`를 공유한다. 로컬 V48/V49/V50은 사용하지 않는다.

```properties
howmuch.settlement.recurring.enabled=true
howmuch.settlement.recurring.poll-enabled=true
howmuch.settlement.recurring.poll-interval=1m
howmuch.settlement.recurring.batch-size=50
spring.batch.job.enabled=false
spring.batch.jdbc.initialize-schema=never
```

- 반복 기능은 `enabled`를 명시하지 않으면 꺼져 있다(기본 `false`). 켜면 poll은 기본 활성화되며, `poll-enabled=false`로 API·Job을 유지한 채 자동 실행만 멈출 수 있다.
- `batch-size`는 한 poll의 후보 탐색 상한이다. 도래 조회는 `retry_at IS NULL`(fresh, `idx_recurring_settlements_due`)과 `retry_at IS NOT NULL AND retry_at<=now`(retry, `idx_recurring_settlements_retry_due`) 두 가지를 UNION ALL로 합쳐 각 인덱스를 활용한다. 각 cursor 스캔 주기는 시작 시각으로 고정한 **scheduled-through horizon**(`next_run_at<=horizon`)을 써서 새로 도래하는 행이 꼬리를 무한히 늘리지 못하게 하고, 꼬리 도달 시 cursor·horizon을 리셋해 커서 뒤에서 늦게 자격을 얻은 retry도 다음 주기에 집는다.
- `JobRepository`·`JobOperator`는 압축 정산이 켜져 있으면 기존 Netting JDBC 구성과, 꺼져 있으면 Spring Boot의 JDBC Batch 자동 구성과 공유한다. `RecurringSettlementBatchConfiguration`은 자체 `JobRepository`를 만들지 않는다.
- 애플리케이션 시작 시 Job을 자동 실행하지 않는다(`spring.batch.job.enabled=false`, 구성에서 재확인). `initialize-schema`는 항상 `never`다.

## API 요약

| 메서드 | 경로 |
|---|---|
| `POST` | `/api/v2/groups/{groupId}/recurring-settlements` |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements?scope=&status=&cursor=&limit=` |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements/schedule-preview?frequency=&dayOfWeek=&dayOfMonth=&firstRunDate=` |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements/{id}` |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements/{id}/occurrences` |
| `POST` | `/api/v2/groups/{groupId}/recurring-settlements/{id}/cancel` |
| `GET` | `/api/v2/recurring-settlements/home?limit=5` |

- 등록 body: `sourceSettlementId`, `frequency`, `dayOfWeek`/`dayOfMonth`, `categoryCode`, `firstRunDate`(선택).
- 목록 `scope`: `ALL`(기본)/`PARTICIPATING`/`OTHER`, `status`: `ACTIVE`(기본)/`CANCELLED`.
- 카테고리는 `GET /api/v2/settlement-categories`의 선택 가능 코드만 허용한다. 발행 회차는 코드가 `UNCATEGORIZED`면 `settlement_categories.source='UNCATEGORIZED'`, 그 밖의 명시 코드는 `source='RECURRING'`으로 기록하며 LLM 분류를 호출하지 않는다.

## 일정 정책

- `Asia/Seoul` 09:00 발행. KST 09:00 = 같은 날짜 UTC 00:00.
- `firstRunDate`를 생략하면 `RecurrenceScheduleResolver`가 주입된 `Clock`으로 가장 가까운 미래 유효 일정을 계산한다. KST 09:00 이전이면 오늘이 될 수 있다.
- 월 29~31일이 없는 달은 말일로 보정하고 다음 달 원래 날짜로 복원한다.
- `nextRunAt`은 실제 성공 발행 후에만 다음 회차로 전진한다. 지연·실패 시 원래 회차에 머물고 목록에는 `OVERDUE`/`RETRYING`/`REVIEW`로 표시된다.
- D-day는 KST 달력일 기준 실제 거리(고정 30일 아님)이며 DB 컬럼으로 저장하지 않는다.

## 자동 시도와 수동 확인 기준

- 식별자: `recurringSettlementRequestJob` + `recurringSettlementId` + UTC ISO-8601 `scheduledFor`. `trigger=AUTOMATIC/MANUAL`은 비식별 파라미터다.
- 일시 DB 장애: 최초 포함 최대 3회. 첫 실패 후 1분, 두 번째 실패 후 5분.
- 비일시 오류: 즉시 수동 확인. 자동 재실행하지 않는다.
- 실패 요약 저장 실패: Batch의 자동 JobExecution 수도 대조해 자동 시도를 제한한다.
- `STARTING/STARTED/STOPPING`, `STOPPED`, 판정 불가 상태: 자동 restart/recover하지 않는다.
- 실패 회차의 `next_run_at`은 그대로 둔다. 성공해야 다음 예정 회차로 이동한다.
- 자격 상실: `CANCELLED`와 사유를 기록한다. 새 구성원·계좌를 자동 선택하지 않는다.

업무 발행 여부는 occurrence가 기준이다. Batch 이력이 실패·실행 중이어도 occurrence가 있으면 정산을 다시 만들지 않는다. 같은 회차 시작은 PostgreSQL session advisory lock으로 직렬화하고 잠금 안에서 시도 상한을 다시 확인한다.

## 읽기 전용 확인 쿼리

```sql
SELECT id, group_id, creator_id, status, next_run_at, failure_count,
       retry_at, last_failure_code, last_failure_at, cancellation_reason
FROM recurring_settlements
WHERE status = 'ACTIVE' AND next_run_at <= CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
ORDER BY next_run_at, id;

SELECT id, recurring_settlement_id, scheduled_for, settlement_id, requested_at
FROM recurring_settlement_occurrences
WHERE recurring_settlement_id = '<recurring UUID>'::uuid
ORDER BY scheduled_for;

SELECT settlement_id, status, category_code, source, classified_at
FROM settlement_categories WHERE settlement_id = '<occurrence settlement UUID>'::uuid;
```

## 제한된 내부 재실행 명령

공개 HTTP 복구 endpoint는 없다. 필요한 환경이 설정된 운영자 전용 실행 환경에서 정확한 반복 ID·원래 예정 시각을 입력한다.

```bash
./gradlew bootRun --args='--howmuch.settlement.recurring.enabled=true --howmuch.settlement.recurring.poll-enabled=false --howmuch.settlement.recurring.replay.enabled=true --howmuch.settlement.netting.enabled=false --howmuch.settlement.notification-outbox.enabled=false --howmuch.settlement.automatic-reminder.enabled=false --recurring-replay-id=<UUID> --recurring-replay-scheduled-for=2026-10-31T00:00:00Z --server.address=127.0.0.1 --server.port=0'
```

`scheduledFor`는 원래 회차의 UTC 예정 시각이다. 기본 한국 시간 09:00은 같은 날짜 UTC 00:00이다. 강제 종료로 실행 중 상태가 남으면 occurrence·실제 Settlement를 확인하고 원인을 조치한 뒤 `--recurring-replay-confirm-worker-stopped=true`를 추가한다.

## 검증 범위

- Docker(Testcontainers)를 쓸 수 없는 환경이므로 DB 통합·HTTP 테스트는 명시적 환경 변수로 격리 DB·Redis를 지정할 때만 실행한다. 지정이 없으면 비활성화되어 개발 DB에 연결하지 않는다.
- 카테고리 등 기존 `@SpringBootTest` DB 테스트는 기본 `spring.datasource`(로컬 `DB_URL`)를 쓰므로, 같은 격리 DB를 쓰려면 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`도 함께 지정한다.

```bash
export RECURRING_TEST_DB_URL=jdbc:postgresql://<host>:<port>/<db>
export RECURRING_TEST_DB_USERNAME=<user> RECURRING_TEST_DB_PASSWORD=<pw>
export RECURRING_TEST_REDIS_HOST=<host> RECURRING_TEST_REDIS_PORT=<port>
export DB_URL="$RECURRING_TEST_DB_URL" DB_USERNAME="$RECURRING_TEST_DB_USERNAME" DB_PASSWORD="$RECURRING_TEST_DB_PASSWORD"
export REDIS_HOST="$RECURRING_TEST_REDIS_HOST" REDIS_PORT="$RECURRING_TEST_REDIS_PORT"
set -o pipefail
./gradlew clean test \
  --tests '*RecurringSettlement*' --tests '*RecurrenceSchedule*' \
  --tests '*SettlementRequestServiceTest' --tests '*SettlementCategory*'
```

- 2026-10-18 네이티브 PostgreSQL 18·Redis(`127.0.0.1:18534/recurring_test`, `127.0.0.1:18579`)에서 위 명령으로 145개 테스트 전부 통과(0 skip). 실제 인증 HTTP 스모크(`RecurringSettlementHttpIntegrationTest`)와 bounded-horizon 회귀(`RecurringSettlementDispatcherTest`, flow의 horizon read-query)도 포함한다.
- 날짜 직렬화는 프로젝트 계약대로 offset 없는 UTC `LocalDateTime`이며, `firstRunAt`은 `2026-10-31T00:00:00`(UTC), `timezone=Asia/Seoul`, `requestTime=09:00`으로 확인한다.
- 남은 한계: 실제 프로세스 강제 종료 복구 훈련, 운영 푸시·실제 은행, 전체 저장소 suite는 이번 검증에 포함하지 않았다.
