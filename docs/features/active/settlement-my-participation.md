# Feature Plan: 앱에서 내 정산에 참여하기

- Status: In progress
- Last updated: 2026-09-02
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

품목 정산의 참여자는 **앱 안에서 자기 몫을 고를 방법이 없다.** 링크를 다시 열어야만 한다.

참여자용 품목 선택 API는 `/api/v2/settlement-links/{token}/participants/{participantId}/...`
하나뿐이고 링크 토큰을 요구한다. 앱의 정산 목록 응답에는 토큰이 없고, 앱 안에서 토큰을 얻는
경로도 없다.

회원용 `POST /api/v2/settlements/{id}/my-items`가 있지만 생성자 전용이다.

```java
if (!settlement.isCreatedBy(actor.userId())) {
    throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
}
```

그래서 낼 정산 목록에서 품목 정산을 눌러도 갈 곳이 없다. 카톡으로 받은 링크를 찾아 다시
눌러야 한다.

더해서 **목록 응답에 `allocationType`이 없다.** 상세에만 있다. 그래서 목록에서 "이건 품목
정산이라 항목을 골라야 한다"를 알 수 없고, 금액이 비어 있는 이유가 품목 미선택인지 배분 전인지
구분하지 못한다.

### Why now

품목 정산 흐름이 앱에서 끝까지 이어지지 않는다. 링크를 잃어버리면 참여할 방법이 없다.

### Success criteria

- [ ] 로그인 회원이 링크 없이 자기 몫을 고른다.
- [ ] 참여자가 아니면 거부된다.
- [ ] 목록 응답으로 품목 정산인지 구분할 수 있다.

### Out of scope

- 비회원 참여. 링크 경로를 그대로 쓴다.
- 참여 화면 자체의 변경. 화면은 그대로 두고 호출할 API만 바뀐다.
- 비회원·링크 경로의 변경.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `SettlementItemSelection`
- Reason a new domain is required, if applicable: 없음. 진입 경로만 하나 더 연다.
- Relationships to other domains: 전원 제출 시 `SettlementShare`가 만들어진다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 진입 경로 | 품목 선택을 어디로 들어왔는지. 허용 상태와 오류 코드가 다르다 | `SettlementItemAllocationEntry` |
| 내 참여 | 로그인 회원이 이 정산에서 갖는 참여자 신분 | `SettlementParticipant.isLinkedTo(userId)` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 참여자(회원) | 앱에서 바로 내 몫을 고른다 | `ResolveMyItemAllocationUseCase` (신규) |

### State and invariants

- State transitions: 기존 품목 선택 흐름과 같다.
- Rules that must always hold: 요청자는 자기 정산의 참여자여야 한다. REQUESTED 이후에만 고칠 수 있다.
- Invalid states: 없음.

## 3. Scenarios

### Normal flow

1. 낼 정산 목록에서 품목 정산을 누른다.
2. 앱이 `GET /api/v2/settlements/{id}/my-item-selection`을 부른다.
3. 서버가 로그인 회원과 연결된 참여자를 찾아 그 사람의 몫을 돌려준다.
4. 앱이 품목 선택 화면을 연다.

### Failure flow

- Condition: 로그인 회원이 이 정산의 참여자가 아니다.
- Returned error or state transition: `SETTLEMENT_ACCESS_DENIED`

- Condition: 정산이 없다.
- Returned error or state transition: `SETTLEMENT_NOT_FOUND`

### Boundary conditions

- 생성자도 참여자라 이 경로를 쓸 수 있지만, DRAFT에서는 막힌다. 생성자는 `my-items`를 쓴다.
- 링크가 만료됐거나 없어도 동작한다. 링크와 무관한 경로다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ResolveMyItemAllocationUseCase
Command: SaveSettlementItemSelectionCommand (기존 재사용)
Result:  SettlementItemAllocationResult (기존 재사용)
Result -> Web Response DTO: SettlementItemAllocationWebResponse (기존 재사용)
```

목록 쪽은 `SettlementListItemResult`와 `SettlementListItemWebResponse`에 `allocationType`을 더한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementParticipantPort` | 내 참여자 찾기 | `SettlementMemberItemAllocationService` | 기존 Adapter |
| `LoadSettlementPort` | 정산 조회 | 위와 같음 | 기존 Adapter |

새 Port는 만들지 않는다.

### Application Policy

- Decision owned by the Policy: 없음. 참여자 확인만 서비스가 한다. 배분 규칙은 기존 Policy가 그대로 맡는다.
- Facts loaded by the Service and passed to the Policy: 해당 없음
- Why the rule is not an Entity-local invariant: 해당 없음
- Future inputs deliberately not implemented yet: 비회원 참여

### Web API

```text
GET    /api/v2/settlements/{settlementId}/my-item-selection
PUT    /api/v2/settlements/{settlementId}/my-item-selection/{itemId}
DELETE /api/v2/settlements/{settlementId}/my-item-selection/{itemId}
POST   /api/v2/settlements/{settlementId}/my-item-selection/submission
Errors: 403 SETTLEMENT_ACCESS_DENIED, 404 SETTLEMENT_NOT_FOUND
Authentication: bearerAuth

GET /api/v2/settlements  (목록)
Response 추가 필드: "allocationType": "DUTCH_PAY" | "ITEM_ALLOCATION" | null
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 목록 조회 쿼리 SELECT·GROUP BY에 `allocation_type` 추가
- Flyway migration required (`Yes` / `No`): **No**
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 참여자 확인은 `SettlementMemberItemAllocationService`,
  상태 규칙은 `SettlementItemAllocationStateLoader`와 `SettlementItemAllocationWriter`
- Behavior owned by Domain: `SettlementParticipant.isLinkedTo`
- Decisions owned by Application Policy: 배분·상한·확정은 기존 `SettlementItemAllocationPolicy`
- Flow orchestrated by Application: 참여자 확인 → 기존 Writer/Reader 재사용

### Technology and patterns

- Constraint to solve: 품목 선택 경로가 링크 토큰에만 묶여 있다.
- Selected approach: 생성자 전용 경로와 대칭인 회원 참여자용 경로를 새로 연다. 진입 구분에
  `MEMBER`를 더해 상태 규칙은 링크와 같게 두고 오류 코드만 정산 기준으로 쓴다.
- Reason for selection: 참여 문맥만 발급해서는 소용이 없다. 참여 API가 링크 토큰과 문맥을 함께
  검증하고 경로 자체가 `/settlement-links/{token}/...`이라, 앱은 여전히 링크 토큰이 필요하다.
- Alternatives considered:
  - 참여 문맥만 발급 — 위 이유로 불가능하다.
  - `my-items`를 참여자에게도 열기 — `CREATOR` 진입은 DRAFT 접근 특권이 붙어 있어, 넓히면
    참여자가 요청 전 정산에 손댈 수 있다.
  - 목록 응답에 링크 토큰 싣기 — 토큰이 목록에 노출되고 만료 관리가 어려워진다.
- Trade-offs and limits:
  - 생성자도 이 경로를 쓸 수 있다. 다만 DRAFT에서는 막히므로 생성자는 `my-items`를 써야 한다.
  - 품목 선택 경로가 링크·생성자·회원 셋으로 늘었다. 상태·오류 규칙이 진입마다 다르다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 엔드포인트 4개 추가, 목록 응답 필드 1개 추가 | `SettlementMemberItemAllocationController` |
| Application | UseCase·Service 추가, 진입 구분에 MEMBER 추가, 목록 Result 확장 | `SettlementMemberItemAllocationService` |
| Domain | 없음 | — |
| Persistence | 목록 쿼리 SELECT·GROUP BY | `SettlementJpaRepository` |
| Redis | 없음 | — |
| Security | 참여자 확인 | `SettlementMemberItemAllocationService` |
| Configuration | 없음 | — |
| Documentation | 이 Feature Plan, Swagger | `SettlementMemberItemAllocationDocs` |

Existing behavior that must remain unchanged:

- 링크 기반 참여 경로.
- `my-items`의 생성자 전용 규칙과 DRAFT 접근 특권.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports (`N/A` — 기존 Port 재사용)
- [x] Domain models and rules (`N/A` — 변경 없음)
- [x] Application Policies (`N/A` — 판단 규칙 없음)
- [x] Application Service
- [x] Output Adapters
- [x] Web Response DTO와 Swagger
- [x] Infra configuration and Bean wiring (`N/A` — 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 앱 내 정산 참여 설계 문서 추가
feat(settlement): 정산 목록 응답에 배분 방식을 담는다
feat(settlement): 참여자가 링크 없이 앱에서 자기 몫을 고르는 경로 추가
test(settlement): 회원 참여자 품목 몫 테스트 추가
```

## 8. Verification

```bash
./gradlew compileJava
./gradlew test
```

### Completion criteria

- [ ] 참여자면 자기 몫이 조회되는지 확인
- [ ] 참여자는 DRAFT에서 막히는지 확인
- [ ] 참여자가 아니면 `SETTLEMENT_ACCESS_DENIED`인지 확인
- [ ] 목록 응답에 `allocationType`이 오는지 확인

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | 경고·오류 없음 |
| 단위 테스트 (`*domain*`, `*policy*`, `*ServiceTest*`, `*ControllerTest*`) | Pass | `BUILD SUCCESSFUL` |
| 통합 테스트 | **미실행** | 로컬에 PostgreSQL·Docker가 없다. |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-02 | 설계 문서 작성 | 목록 쿼리·서비스 구현 | 목록 쿼리가 네이티브라 GROUP BY를 함께 고쳐야 한다 |
| 2026-09-02 | 참여 문맥 발급안 폐기, 회원 참여자용 경로로 전환. 목록 allocationType 추가 | 프론트 연결 | 진입 경로가 셋으로 늘어 상태 규칙이 분산됐다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-02 | 참여 문맥 발급안 폐기 | 참여 API가 링크 토큰도 함께 검증해 문맥만으로는 부를 수 없다 | 회원 전용 경로를 새로 열었다 |
| 2026-09-02 | 진입 구분에 MEMBER 추가 | CREATOR는 DRAFT 특권이 있어 넓힐 수 없다 | 경로가 셋으로 늘었다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
