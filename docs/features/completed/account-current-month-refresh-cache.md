# Feature Plan: 현재 월 CODEF 거래 1분 refresh cache

- Status: Complete
- Last updated: 2026-09-02
- Related issue: N/A
- Related ADR: N/A

## 1. Purpose

### Problem

`account_monthly_summaries` 행의 존재 여부를 해당 월 거래 조회 완료 표식으로 사용하고 있어, 현재 월에 CODEF Mock 거래가 추가되어도 월 요약이 생성된 뒤에는 자동 반영되지 않는다.

### Why now

현재는 CODEF Mock 기반 테스트 기간이므로 현재 월 거래를 짧은 주기로 재조회할 수 있어야 한다. 운영용 주기 동기화 정책은 별도 결정한다.

### Success criteria

- [x] 현재 월은 마지막 CODEF 조회 후 1분 동안 저장된 거래를 반환한다.
- [x] 현재 월의 1분 TTL이 지나면 CODEF를 다시 조회하고 신규 거래를 중복 없이 저장한다.
- [x] 과거 월 동작과 기존 거래·월 요약 중복 방지는 유지한다.

### Out of scope

- 수동 sync endpoint, Scheduler, Spring Batch
- 과거 월의 자동 refresh
- 월 요약 테이블 스키마 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `account`
- Relationships to other domains: settlement reconciliation may also save external transactions and invalidate the affected month summary.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 현재 월 | Seoul 기준 현재 `YearMonth` | `YearMonth.now(SEOUL)` |
| refresh marker | 계좌·월의 최근 CODEF 조회가 1분 이내임을 나타내는 Redis 키 | `AccountTransactionRefreshStorePort` |

### State and invariants

- 기존 DB 거래는 외부 거래 Key로 중복 저장하지 않는다.
- 신규 거래가 저장되면 해당 월의 summary를 삭제하고 다음 조회에서 다시 계산한다.
- Redis marker는 DB 트랜잭션 커밋 성공 후에만 기록한다.

## 3. Scenarios

### Normal flow

1. 현재 월 상세 또는 거래 목록을 요청한다.
2. 월 요약과 1분 refresh marker가 모두 있으면 CODEF를 호출하지 않는다.
3. marker가 없거나 만료되면 현재 월 전체를 CODEF에서 조회한다.
4. 거래를 중복 제거해 저장하고, 신규 거래가 있으면 월 요약을 재계산한 뒤 marker를 1분으로 기록한다.

### Boundary conditions

- 과거 월은 기존처럼 summary가 있으면 외부 조회하지 않는다.
- 월 요약이 없으면 marker와 무관하게 최초 적재한다.
- Redis marker는 응답 데이터를 저장하지 않고 refresh 시각만 저장한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: existing GetAccountDetailUseCase / GetAccountTransactionsUseCase
Command: unchanged
Result: unchanged
Web API: unchanged
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `AccountTransactionRefreshStorePort` | 계좌·월 refresh marker의 조회와 1분 저장 | `AccountQueryService` | `AccountTransactionRefreshRedisAdapter` |

### Persistence and external integrations

- Database Entity, constraint, or query changes: none
- Flyway migration required (`Yes` / `No`): No
- Redis key, TTL, or serialization changes: `account:transaction-refresh:<accountId>:<yearMonth>`, value `1`, TTL 1 minute
- External API or message changes: none
- Environment variable or profile changes: none

## 5. Design decisions

### Technology and patterns

- Constraint to solve: current-month Mock changes must appear during testing without making every request call CODEF.
- Selected approach: Redis refresh marker only for the current month; continue using PostgreSQL transactions and monthly summary as the response source.
- Reason for selection: no schema migration and no duplicate response cache; existing external transaction deduplication remains authoritative.
- Alternatives considered: permanent DB summary only (stale), Caffeine (not shared across instances), automatic refresh for all months (unnecessary external calls).
- Trade-offs and limits: concurrent requests can still issue duplicate CODEF calls; the database unique key prevents duplicate transaction rows.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| Application | current-month refresh decision | `AccountQueryService` |
| Redis | marker and 1-minute TTL | `AccountTransactionRefreshStorePort`, `AccountTransactionRefreshRedisAdapter` |
| Persistence | unchanged | `AccountTransactionPersistenceAdapter` |
| API | unchanged | `AccountController` |

Existing behavior that must remain unchanged:

- Future month rejection, account ownership checks, external transaction deduplication, and settlement-linked transaction responses.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` - Redis key only)
- [x] Required Output Port
- [x] Application Service
- [x] Output Adapter
- [x] Documentation and verification results

### Expected commit boundaries

```text
feat(account): 현재 월 CODEF 거래 refresh TTL 적용
```

## 8. Verification

### Commands

```bash
./gradlew test --tests '*AccountQueryServiceTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### Results

| Item | Result | Evidence |
|---|---|---|
| Current-month marker hit skips CODEF | Passed | `AccountQueryServiceTest.returnsCurrentMonthSummaryWhenRefreshMarkerIsFresh` |
| Current-month marker miss refreshes CODEF | Passed | `AccountQueryServiceTest.refreshesCurrentMonthWhenRefreshMarkerIsExpired` |
| Refresh marker is published after commit | Passed | `AccountQueryServiceTest.refreshesCurrentMonthWhenRefreshMarkerIsExpired` |
| Focused test | Passed | `./gradlew test --tests 'com.amateur.howmuchbackend.application.account.service.AccountQueryServiceTest'` |
| Compile and diff check | Passed | `./gradlew compileJava`, `git diff --check` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-02 | Existing monthly summary cache and invalidation flow inspected | Implemented 1-minute Redis marker | Concurrent CODEF calls may still duplicate but DB uniqueness protects rows |
| 2026-09-02 | Current-month Redis marker, service branch, focused tests, compile and diff check completed | N/A | Deployment is intentionally not included |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-02 | 현재 월만 1분 TTL | 테스트 기간의 Mock 변경을 빠르게 반영 | 과거 월 자동 refresh와 운영 동기화는 후속 결정 |
