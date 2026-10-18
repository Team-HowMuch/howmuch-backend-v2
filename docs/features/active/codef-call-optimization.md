# Feature Plan: CODEF 호출 최적화와 계좌 거래 동기화 분리

- Status: 진행 중 (2~5단계 구현, 검증 일부 완료)
- Last updated: 2026-10-18
- Related issue:
- Related ADR: [목적 중심 Port](../../adr/0001-purpose-oriented-ports.md), [Capability 중심 Output Port](../../adr/0003-capability-oriented-output-ports.md), [활성 Account와 Account Record 수명주기](../../adr/0004-separate-active-account-and-account-record-lifecycles.md)
- 기준 커밋: `origin/develop` `87b223e9`
- 작업 브랜치/worktree: `codex/codef-sync-optimization` / `howmuch-backend-v2-codef-sync`
- 인수인계 문서: [codef-call-optimization-handoff.md](codef-call-optimization-handoff.md)

## 1. Purpose

### Problem

CODEF는 호출 한도·지연·비용이 있는 외부 금융 API다. 그런데 지금은 **저장된 데이터를 조회만 해도 CODEF를 호출**한다.

- `GET /api/v2/accounts/{accountId}`와 `GET /api/v2/accounts/{accountId}/transactions`는 현재 월이 1분 지났거나 과거 월 summary가 없으면 CODEF `transaction-list`를 호출한다. 두 메서드는 `@Transactional`이라 CODEF 응답(기본 read timeout 305초) 동안 DB 커넥션과 `accounts` 행 락을 잡는다.
- 정산 입금 대조·위젯·자동 대조·압축 정산은 같은 계좌의 같은 기간을 각자 다시 조회한다.
- 계좌 조회 경로는 일일 CODEF 호출 예산 밖이다.

이 때문에 화면 진입만으로 외부 호출이 발생하고, 느린 CODEF 응답이 계좌·정산 동시성에 영향을 준다.

### Why now

거래 기록이 서비스의 핵심 데이터이고 정산 확인·입금 대조가 여기에 의존한다. 조회 경로의 암묵적 CODEF 호출을 제거하지 않으면 호출량과 장애 영향 범위를 통제할 수 없다.

### Success criteria

- [x] 일반 계좌 조회(GET 상세·거래내역)는 CODEF를 호출하지 않고 저장 데이터를 반환한다.
- [x] 최신 데이터가 필요한 시점에 명시적 동기화 API를 호출한다.
- [x] 동기화는 CODEF 호출을 DB 트랜잭션 밖에서 수행하고, 저장 직전에 계좌 활성 상태를 재검증한다.
- [x] 같은 계좌·월의 동시 동기화는 중복 CODEF 호출을 억제한다.
- [x] 같은 계좌·기간을 여러 도메인이 중복 수집하지 않도록 수집 재사용 경계를 만든다. (4단계: 정산 fetch 경로가 coverage로 저장 거래를 재사용)
- [x] 조회 범위·성공 시각·"0건 성공"과 "미조회"를 구분한다. (5단계: coverage 값에 수집 시각 저장, 계좌 상세에 synced/lastSyncedAt 노출)

### Out of scope

- CODEF ID/PW 로그인, 은행 등록·인증 자체의 제거
- 비동기 sync job과 Kafka producer/consumer 도입
- 정산 입금 매칭·완료 판정·Kafka 후속 처리의 의미 변경
- 계좌 동기화의 CODEF 예산 통합(별도 정책 결정 필요)
- coverage의 DB 영속화(Redis hint + PostgreSQL 거래로 해결)

## 2. Domain analysis

### Domain boundary

- 기존 domain을 재사용한다: `account`(계좌·거래·월 요약), `external`(연결·CODEF), `settlement`(입금 대조)은 경계를 바꾸지 않는다.
- 새 domain을 만들지 않는다.
- 관계: 계좌 거래 조회는 `account`가 소유하고, 정산 대조·압축 관측은 `settlement`이 소비한다. 동기화는 두 경계 사이의 데이터 공급 지점이다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 동기화 | CODEF에서 특정 계좌·월의 거래를 조회해 저장·집계하는 명시적 작업 | `SyncAccountTransactionsUseCase.sync` |
| 조회 | 저장된 거래·요약을 읽는 것. 외부 호출 없음 | `GetAccountDetailUseCase`, `GetAccountTransactionsUseCase` |
| refresh marker | 성공한 동기화의 짧은 중복 방지 표식 | `account:transaction-refresh:v2:{accountId}:{YYYY-MM}` |
| sync claim | 동시 동기화 1건만 진행시키는 best-effort 선점 | `account:transaction-sync-claim:{accountId}:{YYYY-MM}` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 인증 사용자 | 최신 거래를 반영한다 | `SyncAccountTransactionsUseCase` |
| 인증 사용자 | 저장된 계좌 상세·거래내역을 조회한다 | `GetAccountDetailUseCase`, `GetAccountTransactionsUseCase` |
| 정산/압축 처리 | 기존처럼 자기 경로에서 대조·관측한다 | 변경 없음 |

### State and invariants

- 상태 전이: 저장된 거래 없음 → 동기화 성공 시 거래 저장·월 요약 재집계·marker 기록. 실패 시 저장·marker 변화 없음.
- 항상 성립: 동기화 저장은 계좌 활성 상태를 저장 트랜잭션에서 재검증한다. 거래 중복은 `UNIQUE(account_id, external_transaction_key)`로 막는다.
- 잘못된 상태: 삭제·비활성 계좌에 stale 결과를 저장하지 않는다. 미래 월은 CODEF 호출 전에 거절한다.

## 3. Scenarios

### Normal flow

1. 사용자가 계좌 화면을 연다.
2. 화면이 `GET` 상세·거래내역을 호출한다 → 저장 데이터 즉시 반환(CODEF 없음).
3. 사용자가 새로고침하거나 월을 바꾼다 → `POST /transactions/sync` 호출.
4. 서버가 계좌·연결을 읽고, Redis claim을 잡고, 트랜잭션 밖에서 CODEF를 조회한다.
5. 짧은 트랜잭션에서 계좌를 재검증하고 거래를 저장, 월 요약을 재집계, 압축 관측을 큐잉한다.
6. 성공 marker를 쓰고 claim을 해제한다. 응답으로 `refreshed=true`와 조회 범위·건수·요약을 반환한다.

### Failure flow

- 조건: CODEF 실패, 연결 없음, 동기화 도중 계좌 비활성화.
- 결과: 저장·marker 변화 없음. `CODEF_*`(502/503), `EXTERNAL_CONNECTION_NOT_FOUND`(404), `ACCOUNT_NOT_FOUND`(404) 그대로 반환. claim은 `finally`에서 해제한다.

### Boundary conditions

- 1분 내 재요청: `refreshed=false`, CODEF 미호출.
- 동시 요청: 먼저 선점한 요청만 CODEF 호출, 나머지는 `syncInProgress=true`로 저장 결과 반환.
- claim 만료 후 이전 작업이 늦게 저장: DB 재검증으로 계좌가 유효하면 멱등 저장된다. 중복 CODEF 호출은 남을 수 있다(아래 한계).
- Redis 장애: claim을 건너뛰고 동기화를 계속한다. 저장 정합성은 DB 제약이 담당한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: SyncAccountTransactionsUseCase.sync(Long accountId, YearMonth yearMonth)
Command: 단순 식별자이므로 별도 Command 없음
Result: AccountTransactionSyncResult
Web Request DTO -> Command: 없음(쿼리 파라미터 yearMonth)
Result -> Web Response DTO: AccountTransactionSyncWebResponse.from(result)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadAccountPort` (기존) | 활성 계좌 조회·잠금 | Sync Service, Sync Writer | `AccountPersistenceAdapter` |
| `LoadAccountTransactionPort` (기존) | 저장 거래·요약 조회·재집계 | Sync Service, Sync Writer | `AccountTransactionPersistenceAdapter` |
| `SaveAccountTransactionPort` (기존) | 거래 중복 무시 저장·요약 저장 | Sync Writer | `AccountTransactionPersistenceAdapter` |
| `ExternalConnectionStorePort` (기존) | 활성 CODEF 연결 조회 | Sync Service | `ExternalConnectionPersistenceAdapter` |
| `BankTransactionPort` (기존) | CODEF 거래 조회 | Sync Service | `CodefBankTransactionAdapter` |
| `AccountTransactionRefreshStorePort` (기존, 확장) | 신선도 marker + 동시 동기화 claim | Sync Service | `AccountTransactionRefreshRedisAdapter` |
| `AccountTransactionCoveragePort` (신규) | 수집 완료 월 표식·시각 조회·기록 | Sync Service, Settlement fetch, Account detail | `AccountTransactionCoverageRedisAdapter` |
| `BankAccountCandidateCachePort` (신규) | 계좌 후보 목록 단기 캐시 | BankAccountService, BankConnectionQuery | `BankAccountCandidateCacheRedisAdapter` |
| `QueueNettedDepositDetectionPort` (기존) | 압축 정산 거래 관측 큐잉 | Sync Writer | `NettedDepositDetectionJdbcAdapter` |

### Application Policy

- 해당 없음. 정책으로 분리할 다중 사실 판단이 없다. 월 검증·상품 검증은 서비스 내 단순 규칙이고, 계좌 활성 재검증은 저장 트랜잭션의 사실 확인이다.

### Web API

```text
POST /api/v2/accounts/{accountId}/transactions/sync?yearMonth=YYYY-MM
Request: 없음. yearMonth 생략 시 현재 월(Asia/Seoul)
Response: accountId, yearMonth, refreshed, syncInProgress, syncedAt, fetchedFrom, fetchedTo,
          fetchedCount, monthlyExpenseAmount, expenseCount, lastUsedAt
Errors: 400 INVALID_ACCOUNT_QUERY, 404 ACCOUNT_NOT_FOUND, 404 EXTERNAL_CONNECTION_NOT_FOUND,
        422 ACCOUNT_TRANSACTION_NOT_SUPPORTED, 409 CODEF_ADDITIONAL_AUTH_REQUIRED,
        502/503 CODEF 계열
Authentication: bearerAuth, 본인 활성 계좌
변경: GET /accounts/{id}, GET /accounts/{id}/transactions는 CODEF를 호출하지 않는다.
      GET /accounts/{id} 응답에 synced(해당 월 수집 성공 여부), lastSyncedAt(마지막 성공 수집 시각)이 추가된다(기존 필드 유지).
```

### Persistence and external integrations

- Database Entity/constraint/query 변경: 없음. 기존 `account_transactions`, `account_monthly_summaries` 재사용.
- Flyway migration required: **No**
- Planned migration: 없음
- Migration pull request: `N/A`
- Redis key/TTL/serialization 변경:
  - 기존 `account:transaction-refresh:v2:{accountId}:{YYYY-MM}` TTL 1분 유지
  - 신규 `account:transaction-sync-claim:{accountId}:{YYYY-MM}` 값 = UUID claim token, TTL 6분, Lua compare-and-delete로 해제
  - 신규 `account:transaction-coverage:v2:{accountId}:{YYYY-MM}` 값 = epoch millis|수집 시작일|수집 종료일, TTL 30일. 성공 수집 월 표식이며 eviction·만료 시 재조회로 안전하게 복구된다.
  - 신규 `account:connection-candidates:{userId}:{connectionId}:{organization}` 값 = 후보 목록 JSON(candidateKey·maskedNumber만), TTL 30초. `link` 성공 시 무효화한다.
- External API/message 변경: 없음
- 환경변수/profile 변경: 없음

## 5. Design decisions

### Business logic

- 규칙 위치: 월 검증·상품 검증은 `AccountTransactionSyncService`, 활성 계좌 재검증은 `AccountTransactionSyncWriter`(트랜잭션), 거래 중복은 DB unique+`ON CONFLICT DO NOTHING`.
- Domain 소유: 거래 식별 키(`AccountTransactionKey`)와 월 요약 계산은 기존 규칙 유지.
- Application Policy: 없음.
- Application orchestration: 읽기 → claim → 외부 호출 → 짧은 저장 → marker/해제.

### Technology and patterns

- 해결할 제약: 조회 경로의 암묵적 외부 호출과 HTTP 동안의 락 점유.
- 선택한 접근: 조회를 순수 읽기로 만들고 명시적 동기화 API로 분리. 외부 호출은 트랜잭션 밖, 저장은 짧은 트랜잭션. 동시 중복은 기존 Redis 저장소를 확장한 claim으로 억제.
- 선택 이유: 기존 `SettlementBankTransactionFetchService`/`SettlementDepositWriter`가 이미 "락 해제 후 외부 호출 → 짧은 저장 재검증" 패턴을 쓰고 있어 일관적이다.
- 검토한 대안:
  - 조회에 TTL을 늘리는 방법: 호출 자체를 없애지 못함.
  - V32 async job 즉시 도입: 결과 consumer가 없고 범위가 크다. 후속 단계로 분리.
  - DB 작업 테이블(V52 패턴): 마이그레이션이 필요해 3단계에서는 Redis claim으로 한정.
- trade-off/한계:
  - Redis claim은 best-effort다. claim TTL(6분)이 만료된 뒤 이전 작업이 늦게 저장하면 중복 CODEF 호출이 발생할 수 있다. 데이터 정합성은 DB 재검증·unique 제약이 보장한다.
  - 계좌 동기화는 아직 호출 예산 밖이다.
  - `account_monthly_summaries` 존재는 엄밀한 coverage가 아니다(거래 insert 시 삭제됨).

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | GET 2종 CODEF 제거, POST sync 추가 | `AccountController.java`, `AccountDocs.java` |
| Application | `AccountQueryService` 읽기 전용화, `AccountTransactionSyncService`/`Writer` 추가 | `application/account/service/*` |
| Domain | 변경 없음 | `AccountTransactionKey`, `AccountMonthlySummary` |
| Persistence | 변경 없음(기존 테이블·제약) | `AccountTransactionPersistenceAdapter` |
| Redis | claim key 추가, marker 유지 | `AccountTransactionRefreshRedisAdapter` |
| Security | 인증 경계 유지, 신규 민감정보 없음 | 기존 `bearerAuth` |
| Configuration | 변경 없음 | — |
| Documentation | `AccountDocs`, 완료 문서 2건, 본 계획 | `docs/features/*` |

Existing behavior that must remain unchanged:

- 정산 입금 매칭·완료 전이·알림·Kafka 후속 처리
- 압축 정산 거래 관측(`QueueNettedDepositDetectionPort.observed`)
- 거래 중복 방지와 월 요약 무효화/재집계
- 계좌 소유권·삭제 정책과 응답 호환성

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 마이그레이션 불필요)
- [x] UseCase와 Application Result (`SyncAccountTransactionsUseCase`, `AccountTransactionSyncResult`)
- [x] 필요한 Output Port (`AccountTransactionRefreshStorePort.tryClaim/release` 확장, 나머지 재사용)
- [x] Domain model/rule (`N/A` — 신규 도메인 규칙 없음)
- [x] Application Policy (`N/A` — 정책 없음)
- [x] Application Service (`AccountTransactionSyncService`, `AccountTransactionSyncWriter`) + `AccountQueryService` 읽기 전용화
- [x] Output Adapter (`AccountTransactionRefreshRedisAdapter` claim 구현)
- [x] Web DTO·Controller·Swagger (`AccountTransactionSyncWebResponse`, `AccountController`, `AccountDocs`)
- [x] Infra Config (`N/A` — 신규 Bean/설정 없음)
- [x] Documentation (본 계획, `AccountDocs`, 완료 문서 후속 변경 기록)
- [x] 4단계: 계좌별 공통 수집·재사용 (`SettlementBankTransactionFetchService` coverage 재사용, sync coverage 기록)
- [x] 5단계: 기간별 동기화 상태(synced/lastSyncedAt)·계좌 후보 캐싱

### Expected commit boundaries

```text
feat(account): 계좌 조회에서 CODEF 호출 제거
feat(account): 계좌 거래 명시적 동기화 API 추가
feat(account): 동시 동기화 중복 호출 억제 claim 추가
test(account): 조회 무호출·동기화 1회·진행 중·실패 보존 검증
docs(account): 조회·동기화 계약 문서 갱신
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew compileTestJava
./gradlew test --tests '*AccountQueryServiceTest' --tests '*AccountTransactionSyncServiceTest' --tests '*AccountControllerTest'
git diff --check
git status --short --untracked-files=all
```

필요 환경: 전체/통합 테스트는 PostgreSQL·Redis가 필요하다. Docker 데몬 응답이 없어 미실행.

### Completion criteria

- [x] Purpose의 사용자 결과 검증(조회 무 CODEF, 동기화 1회)
- [x] 정상·실패·경계 흐름(미래 월, 미지원 상품, 연결 없음, 진행 중, 저장 직전 비활성)
- [x] 문서와 Swagger가 실제 계약과 일치
- [x] 미실행 검증과 이유 기록

### Results

| Item | Result | Evidence |
|---|---|---|
| `compileJava` | 통과 | Gradle |
| `compileTestJava` | 통과 | Gradle |
| `AccountQueryServiceTest` | 통과 8/8 | `build/test-results/test/TEST-*AccountQueryServiceTest.xml` |
| `AccountTransactionSyncServiceTest` | 통과 8/8 | `build/test-results/test/TEST-*AccountTransactionSyncServiceTest.xml` |
| `AccountControllerTest` | 통과 12/12 | `build/test-results/test/TEST-*AccountControllerTest.xml` |
| `SettlementBankTransactionFetchServiceTest` | 통과 3/3 | `build/test-results/test/TEST-*SettlementBankTransactionFetchServiceTest.xml` |
| `SettlementDepositServiceTest` | 통과 17/17 | `build/test-results/test/TEST-*SettlementDepositServiceTest.xml` |
| `BankAccountServiceTest` | 통과 11/11 | `build/test-results/test/TEST-*BankAccountServiceTest.xml` |
| 전체/통합 테스트 | 미실행 | Docker 데몬 응답 없음, DB·Redis 필요 |
| `git diff --check` | 통과 | — |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-17 | CODEF 호출 경로 전수 조사, 스키마·테스트 현황 분석, 2단계 설계 | 2단계 구현 | GET 계약 변경의 프론트 영향, 예산 미적용 |
| 2026-10-18 | 조회/동기화 분리와 동기화 API 구현, 단위 테스트 통과 | 3단계 claim 검증, 4단계 설계 | claim 만료 시 중복 호출 가능, coverage 미영속 |
| 2026-10-18 | Redis claim 기반 동시 동기화 억제 구현, 단위 테스트 8개 통과 | 4단계 수집 재사용 | — |
| 2026-10-18 | coverage 기반 수집 재사용 구현(정산 fetch + sync 기록), fetch/deposit 테스트 통과 | 5단계 기간 상태·계좌 후보 캐싱 | 과거 월 30일 재검증, 현재·직전 월 항상 재조회 |
| 2026-10-18 | coverage 수집 시각 저장·상세 노출, 계좌 후보 30초 캐시 구현, 관련 테스트 통과 | 배포 전 통합 검증 | 후보 캐시는 birthDate를 key에 포함하지 않음(연결별 고정 전제) |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-18 | 동기 API(200) + `refreshed`/`syncInProgress` 플래그 | 첫 구현은 동기로 충분, 응답 호환 유지 | 비동기 job(V32)은 결과 consumer 필요 |
| 2026-10-18 | 1분 marker는 사후 중복 방지, 별도 claim으로 in-flight 억제 | marker만으로는 동시 중복을 막지 못함 | DB job lease는 마이그레이션 필요 |
| 2026-10-18 | claim TTL 6분, best-effort | CODEF 일반 응답보다 길고 락 점유 없음 | TTL 만료 후 늦은 저장 시 중복 호출 가능(정합성은 DB가 보장) |
| 2026-10-18 | 계좌 동기화 예산 통합은 4단계로 | 현재 예산 namespace가 정산 전용 | 계좌/정산 예산 정책 결정 필요 |
| 2026-10-18 | 수집 재사용은 PostgreSQL 거래 + Redis coverage(TTL 30일)로 구현 | 마이그레이션 없이 기존 저장·Redis로 해결, eviction 시 재조회로 안전 | DB coverage 영속화는 5단계에서 검토 |
| 2026-10-18 | 현재 월·직전 월은 always revalidate | 지연 반영·과거 거래 수정 가능성 | 오래된 월만 재사용하므로 절감 효과는 장기 정산에서 크다 |
| 2026-10-18 | coverage에 epoch millis 수집 시각 저장 | "0건 성공" vs "미조회" 구분, lastSyncedAt 노출 | 값 파싱 실패 시 미수집으로 간주 |
| 2026-10-18 | 계좌 후보는 client-visible shape(해시 key·마스킹 번호)만 30초 캐시 | 재진입 CODEF 중복 제거, raw 계좌번호 미저장 | link는 항상 CODEF 재조회·재검증 |

## 11. Completion record

- Completed on: 2026-10-18 (2~5단계 로컬 구현 완료, 배포·통합 검증 전)
- Final behavior: 조회는 저장 데이터만 반환하고, 명시적 동기화가 CODEF를 호출한다. 수집 완료 월은 정산·위젯·자동 대조·압축이 재사용하며, 계좌 상세가 synced/lastSyncedAt을 제공한다.
- Unverified items: 전체/통합 테스트(PostgreSQL·Redis), 배포 후 실제 CODEF 흐름
- Remaining risks: claim 만료 후 중복 호출, 계좌 경로 예산 밖, 후보 캐시 birthDate 미포함
- Follow-up work: 계좌/정산 CODEF 예산 정책 통합, 비동기 sync(V32) 정렬, 실제 배포 검증

## 12. 로컬 보완 설계 (2026-10-18)

- DB/Flyway 변경 없음. 원격 발행 없음.
- coverage v2는 실제 수집 시작일/종료일/시각을 보관한다. 정산의 부분 조회는 coverage를 기록하지 않는다. 계좌 sync writer의 커밋 성공 후에만 기록한다. 정산은 월 전체를 포함하는 v2 범위만 재사용한다. 이전 불완전 표식은 새 key namespace로 무시한다.
- 당월 중간까지 수집한 표식이 다음 달 이후 월 전체 수집으로 오인되지 않도록 범위를 검사한다. 마지막 날 당일 수집도 일 종료 전이므로 월 전체 재사용에서 제외한다.
- GET 상세에 syncInProgress 추가. 프론트는 진행 중 응답 후 GET만 polling하며, 성공 수집 시각이 전진했는지 확인하고 완료/실패/시간 초과를 구분한다. sync 생략 응답에도 기존 syncedAt을 제공한다.
- claim은 CODEF read timeout(305초)보다 긴 6분. 프론트 POST timeout 330초, 대기 polling은 제한 시간을 두며 자동 POST 재시도 없음.
- 실제 프론트 `howmuch-frontend-v2`에 계좌·월별 상태/중복 억제/실패 재시도/독립된 월 이동을 구현한다. 기존 캐시를 유지하며 빈 거래와 미수집을 구분한다. 실기기도 실제 프론트 작업 디렉터리의 Metro 번들을 사용한다.
- 검증: 부분 범위/0건/월 경계/저장 실패/구형 Redis 표식, GET 진행 상태, 프론트 중복·다른 월 동시 진행·완료 polling·실패·시간 초과·로그아웃 시 cache 오염 방지. 기존 관련 테스트와 실제 로컬 API/실기기 확인.


## 14. 앱 실행·복귀 자동 갱신 보완 (2026-10-18)

- 사용자 목표: 로그인/잠금 해제 후 앱 실행 및 background→active 복귀 시 기존 화면을 유지하면서 계좌 당월 수집과 진행 정산 전체 입금 대조를 수행한다. 홈/정산 상세/목록은 자동으로 최신 DB 상태를 표시한다. 수동 새로고침은 실패 시 보조 수단이다.
- 기존 위젯 `POST /api/v2/settlements/widget-summary/refresh`를 재사용한다. 모바일 홈 화면 위젯 UI를 새로 만드는 범위는 아니다. 새 API/스키마/마이그레이션 없음. 원격 발행 없음.
- 프론트 app 계층에서 실행/복귀 트리거를 조합한다. 잠금 해제 전/로그아웃 후 실행 금지. 토큰 회전과 화면 전환은 새 작업을 유발하지 않는다. 세션별 진행 작업 공유 및 60초 재시도 간격, inactive(권한 팝업) 복귀로 중복 실행 금지. 명시적 재시도만 클라이언트 간격 우회(서버 제한 유지).
- 계좌·월 sync는 기존 화면과 같은 runner를 사용한다. 계좌 하나의 실패가 다른 계좌나 정산 대조를 막지 않는다. 모든 성공만 성공 안내하며 실패 중에도 저장 화면은 유지한다. 로그아웃/사용자 변경 시 후속 요청과 캐시 반영 중단.
- 계좌 수집 후 위젯이 같은 거래를 중복 수집하지 않도록, 위젯 경로에 한해 커밋된 coverage v2의 정확한 요청 범위 + 수집 시작 후 60초 이내인 snapshot을 재사용한다. 범위 밖/만료/미래 시각/Redis 장애면 기존 외부 조회로 복구한다. 일반 수동/자동 정산 fetch의 현재·직전월 재검증 규칙은 유지한다.
- 검증: CODEF mock의 transaction-list 응답에 8초 지연 및 실패를 주입. 실행/복귀 자동 처리, 중복 억제, 화면 유지, 정산 자동 완료, 실패·재시도, 로그아웃 도중 완료를 회귀 및 로컬 HTTP/기기에서 확인한다. 실제 은행 호출은 사용하지 않는다. 완납은 가상 거래로만 확인한다.


### 14절 구현·검증 결과

- 실제 프론트의 RootNavigator/AppState 연결, 세션별 중복·60초 억제, 계좌 sync와 위젯 일괄 대조, 홈/정산 조회 갱신 및 진행/실패 안내 반영 완료.
- 관련 Java 35개·JS 35개, 프론트 회귀 19개·typecheck, mock Python 9개, compileJava/bootJar 및 diff/link 검증 통과.
- production TS coordinator→실제 로컬 API→DB/Redis→CODEF HTTP Adapter→8초 지연 mock 통합 QA 통과: 수동 정산 refresh 0회로 가상 정산 자동 완료. 진행 중 GET 24ms, 정상 은행 수집 1회와 중복 억제 확인. 503 오류 시 기존 거래/미납 보존 및 명시적 재시도 성공 확인.
- 네이티브 실행/복귀 QA도 완료했다: 가상 정산 2건 자동 완료, 빠른 복귀 3회 억제, 60초 이후 재수집, 계좌 화면에서 8초 수집 중 기존 7건 유지 및 중복 방지를 확인했다. 실제 은행/실제 송금·모바일 홈 화면 위젯 UI는 검증하지 않았다. 전체 Java suite는 이번 단계에서 반복하지 않았다. [측정과 제한](../../qa/codef-foreground-slow-2026-10-18.json).


## 17. 이슈 #579 / 최신 develop 게시 준비 (2026-10-18)

- 기준: develop `3cdceaf0` (SMS #578 및 정기 정산 #575 병합). 기존 로컬 QA 브랜치를 보존하고 `feat/579-codef-call-optimization`에서 변경을 적용한다. migration 추가·변경 없음.
- SMS 기능 flag가 켜진 경우 거래 수집의 기존 사용자·기관별 admission transaction을 보존한다. 이 opt-in 경로의 최초 CODEF 호출은 advisory lock/DB transaction을 유지하므로 모든 sync가 DB transaction 밖이라는 보장은 하지 않는다. GET은 SMS 활성 여부와 무관하게 DB 조회만 한다. 기본 SMS 비활성화 경로의 CODEF 호출은 DB transaction 밖이다.
- SMS continuation의 `TRANSACTION_LIST` 저장을 Query Service에서 Sync Service/Writer로 옮긴다. 암호화된 FinanceOperation에 원래 수집 시작 시각과 종료일을 선택 필드로 보관하고 구형 7필드 생성자/저장 payload도 읽는다. SMS 재개가 자정을 넘겨도 원래 범위만 기록한다.
- SMS 완료는 외부 worker transaction에 참여하므로 coverage/refresh 표식은 outer afterCommit 이후에만 기록한다. 롤백이나 구형 범위 없는 payload에서는 coverage를 주장하지 않는다.
- 후보 캐시는 기존 SMS admission/완료 결과를 유지하며 최종 연결에서 외부/인증 완료 후보를 재검증한다. 새 SMS 결과로 기존 display cache를 무효화한다.
- 검증 계획: 계좌/coverage/위젯 관련 회귀, SMS 어댑터·실제 PostgreSQL 재개·Bean wiring, commit/rollback/자정·구형 payload 회귀, Java compile 및 패키징. 기존 실기기/실 CODEF 수치는 게시 브랜치의 새 실기기 실행 결과와 구분한다.
- 게시 커밋 경계: 계좌 sync·재사용·SMS 호환 구현 / 회귀 및 mock 도구 / 설계·QA 기록. PR base는 develop, 제목은 `feat(account): 계좌 조회와 CODEF 동기화 분리 및 수집 재사용`.
