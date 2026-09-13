# Feature Plan: 품목 정산 선택 대기 중 미수금 계산

- Status: In progress
- Last updated: 2026-09-14
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/373
- Related ADR: N/A

## 1. Purpose

### Problem

품목 정산이 참여자의 품목 선택을 기다리는 `REQUESTED` 상태일 때 아직 `SettlementShare`가
없는 참여자가 존재한다. 정산 상세는 생성된 Share 금액만 합산해 `paymentSummary`를 만들기
때문에 선택 전에는 `expectedAmount`와 `unpaidAmount`가 모두 0원이 된다. 생성자는 실제로
받아야 할 금액이 남아 있는데도 상세 화면과 AI 요약에서 0원으로 안내받는다.

### Why now

앱은 정산 상세의 `paymentSummary.unpaidAmount`를 "아직 받을 금액"으로 사용한다. 품목 선택이
끝나기 전이라는 정상 구간에서 금액이 축소되면 생성자가 정산 진행 상황을 잘못 판단한다.

### Success criteria

- [ ] `ITEM_ALLOCATION` 정산이 `REQUESTED`이면 Share가 없어도 `expectedAmount`와
  `unpaidAmount`가 정산 총액을 기준으로 계산된다.
- [ ] 일부 Share의 입금이 확인된 경우 `unpaidAmount`는 정산 총액에서 확인 입금액을 뺀 값이다.
- [ ] Share가 없는 참여자 행은 금액을 추측하지 않고 `expectedAmount = null`,
  `paymentStatus = NOT_READY`를 유지한다.
- [ ] 더치페이와 품목 선택 종료 후의 요약은 기존처럼 납부 대상 Share 합계를 기준으로 계산한다.

### Out of scope

- 참여자 품목 선택·Share 생성·입금 대사 규칙 변경
- 앱의 "선택 안 함" 문구 표시 변경
- 정산 목록과 AI 총무의 별도 계약 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 없음

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 선택 대기 | 품목 정산 참여자의 Share가 아직 모두 확정되지 않은 상태 | `ITEM_ALLOCATION` + `REQUESTED` |
| 확인 입금액 | Share에 연결된 실제 입금 거래 금액 합계 | `confirmedAmount` |
| 미수금 | 선택 대기 중인 금액을 포함해 생성자가 아직 받지 못한 금액 | `unpaidAmount` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자·연결 참여자 | 현재 정산의 전체 납부 진행 상황을 확인한다 | `GetSettlementUseCase` |

### State and invariants

- State transitions: 없음. 조회 계산만 변경한다.
- Rules that must always hold:
  - 선택 대기 중인 품목 정산의 요약은 아직 생성되지 않은 Share 금액을 누락하지 않는다.
  - 참여자별 금액이 정해지지 않았으면 nullable 계약을 유지한다.
  - `confirmedAmount`는 실제 입금 대조 결과만 반영한다.
- Invalid states: `unpaidAmount < 0`, `progressPercent > 100`

## 3. Scenarios

### Normal flow

1. 생성자가 15,800원 품목 정산을 요청한다.
2. 참여자가 품목을 고르기 전 상세를 조회한다.
3. 서버는 `expectedAmount = 15,800`, `confirmedAmount = 0`,
   `unpaidAmount = 15,800`을 반환한다.
4. 아직 Share가 없는 참여자 행은 `NOT_READY`와 nullable 금액을 반환한다.

### Failure flow

- Condition: 없음. 기존 조회 권한·정산 없음 오류를 그대로 사용한다.
- Returned error or state transition: 변경 없음

### Boundary conditions

- 일부 참여자의 Share와 확인 입금이 이미 있어도 `REQUESTED`이면 정산 총액을 분모로 삼는다.
- 확인 입금액은 Share 금액까지만 인정하고 진행률은 기존처럼 100을 넘지 않는다.
- `AWAITING_DEPOSIT`, `SUSPENDED`, `COMPLETED`에서는 확정된 납부 대상 Share 합계를 사용한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetSettlementUseCase.get(UUID settlementId)
Command: N/A
Result: SettlementDetailResult (필드 변경 없음)
Web Request DTO -> Command: N/A
Result -> Web Response DTO: SettlementDetailWebResponse.from(result) (변경 없음)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 Port 재사용 | 정산·참여자·Share·확인 입금 조회 | `SettlementDetailService` | 기존 Adapter |

새 Output Port와 외부 대화는 추가하지 않는다.

### Application Policy

- Decision owned by the Policy: N/A
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 상세 응답을 조합하는 조회 계산이므로
  `SettlementDetailService`가 소유한다.
- Future inputs deliberately not implemented yet: N/A

### Web API

```text
GET /api/v2/settlements/{settlementId}
Request: 변경 없음
Response: 필드 변경 없이 paymentSummary 계산만 보정
Errors: 변경 없음
Authentication: required
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): **No**
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: `SettlementDetailService.summary`
- Behavior owned by Domain: 변경 없음
- Decisions owned by Application Policy: 변경 없음
- Flow orchestrated by Application: 품목 정산의 선택 대기 여부를 판단하고 요약 분모를 선택한다.

### Technology and patterns

- Constraint to solve: 확정 전에는 참여자별 Share 합계만으로 전체 미수금을 알 수 없다.
- Selected approach: `ITEM_ALLOCATION`이면서 `REQUESTED`인 동안 `expectedAmount`를
  `Settlement.totalAmount`로 사용하고, 다른 상태·배분 유형은 기존 Share 합계를 사용한다.
- Reason for selection: 이슈의 결제 총액 기준 요구를 충족하면서 참여자별 미확정 금액을
  임의 배분하지 않고 기존 응답 계약을 유지한다.
- Alternatives considered:
  - 미확정 금액을 참여자별로 나눔: 실제 선택 전에는 알 수 없어 잘못된 금액을 만든다.
  - 모든 상태에서 정산 총액 사용: 생성자 본인 몫까지 받을 금액에 포함되어 기존 계산을 깨뜨린다.
- Trade-offs and limits: 선택 대기 중 요약은 전체 미수금을 보여주지만, 미확정 금액이 어느
  참여자의 몫인지는 참여자 행으로 표현하지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 필드 구조 변경 없이 선택 대기 중 요약 값 보정 | `SettlementDetailResult.PaymentSummary` |
| Application | 상태·배분 유형에 따른 요약 분모 선택 | `SettlementDetailService` |
| Domain | 없음 | 기존 `Settlement` 값 사용 |
| Persistence | 없음 | 기존 조회 Port 재사용 |
| Redis | 없음 | N/A |
| Security | 없음 | 기존 상세 권한 유지 |
| Configuration | 없음 | N/A |
| Documentation | 수정 근거와 검증 결과 기록 | 이 문서 |

Existing behavior that must remain unchanged:

- 참여자별 `NOT_READY` 계약과 역할별 참여자 목록 가시성
- 더치페이와 품목 선택 종료 후 Share·입금 기준 요약
- 독촉 쿨다운, 수금 계좌, 결제 정보 조회

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result (`N/A` — 계약 변경 없음)
- [x] Required Output Ports (`N/A` — 기존 Port 재사용)
- [x] Domain models and rules (`N/A` — 변경 없음)
- [x] Application Policies (`N/A`)
- [ ] Application Service
- [x] Output Adapters (`N/A`)
- [x] Web Request/Response DTOs, Controllers, and Swagger (`N/A` — 구조 변경 없음)
- [x] Infra configuration and Bean wiring (`N/A`)
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 품목 정산 미수금 계산 수정 계획 작성
fix(settlement): 품목 선택 대기 중 미수금을 총액 기준으로 계산
docs(settlement): 품목 정산 미수금 수정 결과 기록
```

## 8. Verification

### Commands

```bash
./gradlew test --tests 'com.amateur.howmuchbackend.application.settlement.service.SettlementDetailServiceTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [ ] Share가 전혀 없는 `REQUESTED` 품목 정산의 총 미수금을 검증한다.
- [ ] 일부 Share·입금만 있는 `REQUESTED` 품목 정산의 총 미수금을 검증한다.
- [ ] 참여자별 미확정 금액과 기존 더치페이 계산이 유지되는지 검증한다.
- [ ] 문서와 실제 계산이 일치하는지 확인한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 변경 전 집중 테스트 | Passed | 기존 `SettlementDetailServiceTest` |
| 변경 후 집중 테스트 | Pending |  |
| 컴파일 | Pending |  |
| 저장소 검사 | Pending |  |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 이슈와 상세 계산 흐름 분석, Flyway 불필요 확인 | 회귀 테스트와 계산 수정 | 참여자별 미확정 금액은 계속 nullable이어야 한다. |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | 선택 대기 중인 품목 정산만 총액을 요약 분모로 사용 | Share 합계가 아직 전체 금액을 표현할 수 없다 | 다른 상태·배분 유형은 기존 Share 합계를 유지 |
