# Feature Plan: 계좌 거래 정산 연결 확장 (입금·납부 거래)

- Status: In progress
- Last updated: 2026-09-12
- Related issue: [#360](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/360)
- Related ADR: N/A
- Prerequisite: [계좌 거래내역 연결 정산 정보](../completed/account-transaction-settlements.md), [정산 입금 대조 및 종료](../completed/settlement-deposit-reconciliation.md), 납부 거래 스키마 [#361](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/361)

## 1. Purpose

### Problem

계좌 거래내역(`GET /api/v2/accounts/{accountId}/transactions`)의 `settlements`는 결제 내역으로 가져온 `PAYMENT_HISTORY` 지출만 연결한다. 실제 응답에서 거래 15건 중 이체 14건이 전부 `settlements: []`였다. 이 목록의 거래는 모두 정산과 얽혀 있다. 수입은 받을 정산에서 참여자가 보낸 입금이고, 지출은 낼 정산에서 내가 보낸 납부다. 그런데 앱은 연결이 비어 있으면 누를 수 없는 행으로 그리므로, 사용자는 대부분의 거래에서 정산 상세로 이동하지 못한다.

기존 조회에는 두 가지 제약이 있었다.

1. `expense.source = 'PAYMENT_HISTORY'` — 이체 거래는 이 경로로 `settlement_expenses`에 들어오지 않는다.
2. `settlement.creator_id = :creatorId` — 내가 만든 정산만 걸린다. 낼 정산은 남이 만든 정산이라 어떤 거래도 연결될 수 없었다.

### Why now

받을 정산 쪽 데이터는 이미 존재한다. 입금 대조가 `settlement_deposits.account_transaction_id`로 수금 계좌의 거래 PK를 유니크하게 기록한다. 낼 정산 쪽은 #361이 같은 구조의 `settlement_payments`를 추가했다. 조회만 확장하면 마이그레이션 없이 받을 정산 행이 살아나고, 납부 행이 채워지는 즉시 낼 정산 행도 같은 응답으로 나온다.

### Success criteria

- [x] 수금 계좌의 입금 거래가 `settlement_deposits`로 대조된 정산을 `settlements[]`에 반환한다.
- [x] 납부자 계좌의 송금 거래가 `settlement_payments`로 연결된 정산을 `settlements[]`에 반환한다.
- [x] 결제 내역으로 가져온 지출 연결은 기존과 같이 유지된다.
- [x] `DRAFT` 정산은 어떤 경로로 연결돼 있어도 반환하지 않는다.
- [x] 응답 스펙(`settlementId`, `title`, `status`)과 거래별 정렬 규칙은 바뀌지 않는다.
- [x] 다른 계좌, 다른 사용자의 연결은 반환하지 않는다.

### Out of scope

- `settlement_payments` 행을 채우는 매칭 구현 (#361 후속 작업)
- 거래 하나에 정산이 여러 개일 때의 대표 선택 (앱 #114에서 처리)
- 정산 상세 API나 거래내역 응답 필드 추가

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Account`, `Transaction`, `Settlement`, `SettlementExpense`, `SettlementDeposit`
- Reason a new domain is required, if applicable: N/A. 저장된 연결 관계를 조회에서 합치는 읽기 전용 확장이다.
- Relationships to other domains: account는 소유권과 거래 목록을, settlement는 세 연결 테이블(`settlement_expenses`, `settlement_deposits`, `settlement_payments`)을 통해 거래와 정산의 관계를 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 지출 연결 | 결제 내역에서 가져온 지출의 원본 거래 | `settlement_expenses.source_account_id`, `source_reference` |
| 입금 연결 | 입금 대조가 수금 계좌 거래를 Share에 붙인 기록 | `settlement_deposits.source_account_id`, `account_transaction_id` |
| 납부 연결 | 참여자의 송금 거래를 Share에 붙인 기록 | `settlement_payments.payer_account_id`, `account_transaction_id` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 자신의 계좌 거래에서 관련 정산으로 이동한다. | 기존 `GetAccountTransactionsUseCase` |

### State and invariants

- State transitions: 없음. 읽기 전용이다.
- Rules that must always hold:
  - 활성 계좌 소유권을 먼저 검증한다 (`ownedAccount`).
  - 세 연결 경로 모두 조회 대상 계좌(`:accountId`)로 범위를 한정한다. 지출은 `source_account_id`, 입금은 `source_account_id`, 납부는 `payer_account_id`다.
  - `DRAFT` 정산은 제외한다.
- Invalid states: 없음.

## 3. Scenarios

### Normal flow

1. 사용자가 계좌 상세에서 월 거래를 조회한다.
2. 서버가 월 거래의 `externalTransactionKey` 목록으로 세 연결 테이블을 한 번에 조회한다.
3. 거래별로 연결 정산을 묶어 `settlements[]`로 반환한다.
4. 앱은 항목이 있는 행을 눌러 정산 상세로 이동한다.

### Failure flow

- Condition: 계좌가 사용자 소유가 아니거나 비활성 → 기존과 같이 404.
- Condition: 연결이 없음 → `settlements: []`.

### Boundary conditions

- 같은 거래가 여러 정산에 연결되면 모두 반환한다. `UNION`으로 (거래 Key, 정산) 쌍은 한 번만 나온다.
- 거래 Key 목록이 비어 있으면 조회하지 않고 빈 목록을 반환한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 GetAccountTransactionsUseCase (변경 없음)
Result: 기존 AccountTransactionResult.SettlementSummary (변경 없음)
Web Response: 기존 AccountTransactionWebResponse.SettlementSummaryWebResponse (변경 없음)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadAccountTransactionPort.findLinkedSettlements(accountId, externalTransactionKeys)` | 계좌 거래 Key에 연결된 정산 요약 조회 | `AccountQueryService` | `AccountTransactionPersistenceAdapter` |

`creatorId` 매개변수를 제거했다. 세 연결 경로가 모두 계좌 ID로 범위를 한정하고, 계좌 소유권은 Service가 먼저 검증하므로 사용자 ID 조건이 더 이상 필요 없다.

### Application Policy

- Decision owned by the Policy: N/A. 연결 조건은 조회 쿼리에서 적용한다.

### Web API

```text
GET /api/v2/accounts/{accountId}/transactions
Response: 변경 없음. settlements[]가 입금·납부 연결까지 포함한다.
Errors: 변경 없음
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: `AccountTransactionJpaRepository.findLinkedSettlements` 네이티브 쿼리를 세 연결 테이블의 `UNION`으로 확장하고 `settlement.status <> 'DRAFT'` 조건을 추가
- Flyway migration required: No (`settlement_payments`는 #361 `V34`로 이미 존재)
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 연결 경로와 `DRAFT` 제외는 `AccountTransactionJpaRepository` 쿼리, 거래별 묶음은 `AccountQueryService`.
- Behavior owned by Domain: 없음.
- Decisions owned by Application Policy: 없음.
- Flow orchestrated by Application: 기존 흐름 유지.

### Technology and patterns

- Constraint to solve: 입금·납부는 거래 PK로, 지출은 거래 Key로 연결돼 있어 키가 다르다.
- Selected approach: 입금·납부 분기에서 `account_transactions`를 조인해 `external_transaction_key`로 맞춘 뒤 세 분기를 `UNION`한다. Service와 Projection은 거래 Key 기준을 유지한다.
- Reason for selection: Port 계약과 Service 묶음 로직을 바꾸지 않는다. 조인 조건에 `(id, account_id)`를 함께 걸어 FK 구조와 같은 범위를 유지한다.
- Alternatives considered: 거래 PK 목록을 별도로 넘겨 두 번 조회 — 호출부가 두 키를 관리해야 해서 제외.
- Trade-offs and limits: `settlement_payments`는 #361 시점에 행이 비어 있어 납부 분기는 데이터가 채워질 때까지 빈 결과다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | Swagger 설명만 갱신 | `AccountDocs.getAccountTransactions` |
| Application | Port 시그니처에서 `creatorId` 제거 | `LoadAccountTransactionPort`, `AccountQueryService` |
| Domain | 없음 | |
| Persistence | 네이티브 쿼리 확장 | `AccountTransactionJpaRepository`, `AccountTransactionPersistenceAdapter` |
| Redis | 없음 | |
| Security | 계좌 소유권 검증은 유지. 사용자 ID 조건 제거는 계좌 범위 조건으로 대체 | `AccountQueryService.ownedAccount` |
| Configuration | 없음 | |
| Documentation | 본 문서 | |

Existing behavior that must remain unchanged:

- 결제 내역 지출 연결, 거래 정렬, 월 동기화, 계좌 소유권 검증

## 7. Implementation plan

- [x] Migration-only pull request to `develop` — N/A (#361 `V34`로 스키마 존재)
- [x] UseCase and Application Command/Result — N/A (변경 없음)
- [x] Required Output Ports — `findLinkedSettlements` 시그니처 변경
- [x] Domain models and rules — N/A
- [x] Application Policies — N/A
- [x] Application Service — 호출부 갱신
- [x] Output Adapters — 쿼리·Adapter 갱신
- [x] Web Request/Response DTOs, Controllers, and Swagger — Swagger 설명 갱신
- [x] Infra configuration and Bean wiring — N/A
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(account): 계좌 거래 정산 연결 확장 Feature Plan
feat(account): 계좌 거래 연결 정산에 입금·납부 거래 포함
test(account): 입금·납부 연결과 작성 중 정산 제외 검증
```

## 8. Verification

### Commands

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests 'com.amateur.howmuchbackend.application.account.service.AccountQueryServiceTest'
./gradlew test --tests 'com.amateur.howmuchbackend.adapter.out.persistence.account.AccountTransactionPersistenceIntegrationTest'
git diff --check
```

통합 테스트는 `compose.test.yml`(PostgreSQL 18, Redis 8)이 필요하다.

### Completion criteria

- [ ] Verified the user outcome defined under Purpose.
- [ ] Verified normal, failure, and boundary flows.
- [ ] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| 컴파일 | 통과 | `./gradlew compileJava compileTestJava` exit 0 (2026-09-12) |
| 단위 테스트 | 통과 | `AccountQueryServiceTest` 9건 성공, 실패 0 |
| 통합 테스트 | 미실행 | 작업 환경에 Docker와 PostgreSQL이 없어 `compose.test.yml`을 띄울 수 없음. `AccountTransactionPersistenceIntegrationTest`는 CI 또는 DB가 있는 환경에서 실행해야 함 |
| 공백 검사 | 통과 | `git diff --check` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-12 | #360 분석, 쿼리·Port·Service·Swagger·테스트 작성, 컴파일·단위 테스트 통과 | DB 환경에서 통합 테스트 실행, PR | 로컬에 Docker/PostgreSQL이 없어 통합 테스트 미실행. CI는 `-x test`라 자동으로도 돌지 않음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-12 | `creator_id` 조건 제거 | 낼 정산은 남이 만든 정산이라 이 조건이 모든 납부 연결을 막는다. 세 분기가 계좌 ID로 범위를 한정하고 소유권은 Service가 검증한다. | "생성자 또는 참여자" 조건으로 넓히는 안은 조인이 늘고 계좌 범위 조건과 중복돼 제외 |
| 2026-09-12 | `DRAFT` 제외를 서버에서 적용 | 작성 중 정산은 참여자·금액이 없어 상세가 빈 화면이다. #360 요청 사항. | 앱도 같은 필터를 갖고 있어 이중 방어가 된다 |
| 2026-09-12 | 납부 분기를 지금 포함 | 스키마가 이미 있고 행이 채워지면 별도 배포 없이 동작한다. | 행이 채워지기 전까지는 빈 결과 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work: `settlement_payments` 행 채우기 (#361 후속)
