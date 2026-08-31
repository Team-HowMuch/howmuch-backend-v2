# Feature Plan: 계좌 거래내역 연결 정산 정보

- Status: Completed
- Last updated: 2026-08-31
- Related issue: [#208](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/208)
- Related ADR: N/A

## 1. Purpose

### Problem

계좌 상세 화면은 `GET /api/v2/accounts/{accountId}/transactions`로 거래를 날짜순으로 보여 주지만, 각 거래가 어느 정산에 사용됐는지 알 수 없다. 프론트는 정산 관련 거래만 구분하거나 연결된 정산으로 이동할 수 없다.

### Why now

계좌 거래와 정산 지출의 연결은 이미 `settlement_expenses.source_reference`에 저장되어 있다. 저장된 관계를 기존 거래내역 API에서 제공하면 새 화면 전용 API 없이 현재 UI 구조를 유지할 수 있다.

### Success criteria

- [x] 각 거래가 현재 사용자 정산에 연결된 경우 `settlements[]`로 `settlementId`, `title`, `status`를 반환한다.
- [x] 연결이 없으면 `settlements: []`를 반환한다.
- [x] 동일 거래가 여러 정산에 연결되면 모두 반환한다.
- [x] 거래별 추가 조회 없이 월 거래 전체의 연결 정산을 한 번에 조회한다.
- [x] 기존 거래 정렬, 월 동기화, 계좌 소유권 검증을 유지한다.

### Out of scope

- 별도 `/api/v2/accounts/{accountId}/settlements` API
- `settledOnly` 필터
- 정산 참여자, 납부 현황, 공유 링크
- 거래 하나를 정산 하나로 제한하는 정책·스키마 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Account`, `Transaction`, `Settlement`, `SettlementExpense`
- Reason a new domain is required, if applicable: N/A. 기존 거래 조회에 저장된 정산 연결 요약을 조합하는 조회 기능이다.
- Relationships to other domains: account는 소유권과 거래 목록을 제공하고 settlement는 `PAYMENT_HISTORY` 지출의 원본 거래 Key와 화면용 요약을 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 연결 정산 | 계좌 거래의 `externalTransactionKey`를 `PAYMENT_HISTORY` 지출 원본으로 사용한 정산 | `settlement_expenses.source_reference` |
| 정산 요약 | 계좌 거래 행에서 표시할 최소 정산 정보 | `settlementId`, `title`, `status` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 자신의 계좌 거래와 연결 정산을 함께 조회한다. | 기존 `GetAccountTransactionsUseCase` |
| 앱 클라이언트 | `settlements`가 비어 있지 않은 거래를 정산 관련 내역으로 표시한다. | 기존 계좌 거래내역 API |

### State and invariants

- State transitions: 없음. 읽기 전용 기능이다.
- Rules that must always hold:
  - 활성 계좌 소유권을 먼저 검증한다.
  - `PAYMENT_HISTORY` 지출만 계좌 거래 연결로 해석한다.
  - 현재 계좌 소유자가 생성한 정산만 반환한다.
  - 동일 거래·정산 조합은 한 번만 반환한다.
- Invalid states: 다른 사용자의 정산 또는 다른 계좌 거래를 연결 정보로 노출하는 상태.

## 3. Scenarios

### Normal flow

1. 사용자가 기존 계좌 거래내역 API를 호출한다.
2. Application이 계좌 소유권을 검증하고 기존 월 거래 동기화·조회 흐름을 실행한다.
3. 거래의 `externalTransactionKey` 목록으로 연결 정산을 한 번에 조회한다.
4. 거래별로 연결 정산 요약을 묶어 기존 거래 응답에 `settlements`를 추가한다.

### Failure flow

- 타인 또는 비활성 계좌: 기존 `ACCOUNT_NOT_FOUND`를 반환한다.
- 미래 월 또는 지원하지 않는 계좌: 기존 오류 계약을 유지한다.
- 연결 정산이 없음: 오류가 아니라 빈 배열을 반환한다.

### Boundary conditions

- 거래가 없으면 연결 정산 쿼리를 실행하지 않고 빈 거래 목록을 반환한다.
- 한 거래가 여러 정산에 연결될 수 있으므로 단일 nullable `settlementId`를 사용하지 않는다.
- 정산 요약은 최신 생성 순으로 반환하고 UUID를 보조 정렬 기준으로 사용한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 GetAccountTransactionsUseCase.getAccountTransactions(accountId, yearMonth)
Command: N/A
Result: AccountTransactionResult + settlements: List<SettlementSummary>
Web Request DTO -> Command: N/A
Result -> Web Response DTO: AccountTransactionWebResponse
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `LoadAccountTransactionPort` | 월 거래와 해당 거래 Key에 연결된 정산 요약 조회 | `AccountQueryService` | `AccountTransactionPersistenceAdapter` |

새 Port를 만들지 않고 기존 계좌 거래 조회 Port에 연결 정산 조회 capability를 추가한다. 저장된 연결은 거래 Key 기준의 읽기 모델이며 현재 호출자도 동일 Service다.

### Application Policy

- Decision owned by the Policy: N/A. 소유자·source·Key 일치 조건을 조회에서 적용한다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 계좌 거래와 정산 지출·정산을 결합한 조회다.
- Future inputs deliberately not implemented yet: 참여자·납부 상태와 서버 필터.

### Web API

```text
GET /api/v2/accounts/{accountId}/transactions?yearMonth=YYYY-MM

Response item:
{
  "id": 1,
  "type": "EXPENSE",
  "amount": 12000,
  "transactedAt": "2026-08-31T03:00:00",
  "description": "식당",
  "afterBalance": 100000,
  "settlements": [
    {
      "settlementId": "UUID",
      "title": "점심 정산",
      "status": "AWAITING_DEPOSIT"
    }
  ]
}

Authentication: bearerAuth
Errors: 기존 계좌 거래내역 오류와 동일
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 스키마 변경 없이 `account_transactions.external_transaction_key`, `settlement_expenses.source_reference`, `settlements`를 조회한다.
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 없음.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 계좌 소유권·월 검증은 기존 `AccountQueryService`, 연결 정산 필터는 Persistence query.
- Behavior owned by Domain: 없음.
- Decisions owned by Application Policy: N/A.
- Flow orchestrated by Application: 거래 조회, 연결 정산 일괄 조회, 거래별 Result 조립.

### Technology and patterns

- Constraint to solve: 거래별 정산 조회 시 N+1이 발생한다.
- Selected approach: 월 거래 Key를 `IN` 조건으로 한 번 조회하고 Application에서 Key별로 그룹화한다.
- Reason for selection: 기존 API와 Port를 최소 확장하며 다중 정산 연결을 보존한다.
- Alternatives considered: 별도 endpoint는 현재 화면에 중복 조회가 필요해 제외한다. 단일 `settlementId`는 현재 DB 제약과 맞지 않아 제외한다.
- Trade-offs and limits: `source_reference` 단독 인덱스는 없다. 실제 성능 문제가 측정되면 별도 migration으로 인덱스를 추가한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 거래 응답에 `settlements` 추가 | `AccountTransactionWebResponse`, `AccountDocs` |
| Application | 거래별 정산 요약 조립 | `AccountQueryService`, `AccountTransactionResult` |
| Domain | 변경 없음 | 기존 `Transaction`, `SettlementStatus` |
| Persistence | 거래 Key 기준 연결 정산 일괄 조회 | `LoadAccountTransactionPort`, account persistence query |
| Redis | 변경 없음 | N/A |
| Security | 계좌 소유자와 정산 생성자 ID를 함께 제한 | 기존 `CurrentActorPort`, 신규 query 조건 |
| Configuration | 변경 없음 | N/A |
| Documentation | 응답 계약과 구현 결과 기록 | 이 문서, Swagger |

Existing behavior that must remain unchanged:

- CODEF 최초 조회·저장과 월 요약 캐시.
- 거래 정렬 `transactedAt DESC, id DESC`.
- 정산 결제내역 가져오기와 동일 정산 중복 방지.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: 스키마 변경 없음)
- [x] UseCase and Application Command/Result (`기존 UseCase, Result 확장`)
- [x] Required Output Ports
- [x] Domain models and rules (`N/A`: 변경 없음)
- [x] Application Policies (`N/A`: 별도 Policy 불필요)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`: 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
feat(account): 계좌 거래내역에 연결 정산 정보 반환
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*AccountQueryServiceTest' --tests '*AccountControllerTest' --tests '*AccountTransactionPersistenceIntegrationTest'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [x] Purpose의 다중·미연결 정산 응답을 검증한다.
- [x] 계좌·정산 소유권과 빈 거래 경계를 검증한다.
- [x] Swagger와 실제 JSON 계약이 일치한다.
- [x] 미검증 범위를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Issue | Passed | #208 생성 |
| 설계와 migration 판단 | Passed | 기존 컬럼과 관계 재사용, schema 변경 없음 |
| Compile | Passed | `./gradlew compileJava` |
| 집중 테스트 | Passed | Application·Web·PostgreSQL persistence 테스트 통과 |
| 전체 회귀 | Passed | 임시 PostgreSQL 16·Redis 7에서 509개, 실패 0 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-31 | #208 생성, API·Application·Persistence 흐름 분석, migration 불필요 확정 | 계약 구현과 검증 | 거래 하나가 여러 정산에 연결 가능, source_reference 단독 인덱스 없음 |
| 2026-08-31 | 거래 응답·일괄 조회·소유권 조건 구현, 집중·전체 회귀 통과 | 프론트 연결 | 성능 문제는 현재 측정되지 않음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-31 | 기존 거래내역 API에 `settlements[]` 추가 | 현재 날짜별 거래 UI를 유지하고 추가 API를 피함 | 프론트가 빈 배열로 정산 관련 거래를 필터링 |
| 2026-08-31 | `settledOnly` 미구현 | 현재 응답에서 프론트 필터가 가능하고 서버 필터 필요가 측정되지 않음 | 데이터량 문제가 생기면 추가 |
| 2026-08-31 | migration 없음 | 저장 관계가 이미 존재하고 신규 인덱스 근거가 없음 | 성능 측정 후 별도 인덱스 migration |

## 11. Completion record

- Completed on: 2026-08-31
- Final behavior: 기존 계좌 거래내역 응답이 각 거래의 연결 정산을 `settlements[]`로 반환하며 미연결은 빈 배열, 다중 연결은 최신순 목록으로 반환한다.
- Unverified items: 실제 운영 데이터 규모에서의 query latency.
- Remaining risks: `source_reference` 단독 인덱스가 없어 정산 지출 데이터가 크게 증가하면 조회 비용이 커질 수 있다.
- Follow-up work: 운영 측정에서 병목이 확인되면 부분 인덱스 migration을 별도 이슈로 진행하고, 서버 필터가 필요해지면 `settledOnly`를 추가한다.
