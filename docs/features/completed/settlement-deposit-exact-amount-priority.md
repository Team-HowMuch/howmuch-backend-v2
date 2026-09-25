# Feature Plan: 정산 입금 정확한 금액 후보 우선 매칭

- Status: Implementation complete
- Last updated: 2026-09-26
- Related issue: [#425](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/425)
- Related ADR: 없음

## 1. Purpose

### Problem

동일 입금자와 수취 계좌에 미완료 정산이 여러 건 있으면, 입금액과 정확히 같은 정산이 있어도 더 큰 남은 금액의 정산까지 후보가 되어 자동 대조가 실패한다.

### Why now

AWS 개발 환경에서 남은 금액 7,300원과 1,750원인 정산이 함께 있을 때 1,750원 입금이 두 후보로 해석되어 실제 완료 처리가 누락됐다.

### Success criteria

- [x] 정확한 금액 후보가 하나이면 더 큰 부분 입금 후보보다 우선한다.
- [x] 정확한 금액 후보가 여러 건이면 자동 매칭하지 않는다.
- [x] 정확한 금액 후보가 없으면 기존의 유일한 부분 입금 후보를 매칭한다.
- [x] 서로 다른 입금자 후보는 정확한 금액이 하나여도 자동 매칭하지 않는다.

### Out of scope

- API, DB 스키마, CODEF 요청 형식 변경
- 사용자에게 후보를 선택시키는 수동 매칭 기능

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement` 입금 대조
- Reason a new domain is required, if applicable: 없음
- Relationships to other domains: `account.Transaction`과 미납 `Share` 후보를 Application Policy가 대조한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 정확한 금액 후보 | 남은 금액과 입금액이 같은 Share | `remainingAmount == amount` |
| 부분 입금 후보 | 남은 금액이 입금액보다 큰 Share | `remainingAmount > amount` |
| 유일 후보 | 같은 우선순위에서 하나만 존재하는 후보 | `Optional<UUID>` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 수취 계좌의 입금을 올바른 정산에 대조한다. | `ReconcileSettlementDepositsUseCase` |

### State and invariants

- State transitions: 기존 `AWAITING_DEPOSIT -> COMPLETED` 전환을 재사용한다.
- Rules that must always hold: 수입, 양의 정수 금액, 이름, 시작 시각, 남은 금액, 유일 후보 검증을 유지한다.
- Invalid states: 정확한 금액 후보가 여러 건인데 임의로 하나를 선택하는 상태.

## 3. Scenarios

### Normal flow

1. 동일 입금자에게 남은 금액 7,300원과 1,750원인 미완료 Share가 있다.
2. 1,750원이 입금된다.
3. Policy가 1,750원 Share를 정확한 금액 후보로 우선한다.
4. 기존 Writer가 입금 연결과 완료 전환을 처리한다.

### Failure flow

- Condition: 정확한 금액 후보가 둘 이상이다.
- Returned error or state transition: 자동 연결하지 않고 기존 상태를 유지한다.

### Boundary conditions

- 정확한 후보가 없고 부분 입금 후보가 하나이면 기존처럼 매칭한다.
- 정확한 후보가 없고 부분 입금 후보가 여러 개이면 기존처럼 매칭하지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 ReconcileSettlementDepositsUseCase
Command: 변경 없음
Result: 기존 SettlementDepositSummaryResult
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 변경 없음
```

### Output Ports

변경 없음.

### Application Policy

- Decision owned by the Policy: 이름·시각·금액 조건을 통과한 후보 중 정확한 금액을 우선하고 유일성을 판정한다.
- Facts loaded by the Service and passed to the Policy: `Transaction`, `PayableShare` 목록
- Why the rule is not an Entity-local invariant: 외부 거래 한 건과 여러 정산 Share를 함께 비교한다.
- Future inputs deliberately not implemented yet: 사용자 수동 선택

### Web API

```text
POST /api/v2/settlements/{settlementId}/deposits/reconcile
Request/Response/Errors/Authentication: 변경 없음
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

- Rules and their locations: `SettlementDepositMatchingPolicy`
- Behavior owned by Domain: 기존 정산 완료 전환
- Decisions owned by Application Policy: 정확한 금액 우선과 후보 유일성
- Flow orchestrated by Application: 기존 `SettlementDepositWriter`

### Technology and patterns

- Constraint to solve: 부분 입금을 허용하면서 정확한 금액 후보를 놓치지 않아야 한다.
- Selected approach: 모든 기존 조건을 통과한 후보를 한 번 순회해 서로 다른 입금자 후보를 먼저 거부하고, 같은 입금자의 정확한 후보와 전체 후보의 개수 및 ID를 각각 기록한다.
- Reason for selection: 후보 전체를 별도 목록으로 만들지 않고 정확한 금액 우선순위를 표현한다.
- Alternatives considered: 부분 입금을 제거하면 기존 동작이 깨지므로 제외한다.
- Trade-offs and limits: 정확한 금액 후보가 여러 건이면 사용자 확인 기능이 없어 계속 자동 매칭하지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음 | `SettlementDepositController` |
| Application | 후보 우선순위 변경 | `SettlementDepositMatchingPolicy` |
| Domain | 없음 | 기존 `Settlement` 전환 재사용 |
| Persistence | 없음 | 기존 저장 흐름 재사용 |
| Redis | 없음 | N/A |
| Security | 없음 | 생성자 권한 유지 |
| Configuration | 없음 | N/A |
| Documentation | Feature Plan | 이 문서 |

Existing behavior that must remain unchanged:

- 정확한 후보가 없을 때의 유일한 부분 입금 매칭
- 복수 후보, 이름 불일치, 잘못된 금액과 시각 거부

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`)
- [x] UseCase and Application Command/Result (`N/A`)
- [x] Required Output Ports (`N/A`)
- [x] Domain models and rules (`N/A`)
- [x] Application Policies
- [x] Application Service (`N/A`)
- [x] Output Adapters (`N/A`)
- [x] Web Request/Response DTOs, Controllers, and Swagger (`N/A`)
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] Documentation and verification results

### Expected commit boundaries

```text
fix(settlement): 정확한 입금 금액 후보를 우선 매칭한다
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

- [x] 정확한 금액 우선, 정확한 금액 복수 거부, 기존 부분 입금 테스트가 통과한다.
- [x] Java 컴파일이 통과한다.
- [x] API와 DB 계약에 변경이 없음을 확인한다.
- [x] 실행하지 못한 검증과 이유를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Policy unit tests | Passed | `./gradlew test --tests '*SettlementDepositMatchingPolicyTest'` |
| Java compilation | Passed | `./gradlew compileJava` |
| AWS 개발 환경 재대조 | Not run | 코드가 아직 배포되지 않아 PR 범위에서는 실행하지 않음 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-26 | AWS 개발 DB에서 동일 이름의 7,300원·1,750원 후보 충돌 확인, 이슈 #425와 설계 작성 | Policy와 회귀 테스트 구현 | 정확한 금액 후보가 여러 건이면 자동 판정 불가 |
| 2026-09-26 | Policy 변경과 집중 테스트·컴파일 완료 | PR 리뷰 및 배포 후 AWS 재대조 | 배포 전 운영 동작은 미검증 |
| 2026-09-26 | PR 리뷰에 따라 서로 다른 입금자 후보의 금액 우선 매칭을 차단하고 회귀 테스트 추가 | 재검증 및 리뷰 반영 커밋 | 거래 설명에 여러 이름이 있으면 자동 판정 불가 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-26 | 정확한 금액 후보가 하나일 때만 부분 입금 후보보다 우선한다. | 실제 입금액이 정산 잔액과 같으면 가장 강한 판별 조건이며, 복수 정확 후보의 오매칭은 막아야 한다. | 부분 입금을 전부 금지하지 않고 기존 호환성을 유지한다. |
| 2026-09-26 | 정확한 금액 우선순위는 정규화한 입금자명이 같은 후보 사이에만 적용한다. | 금액만으로 거래 설명에 포함된 서로 다른 사람 중 한 명을 추정하면 오매칭할 수 있다. | 서로 다른 이름이 함께 일치하면 기존처럼 자동 매칭하지 않는다. |

## 11. Completion record

- Completed on: 2026-09-26
- Final behavior: 서로 다른 입금자 후보는 먼저 거부하며, 같은 입금자의 정확한 금액 후보가 하나이면 더 큰 부분 입금 후보보다 우선하고 정확한 후보가 없을 때만 기존 부분 입금 후보 유일성을 적용한다.
- Unverified items: PR 배포 후 AWS 개발 환경의 실제 1,750원 거래 재대조
- Remaining risks: 동일 입금자·동일 금액 정산이 여러 건이면 사용자 확인 없이는 자동 매칭할 수 없다.
- Follow-up work: 필요 시 수동 입금 연결 기능을 별도 설계한다.
