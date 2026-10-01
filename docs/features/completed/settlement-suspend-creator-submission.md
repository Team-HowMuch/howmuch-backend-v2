# Feature Plan: 생성자만 제출한 품목 정산의 수동 중단 허용

- Status: Completed
- Last updated: 2026-10-01
- Related issues: [#456](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/456), [#454](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/454)
- Related ADR: N/A

## 1. Purpose

### Problem

품목 정산 생성 흐름은 요청 전에 생성자의 품목 몫을 제출한다. 현재 수동 중단 정책은 모든 제출을 동일하게 막기 때문에, 생성자만 제출하고 다른 참여자는 아직 제출하지 않은 새 정산도 중단할 수 없다.

### Why now

수동 중단 검증을 추가한 #455 이후 생성자가 품목 정산을 만든 직후 중단할 수 없는 실제 흐름이 확인됐다. 생성자의 자기 부담 몫과 다른 참여자의 확정 의사를 구분해야 한다.

### Success criteria

- [x] 생성자 제출만 있거나 제출이 없으면 `REQUESTED` 품목 정산을 수동 중단할 수 있다.
- [x] 생성자 외 참여자의 제출이 하나라도 있으면 수동 중단은 `SETTLEMENT_NOT_SUSPENDABLE`로 거부된다.
- [x] 전체 배분 확정에는 생성자 제출도 계속 포함된다.
- [x] 자동 중단, 재개 시 제출 초기화와 품목 선택 보존, 권한·상태 오류는 유지된다.

### Out of scope

- 프론트 생성 단계의 생성자 제출 API 호출 변경
- 상세 응답의 참여자 목록 또는 `submitted` 의미 변경
- 자동 중단 정책, `AWAITING_DEPOSIT` 중단, DB 스키마 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement` (`SettlementParticipant`, `SettlementItemSubmission`, `Settlement`)
- Reason a new domain is required: N/A
- Relationships to other domains: N/A

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 생성자 제출 | 생성자가 생성 중 본인 부담 몫을 확정한 기록 | submission whose participant is linked to `Settlement.creatorId()` |
| 외부 참여자 제출 | 생성자 이외의 정산 참여자가 품목 선택을 제출한 기록 | `SettlementItemSubmission` for a non-creator participant |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 다른 참여자가 제출하기 전 정산을 중단한다 | `SuspendSettlementUseCase.suspend(UUID)` |

### State and invariants

- State transitions: 수동 중단 `REQUESTED -> SUSPENDED`; 자동 배분 결과 전이는 그대로 유지한다.
- Rules that must always hold: 생성자 제출은 전체 제출 수·배분 확정에는 포함되지만 수동 중단 차단 기준에서는 제외한다. 외부 참여자 제출은 하나라도 있으면 수동 중단을 막는다.
- Invalid states: 수동 중단 허용 상태가 아닌 경우 기존 `INVALID_SETTLEMENT_STATUS_TRANSITION` 우선 반환.

## 3. Scenarios

### Normal flow

1. 생성자는 품목 정산을 만들고 본인 몫을 요청 전에 제출한다.
2. 다른 참여자는 아직 제출하지 않는다.
3. 생성자가 수동 중단을 요청한다.
4. Application이 제출 참여자를 확인해 생성자 제출만 있음을 판별하고 정산을 `SUSPENDED`로 저장한다.

### Failure flow

- Condition: 외부 참여자 제출이 존재한다.
- Returned error or state transition: `SETTLEMENT_NOT_SUSPENDABLE` (409); 상태와 제출·선택 데이터는 변경하지 않는다.

### Boundary conditions

- 제출 기록이 비어 있으면 기존처럼 수동 중단을 허용한다.
- 생성자 참여자 행을 찾지 못하면 fail-closed로 제출을 외부 제출로 취급한다.
- 생성자 제출은 품목 배분 완료 여부 계산과 Share 확정 조건에서 계속 전체 참여자 수에 포함한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: SuspendSettlementUseCase.suspend(UUID settlementId)
Command: N/A
Result: SettlementResult
Web Request DTO -> Command: N/A
Result -> Web Response DTO: existing SettlementWebResponse
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementParticipantPort` | 생성자 참여자 ID 구분 | `SettlementService` | `SettlementParticipantPersistenceAdapter` |
| `SettlementItemAllocationStorePort` | 제출 기록 조회 | `SettlementService` | `SettlementItemAllocationPersistenceAdapter` |

신규 Port는 만들지 않는다.

### Application Policy

- Decision owned by the Policy: 외부 참여자 제출이 있을 때만 수동 중단 거부.
- Facts loaded by the Service and passed to the Policy: 생성자 참여자 ID 집합과 제출 기록으로 계산한 `hasNonCreatorSubmissions`.
- Why the rule is not an Entity-local invariant: 참여자와 제출이라는 여러 저장 facts를 결합한다.
- Future inputs deliberately not implemented yet: N/A.

### Web API

```text
POST /api/v2/settlements/{settlementId}/suspend
Request: none
Response: existing SettlementWebResponse
Errors: SETTLEMENT_NOT_SUSPENDABLE (409) only when a non-creator participant submitted
Authentication: authenticated settlement creator
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required: No
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: Service identifies creator participant IDs from the existing participant Port; `SettlementSuspensionPolicy` validates the non-creator submission fact.
- Behavior owned by Domain: existing `REQUESTED -> SUSPENDED` transition.
- Decisions owned by Application Policy: whether submitted participants block manual suspension.
- Flow orchestrated by Application: lock owned settlement, validate state transition, load participants/submissions, validate policy, persist.

### Technology and patterns

- Constraint to solve: generator flow stores the creator's submission before the settlement request, so “any submission” is not equivalent to another participant committing.
- Selected approach: ignore only creator participant IDs when calculating the manual-suspension blocking fact.
- Reason for selection: preserves creator submission in the allocation state machine while allowing the creator to stop a settlement before any other participant submits.
- Alternatives considered: stop auto-submitting the creator in the frontend; rejected because this would change the creator allocation flow and completion counting.
- Trade-offs and limits: relies on the invariant that the settlement creator participant is linked to `creatorId`; missing creator identity fails closed.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | Error condition semantics only | `SettlementDocs` |
| Application | Load creator participant and distinguish external submissions | `SettlementService`, `SettlementSuspensionPolicy` |
| Domain | No change | `Settlement`, `SettlementParticipant` |
| Persistence | Existing participant/submission reads | Existing settlement adapters |
| Redis | None | N/A |
| Security | None | Existing creator-only endpoint |
| Configuration | None | N/A |
| Documentation | Feature plan and API description | This plan, `SettlementDocs` |

Existing behavior that must remain unchanged:

- External participant submission blocks manual suspension.
- Creator submission remains part of total submission counting and allocation finalization.
- Automatic suspension and resume behavior remain unchanged.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (N/A; no schema change)
- [x] UseCase and Application Command/Result (existing contract)
- [x] Required Output Ports (existing ports only)
- [x] Domain models and rules (existing rules)
- [x] Application Policies
- [x] Application Service
- [x] Output Adapters (existing reads)
- [x] Web Request/Response DTOs, Controllers, and Swagger (documentation only)
- [x] Infra configuration and Bean wiring (N/A)
- [x] Documentation and verification results

### Expected commit boundaries

```text
fix(settlement): 생성자만 제출한 품목 정산 중단 허용
docs(settlement): 생성자 제출 중단 정책과 검증 기록
```

## 8. Verification

### Commands

```bash
bash scripts/test.sh compileJava test --console=plain \
  --tests '*SettlementServiceTest' \
  --tests '*SettlementControllerTest' \
  --tests '*SettlementItemAllocationAtomicityIntegrationTest' \
  --tests '*SettlementItemAllocationServiceTest'
bash scripts/test.sh clean test --console=plain
git diff --check
git status --short --untracked-files=all
```

Integration tests use disposable PostgreSQL and Redis services from `compose.test.yml`; do not use local development or production data.

### Completion criteria

- [x] Creator-only and no-submission manual suspension succeed.
- [x] Any external participant submission blocks manual suspension without mutation.
- [x] Allocation completion still counts creator submission; automatic suspension and resume regressions pass.
- [x] Documentation matches the API behavior; focused and full tests pass.

### Results

| Item | Result | Evidence |
|---|---|---|
| Focused tests | Passed | `scripts/test.sh compileJava test` with 4 settlement test classes; Java and JS passed |
| Full Java and JavaScript tests | Passed | Post-review `scripts/test.sh clean test`; Java 1402/1402 and JS 35/35 |
| Independent code review | Passed | `.omo/evidence/456-settlement-creator-suspend-code-review.md`; findings addressed |
| Executor self-review | Passed | Reviewed status-before-submission order, fail-closed participant matching, unchanged allocation completion, and mutation boundaries |
| Diff and status checks | Passed | `git diff --check`; final status recorded before PR |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-01 | Issue #456 filed; branch created from latest `origin/develop`; independent review findings fixed; focused and full test suites passed | Commit functional and documentation changes, then open PR | Creator submission remains in total allocation counting |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-01 | Only non-creator submission blocks manual suspension | Creator submission is automatically made during creation to record the creator's own share | Keep creator submission in allocation completion counting |
