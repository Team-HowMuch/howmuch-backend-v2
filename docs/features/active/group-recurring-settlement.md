# Feature Plan: 그룹 고정 지출 반복 정산 (후속 구현)

- **Status:** 격리 워크트리(`feat/574-group-recurring-settlement`)에서 구현·검증 완료 — 이슈 #574·구현 PR #575 게시 완료, 리뷰 반영
- **Last updated:** 2026-10-18
- **Source:** 기존 로컬 구현(읽기 전용 참조), 인수인계 문서, 2026-10-17 화면 흐름 대조, 후속 구현 지시
- **기준 브랜치:** `develop` `3d386b3a` (migration-only PR #573 병합, V59 반복 정산 스키마 포함)
- **Related ADR:** [ADR-0001 Purpose-oriented Ports](../../adr/0001-purpose-oriented-ports.md), [ADR-0003 Capability-oriented Output Ports](../../adr/0003-capability-oriented-output-ports.md)
- **관련 문서:** [Architecture](../../architecture.md), [Development Workflow](../../development-workflow.md), [운영 메모](group-recurring-settlement-runbook.md), [인수인계](group-recurring-settlement-handoff.md)

**작업 범위:** 사용자 후속 지시에 따라 이슈 #574를 만들고 구현을 커밋·푸시하여 `develop` 대상 PR을 생성한다. 배포는 수행하지 않는다. migration은 변경하지 않는다(V59 그대로 사용). 프론트엔드·dev/prod·실제 은행·자동 이체·반복 설정 수정은 범위 밖이다.

## 1. 목적

월세·구독료처럼 같은 그룹 멤버가 같은 금액을 반복 부담하는 고정 지출을 한 번 설정하면, **지정한 첫 정산일부터 매주 또는 매월 새로운 정산 요청이 자동 발행**된다. 등록 시점에는 청구하지 않고, 원본 초안을 소프트 삭제한 뒤 회차마다 새 Settlement·Share·링크·알림 Outbox를 만든다.

### 기능 요구사항 요약

| 항목 | MVP 계약 |
|---|---|
| 생성 위치 | 그룹 내부. 기존 DRAFT·DUTCH_PAY·MANUAL 지출·양수 확정 배분 초안을 등록 |
| 참여자 | 그룹 활성 멤버, 생성자 포함 서로 다른 사용자 2명 이상 |
| 금액 | 고정 총액·참여자별 확정 부담액 스냅샷. 회차 금액은 발생 주기당 금액(주간=주, 월간=월)이며 임의 월 환산 없음 |
| 주기 | 매주 지정 요일 또는 매월 1~31일. Asia/Seoul 09:00 발행 |
| 첫 요청 | `firstRunDate` 생략 시 **가장 가까운 미래 유효 일정**을 계산. 등록 직후 청구·Share·링크·알림 없음 |
| 카테고리 | 등록 시 `categoryCode`를 설정의 **기본값**으로 저장. 회차 발행 시 `settlement_categories.source=RECURRING`으로 기록, LLM 재분류 없음 |
| 목록 | `scope=ALL/PARTICIPATING/OTHER`, `status=ACTIVE/CANCELLED`, cursor+limit. 참여 여부 독립 페이지 |
| 홈 | 현재 사용자가 참여 중인 ACTIVE 설정만, 도래 임박 순 |
| 해지 | 생성자만, 이후 회차만 중단. 멱등 |
| 실행 | `@Scheduled`(기본 1분) → Spring Batch Job(회차당 1개) → Tasklet → UseCase |
| 재시도 | 최초 포함 최대 3회 자동, 1분/5분 백오프, 초과·비일시 오류는 수동 확인. stale `STARTED` 자동 복구 금지 |

### 이번 개발에서 제외

반복 설정 수정·일시정지·재개·회차 건너뛰기, 자동 출금·송금, 신규 관리자 UI, 별도 Lease 테이블·Redis 분산 잠금·Kafka, 공개 수동 재실행 endpoint, 프론트엔드 구현.

## 2. 기존 구조 재사용

- `SettlementRequestController`: 즉시 요청 API 계약은 변경하지 않는다. `SettlementRequestService`의 실제 기록 부분을 `SettlementRequestWriter`로 추출해 즉시 요청과 반복 발행이 공유한다.
- `SettlementRequestPolicy.prepareShares`, `NotificationRecorder`, `SettlementCategoryJobStorePort`, `NettingEstimateInvalidationService`: 기존 동작 보존.
- 그룹·사용자·계좌 Port: 매 회차 활성 상태·멤버십·수령 계좌 소유권 재검증.
- 기존 Spring Batch 6 JDBC 인프라(`NettingBatchConfiguration`이 제공하는 `JobRepository`/`JobOperator`)와 V53 `BATCH_*` 메타데이터를 **공유**한다. 반복 Job은 별도 이름을 쓰고 새 메타데이터 테이블을 만들지 않는다.
- V59 스키마(`recurring_settlements`의 `category_code`, NULL-safe failure CHECK, `retry_at` partial index)를 그대로 사용한다.

## 3. 계약

### Application UseCase

```text
CreateRecurringSettlementUseCase.create(groupId, command)
GetGroupRecurringSettlementsUseCase.get(groupId, query)          // scope/status/cursor/limit
GetRecurringSettlementUseCase.get(groupId, recurringSettlementId)
GetRecurringSettlementOccurrencesUseCase.get(groupId, id, query)
CancelRecurringSettlementUseCase.cancel(groupId, id)
GetRecurringSettlementSchedulePreviewUseCase.preview(groupId, command)
GetRecurringSettlementHomeUseCase.getHome(limit)
DispatchRecurringSettlementsUseCase.dispatch(batchSize)
ProcessRecurringSettlementUseCase.process(recurringSettlementId, scheduledFor)
```

- Command/Result는 plain record. Jakarta Validation·Servlet·JPA·Redis 타입 금지.
- `ProcessRecurringSettlementUseCase`는 저장된 생성자·그룹 사실로 판단하며 백그라운드에서 JWT·가짜 CurrentActor를 쓰지 않는다.

### Web API

| 메서드 | 경로 | 목적 |
|---|---|---|
| `POST` | `/api/v2/groups/{groupId}/recurring-settlements` | 초안을 반복 설정으로 등록 (categoryCode 포함, firstRunDate 선택) |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements` | 목록 (`scope`, `status`, `cursor`, `limit`) |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements/schedule-preview` | 생성 전 정규화 일정·첫 UTC 실행 시각 미리보기 |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements/{id}` | 상세 |
| `GET` | `/api/v2/groups/{groupId}/recurring-settlements/{id}/occurrences` | 발행 회차 이력 |
| `POST` | `/api/v2/groups/{groupId}/recurring-settlements/{id}/cancel` | 생성자 해지 |
| `GET` | `/api/v2/recurring-settlements/home` | 현재 사용자 참여 반복 설정 요약 |

- 목록·홈 요약 필드: `category(code/name/icon)`, `cadence(frequency/dayOfWeek/dayOfMonth)`, `totalAmount`, `myAmount`(비참여 시 null), `isParticipant`, `nextRunAt`(UTC), `daysUntilNextRun`(KST 달력일), `displayState`.
- 시각은 UTC ISO offset을 쓰고, D-day는 KST 달력일 기준 실제 달력 거리로 계산한다(고정 30일 아님).
- 홈/목록은 다른 사용자의 계좌·비밀 정보를 포함하지 않는다.

### 일정 정책

- Asia/Seoul 09:00. KST 09:00 = 같은 날짜 UTC 00:00.
- `firstRunDate` 생략 시: 오늘 KST 날짜의 유효 회차 시각이 `now`보다 미래면 오늘, 아니면 다음 주/월. KST 09:00 이전이면 오늘이 될 수 있다.
- `firstRunDate` 명시 시: 주기와 정렬되어야 하고 미래여야 한다.
- 월 29~31일이 없는 달은 말일로 보정하고 다음 달 원래 날짜로 복원한다.
- `nextRunAt`은 **실제 성공 발행 후에만** 다음 회차로 전진한다. 지연·실패 시 원래 회차에 머문다.
- 선행 실패가 해결되기 전 다음 회차로 넘어가지 않는다. 누적분은 오래된 순서로 처리한다.

### Batch Job 식별 계약

```text
jobName = recurringSettlementRequestJob
identifying: recurringSettlementId(UUID), scheduledFor(UTC ISO-8601)
non-identifying: trigger=AUTOMATIC|MANUAL
```

- 같은 회차는 같은 JobInstance. `run.id`·현재 시각·재시도 횟수를 식별 파라미터로 추가하지 않는다.
- `COMPLETED`는 재실행하지 않고, `FAILED`는 `JobOperator.restart`, 비식별 파라미터 갱신은 `JobOperator.start(job, params)`.
- `STARTED` 잔류는 자동 복구하지 않는다. 명시적 복구 확인 시 `JobOperator.recover`.

### 카테고리

- 등록 시 `SettlementCategoryCatalog.byCode()`로 선택 가능 코드(attr2=Y, 저장 가능 형식)를 검증한다.
- 발행 시 `SettlementCategoryJobStorePort.saveRecurring(...)`로 `status=CLASSIFIED`, `source=RECURRING`을 기록한다. LLM 분류를 호출하지 않고 사용자의 명시 선택을 덮지 않는다.

## 4. 영향 범위와 Persistence

- 신규 테이블 없음(V59 그대로). `recurring_settlements.category_code`를 매핑·검증에 사용.
- 도래 조회는 `retry_at IS NULL`(fresh) 가지와 `retry_at IS NOT NULL AND retry_at<=now`(retry) 가지를 UNION ALL로 합쳐 각각의 partial index를 활용한다. `(retry_at IS NULL OR retry_at<=now)` 같은 단일 조건으로 인덱스 자동 사용을 주장하지 않는다.
- 한 번의 cursor 스캔 주기는 시작 시각으로 고정한 **scheduled-through horizon**을 쓴다. 후보 `next_run_at<=horizon`이고 retry 자격만 실제 `now`로 판정한다. 새로 도래하는 행이 스캔 꼬리를 무한히 늘리지 못하게 하고, 꼬리에 도달하면 cursor·horizon을 리셋해 다음 주기가 현재 시각 기준으로 커서 뒤의 오래된 retry까지 집는다.
- 요약은 목록/홈 전용 focused 쿼리(`LEFT JOIN recurring_settlement_participants`)로 plain Application 값을 돌려주어 N+1 hydration을 피한다.
- D-day·상대 멤버십·myAmount는 DB 컬럼으로 추가하지 않는다.

## 5. 구현 단계 (coherent compileable stages)

1. **일정·도메인·등록 계약:** `RecurrenceSchedule`(정규화 helper), `RecurringSettlement`(categoryCode), 포트/Command/Result, `RecurrenceScheduleResolver`.
2. **저장·워커:** Persistence Adapter/Mapper(category_code 반영), `SettlementRequestWriter` 추출 + category override, Worker/Dispatcher/FailureRecorder/Replay.
3. **읽기·API:** 목록 scope/status, summary read model/adapter/assembler, schedule-preview, home, Controller/Docs/DTO.
4. **Batch 인프라:** Configuration/Properties/Scheduler/Tasklet/JobLauncher/ReplayCommand, application.yaml 기본값.
5. **회귀·문서:** 즉시 요청 회귀, 단위 테스트, 통합 테스트.

각 단계마다 `./gradlew compileJava`(필요 시 `compileTestJava`)로 컴파일을 확인한다.

## 6. commit 경계

1. `feat(settlement): 그룹 고정 지출 API와 반복 정산 자동 발행 구현` — 실제 연결된 도메인·서비스·저장·API·Batch 및 기술 설정을 함께 포함하여 미사용 Port만 남는 중간 상태를 피한다.
2. `test(settlement): 고정 지출 일정과 자동 발행 통합 회귀 검증` — 단위·실제 DB·인증 HTTP 검증 및 기존 즉시 요청 회귀.
3. `docs(settlement): 고정 지출 구현 결과와 운영 절차 정리` — Feature Plan·인수인계·운영 메모.

이슈: #574. 스키마 선행 PR: #573(병합 완료). `feat/574-group-recurring-settlement`에서 `develop`으로 게시한다.

## 7. 검증 기준

| 테스트 | 완료 조건 |
|---|---|
| 일정 | KST 09:00 이전/정각/이후, 주간 요일, 월말·윤년 보정·복원, firstRunDate 생략 시 최근 미래 계산 |
| 요약 | D-day(달력일), myAmount nullable, displayState, 비참여자 필드 |
| 등록 | 같은 입력 replay 동일 결과, 다른 일정/카테고리 409, 카테고리 검증, 원본 소프트 삭제 |
| 발행 | 참여자·지출·배분 복사, 카테고리 RECURRING/UNCATEGORIZED 기록, nextRunAt 전진, 전체 롤백 |
| 실행 | 자동 3회 제한, 수동 재실행, stale STARTED 미복구, poison/manual-review 회차 제외 |
| 목록/홈 | scope/status 필터, cursor(그룹·필터·템플릿) 검증, 다중 그룹 개인정보 비노출, 비활성 멤버 차단 |
| HTTP | 실제 JWT·Redis 세션 인증, 401/403, 미리보기·등록·목록·홈·발행·replay·해지 전 과정 |

- Java 21로 `compileJava`, `compileTestJava`.
- DB 통합·HTTP 테스트는 Docker 없이 격리 PostgreSQL·Redis를 가리키는 환경 변수(`RECURRING_TEST_DB_URL`, `RECURRING_TEST_REDIS_*`)가 지정될 때만 실행한다. 기본값으로 개발 DB에 연결하지 않는다.

## 8. 진행 기록

| 날짜 | 완료 | 다음 작업 |
|---|---|---|
| 2026-10-17 | 기존 읽기 전용 구현과 인수인계·화면 흐름을 최신 `develop`/V59에 맞춰 후속 구현 계획 수립 | 단계 1 구현 |
| 2026-10-18 | 단계 1~5 로컬 구현 완료. 카테고리·일정 미리보기·목록 필터·홈·요약 추가, Batch 인프라 재사용 | 부모 리뷰·독립 실행 |
| 2026-10-18 | 부모 리뷰 4건 수정(replay 순서·UNCATEGORIZED·cursor 검증·도래 조회 poison 필터) + 실제 DB/HTTP 검증 | 부모 최종 리뷰 |
| 2026-10-18 | cursor 스캔 주기 bounded scheduled-through horizon 도입(커서 뒤 retry 굶김 방지) + 결정적 회귀 2건 | 부모 최종 리뷰 |
| 2026-10-18 | PR #575 리뷰 4303686863 반영: 발행 참여자 source를 생성자/비생성자로 분리하고 실제 `GroupNettingInputReader` 계약 회귀 추가 | 부모 DB 통합 테스트 |

## 8-1. 로컬 검증 결과 (2026-10-18, 네이티브 PostgreSQL 18 + Redis, Docker 없음)

- `./gradlew compileJava`, `./gradlew compileTestJava` 성공.
- 네이티브 격리 DB·Redis(`127.0.0.1:18534/recurring_test`, Redis `127.0.0.1:18579`)로 관련 145개 테스트 전부 통과(0 skip):
  - 단위: 일정 7, 도메인 6(+카테고리), retry 정책 2, replay 명령 2, resolver 4, 요약 조립 2, 서비스 replay/카테고리 5, 즉시 요청 회귀 6, **dispatcher bounded-horizon 1**.
  - DB 통합: `RecurringSettlementFlowIntegrationTest` 22, `RecurringSettlementSchemaIntegrationTest` 2.
  - 실제 인증 HTTP: `RecurringSettlementHttpIntegrationTest` 1(RANDOM_PORT + java.net.http.HttpClient + 실제 JWT·Redis 세션).
  - 카테고리 회귀: `SettlementCategory*` 37(내 `saveRecurring` 변경 포함).
- 실행 명령:
  `./gradlew clean test --tests '*RecurringSettlement*' --tests '*RecurrenceSchedule*' --tests '*SettlementRequestServiceTest' --tests '*SettlementCategory*'` (위 환경 변수 export 후, 로그를 durable 디렉터리에 저장하고 `set -o pipefail`로 종료 코드 확인)
- 실제 HTTP 검증 항목: 미인증 401, 활성 그룹 멤버 미리보기 200, firstRunDate 생략 등록, 목록 scope/status, 홈 myAmount/isParticipant/D-day, 실제 워커 발행(occurrence 1·nextRunAt 전진·카테고리 기록·홈 D-day 갱신), 원래 첫 회차 경과 후 동일 replay 동일 ID, 해지 후 홈 제외, 무관 사용자 403·홈 비노출, limit 1..20 밖 400.
- 날짜 직렬화는 이 프로젝트의 계약대로 **offset 없는 UTC `LocalDateTime`**이다(`SettlementOpenApiIntegrationTest`가 "offset 없는 값"을 확인). 스모크 테스트는 `firstRunAt`이 `2026-10-31T00:00:00`(UTC)이고 `timezone=Asia/Seoul`, `requestTime=09:00`임을 확인하며, offset·`Z` 문자열은 계약과 다르므로 단정하지 않는다. 응답에 계좌 마스킹(`****`)·계좌번호 필드가 없음도 확인했다.
- 남은 한계: 실제 프로세스 강제 종료 복구 훈련, 운영 FCM/APNs·실제 은행 검증, 전체 저장소 테스트 suite는 이번 검증에 포함하지 않았다.

### Codex 독립 검증 (2026-10-18)

- OpenCode 구현 결과와 별도로 `compileJava`, `compileTestJava`, 관련 테스트 143개를 직접 재실행해 실패/skip 0을 확인했다.
- 커서 순회 범위 보완 후 Dispatcher·실제 PostgreSQL 회차 흐름·실제 JWT/Redis 인증 HTTP 테스트 24개를 다시 실행해 실패/skip 0을 확인했다. 이 24개는 145개와 별도 기능을 더한 수가 아니라 영향 경로의 재검증이다.
- 추가된 파일을 포함해 공백·마지막 개행, 문서 상대 링크, `git diff --check`를 확인했다. migration 변경은 없다. 전체 저장소 suite·실은행·실기기 푸시는 검증 범위 밖이다.

## 8-2. 구현 결정 (부모 리뷰 반영)

- **replay 우선:** `create`는 새 일정을 resolve하기 전에 `findBySourceSettlementId`로 기존 등록을 먼저 확인하고, 원본 행 잠금 뒤 다시 확인한다. 기존 등록이 있으면 미래 일정 검증·카탈로그 조회 없이 요청 형태(그룹·카테고리·주기·명시 firstRunDate)만 비교해 같은 요청은 원래 ID를 반환하고, 다르면 409다. 원래 첫 회차가 지나도, 카탈로그에서 코드가 비활성화돼도 템플릿을 변경·교체하지 않는다.
- **카테고리 일관성:** `UNCATEGORIZED`를 선택 가능하게 허용하고, 발행 회차는 코드가 `UNCATEGORIZED`면 `settlement_categories.source='UNCATEGORIZED'`, 그 밖의 명시 코드는 `source='RECURRING'`으로 기록한다. 상태는 `CLASSIFIED`, LLM 분류는 호출하지 않는다(V56가 migration 없이 허용).
- **cursor 검증:** 발생 이력 cursor는 요청한 템플릿 소속이어야 하고, 목록 cursor는 그룹·상태·참여 구역 페이지에 속해야 한다. 아니면 기존 `INVALID_RECURRING_SETTLEMENT_CURSOR` 400이다(빈 200 아님).
- **도래 조회:** fresh/retry 두 가지 모두 `last_failure_code IS NULL OR = RECURRING_TRANSIENT_DATABASE_FAILURE`로 영구/수동확인 실패 행을 제외하고, 두 partial index를 각각 사용하며 원래 `next_run_at`(scheduledFor)을 유지한다. **스캔 공정성은 전역 정렬 하나가 아니라 bounded snapshot scan으로 보장한다:** cursor가 없는 새 주기에서 `scheduledThrough = now`를 고정하고, 후보 `next_run_at<=scheduledThrough`·retry 자격 `retry_at<=now`로 읽는다. 꼬리(부분/빈 페이지)에 도달하면 cursor·horizon을 리셋하므로, 커서 뒤에서 늦게 자격을 얻은 오래된 retry도 다음 주기에 반드시 선택된다. 지속적으로 새로 도래하는 행이 페이지를 계속 채워도 horizon이 고정이라 주기가 유한하다.
- `SettlementRequestWriter`가 즉시 요청과 반복 발행의 실제 기록 처리를 공유한다.

## 8-3. PR #575 리뷰 반영 (4303686863)

- **문제:** 발행 워커가 스냅샷의 모든 참여자를 `SettlementParticipant.groupMember(...)`로 저장했다. `GroupNettingInputReader.eligible`은 생성자 정확히 1명이 `source=REGISTERED_USER`·`source_group_id IS NULL`이고 나머지가 해당 그룹의 `GROUP_MEMBER`일 때만 소스를 인정한다. 따라서 반복 발행된 정산은 어떤 경우에도 `NETTING_INVALID_SOURCE`로 거절되어 그룹 넷팅 대상이 될 수 없었다.
- **수정:** `RecurringSettlementWorker`가 `userId == creatorId`인 참여자만 `SettlementParticipant.registeredUser(...)`, 나머지는 기존처럼 `SettlementParticipant.groupMember(..., groupId, ...)`로 저장한다. 금액·표시 순서 스냅샷과 현재 사용자 이름은 그대로 유지한다.
- **회귀:** 기존 `registration_waitsUntilFirstDate_thenCopiesAmountsAndUsesExistingRequestOutbox`가 `GROUP_MEMBER` 2건을 기대하던 단정을 다음으로 교체했다.
  - 생성자 1명: `REGISTERED_USER`, `source_group_id IS NULL`, `display_order=0`, 이름 스냅샷 일치.
  - 비생성자 1명: `GROUP_MEMBER`, `source_group_id=그룹 ID`, `display_order=1`, 이름 스냅샷 일치.
  - 배분 금액 `12,000`/`8,000` 유지.
  - 실제 `GroupNettingInputReader.readForEstimate`로 발행 정산이 소스로 조회되고 파티 구성(생성자/비생성자)과 8,000원 잔여 채무가 계약을 만족하는지 확인했다. 별도 fixture·인프라 추가는 없다.
- **migration:** 필요 없음. 스키마·Port·API 계약은 변경하지 않았다.
- **검증:** `./gradlew compileJava compileTestJava test --tests '*RecurringSettlement*' --tests '*RecurrenceSchedule*' --tests '*SettlementRequestServiceTest' --tests '*SettlementCategory*'` 성공. 격리된 네이티브 PostgreSQL 18·Redis에서 실제 압축 정산 조회기 호환 검증을 포함한 관련 테스트 145개 통과(실패·오류·skip 0). `git diff --check` 성공. 전체 저장소 suite·dev/prod 배포는 수행하지 않았다.

## 9. 결정 기록

| 상태 | 결정 |
|---|---|
| 확정 | V59 그대로 사용, migration 변경 없음 |
| 확정 | 일정 계산은 단일 `RecurrenceScheduleResolver`(주입 Clock) 공유 |
| 확정 | replay는 resolve보다 먼저, 기존 스냅샷을 미래 검증 없이 비교 |
| 확정 | UNCATEGORIZED 선택 허용, source=UNCATEGORIZED; 그 밖은 source=RECURRING |
| 확정 | 도래 조회는 fresh/retry 두 인덱스 가지 UNION ALL + 영구 실패 제외 |
| 확정 | cursor 스캔 주기는 시작 시각으로 고정한 scheduled-through horizon으로 유한화(커서 뒤 retry 굶김 방지) |
| 확정 | 목록/홈 요약은 전용 JDBC read model, DB 컬럼 추가 없음 |
| 확정 | cursor는 그룹·필터·템플릿 소속을 검증하고 아니면 400 |
| 기본값 사용 | 09:00, 월말 보정, 자동 3회, 생성자 포함 전원 양수 배분 |
| 이번 지시 우선 | 이슈 #574 및 구현 PR 게시. 배포는 별도 |
