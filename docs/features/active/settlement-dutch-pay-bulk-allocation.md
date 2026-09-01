# Feature Plan: 더치페이 배분 일괄 저장

- Status: In progress
- Last updated: 2026-09-01
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/219
- Related ADR: -

## 1. Purpose

### Problem

앱의 금액 분배 화면(정산 생성 3/4단계)은 참여자 전원의 금액을 한 화면에서 정한다.
한 명을 고치면 나머지가 자동으로 다시 N빵되므로 **확정 시점에는 항상 전원의 금액이 함께 바뀐다.**

현재 서버가 제공하는 쓰기 API는 `PATCH /api/v2/settlements/{settlementId}/dutch-pay-allocations/{participantId}`
하나뿐이라, 클라이언트는 인원 수만큼 순차 호출한다. 3명 중 2명까지 저장되고 세 번째가 실패하면
합계가 총액과 어긋난 상태가 서버에 남는다. 클라이언트가 되돌리려면 이전 값을 다시 PATCH해야 하는데
그 호출도 실패할 수 있다.

어긋난 상태는 `POST /api/v2/settlements/{settlementId}/request`에서
`SETTLEMENT_SHARE_TOTAL_MISMATCH`로 뒤늦게 드러난다. 정산 요청이 실패하면 `SettlementShare`가
생성되지 않고, 정산 상세는 `expectedAmount: null` + `NOT_READY`를 반환한다.

### Why now

정산 생성 플로우가 앱에서 처음부터 끝까지 연결되는 시점이다. 순차 PATCH를 그대로 두면
인원이 늘수록 왕복과 부분 실패 확률이 비례해 커지고, 실패 흔적이 서버에 남아 정산 요청 단계에서만
증상이 보인다.

### Success criteria

- [ ] 한 번의 요청으로 참여자 전원의 금액이 저장되고, 하나라도 어긋나면 아무것도 저장되지 않는다.
- [ ] 합계가 정산 총액과 다르면 저장을 거부한다.
- [ ] 참여자가 빠졌거나 중복이거나 정산에 없는 참여자면 저장을 거부한다.
- [ ] 기존 `PATCH` 단건 API의 동작은 그대로 유지된다.

### Out of scope

- 품목별 할당(`ITEM_ALLOCATION`) 배분 저장
- `PATCH` 단건 API 제거 — 클라이언트 전환 후 별도 과제로 판단한다.
- 정산 요청 시점의 `SettlementShare` 생성 규칙 변경

## 2. Domain analysis

### Domain boundary

- 재사용할 기존 도메인: `settlement` (`SettlementDutchPayAllocation`, `SettlementParticipant`, `Settlement`)
- 새 도메인이 필요한 이유: 없음. 저장 단위만 참여자 1명에서 전원으로 넓힌다.
- 다른 도메인과의 관계: 없음

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 배분 | 더치페이에서 참여자 1명이 부담할 금액 | `SettlementDutchPayAllocation` |
| 일괄 저장 | 참여자 전원의 배분을 한 트랜잭션에서 교체 | `ReplaceSettlementDutchPayAllocationsUseCase` |
| 균형 | 배분 합계와 정산 총액이 같은 상태 | `SettlementDutchPayResult.balanced()` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 분배 화면에서 정한 전원의 금액을 한 번에 확정한다 | `ReplaceSettlementDutchPayAllocationsUseCase` |

### State and invariants

- 상태 전이: 없음. `Settlement`는 `DRAFT`에 머문다.
- 항상 지켜야 하는 규칙:
  - 요청은 정산 참여자 전원을 정확히 한 번씩 포함한다.
  - 각 금액은 0원 이상이다(`SettlementDutchPayAllocation`의 기존 불변식).
  - 금액 합계는 `Settlement.totalAmount()`와 같다.
- 잘못된 상태: 참여자 누락·중복·외부 참여자, 합계 불일치, `DRAFT`가 아닌 정산

## 3. Scenarios

### Normal flow

1. `DRAFT` 상태의 `DUTCH_PAY` 정산에 참여자 3명과 배분 3건이 있다.
2. 생성자가 분배 화면에서 금액을 조정하고 확인을 누른다.
3. 앱이 참여자 3명분 금액을 한 번에 `PUT`으로 보낸다.
4. 한 트랜잭션에서 3건이 모두 갱신되고, `balanced: true`인 현재 배분 상태가 응답된다.

### Failure flow

- 참여자 누락·중복·정산에 없는 참여자 → `INVALID_DUTCH_PAY_ALLOCATION_REQUEST` (400)
- 합계 != 정산 총액 → `SETTLEMENT_DUTCH_PAY_TOTAL_MISMATCH` (409)
- 금액 음수 → `INVALID_ARGUMENT` (400, Bean Validation) 또는 `INVALID_DUTCH_PAY_AMOUNT` (400)
- 합계 overflow → `INVALID_DUTCH_PAY_TOTAL` (400)
- `DRAFT`가 아님 → `SETTLEMENT_NOT_EDITABLE` (409)
- 배분 유형 미선택 / 품목별 정산 → `SETTLEMENT_ALLOCATION_NOT_SELECTED`, `SETTLEMENT_DUTCH_PAY_REQUIRED` (409)
- 참여자 수와 저장된 배분 수가 다름 → `SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE` (409)
- 생성자가 아님 → `SETTLEMENT_ACCESS_DENIED` (403)

### Boundary conditions

- 동시성: `loadOwnedSettlementForUpdate`의 비관적 잠금을 기존 `PATCH`와 동일하게 사용한다.
  같은 정산에 대한 동시 저장은 직렬화된다.
- 빈 목록: 참여자 전원 포함 규칙에 걸려 거부된다. 참여자는 최소 2명이다.
- 금액 0원: 저장은 허용한다(기존 `PATCH`의 `@PositiveOrZero`와 동일). 정산 요청 시점의
  `SETTLEMENT_SHARE_AMOUNT_REQUIRED` 검증은 그대로 둔다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ReplaceSettlementDutchPayAllocationsUseCase.replace(UUID settlementId, ReplaceSettlementDutchPayAllocationsCommand command)
Command: ReplaceSettlementDutchPayAllocationsCommand(List<Allocation> allocations)
         Allocation(UUID participantId, long amount)
Result:  SettlementDutchPayResult (기존 재사용)
Web Request DTO -> Command: ReplaceSettlementDutchPayAllocationsWebRequest.toCommand()
Result -> Web Response DTO: SettlementDutchPayWebResponse.from(result) (기존 재사용)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementPort` | 잠금 포함 정산 조회 | `SettlementAllocationService` | `SettlementPersistenceAdapter` |
| `LoadSettlementParticipantPort` | 참여자 전원 조회 | `SettlementAllocationService` | `SettlementParticipantPersistenceAdapter` |
| `SettlementDutchPayAllocationStorePort` | 배분 조회·갱신 | `SettlementAllocationService` | `SettlementDutchPayAllocationPersistenceAdapter` |

새 Port는 만들지 않는다. 기존 `update(SettlementDutchPayAllocation)`를 한 트랜잭션 안에서 참여자 수만큼
호출해 원자성을 얻는다. 참여자는 소수이고, 행을 지웠다 다시 넣는 `replaceAll`과 달리
`createdAt`과 행 ID가 보존된다.

### Application Policy

- Policy가 소유하는 판단: 요청 목록이 참여자 전원을 정확히 한 번씩 덮는지, 합계가 정산 총액과 같은지
- Service가 Port로 읽어 Policy에 넘기는 사실: `Settlement`, `List<SettlementParticipant>`, 요청 금액 목록
- Entity 불변식이 아닌 이유: 배분 1건은 자기 금액만 알고, 전원 포함·합계 일치는 정산·참여자 목록·요청
  전체를 함께 봐야 결정된다.
- 지금 구현하지 않는 입력: 없음

### Web API

```text
PUT /api/v2/settlements/{settlementId}/dutch-pay-allocations

Request:
{
  "allocations": [
    { "participantId": "8f14...", "amount": 13334 },
    { "participantId": "2b90...", "amount": 13333 },
    { "participantId": "c051...", "amount": 13333 }
  ]
}

Response: SettlementDutchPayWebResponse (GET / PATCH와 동일)
{
  "settlementId": "...",
  "totalAmount": 40000,
  "allocatedAmount": 40000,
  "differenceAmount": 0,
  "balanced": true,
  "participants": [ { "participantId": "...", "displayName": "...", "amount": 13334, "creator": true }, ... ]
}

Errors: 3. Failure flow 참고
Authentication: bearerAuth (정산 생성자)
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

- 규칙 위치: 금액 0원 이상은 `SettlementDutchPayAllocation`(Domain), 전원 포함·합계 일치는
  `DutchPayAllocationPolicy`(Application Policy), 소유권·상태·유형 확인은 `SettlementAllocationService`
- Domain이 소유하는 행위: `SettlementDutchPayAllocation.updateAmount(amount, now)`
- Application Policy가 소유하는 판단: `DutchPayAllocationPolicy.validateReplacement(...)`
- Application이 조립하는 흐름: 잠금 조회 → 편집 가능 확인 → 더치페이 확인 → 참여자·배분 조회 →
  Policy 검증 → 배분 갱신 → 결과 조립

### Technology and patterns

- 해결할 제약: 참여자 수만큼의 순차 HTTP 호출에는 원자성이 없다.
- 선택한 접근: 단일 `@Transactional` UseCase에서 전원 갱신
- 선택 이유: 트랜잭션 경계가 곧 원자성 경계이고, 기존 Port·Result·Response DTO를 그대로 쓸 수 있다.
- 검토한 대안:
  - `replaceAll`로 전체 삭제 후 재삽입 — 구현은 짧지만 행 ID와 `createdAt`이 매번 바뀐다.
  - `PATCH` 유지 + 클라이언트 보상 트랜잭션 — 보상 호출 자체가 실패할 수 있어 문제가 남는다.
- 트레이드오프와 한계: 참여자 수만큼 UPDATE 쿼리가 나간다. 참여자는 수십 명 규모라 문제되지 않는다고
  판단했고, 병목이 확인되면 Port에 일괄 갱신을 추가한다.

### 이슈 #219가 남긴 결정 사항에 대한 답

1. **전원 포함 강제** — 강제한다. 빠진 참여자가 있으면 400. 프론트는 항상 전원을 보내고,
   부분 저장을 허용하면 이 API가 없애려는 중간 상태가 그대로 남는다.
2. **합계 검증** — `sum(amount) != totalAmount`면 409로 거부한다. 단건 `PATCH`는 편집 중
   불일치를 허용해야 해서 그대로 두고, 확정용인 이 API에서만 균형을 요구한다.
3. **음수·0** — 음수는 거부(기존 Domain 불변식), 0은 허용. 정산 요청 시점의
   `SETTLEMENT_SHARE_AMOUNT_REQUIRED`가 최종 방어선이다.
4. **DRAFT 제한** — 기존 `PATCH`와 동일하게 `DRAFT`에서만 허용한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `PUT /api/v2/settlements/{settlementId}/dutch-pay-allocations` 추가 | `SettlementAllocationController`, `SettlementAllocationDocs` |
| Application | UseCase·Command 추가, Service에 구현 추가, Policy에 검증 추가 | `SettlementAllocationService`, `DutchPayAllocationPolicy` |
| Domain | 상태 코드 2개 추가 | `SettlementStatusCode` |
| Persistence | 변경 없음 | `SettlementDutchPayAllocationStorePort` |
| Redis | 변경 없음 | - |
| Security | 변경 없음 (기존 인증 경로) | `SecurityConfig` |
| Configuration | 변경 없음 | - |
| Documentation | 이 Feature Plan, Swagger | `docs/features/active/settlement-dutch-pay-bulk-allocation.md` |

변경되면 안 되는 기존 동작:

- `PATCH /dutch-pay-allocations/{participantId}`는 편집 중 합계 불일치를 계속 허용한다.
- `PUT /allocation-type`에서 같은 유형을 다시 선택할 때 기존 금액을 유지하는 동작
- `POST /request`의 `SettlementShare` 생성·검증 규칙

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports (기존 재사용, 추가 없음)
- [x] Domain models and rules (상태 코드 추가)
- [x] Application Policies
- [x] Application Service
- [x] Output Adapters (변경 없음)
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A` — 신규 Bean 없음)
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 더치페이 배분 일괄 저장 설계 문서 추가
feat(settlement): 더치페이 배분 일괄 저장 API 추가
test(settlement): 더치페이 배분 일괄 저장 검증 테스트 추가
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*DutchPayAllocationPolicyTest*'
git diff --check
git status --short --untracked-files=all
```

수동 확인 절차(PostgreSQL·Redis 필요):

1. `DRAFT` 더치페이 정산에 참여자 3명을 만들고 `PUT /allocation-type`으로 `DUTCH_PAY`를 선택한다.
2. 총액과 합계가 같은 3건을 `PUT /dutch-pay-allocations`로 보내고 `balanced: true`를 확인한다.
3. 한 명을 빼고 보내 400 `INVALID_DUTCH_PAY_ALLOCATION_REQUEST`를 확인한다.
4. 합계를 1원 어긋나게 보내 409 `SETTLEMENT_DUTCH_PAY_TOTAL_MISMATCH`와, 실패 후
   `GET /dutch-pay-allocations`가 직전 값 그대로인지 확인한다.

### Completion criteria

- [ ] Purpose의 사용자 결과를 확인했다.
- [ ] 정상·실패·경계 흐름을 확인했다.
- [ ] 문서와 Swagger가 실제 계약과 일치한다.
- [ ] 수행하지 못한 검증과 이유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | 오류 없음 |
| `DutchPayAllocationPolicyTest` | Pass | 균형·불일치·누락·중복·외부 참여자 5개 케이스 추가 |
| `SettlementAllocationControllerTest` | Pass | Command 변환 1개, Bean Validation 거부 2개 추가 |
| `SettlementControllerTest` | Pass | 아래 "발견한 기존 실패" 참고 |
| 수동 API 확인 | Not run | 로컬 PostgreSQL·Redis 미기동 |

### 발견한 기존 실패 (이 작업과 무관)

`./gradlew test` 전체 실행에서 65건이 실패했다. 63건은 PostgreSQL 미기동으로 인한
`ApplicationContext` 로딩 실패이고, 나머지 2건은 실제 실패였다.

1. `SettlementControllerTest` **컴파일 실패** — PR #213이 `SettlementController`에
   `RenameSettlementParticipantUseCase`를 추가하면서 테스트 생성자를 갱신하지 않았다.
   CI가 `build -x test`로 도는 탓에 드러나지 않았고, 그 사이 `compileTestJava`가 깨져 있어
   어떤 테스트도 실행할 수 없었다. 이 작업에서 함께 고쳤다.
2. `SettlementParticipantTest.rejectsInvalidPhoneHash[1] phoneHash = null` — PR #215가
   연락처 없는 참여자를 허용하도록 `validateIdentity`를 완화했는데 테스트를 갱신하지 않았다.
   `@NullSource`를 제거하고 "hash 없이 이름만으로 추가할 수 있다" 케이스를 추가했다.
3. `SettlementDepositServiceTest.completesWhenEveryExternalPayerIsConfirmed` —
   `bankTransactionPort.fetch`가 1회 기대에 2회 호출된다
   (`SettlementDepositService.fetchTransactions:109`). 입금 대조 영역이라 이 작업과 무관하고,
   **고치지 않고 남겨둔다.** #205·#206과 함께 다룰 사안으로 보인다.

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-01 | 설계 문서 작성, 계약 확정 | 구현 및 검증 | `PUT /allocation-type` 재선택 시 참여자 수와 배분 수가 다르면 `SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE`가 난다. 배분 유형 선택 후 참여자를 추가하는 클라이언트 순서에서 재현될 수 있어 별도 확인이 필요하다. |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-01 | 일괄 저장에서만 합계 일치를 강제 | 확정용 API이고, 편집 중 불일치는 단건 `PATCH`가 담당 | 두 API의 검증 강도가 다르다는 점을 Swagger에 명시 |
| 2026-09-01 | `replaceAll` 대신 참여자별 `update` 반복 | 행 ID와 `createdAt` 보존, 새 Port 불필요 | 참여자 수만큼 UPDATE 쿼리 발생 |
| 2026-09-01 | 전용 상태 코드 2개 신설 | `INVALID_DUTCH_PAY_TOTAL`은 overflow 의미로 이미 쓰이고 있어 재사용하면 원인 구분이 불가능 | 클라이언트가 분기할 수 있는 코드가 늘어난다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
