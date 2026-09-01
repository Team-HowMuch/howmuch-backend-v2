# Feature Plan: 품목을 고르면 바로 송금한다

- Status: In progress
- Last updated: 2026-09-02
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

품목 정산 참여자는 **자기 몫을 다 고르고 제출해도 송금할 수 없다.** 마지막 참여자가 제출할 때까지 기다려야 한다.

```java
if (submissions.size() < participants.size()) {
    return;   // Share 없이 끝난다
}
```

Share가 없으면 낼 금액도 수금 계좌도 조회되지 않는다. 계좌 스냅샷은 정산 요청 때 이미 만들어져 있는데도 그렇다.

더치페이는 요청 시점에 `awaitDeposit`으로 가고 Share가 함께 저장되어 바로 송금할 수 있다. 품목 정산만 뒤로 밀린다.

기다려야 하는 이유는 **나머지 배분** 하나뿐이다. 5,000원짜리를 셋이 1/3씩 나누면 1666×3=4998이고, 남는 2원을 먼저 고른 순서대로 얹어 1667/1667/1666이 된다. 이 배분은 품목이 다 차야 일어나므로 그 전까지 각자 금액이 잠정치다.

### Why now

"영수증에서 내 몫 고르고 바로 보낸다"가 이 기능의 핵심 흐름인데 지금은 끊긴다. 2원을 정확히 나누려고 흐름 전체를 막고 있다.

### Success criteria

- [ ] 참여자가 제출하면 그 즉시 자기 낼 금액과 수금 계좌를 조회할 수 있다.
- [ ] 다른 참여자를 기다리지 않는다.
- [ ] 선택 중 보던 금액과 제출 후 금액이 같다.
- [ ] 품목이 다 안 찬 채로 전원이 제출하면 여전히 SUSPENDED로 간다.

### Out of scope

- 더치페이 배분.
- 입금 대사(입금 확인) 규칙.
- 참여자별 부분 입금 처리.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementShare`, `SettlementItemSelection`, `SettlementItemSubmission`
- Reason a new domain is required, if applicable: 없음. 확정 단위를 정산 전체에서 참여자별로 바꾼다.
- Relationships to other domains: 입금 대사가 `SettlementShare`를 기준으로 동작한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 내 몫 확정 | 참여자 한 명의 낼 금액이 정해지는 것 | `SettlementShare` |
| 전체 배분 검증 | 모든 품목이 빠짐없이 배분됐는지 | `evaluateAllocation` |

핵심 변경: **확정 단위가 "정산 전체"에서 "참여자 한 명"으로 바뀐다.**

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 참여자 | 내 몫을 제출하고 바로 보낸다 | 기존 제출 UseCase |

새 UseCase·엔드포인트 없음.

### State and invariants

- State transitions: 상태 전이는 그대로다. `REQUESTED`에서 각자 제출하고, 전원 제출 시
  `AWAITING_DEPOSIT` 또는 미배분이면 `SUSPENDED`. 송금 가능 여부는 상태가 아니라
  **내 Share의 존재**로 판단한다.
- Rules that must always hold:
  - 참여자 금액 = `floor(품목금액 × 내단위 / 총단위)`의 합. 다른 참여자의 선택과 무관하다.
  - 참여자 금액 합 ≤ 정산 총액.
- Invalid states: 없음.

## 3. Scenarios

### Normal flow

1. 참여자가 품목 몫을 고르고 제출한다.
2. 서버가 그 참여자의 Share를 그 자리에서 만든다.
3. 참여자가 `my-transfer-instruction`으로 금액·계좌를 받아 송금한다.
4. 마지막 참여자가 제출하면 전체 배분을 검증한다. 미배분이 있으면 `SUSPENDED`.

### Failure flow

- Condition: 이미 제출했다.
- Returned error or state transition: `SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS` (기존)

- Condition: 전원 제출했는데 안 채워진 품목이 있다.
- Returned error or state transition: `SUSPENDED` (기존)

### Boundary conditions

- 참여자 금액 합이 정산 총액보다 품목당 최대 (선택 수 − 1)원 적을 수 있다. 그 차액은 생성자가 부담한다.
- 아무 품목도 안 고른 참여자는 Share가 생기지 않는다(금액 0). 기존과 같다.
- DRAFT에서는 여전히 확정하지 않는다. 생성자가 미리 고르는 단계다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 변경 없음
Command: 변경 없음
Result:  변경 없음
```

응답 형태는 그대로다. `phase`가 제출 직후 `TRANSFER_READY`로 바뀌는 점만 다르다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SaveSettlementSharePort` | 참여자 Share 저장 | `SettlementItemAllocationWriter` | 기존 Adapter |

새 Port는 만들지 않는다.

### Application Policy

- Decision owned by the Policy:
  - 선택별 금액 산정 — **나머지 배분을 없애고 항상 내림**
  - 참여자 한 명의 Share 생성 (`buildShareFor`)
  - 전체 배분 검증 (기존 `evaluateAllocation`)
- Facts loaded by the Service and passed to the Policy: 해당 품목들과 그 선택 전체
- Why the rule is not an Entity-local invariant: 금액은 품목 전체 단위를 알아야 계산된다.
- Future inputs deliberately not implemented yet: 부분 입금.

### Web API

```text
변경 없음. POST .../my-item-selection/submission 의 응답 phase가
제출 직후 TRANSFER_READY가 될 수 있다.
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

- Rules and their locations: 금액 산정과 Share 생성은 `SettlementItemAllocationPolicy`,
  제출 흐름은 `SettlementItemAllocationWriter`, 요청 시 상태는 `SettlementRequestService`
- Behavior owned by Domain: `Settlement.awaitDeposit` / `suspend`
- Decisions owned by Application Policy: 내림 금액, Share 생성, 배분 검증
- Flow orchestrated by Application: 제출 → 내 Share 저장 → (마지막이면) 전체 검증

### Technology and patterns

- Constraint to solve: 나머지 1~2원을 정확히 나누려고 전원 제출까지 송금을 막고 있다.
- Selected approach: 나머지 배분을 없애고 **참여자는 항상 내림값**을 낸다. 그러면 금액이
  자기 선택만으로 정해지므로 제출 즉시 Share를 만들 수 있다. 송금 안내는
  `REQUESTED`에서도 열되, 내 Share가 없으면 그대로 404다.
- Reason for selection: 사용자가 기다리는 이유가 사라진다. 선택 중 보던 금액이 그대로라
  "금액이 바뀐다"는 기존 한계도 함께 없어진다.
- Alternatives considered:
  - 잠정 금액으로 먼저 보여주기 — 확정 때 금액이 달라져 입금 대사가 어긋난다.
  - 올림으로 걷기 — 참여자가 총액보다 더 낸다. 받는 쪽이 이득이라 설명하기 나쁘다.
  - 마지막 사람이 나머지를 떠안기 — 늦게 고른 사람만 손해라 불공평하다.
- Trade-offs and limits:
  - 생성자가 품목당 최대 (선택 수 − 1)원을 덜 받는다. 영수증 10개·4명이면 최대 30원.
  - 참여자 금액 합이 정산 총액과 정확히 같지 않을 수 있다. 총액 일치를 전제하던
    검증이 있으면 함께 고쳐야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음(응답 phase 시점만 앞당겨짐) | `SettlementMyItemAllocationDocs` |
| Application | 금액 산정·Share 생성·제출 흐름·요청 시 상태 | `SettlementItemAllocationPolicy`, `SettlementItemAllocationWriter` |
| Domain | 없음 | — |
| Persistence | 없음 | — |
| Redis | 없음 | — |
| Security | 없음 | — |
| Configuration | 없음 | — |
| Documentation | 이 Feature Plan, Swagger 설명 | `SettlementItemAllocationDocs` |

Existing behavior that must remain unchanged:

- 전원 제출 후 미배분 품목이 있으면 `SUSPENDED`.
- DRAFT에서 확정하지 않는 규칙.
- 더치페이 흐름.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result (`N/A` — 변경 없음)
- [x] Required Output Ports (`N/A` — 기존 Port 재사용)
- [x] Domain models and rules (`N/A` — 변경 없음)
- [x] Application Policies
- [x] Application Service
- [x] Output Adapters (`N/A` — 기존 Adapter 재사용)
- [x] Web Response DTO와 Swagger
- [x] Infra configuration and Bean wiring (`N/A` — 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 품목 제출 즉시 송금 설계 문서 추가
refactor(settlement): 품목 몫 금액에서 나머지 배분을 없앤다
feat(settlement): 품목을 제출하면 기다리지 않고 바로 송금한다
test(settlement): 참여자별 확정 테스트 추가
```

## 8. Verification

```bash
./gradlew compileJava
./gradlew test
```

### Completion criteria

- [ ] 제출 즉시 그 참여자의 Share가 생기는지 확인
- [ ] 선택 중 금액과 제출 후 금액이 같은지 확인
- [ ] 전원 제출 후 미배분이면 SUSPENDED인지 확인
- [ ] 더치페이 흐름이 그대로인지 확인

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | 경고·오류 없음 |
| 단위 테스트 (`*domain*`, `*policy*`, `*ServiceTest*`, `*ControllerTest*`) | Pass | `BUILD SUCCESSFUL` |
| 통합 테스트 | **미실행** | 로컬에 PostgreSQL·Docker가 없어 `Failed to load ApplicationContext`. |

바뀐 규칙을 테스트에 반영했다.

- `나누어떨어지지 않아도 각자 내림값만 낸다` — 1666/1666/1666, 합은 총액보다 2원 적다
- `내 금액은 남이 더 고르든 말든 바뀌지 않는다` — 혼자일 때와 셋일 때 같은 값
- `제출하면 남을 기다리지 않고 내 Share부터 만든다` — 상태는 그대로
- `전원이 끝나지 않았어도 내 Share가 있으면 보낼 수 있다`
- `내 Share가 아직 없으면 보낼 금액이 없다`

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-02 | 설계 문서 작성 | 정책·제출 흐름 구현 | 총액 일치를 전제한 검증이 있으면 함께 고쳐야 한다 |
| 2026-09-02 | 정책·제출 흐름·송금 조건 구현, 테스트 반영 | 프론트 연결 | 요청 시 `AWAITING_DEPOSIT`으로 보내려던 초안은 폐기했다. `isEditableStatus`가 `REQUESTED`에서만 선택을 허용해 아무도 품목을 못 고르게 된다. 총액 일치 검증은 내림 손실만큼(선택 수 이내) 허용하도록 완화했다. |
