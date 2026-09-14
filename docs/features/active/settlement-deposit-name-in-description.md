# Feature Plan: 정산 입금자명 포함 거래 설명 매칭 보완

- Status: Implementation complete
- Last updated: 2026-09-14
- Related issue: [#385 정산 입금자명 포함 거래 설명 자동 매칭 보완](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/385)
- Related ADR: 없음

## 1. Purpose

### Problem

CODEF 거래 설명에는 은행명·채널명·계좌 식별자 등이 입금자명과 함께 들어올 수 있다. 현재 입금 대조 Policy는 설명 전체가 참여자 이름과 같거나 마지막 `입금`만 붙은 경우만 허용해, 정상적인 포함 표기에서도 `settlement_deposits`를 만들지 못한다.

### Why now

실제 수금 계좌에 금액과 입금자명이 모두 존재해도 정산이 `AWAITING_DEPOSIT`에 머물러 베타 테스트에서 완료 표시가 누락된다.

### Success criteria

- [ ] 정규화된 참여자 전체 이름이 거래 설명의 독립된 이름 경계 안에 포함되면 매칭한다.
- [ ] 금액·수입 거래·정산 시작 시각·수금 계좌·유일 후보 조건은 유지한다.
- [ ] 부분 이름, 동명이인·복수 후보는 자동 연결하지 않는다.

### Out of scope

- 은행별 로그인 방식 또는 CODEF API 변경
- DB/Flyway 스키마 변경
- 프론트엔드 화면 변경
- 동명이인 자동 추정 및 사용자 확인 없는 강제 연결

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement` 입금 대조
- Reason a new domain is required, if applicable: 없음
- Relationships to other domains: `account.Transaction`의 CODEF 거래 설명을 입금 대조 사실로 사용한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 입금 대조 | 수입 거래를 미납 Share와 연결하는 작업 | `SettlementDepositMatchingPolicy` |
| 입금자명 포함 | 이름 앞뒤에 다른 설명이 있어도 이름 전체가 문자·숫자 경계로 존재하는 상태 | `containsWholeName` |
| 유일 후보 | 하나의 거래가 정확히 하나의 Share에만 해석되는 상태 | `matches.size() == 1` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 최신 수입 거래를 정산에 대조한다. | `ReconcileSettlementDepositsUseCase` |

### State and invariants

- State transitions: 모든 미납 대상 Share가 매칭되면 `AWAITING_DEPOSIT`에서 `COMPLETED`로 전환한다.
- Rules that must always hold: 수입·양의 정수 원 단위·정산 시작 이후·남은 금액 이하·유일 후보만 연결한다.
- Invalid states: 부분 이름 또는 복수 Share 후보를 임의로 연결하는 상태.

## 3. Scenarios

### Normal flow

1. 정산이 `AWAITING_DEPOSIT`이고 수입 거래가 조회된다.
2. 거래 설명에 참여자 전체 이름이 은행명·채널명 등과 함께 독립된 경계로 포함된다.
3. Policy가 금액·시각·후보 조건을 확인한다.
4. 유일한 Share에 `SettlementDeposit`을 저장하고 모든 Share가 충족되면 정산을 완료한다.

### Failure flow

- Condition: 참여자 이름이 다른 이름의 일부이거나 동일 거래가 여러 Share와 일치한다.
- Returned error or state transition: 자동 연결하지 않고 기존 `AWAITING_DEPOSIT` 상태를 유지한다.

### Boundary conditions

- 이름 내부 공백과 Unicode 공백은 기존 정규화를 적용한다.
- 이름 뒤에 숫자·문자가 바로 이어지면 독립된 이름으로 보지 않는다.
- 거래 설명이 없거나 양의 정수 금액이 아니면 기존처럼 무시한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ReconcileSettlementDepositsUseCase.reconcile(UUID settlementId)
Command: 없음
Result: SettlementDepositSummaryResult
Web Request DTO -> Command: 없음
Result -> Web Response DTO: SettlementDepositSummaryWebResponse.from(result)
```

### Output Ports

변경 없음. 기존 `BankTransactionPort`, `LoadAccountTransactionPort`, `SettlementDepositStorePort`를 재사용한다.

### Application Policy

- Decision owned by the Policy: 거래 설명과 참여자 이름의 안전한 포함 매칭 및 유일 후보 판정
- Facts loaded by the Service and passed to the Policy: `Transaction`, 미납 Share 후보 목록
- Why the rule is not an Entity-local invariant: 여러 Share와 외부 거래 설명을 함께 해석하는 Application 판단이다.
- Future inputs deliberately not implemented yet: 은행별 전용 입금자 필드 우선순위, 사용자 수동 확인 큐

### Web API

```text
POST /api/v2/settlements/{settlementId}/deposits/reconcile
Request: 없음
Response: 입금 대조 요약 및 정산 상태
Errors: 기존 정산·CODEF 오류 계약 유지
Authentication: 정산 생성자 bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: `SettlementDepositMatchingPolicy`의 이름 정규화·경계 포함 판정
- Behavior owned by Domain: 기존 `Settlement.complete` 상태 전이
- Decisions owned by Application Policy: 외부 거래와 Share 후보의 매칭
- Flow orchestrated by Application: 기존 `SettlementDepositWriter`가 매칭 결과 저장·완료 전환

### Technology and patterns

- Constraint to solve: 은행 부가 설명이 붙은 정상 입금을 기존 정확 일치 규칙이 누락함
- Selected approach: 정규화한 전체 이름을 문자·숫자 경계가 있는 설명 토큰으로 허용
- Reason for selection: 실제 이름 포함 표기를 허용하면서 더 긴 이름의 일부만 일치하는 오탐은 차단한다.
- Alternatives considered: 전체 문자열 `contains`는 부분 이름 오탐을 허용하므로 선택하지 않는다.
- Trade-offs and limits: 은행이 이름과 다른 문자·숫자를 붙여 반환하는 표기는 자동 매칭하지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 계약 변경 없음 | `SettlementDepositController` |
| Application | 입금자명 포함 매칭 규칙 변경 | `SettlementDepositMatchingPolicy` |
| Domain | 상태 전이 재사용 | `Settlement` |
| Persistence | 변경 없음 | `settlement_deposits` |
| Redis | 변경 없음 | N/A |
| Security | 정산 생성자 인증 유지 | `SettlementDepositService` |
| Configuration | 변경 없음 | N/A |
| Documentation | Swagger·Feature Plan 갱신 | `SettlementDepositDocs` |

Existing behavior that must remain unchanged:

- 금액·수입 유형·시각·수금 계좌·유일 후보 검증
- 대조 결과가 없을 때 `AWAITING_DEPOSIT` 유지
- 모든 Share가 충족될 때만 `COMPLETED` 전환

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — Policy-only change)
- [x] UseCase and Application Command/Result (`N/A` — existing contract reuse)
- [x] Required Output Ports (`N/A` — existing ports reuse)
- [x] Domain models and rules (`N/A` — existing transition reuse)
- [x] Application Policies
- [ ] Application Service (`N/A` — existing orchestration reuse)
- [x] Output Adapters (`N/A` — no external mapping change)
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A` — existing component)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 입금자명 포함 매칭 Feature Plan 추가
fix(settlement): 거래 설명 내 입금자명 포함 매칭 허용
test(settlement): 입금자명 포함 매칭 회귀 검증 추가
```

## 8. Verification

### Commands

```bash
./gradlew test --tests '*SettlementDepositMatchingPolicyTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [x] 포함된 입금자명이 유일 Share에 연결된다.
- [x] 부분 이름·복수 후보·금액·시각 경계 테스트가 통과한다.
- [x] Swagger 설명이 실제 포함 매칭 계약과 일치한다.
- [x] 로컬 단위 테스트 및 컴파일 결과를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Policy unit tests | Passed | `./gradlew test --tests '*SettlementDepositMatchingPolicyTest'` |
| Java compilation | Passed | `./gradlew compileJava` |
| Runtime CODEF/DB | Not run | 실제 금융기관 호출과 공유 운영 DB 변경은 범위 밖 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 이슈 #385 생성, 최신 `develop` 확인 | 완료 및 PR 생성 | 은행별 특수 구분자 표기는 추가 fixture가 필요할 수 있음 |
| 2026-09-14 | Policy에 거래 설명 내 전체 입금자명 포함 매칭, Swagger와 회귀 테스트 반영 | PR 리뷰 대기 | 실제 CODEF/운영 DB 대조는 미실행 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | 문자·숫자 경계가 있는 전체 이름만 포함 매칭 | 은행 부가 설명을 허용하면서 부분 이름 오탐을 차단 | 단순 substring보다 보수적이며 숫자·문자 접합 표기는 보류 |

## 11. Completion record

- Completed on: 2026-09-14
- Final behavior: 정규화된 입금자명 전체가 거래 설명에 문자·숫자 경계로 포함되면, 기존 금액·시각·유일 후보 조건을 통과한 경우 자동 대조한다.
- Unverified items: 실제 CODEF 요청 및 운영 DB 대조
- Remaining risks: 은행별 특수 구분자 표기는 추가 fixture가 필요할 수 있음
- Follow-up work: 필요 시 은행별 입금자 필드 우선순위 정책
