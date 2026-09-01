# Feature Plan: 앱에서 내 송금 안내 보기

- Status: In progress
- Last updated: 2026-09-02
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

정산 참여자가 **앱 안에서 어디로 얼마를 보내야 하는지 볼 수 없다.**

송금 안내는 링크 경로 하나뿐이고 링크 토큰과 참여 문맥을 함께 요구한다.

```java
@RequestMapping("/api/v2/settlement-links/{token}")
public ApiResponse<SettlementTransferInstructionWebResponse> get(
        @PathVariable String token,
        @RequestHeader(PARTICIPANT_CONTEXT_HEADER) UUID participantContext
)
```

앱의 정산 목록·상세 응답에는 토큰이 없고 앱 안에서 토큰을 얻는 경로도 없다.

계좌를 담은 다른 응답이 있는지 확인했지만 쓸 수 없다.
`GET /settlements/{id}/receiving-accounts`는 **생성자가 자기 계좌 중에 고르라고** 만든 목록이고
`maskedAccountNumber`만 준다. 가려진 번호로는 송금할 수 없고, 참여자에게 생성자의 계좌
목록을 열어줄 이유도 없다.

그래서 낼 정산이 확정(`AWAITING_DEPOSIT`)돼도 참여자는 앱에서 계좌를 못 본다. 카톡으로 받은
링크를 다시 찾아야 한다. 품목 선택은 [settlement-my-participation](./settlement-my-participation.md)에서
앱 경로를 열었는데, 그 다음 단계인 송금에서 다시 링크로 나가야 하는 상태다.

### Why now

품목 선택을 앱에서 마쳐도 송금 안내가 링크에만 있어 흐름이 끊긴다. 앱에서 낼 정산 상세를
연결할 수 없는 것도 이 값이 없어서다.

### Success criteria

- [ ] 로그인 회원이 링크 없이 자기 송금 금액과 수금 계좌를 조회한다.
- [ ] 참여자가 아니면 거부된다.
- [ ] 링크 경로의 응답과 같은 모양이라 클라이언트가 화면을 그대로 쓴다.

### Out of scope

- 비회원 참여. 링크 경로를 그대로 쓴다.
- 송금 실행. 서버는 안내만 하고 이체하지 않는다(기존과 동일).
- 계좌 스냅샷·송금 링크 생성 규칙 변경.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `SettlementShare`,
  `SettlementReceivingAccountSnapshot`
- Reason a new domain is required, if applicable: 없음. 진입 경로만 하나 더 연다.
- Relationships to other domains: 확정 시 만들어진 `SettlementShare`를 읽는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 송금 안내 | 누구에게 얼마를 어느 계좌로 보내는지 | `SettlementTransferInstructionResult` |
| 내 참여 | 로그인 회원이 이 정산에서 갖는 참여자 신분 | `SettlementParticipant.isLinkedTo(userId)` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 참여자(회원) | 앱에서 바로 송금 안내를 본다 | `ResolveMyTransferInstructionUseCase` (신규) |

### State and invariants

- State transitions: 없음. 조회만 한다.
- Rules that must always hold: 요청자는 자기 정산의 참여자여야 한다.
  정산이 `AWAITING_DEPOSIT`이어야 한다(기존 규칙 그대로).
- Invalid states: 없음.

## 3. Scenarios

### Normal flow

1. 낼 정산 목록·상세에서 확정된 정산을 연다.
2. 앱이 `GET /api/v2/settlements/{id}/my-transfer-instruction`을 부른다.
3. 서버가 로그인 회원과 연결된 참여자를 찾아 그 사람의 Share와 수금 계좌를 돌려준다.
4. 앱이 기존 송금 안내 화면을 그대로 그린다.

### Failure flow

- Condition: 로그인 회원이 이 정산의 참여자가 아니다.
- Returned error or state transition: `SETTLEMENT_ACCESS_DENIED`

- Condition: 정산이 없다.
- Returned error or state transition: `SETTLEMENT_NOT_FOUND`

- Condition: 확정 전이거나 내 Share·계좌 스냅샷이 없다.
- Returned error or state transition: `SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND`

- Condition: 지원하는 송금 링크가 없다.
- Returned error or state transition: `SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED`

### Boundary conditions

- 생성자도 참여자로 잡히면 이 경로를 쓸 수 있다. 다만 자기 계좌로 보내라는 안내가 나오므로
  클라이언트가 부르지 않는다. 서버가 막지는 않는다(기존 링크 경로도 같다).
- 링크가 만료됐거나 없어도 동작한다. 링크와 무관한 경로다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ResolveMyTransferInstructionUseCase
Command: 없음 (settlementId만)
Result:  SettlementTransferInstructionResult (기존 재사용)
Result -> Web Response DTO: SettlementTransferInstructionWebResponse (기존 재사용)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementParticipantPort` | 내 참여자 찾기 | `SettlementMemberTransferInstructionService` | 기존 Adapter |
| `LoadSettlementPort` | 정산 조회 | `SettlementTransferInstructionReader` | 기존 Adapter |
| `LoadSettlementSharePort` | 확정된 내 몫 | 위와 같음 | 기존 Adapter |
| `LoadSettlementReceivingAccountSnapshotPort` | 수금 계좌 | 위와 같음 | 기존 Adapter |
| `SettlementTransferLinkPort` | 송금 딥링크 생성 | 위와 같음 | 기존 Adapter |

새 Port는 만들지 않는다.

### Application Policy

- Decision owned by the Policy: 없음. 참여자 확인과 조회뿐이다.
- Facts loaded by the Service and passed to the Policy: 해당 없음
- Why the rule is not an Entity-local invariant: 해당 없음
- Future inputs deliberately not implemented yet: 비회원 참여

### Web API

```text
GET /api/v2/settlements/{settlementId}/my-transfer-instruction
Response: SettlementTransferInstructionWebResponse (링크 경로와 동일)
Errors: 403 SETTLEMENT_ACCESS_DENIED,
        404 SETTLEMENT_NOT_FOUND, SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND,
        422 SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED
Authentication: bearerAuth
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

- Rules and their locations: 참여자 확인은 `SettlementMemberTransferInstructionService`,
  조회·조립은 `SettlementTransferInstructionReader`(신규 추출)
- Behavior owned by Domain: `SettlementParticipant.isLinkedTo`
- Decisions owned by Application Policy: 없음
- Flow orchestrated by Application: 참여자 확인 → Reader 재사용

### Technology and patterns

- Constraint to solve: 송금 안내가 링크 토큰에만 묶여 있다.
- Selected approach: 기존 서비스에서 참여자 확인 이후 부분(`Share` 조회 → 계좌 스냅샷 →
  송금 링크 생성 → Result 조립)을 `SettlementTransferInstructionReader`로 뽑고,
  링크용·회원용 서비스가 각자 참여자를 찾아 Reader에 넘긴다.
- Reason for selection: 두 경로가 참여자를 찾는 방법만 다르고 나머지는 완전히 같다.
  `settlement-my-participation`에서 `SettlementItemAllocationReader`/`Writer`로 이미 쓴 구조라
  일관된다.
- Alternatives considered:
  - 회원용 서비스에 로직 복제 — 계좌·송금링크 규칙이 두 곳으로 갈라진다.
  - 목록·상세 응답에 계좌를 싣기 — 확정 전에도 계좌가 노출되고 응답이 무거워진다.
  - `receiving-accounts`를 참여자에게 열기 — 마스킹된 번호라 쓸 수 없고,
    생성자의 계좌 목록 전체가 노출된다.
- Trade-offs and limits:
  - 송금 안내 경로가 링크·회원 둘로 늘었다.
  - 기존 링크 서비스가 Reader에 위임하도록 바뀐다. 외부 계약은 그대로다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 엔드포인트 1개 추가 | `SettlementMemberTransferInstructionController` |
| Application | UseCase·Service 추가, 기존 서비스에서 Reader 추출 | `SettlementTransferInstructionReader` |
| Domain | 없음 | — |
| Persistence | 없음 | — |
| Redis | 없음 | — |
| Security | 참여자 확인 | `SettlementMemberTransferInstructionService` |
| Configuration | 없음 | — |
| Documentation | 이 Feature Plan, Swagger | `SettlementMemberTransferInstructionDocs` |

Existing behavior that must remain unchanged:

- 링크 기반 송금 안내 경로의 요청·응답·오류 코드.
- 확정 상태(`AWAITING_DEPOSIT`) 요구와 송금 링크 미지원 시 422.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports (`N/A` — 기존 Port 재사용)
- [x] Domain models and rules (`N/A` — 변경 없음)
- [x] Application Policies (`N/A` — 판단 규칙 없음)
- [x] Application Service
- [x] Output Adapters (`N/A` — 기존 Adapter 재사용)
- [x] Web Response DTO와 Swagger (`N/A` — 응답 DTO 재사용, Docs만 추가)
- [x] Infra configuration and Bean wiring (`N/A` — 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 앱 내 송금 안내 설계 문서 추가
refactor(settlement): 송금 안내 조회를 Reader로 분리한다
feat(settlement): 참여자가 링크 없이 앱에서 송금 안내를 본다
test(settlement): 회원 송금 안내 테스트 추가
```

## 8. Verification

```bash
./gradlew compileJava
./gradlew test
```

### Completion criteria

- [x] 참여자면 송금 안내가 조회되는지 확인
- [x] 참여자가 아니면 `SETTLEMENT_ACCESS_DENIED`인지 확인
- [x] 확정 전이면 `SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND`인지 확인
- [x] 링크 경로 응답이 그대로인지 확인

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | 경고·오류 없음 |
| 단위 테스트 (`*domain*`, `*policy*`, `*ServiceTest*`, `*ControllerTest*`) | Pass | `BUILD SUCCESSFUL` |
| 링크 경로 회귀 | Pass | `SettlementTransferInstructionServiceTest` 5케이스 그대로 통과. Reader를 실제 객체로 조립해 Port 검증을 유지했다. |
| 통합 테스트 | **미실행** | 로컬에 PostgreSQL·Docker가 없다. |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-02 | 설계 문서 작성 | Reader 추출과 회원 경로 구현 | 링크 서비스 리팩토링이 기존 오류 코드 순서를 바꾸지 않게 해야 한다 |
| 2026-09-02 | Reader 추출, 회원 경로 구현, 테스트 추가 | 프론트 연결 | 송금 안내 경로가 링크·회원 둘로 늘어 상태 규칙이 분산됐다 |
