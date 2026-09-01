# Feature Plan: 품목 수량 단위 몫 배분

- Status: In progress
- Last updated: 2026-09-01
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

품목 몫 선택은 품목 "한 줄"을 `WHOLE_UNITS = 12`로 나눠서 다룬다. 수량이 몇 개든 총 단위가 12로 고정이라
`units × fraction`이 12를 넘을 수 없다.

아메리카노 ×2 (총 10,000원, 단가 5,000원)에서 참여자가 실제로 하려는 선택은 이렇다.

| 사용자가 고르려는 몫 | 필요한 비율 | 현재 표현 가능 여부 |
|---|---|---|
| 2개 | 품목 전체 | `ONE` (12units) |
| 1개 | 품목의 1/2 | `HALF` — 개수가 아니라 줄의 절반이라 우연히 맞는다 |
| 1/2개 | 품목의 1/4 | `QUARTER` — 역시 우연히 맞는다 |
| 1/3개 | 품목의 1/6 | **불가능** |

"1개"를 고르려면 클라이언트가 `HALF`를 보내야 하는데, 이건 수량이 2일 때만 성립한다. 수량이 3이면
"1개"는 `THIRD`, 수량이 5면 표현할 방법이 없다. 클라이언트가 수량을 비율로 환산해야 하고, 그 환산이
`ONE/HALF/THIRD/QUARTER`로 떨어지지 않으면 선택지 자체가 사라진다.

실제로 클라이언트는 "1개"에 `{fraction: ONE, units: 1}`을 보내 품목 전체(10,000원)를 가져가버렸다.

추가로 `SettlementItemAllocationFraction.amountOf`는 금액이 분모로 나눠떨어지지 않으면
`SETTLEMENT_ITEM_ALLOCATION_AMOUNT_NOT_EXACT`를 던진다. 10,000원짜리 품목은 `THIRD` 자체가 실패한다.
가격이 3으로 나눠떨어지는지에 따라 선택지가 생겼다 사라진다.

### Why now

품목 정산(`ITEM_ALLOCATION`) 생성 플로우가 클라이언트에서 붙는 중이고, 이 모델 위에서는 디자인대로
구현할 수 없다. 지금 고치지 않으면 클라이언트가 수량↔비율 환산 규칙을 떠안고, 환산이 안 되는 조합은
말없이 빠진 채로 출시된다.

### Success criteria

- [ ] 수량 N개 품목에서 `1개`부터 `N개`까지 모두 선택할 수 있다.
- [ ] 한 개를 다시 `1/2 · 1/3 · 1/4`로 쪼갠 몫을 선택할 수 있다.
- [ ] 금액이 분모로 나눠떨어지지 않아도 선택이 거부되지 않는다.
- [ ] 품목이 전부 채워지면 참여자 몫의 합이 품목 금액과 1원도 어긋나지 않는다.
- [ ] 남이 이미 가져간 몫을 넘겨 고르면 `SETTLEMENT_ITEM_OVER_ALLOCATED`로 거부된다.

### Out of scope

- `1/5` 이하의 더 잘게 나눈 비율. `ONE/HALF/THIRD/QUARTER`를 유지한다.
- 더치페이(`DUTCH_PAY`) 배분 로직.
- 품목 수량·금액 자체를 수정하는 기능.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `SettlementItem`, `SettlementItemSelection`, `SettlementItemAllocationFraction`
- Reason a new domain is required, if applicable: 없음. 기존 도메인의 단위 기준만 바꾼다.
- Relationships to other domains: `SettlementShare` 생성(확정) 시 배분 결과를 사용한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 몫 단위 | 품목 한 개를 12등분한 최소 단위 | `SettlementItemAllocationFraction.WHOLE_UNITS` |
| 품목 총 단위 | 품목 전체(수량 포함)를 채우는 데 필요한 단위 수 | `SettlementItem.totalAllocationUnits()` = `quantity × 12` |
| 선택 단위 | 한 선택이 차지하는 단위 수 | `SettlementItemSelection.allocatedUnits()` = `fraction.units() × units` |
| 비율 | 품목 **한 개**에 대한 분수 | `ONE(1/1) · HALF(1/2) · THIRD(1/3) · QUARTER(1/4)` |
| 수량 | 그 비율을 몇 번 가져가는지 | `SettlementItemSelection.units` |

핵심 변경: `fraction`의 기준이 "품목 한 줄"에서 **"품목 한 개"** 로 바뀐다.
`{ONE, units: 2}` = 2개, `{THIRD, units: 1}` = 1/3개, `{THIRD, units: 2}` = 2/3개.

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 참여자 | 품목에서 내 몫을 개수·분수로 고른다 | `SaveSettlementItemSelectionUseCase` (기존) |
| 정산 참여자 | 내 몫과 남은 몫을 확인한다 | `ReadSettlementItemAllocationUseCase` (기존) |

새 UseCase·엔드포인트 없음.

### State and invariants

- State transitions: 없음. 선택 저장·취소 흐름은 그대로다.
- Rules that must always hold:
  - 품목별 선택 단위 합 ≤ `item.totalAllocationUnits()`
  - 확정 시점에는 품목별 선택 단위 합 == `item.totalAllocationUnits()`
  - 품목이 전부 채워지면 선택 금액 합 == `item.amount()`
- Invalid states: 단위 합이 총 단위를 넘는 상태.

## 3. Scenarios

### Normal flow

1. 아메리카노 ×2, 총 10,000원. 총 단위 24.
2. 참여자 A가 `{ONE, units: 1}`(1개)을 고른다 → 12units, 5,000원.
3. 참여자 B가 `{HALF, units: 1}`(1/2개)을 고른다 → 6units, 2,500원.
4. 참여자 C가 `{HALF, units: 1}`을 고른다 → 6units, 2,500원. 합 24units로 품목이 채워진다.
5. 확정 시 A 5,000 + B 2,500 + C 2,500 = 10,000원.

나눠떨어지지 않는 경우: 아메리카노 ×1, 5,000원. A·B·C가 각각 `{THIRD, units: 1}`을 고르면
1,666 / 1,666 / 1,666이고 남는 2원은 **먼저 고른 순서대로** 1원씩 얹어 1,667 / 1,667 / 1,666이 된다.

### Failure flow

- Condition: 남은 단위보다 큰 몫을 고른다.
- Returned error or state transition: `SETTLEMENT_ITEM_OVER_ALLOCATED`

- Condition: `units < 1`
- Returned error or state transition: `INVALID_SETTLEMENT_ITEM_SELECTION_UNITS`

### Boundary conditions

- 품목이 다 채워지기 전에는 나머지를 배분하지 않는다. 선택 중 조회되는 `personalAmount`는 내림값이고,
  품목이 채워지는 순간 최대 (선택 수 - 1)원까지 올라갈 수 있다.
- 나머지 배분 순서는 `createdAt`, 동률이면 선택 `id`. 조회 시점이 달라도 결과가 같다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 변경 없음
Command: SaveSettlementItemSelectionCommand(fraction, units) — 변경 없음
Result:  SettlementItemAllocationItemResult(..., allocatedUnits, totalUnits, selections)
         totalUnits 값이 상수 12 → item.quantity × 12 로 바뀐다
Web Request DTO -> Command: SaveSettlementItemSelectionWebRequest — 필드 변경 없음, Schema 설명만 수정
Result -> Web Response DTO: SettlementItemAllocationItemWebResponse — 필드 변경 없음
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| — | 변경 없음 | — | — |

### Application Policy

- Decision owned by the Policy:
  - 품목별 단위 상한 검증 (`validateSelection`)
  - 선택별 금액 산정과 1원 단위 나머지 배분 (`calculateSelectionAmounts`)
  - 확정 가능 여부 판정 (`evaluateAllocation`)
- Facts loaded by the Service and passed to the Policy: `SettlementItem`, 해당 정산의 전체
  `SettlementItemSelection`, `SettlementParticipant`
- Why the rule is not an Entity-local invariant: 상한과 금액 배분 모두 **같은 품목의 다른 선택들**을
  알아야 계산된다. `SettlementItemSelection` 하나만으로는 판단할 수 없다.
- Future inputs deliberately not implemented yet: 1/5 이하 비율.

### Web API

```text
PUT /api/v2/settlements/{settlementId}/my-items/{itemId}
Request:  { "fraction": "ONE" | "HALF" | "THIRD" | "QUARTER", "units": 1 }
Response: SettlementMyItemAllocationWebResponse (구조 동일, totalUnits 의미 변경)
Errors:   SETTLEMENT_ITEM_OVER_ALLOCATED, INVALID_SETTLEMENT_ITEM_SELECTION_UNITS
Authentication: 기존과 동일
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음. `settlement_item_selections.units`의 제약은
  `units >= 1`뿐이라(`V27`) 상한 완화에 스키마 변경이 필요 없다.
- Flyway migration required (`Yes` / `No`): **No**
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations:
  - `SettlementItem.totalAllocationUnits()` — 품목 하나로 닫히는 계산이라 Domain
  - `SettlementItemSelection.allocatedUnits()` — 선택 하나로 닫히므로 Domain (유지)
  - 상한·금액·확정 — 여러 선택을 함께 봐야 하므로 Application Policy
- Behavior owned by Domain: 단위 환산, `units >= 1` 검증
- Decisions owned by Application Policy: 상한 검증, 금액 배분, 확정 판정
- Flow orchestrated by Application: `SettlementItemAllocationReader`가 Policy 결과를 Result로 조립

### Technology and patterns

- Constraint to solve: 총 단위가 수량과 무관하게 12로 고정이고, 금액이 나눠떨어져야만 선택이 가능하다.
- Selected approach: 총 단위를 `quantity × WHOLE_UNITS`로 바꾸고, 금액을 "단위 비례 내림 + 나머지 순차 배분"으로 계산한다.
- Reason for selection: 비율 enum과 저장 스키마를 그대로 두고 기준만 넓히므로 마이그레이션이 필요 없다.
  `{ONE, units: N}`이 곧 N개라서 클라이언트의 환산 규칙이 사라진다.
- Alternatives considered:
  - `WHOLE_UNITS`를 60 등으로 키워 1/5·1/6까지 허용 — 나눠떨어짐 문제는 그대로 남고 수량 개념도 안 생긴다.
  - 금액을 `BigDecimal`로 다루기 — 원 단위 정산에서 소수를 남기면 합계가 안 맞는다.
- Trade-offs and limits:
  - `SettlementItemAllocationFraction.amountOf`와
    `SETTLEMENT_ITEM_ALLOCATION_AMOUNT_NOT_EXACT`가 필요 없어져 제거된다.
    `numerator`는 항상 1이라 함께 제거했다.
  - 선택 중 표시되는 `personalAmount`가 확정 시 최대 (선택 수 - 1)원 오를 수 있다.
  - `totalUnits`의 의미가 바뀌므로 이 값을 상수 12로 가정한 클라이언트는 같이 고쳐야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 `totalUnits`의 의미 변경, Swagger 설명 수정 | `SaveSettlementItemSelectionWebRequest`, `SettlementItemAllocationItemWebResponse` |
| Application | 상한 검증·금액 배분·확정 판정 재작성 | `SettlementItemAllocationPolicy`, `SettlementItemAllocationReader` |
| Domain | 품목 총 단위 추가, 선택 상한 제거, `amountFor`/`amountOf` 제거 | `SettlementItem`, `SettlementItemSelection`, `SettlementItemAllocationFraction` |
| Persistence | 없음 | `V27__add_settlement_item_selection_units.sql` |
| Redis | 없음 | — |
| Security | 없음 | — |
| Configuration | 없음 | — |
| Documentation | 이 Feature Plan | `docs/features/active/settlement-item-allocation-per-quantity.md` |

Existing behavior that must remain unchanged:

- `DRAFT` 상태에서 제출해도 정산이 확정되지 않는다.
- 품목이 전부 채워지지 않으면 확정되지 않고 `invalidItemIds`가 내려간다.
- 남이 가져간 몫을 넘겨 고르면 `SETTLEMENT_ITEM_OVER_ALLOCATED`로 거부된다.

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [ ] UseCase and Application Command/Result (`N/A` — 계약 변경 없음)
- [ ] Required Output Ports (`N/A` — 변경 없음)
- [x] Domain models and rules — `SettlementItem.totalAllocationUnits()`, `SettlementItemSelection` 상한 제거, `amountFor`/`amountOf` 제거
- [x] Application Policies — `validateSelection` 상한 기준 변경, `calculateSelectionAmounts` 추가, `evaluateAllocation` 재작성
- [x] Application Service — `SettlementItemAllocationReader`가 Policy 금액과 `totalAllocationUnits()` 사용
- [x] Output Adapters (`N/A` — 변경 없음)
- [x] Web Request/Response DTOs, Controllers, and Swagger — Schema 설명만 수정
- [x] Infra configuration and Bean wiring (`N/A` — 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 품목 수량 단위 몫 배분 설계 문서 추가
feat(settlement): 품목 몫 기준을 수량 단위로 확장
test(settlement): 품목 수량 단위 배분과 나머지 처리 테스트 추가
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

CI는 `build -x test`라 테스트를 건너뛴다. 병합 전에 `./gradlew test`를 직접 실행해야 한다.

### Completion criteria

- [ ] 수량 N개 품목에서 1개~N개, 1/2·1/3·1/4개를 모두 고를 수 있음을 테스트로 확인
- [ ] 나눠떨어지지 않는 금액에서 합계가 품목 금액과 일치함을 테스트로 확인
- [ ] 상한 초과 시 `SETTLEMENT_ITEM_OVER_ALLOCATED` 확인
- [ ] Swagger 설명이 실제 계약과 일치하는지 확인

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | 경고·오류 없음 |
| 단위 테스트 (`*domain*`, `*policy*`, `*ServiceTest*`) | Pass | `BUILD SUCCESSFUL` |
| 통합 테스트 | **미실행** | 로컬에 PostgreSQL·Docker가 없어 65건이 `PSQLException: ConnectException`으로 실패. 변경과 무관한 환경 문제이며 DB가 있는 환경에서 다시 돌려야 한다. |
| 실기기 확인 | Pending | 배포 후 클라이언트에서 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-01 | 설계 문서 작성 | 도메인·Policy 구현 | `totalUnits`를 상수 12로 가정한 클라이언트가 함께 바뀌어야 한다 |
| 2026-09-01 | 도메인·Policy·Reader·Swagger 구현, 단위 테스트 갱신·추가 | DB 있는 환경에서 통합 테스트 실행, 클라이언트 연동 | 통합 테스트를 로컬에서 못 돌렸다. CI도 `build -x test`라 테스트를 건너뛰므로 병합 전에 누군가 직접 돌려야 한다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-01 | 총 단위를 `quantity × 12`로 확장 | 수량과 분수를 한 모델에서 표현하기 위해 | `WHOLE_UNITS` 확대는 나눠떨어짐 문제를 못 푼다 |
| 2026-09-01 | 금액 나눗셈 정확도 예외 제거, 나머지 순차 배분 | 가격에 따라 선택지가 사라지는 문제를 없애기 위해 | 선택 중 금액이 확정 시 최대 (선택 수 - 1)원 오른다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
