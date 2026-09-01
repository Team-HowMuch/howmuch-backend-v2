# Feature Plan: 생성자 품목 몫 선택 (인증 경로)

- Status: In progress
- Last updated: 2026-09-01
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/227
- Related ADR: -

## 1. Purpose

### Problem

영수증(품목 배분) 정산 생성 흐름의 화면 순서는 이렇다.

```
영수증 스캔 → 영수증 확인 → 인원 선택 → 내 몫 선택 → 알림 발송(완료)
```

생성자도 참여자이므로 **알림을 보내기 전에 자기 몫을 먼저 고른다.**

그런데 품목 몫 선택 API는 **링크 토큰 경로에만** 있다.

```
GET/PUT/DELETE /api/v2/settlement-links/{token}/participants/{participantId}/items...
POST           /api/v2/settlement-links/{token}/participants/{participantId}/item-submission
```

토큰은 `POST /api/v2/settlements/{settlementId}/request`가 발급하고, 그 호출이 곧 참여자
알림 발송이다. 즉 **알림을 먼저 보내야 생성자가 자기 몫을 고를 수 있어 화면 순서와 반대가 된다.**
생성자 몫이 정해지지 않은 상태에서 참여자가 링크를 먼저 열 수 있다.

거기에 더해 링크 경로는 상태 조건이 맞지 않는다.

- `SettlementItemAllocationStateLoader:38` — `REQUESTED`/`AWAITING_DEPOSIT`/`SUSPENDED`만 허용
- `SettlementItemAllocationWriter:119` — 쓰기는 `REQUESTED`만 허용

`DRAFT`에서는 조회조차 안 된다.

### Why now

품목 배분 정산이 앱에 붙는 시점이다. 이 API 없이는 3/4 화면을 연결할 수 없고,
"먼저 알림을 보내고 나서 내 몫을 고른다"는 순서는 사용자에게 잘못된 동작이다.

### Success criteria

- [ ] 생성자가 `DRAFT` 상태에서 자기 몫을 조회·저장·삭제·제출할 수 있다.
- [ ] 링크 경로의 기존 동작과 상태 조건이 그대로 유지된다.
- [ ] `DRAFT`에서 제출해도 정산이 확정(`AWAITING_DEPOSIT`)으로 넘어가지 않는다.
- [ ] 생성자가 아닌 사용자는 접근할 수 없다.

### Out of scope

- 초대받은 앱 회원이 링크 대신 앱에서 고르는 것 — 이슈에서 "당장은 생성자만으로 충분"으로 정리됐다.
  경로를 열 때 참여자 해석만 바꾸면 되는 추가 변경이다.
- 품목 수량(`units`) 지원 — 이슈 #217의 별도 작업이다.
- 링크 경로 API 제거

## 2. Domain analysis

### Domain boundary

- 재사용할 기존 도메인: `settlement` (`SettlementItemSelection`, `SettlementItemSubmission`, `SettlementItem`)
- 새 도메인이 필요한 이유: 없음. **진입 경로만 추가하고 도메인 규칙은 그대로 쓴다.**
- 다른 도메인과의 관계: 인증(`CurrentActorPort`)

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 진입 경로 | 링크 토큰인지, 로그인한 생성자인지 | `SettlementItemAllocationEntry` |
| 내 몫 | 로그인한 생성자의 참여자 행에 달린 품목 선택 | `SettlementItemSelection` |
| 제출 | 더 이상 고치지 않겠다는 확정 | `SettlementItemSubmission` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 알림 발송 전에 자기 몫을 고른다 | `GetMySettlementItemAllocationUseCase` |
| 정산 생성자 | 품목 비율을 저장한다 | `SaveMySettlementItemSelectionUseCase` |
| 정산 생성자 | 품목 선택을 취소한다 | `RemoveMySettlementItemSelectionUseCase` |
| 정산 생성자 | 자기 선택을 확정한다 | `SubmitMySettlementItemAllocationUseCase` |

### State and invariants

- 상태 전이: 생성자 제출만으로는 전이가 없다. 전원 제출 + `REQUESTED`일 때만
  `AWAITING_DEPOSIT` 또는 `SUSPENDED`로 간다.
- 항상 지켜야 하는 규칙:
  - 한 품목의 선택 단위 합은 `WHOLE_UNITS(12)`를 넘지 않는다(기존 규칙).
  - 이미 제출한 참여자는 다시 고칠 수 없다(기존 규칙).
  - **`DRAFT`에서는 확정이 일어나지 않는다**(신규 규칙).
- 잘못된 상태: `DRAFT`인 정산이 `REQUESTED`를 건너뛰고 `AWAITING_DEPOSIT`이 되는 것

## 3. Scenarios

### Normal flow

1. 생성자가 영수증으로 `DRAFT` 품목 배분 정산을 만들고 참여자를 넣는다.
2. `GET /api/v2/settlements/{id}/my-items`로 품목과 현재 선택을 본다.
3. `PUT .../my-items/{itemId}`로 비율을 저장한다.
4. `POST .../my-item-submission`으로 확정한다. 상태는 `DRAFT` 그대로다.
5. `POST .../request`로 알림을 보내고 링크가 발급된다.
6. 나머지 참여자가 링크로 제출하고, 마지막 제출에서 확정이 일어난다.

### Failure flow

- 생성자가 아님 → `SETTLEMENT_ACCESS_DENIED` (403)
- 정산 없음 → `SETTLEMENT_NOT_FOUND` (404)
- 배분 유형이 품목별이 아님 → `SETTLEMENT_ITEM_ALLOCATION_REQUIRED` (409)
- 품목이 없음 → `SETTLEMENT_ITEM_REQUIRED` (409)
- `DRAFT`·`REQUESTED`가 아님 → `SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE` (409)
- 이미 제출함 → `SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS` (409)
- 생성자 참여자 행이 없음 → `SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND` (409)
- 한 품목 초과 배분 → `SETTLEMENT_ITEM_OVER_ALLOCATED` (409)

### Boundary conditions

- **`DRAFT`에서 전원이 제출한 경우**: 확정하지 않고 제출만 기록한다. 확정은 `REQUESTED`에서만
  일어나므로 `POST /request` 이후 마지막 제출이 트리거가 된다. 이 규칙이 없으면 `DRAFT` 정산이
  `REQUESTED`를 건너뛰고 `AWAITING_DEPOSIT`이 된다.
- **이미 요청된 정산에서 생성자가 다시 부르는 경우**: `REQUESTED`면 아직 제출하지 않았을 때만
  수정할 수 있다. 이미 제출했으면 `SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS`.
  링크 경로와 동일한 규칙이다.
- 동시성: 쓰기는 기존과 같이 `findByIdForUpdate`로 정산 행을 잠근다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetMySettlementItemAllocationUseCase.getMyItems(UUID settlementId)
         SaveMySettlementItemSelectionUseCase.saveMyItem(UUID settlementId, UUID itemId, SaveSettlementItemSelectionCommand command)
         RemoveMySettlementItemSelectionUseCase.removeMyItem(UUID settlementId, UUID itemId)
         SubmitMySettlementItemAllocationUseCase.submitMyItems(UUID settlementId)
Command: SaveSettlementItemSelectionCommand (기존 재사용)
Result:  SettlementItemAllocationResult (기존 재사용)
Web Request DTO -> Command: SaveSettlementItemSelectionWebRequest.toCommand() (기존 재사용)
Result -> Web Response DTO: SettlementItemAllocationWebResponse.from(result) (기존 재사용)
```

요청·응답 형태를 링크 경로와 똑같이 두어 프론트가 두 경로를 같은 모델로 다룰 수 있게 한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `CurrentActorPort` | 현재 로그인 사용자 | `SettlementCreatorItemAllocationService` | `SecurityContextCurrentActorAdapter` |
| `LoadSettlementPort` | 정산 조회·소유 확인 | `SettlementCreatorItemAllocationService` | `SettlementPersistenceAdapter` |
| `LoadSettlementParticipantPort` | 생성자의 참여자 행 조회 | `SettlementCreatorItemAllocationService` | `SettlementParticipantPersistenceAdapter` |

새 Port는 만들지 않는다. 기존 `SettlementItemAllocationReader`/`Writer`를 그대로 재사용한다.

### Application Policy

- Policy가 소유하는 판단: 변경 없음. `SettlementItemAllocationPolicy`를 그대로 쓴다.
- 새로 추가한 판단은 **진입 경로별 상태 조건**인데, 이는 Policy가 아니라 Service 협력자
  (`StateLoader`, `Writer`)가 이미 담당하던 책임이라 같은 자리에 둔다.

### Web API

```text
GET    /api/v2/settlements/{settlementId}/my-items
PUT    /api/v2/settlements/{settlementId}/my-items/{itemId}
DELETE /api/v2/settlements/{settlementId}/my-items/{itemId}
POST   /api/v2/settlements/{settlementId}/my-item-submission

Request (PUT):  { "fraction": "HALF" }
Response (전부): SettlementItemAllocationWebResponse (링크 경로와 동일)

Errors: 3. Failure flow 참고
Authentication: bearerAuth (정산 생성자)
```

`my-items`·`my-item-submission`은 리터럴 경로라 기존 `{settlementId}` 하위 경로와 충돌하지 않는다.

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

- 규칙 위치: 진입 경로별 상태 조건은 `SettlementItemAllocationStateLoader`(조회 가능 상태)와
  `SettlementItemAllocationWriter.ensureSelectionEditable`(수정 가능 상태),
  확정 조건은 `SettlementItemAllocationWriter.submit`
- Domain이 소유하는 행위: 변경 없음
- Application Policy가 소유하는 판단: 변경 없음
- Application이 조립하는 흐름: 현재 사용자 → 정산 소유 확인 → 생성자 참여자 해석 →
  기존 Reader/Writer 위임

### Technology and patterns

- 해결할 제약: 같은 도메인 로직에 인증 진입 경로를 하나 더 붙이면서, 링크 경로의 상태 조건은
  건드리지 않아야 한다.
- 선택한 접근: `SettlementItemAllocationEntry { LINK, CREATOR }` 열거형을 `StateLoader`와
  `Writer`에 넘겨 **경로별로 허용 상태와 오류 코드를 분기**한다.
- 선택 이유: 도메인 규칙·Policy·저장 계층을 그대로 재사용하면서 차이 나는 지점만 한 곳에 모은다.
  두 경로가 서로 다른 코드로 갈라지면 규칙이 어긋나기 시작한다.
- 검토한 대안:
  - 링크 경로의 상태 조건을 `DRAFT`까지 넓히기 — 요청 전 정산에 링크로 접근할 수 있게 되어
    보안 경계가 무너진다.
  - 품목 선택 로직을 인증 경로용으로 복제 — 규칙이 두 벌이 되어 갈라진다.
  - 프론트가 `request`를 먼저 호출해 토큰으로 링크 API 사용 — **내 몫을 고르기 전에 알림이
    나가서** 실제 동작이 화면과 어긋난다(이슈에서 이미 기각).
- 트레이드오프와 한계: `StateLoader`·`Writer` 시그니처에 인자가 하나 늘어난다.
  둘 다 package-private이라 영향 범위는 이 패키지 안이다.

### 이슈 #227이 확인 요청한 4가지에 대한 답

1. **DRAFT에서 호출 가능** — 가능하게 했다. `CREATOR` 경로는 `DRAFT`를 허용하고,
   `LINK` 경로는 기존대로 `DRAFT`를 막는다.
2. **생성자만** — 생성자만이다. 이슈의 "당장은 생성자만으로 충분"을 따랐다. 초대받은 앱 회원까지
   열려면 참여자 해석만 바꾸면 되는 추가 변경이라, 계약을 깨지 않고 나중에 넓힐 수 있다.
3. **`item-submission` 제출 시점** — 생성자도 다른 참여자와 동일하게 한 명으로 센다.
   확정은 여전히 전원 제출이 조건이고, **거기에 더해 `REQUESTED`일 때만** 일어난다.
   `DRAFT`에서 생성자가 제출하면 기록만 남고, 나중에 마지막 참여자가 제출할 때 확정된다.
4. **이미 요청된 정산에서 재호출** — `REQUESTED`이고 아직 제출 전이면 수정할 수 있고,
   제출했으면 `SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS`다. 링크 경로와 같다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `my-items` 3개 + `my-item-submission` 1개 추가 | `SettlementMyItemAllocationController`, `SettlementMyItemAllocationDocs` |
| Application | UseCase 4개, Service 1개 추가. `StateLoader`·`Writer`에 진입 경로 인자 추가 | `SettlementCreatorItemAllocationService` |
| Domain | 변경 없음 | - |
| Persistence | 변경 없음 | - |
| Redis | 변경 없음 | - |
| Security | 변경 없음. `/api/v2/settlements/**`는 이미 인증 필요 | `SecurityConfig` |
| Configuration | 변경 없음 | - |
| Documentation | 이 Feature Plan, Swagger | `docs/features/active/settlement-creator-item-allocation.md` |

변경되면 안 되는 기존 동작:

- 링크 경로의 상태 조건(`REQUESTED` 쓰기, `REQUESTED`/`AWAITING_DEPOSIT`/`SUSPENDED` 읽기)
- 링크 경로의 오류 코드(`SETTLEMENT_LINK_NOT_FOUND` 등)
- 전원 제출 시 확정·중단 판정 로직
- 요청·응답 DTO 형태

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result (Command·Result는 기존 재사용)
- [x] Required Output Ports (기존 재사용, 추가 없음)
- [x] Domain models and rules (변경 없음)
- [x] Application Policies (변경 없음)
- [x] Application Service
- [x] Output Adapters (변경 없음)
- [x] Web Request/Response DTOs, Controllers, and Swagger (DTO는 기존 재사용)
- [x] Infra configuration and Bean wiring (`N/A`)
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 생성자 품목 몫 선택 설계 문서 추가
feat(settlement): 생성자가 인증 경로로 품목 몫을 고르는 API 추가
test(settlement): 생성자 품목 몫 선택 검증 테스트 추가
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*ItemAllocation*'
git diff --check
git status --short --untracked-files=all
```

수동 확인 절차(PostgreSQL 필요):

1. 영수증 품목이 있는 `DRAFT` 품목 배분 정산을 만들고 참여자 3명을 넣는다.
2. `GET /my-items`로 품목이 보이는지 확인한다(기존 링크 경로는 여기서 404였다).
3. `PUT /my-items/{itemId}`로 `HALF`를 저장하고 `personalAmount`가 반영되는지 본다.
4. `POST /my-item-submission` 후 정산 상태가 **여전히 `DRAFT`인지** 확인한다.
5. `POST /request` 후 나머지 참여자가 링크로 제출하면 `AWAITING_DEPOSIT`이 되는지 확인한다.
6. 생성자가 아닌 계정으로 `GET /my-items`를 호출해 403이 나오는지 확인한다.

### Completion criteria

- [ ] Purpose의 사용자 결과를 확인했다.
- [ ] 정상·실패·경계 흐름을 확인했다.
- [ ] 문서와 Swagger가 실제 계약과 일치한다.
- [ ] 수행하지 못한 검증과 이유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pending | |
| 단위 테스트 | Pending | |
| 수동 API 확인 | Pending | 로컬 PostgreSQL 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-01 | 설계 문서 작성, 계약 확정 | 구현 및 검증 | `DRAFT` 확정 차단을 빠뜨리면 정산이 `REQUESTED`를 건너뛰고 `AWAITING_DEPOSIT`이 된다. 회귀 테스트로 고정한다. |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-01 | 진입 경로 열거형으로 상태 조건 분기 | 도메인·Policy·저장 계층을 재사용하면서 차이 나는 지점만 모은다 | `StateLoader`·`Writer` 시그니처에 인자 추가 |
| 2026-09-01 | 확정은 `REQUESTED`에서만 | `DRAFT` 정산이 `REQUESTED`를 건너뛰는 것을 막는다 | `DRAFT` 제출은 기록만 남는다 |
| 2026-09-01 | 생성자만 허용 | 이슈에서 요청한 범위. 넓히는 것은 계약을 깨지 않는 추가 변경 | 초대 회원은 당분간 링크로만 |
| 2026-09-01 | 링크 경로 상태 조건 유지 | `DRAFT`까지 넓히면 요청 전 정산에 링크로 접근 가능해진다 | 두 경로의 허용 상태가 다르다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
