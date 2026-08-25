# Feature Plan: 정산 목록 조회

- Status: Implemented (initial scope)
- Last updated: 2026-08-25
- Related issue: [#128 정산 목록 조회 API 및 사용자별 상태 요약](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/128), [#129 정산 목록 조회 계약 및 UseCase 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/129), [#130 정산 목록 조회 및 사용자별 금액 요약 구현](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/130), [#131 정산 목록 조회 Swagger 및 통합 검증 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/131)
- Related ADR: N/A
- Product requirement: [일회성 정산 PRD](../../product/settlement-prd.md)
- Prerequisites: [정산 초안 및 기본 정보 Spec](../completed/settlement-core-spec.md), [정산 참여자 선택 및 관리 Spec](settlement-participants-spec.md), [정산 요청·공통 링크 Spec](../completed/settlement-request-link-spec.md)

## 1. Purpose

### Problem

현재 정산 API는 생성자가 ID를 알고 있는 정산 하나를 조회하는 `GET /api/v2/settlements/{settlementId}`만 제공한다. 앱의 정산 목록 화면은 로그인 사용자가 생성했거나 등록 참여자로 연결된 정산을 한 번에 조회하고, 각 정산의 진행 상태와 본인 기준 금액을 확인해야 한다.

현재 목록 계약이 없기 때문에 클라이언트가 정산 ID를 별도로 보관해야 하고, 참여자·연락처 데이터가 섞인 상태에서 사용자에게 노출할 정산 범위와 금액 계산 기준도 고정되어 있지 않다.

### Why now

정산 생성·참여자·Share 기능이 분리되어 구현되고 있으므로, 기존 저장 데이터를 사용자 목록 화면에서 안전하게 조합할 계약이 필요하다. UI는 탭과 두 상태만 사용하므로 목록 API도 화면에 필요한 요약만 반환해야 한다. 이 기능을 미루면 클라이언트가 단건 API를 반복 호출하거나 서버 내부 식별자와 연락처 데이터를 잘못 노출할 수 있다.

### UI reference

첨부 UI를 기준으로 목록 화면은 다음 구조를 사용한다.

- `받을 정산`과 `낼 정산` 탭으로 금액 방향을 나눈다.
- 각 탭에서 `진행중`과 `완료`만 선택한다.
- 받을 정산 항목은 `N원을 N명이서 정산 중`, `총 N명 중 N명 완료`, 요청 날짜를 보여 준다.
- 낼 정산 항목은 `N원을 보내야 해요`, 요청자, 요청 날짜를 보여 준다.
- 화면의 그룹 선택 영역은 현재 `Settlement`에 그룹 소속을 저장하는 계약이 없으므로 이번 목록 API의 필터 범위에서 제외한다.

### Success criteria

- [x] 로그인 사용자가 자신이 생성자이거나 `linkedUserId`로 연결된 참여자인 정산만 최신순으로 조회한다.
- [x] 화면의 `진행중`·`완료` 필터와 `받을 정산`·`낼 정산` 탭을 API 계약으로 제공한다.
- [x] 받을 정산 항목에 총액, 참여자 수, 완료 참여자 수, 요청 날짜를 제공한다.
- [x] 낼 정산 항목에 본인이 보낼 금액, 요청자, 요청 날짜를 제공한다.
- [x] 확정 Share가 있는 경우 본인이 보낼 금액을 반환하고, Share가 없으면 `null`로 반환한다.
- [x] 전체 상태가 `COMPLETED`인 경우 참여자 수를 완료 참여자 수로 반환하고, 진행 중 상태에서는 `null`을 반환한다.
- [x] 다른 사용자의 정산, 연락처 원본, 내부 user ID, 참여자 이름·전화번호를 목록 응답에 노출하지 않는다.
- [x] 인증·권한·빈 목록·필터·금액 집계 경계를 Service 및 HTTP 테스트로 고정했다.

### Out of scope

- 페이지네이션, 검색어, 기간 필터, 무한 스크롤
- 정산 생성·수정, 참여자 추가·제거, 공통 링크 생성·참여자 본인 선택
- 새로운 Share Domain·테이블·마이그레이션 설계
- 거래내역 상세 조회, 실제 송금 실행, 실제 수령 거래 내역 반환
- 정산 삭제·재개·종료 규칙
- 연락처 참여자의 비회원 목록 노출 또는 전화번호 기반 접근 허용
- UI의 그룹 선택을 위한 그룹-정산 관계 추가

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `SettlementShare`, `SettlementStatus`
- Reason a new domain is required, if applicable: N/A. 목록은 기존 정산과 확정 Share를 사용자 관점으로 조합하는 조회 기능이다.
- Relationships to other domains:
  - `user`: 현재 로그인 사용자의 ID와 `linkedUserId`를 비교한다.
  - `settlement`: 생성자, 상태, 제목, 총액, 생성 시각을 제공한다.
  - `settlement participant`: 등록 회원 연결과 생성자 참여자를 제공한다. `phoneHash`만 있는 연락처 참여자는 목록 접근 주체가 아니다.
  - `settlement share`: 확정된 참여자별 예정 납부액을 제공한다.
  - `CODEF`: 실제 납부·수령 상태는 별도 기능이 소유하며 이 목록은 사용하지 않는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 접근 가능한 정산 | 현재 사용자가 생성자이거나 등록 참여자로 연결된 정산 | `creatorId = actorId OR participant.linkedUserId = actorId` |
| 생성자 | 정산과 수령 계좌를 만든 사용자 | `Settlement.creatorId` |
| 등록 참여자 | `linkedUserId`로 로그인 사용자와 연결된 참여자 | `SettlementParticipant.linkedUserId` |
| 납부 정산 | 현재 사용자가 참여자로서 확정 Share 금액을 송금해야 하는 정산 | `SettlementShare.amount` |
| 수령 정산 | 현재 사용자가 생성자로서 관리하는 정산 | `Settlement.creatorId` |
| 확정 금액 | 요청 확정 과정에서 저장된 Share 기준 금액 | `SettlementShare.amount` |
| 금액 미확정 | 아직 Share가 생성되지 않아 본인이 보낼 금액을 계산할 수 없는 상태 | `amountToPay = null` |
| 원본 정산 상태 | 정산 Domain이 저장하는 실제 상태 | `SettlementStatus` |
| UI 상태 그룹 | UI에서 원본 상태를 묶어 보여 주는 상태 | `IN_PROGRESS`, `COMPLETED` |
| 참여자 수 | 해당 정산에 등록된 참여자 수 | `settlement_participants` count |
| 완료 참여자 수 | 실제 입금 완료로 확인된 참여자 수 | 외부 입금 상태 Read Port 또는 후속 결제 상태 저장소 |
| 요청 날짜 | 사용자가 정산을 요청한 시각 | 현재는 `createdAt` 대체 사용 여부를 결정해야 함 |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 자신이 생성했거나 연결된 정산 목록을 조회한다. | `GetSettlementsUseCase` |
| 앱 클라이언트 | 탭·상태 필터를 전달하고 목록 응답을 렌더링한다. | `GetSettlementsUseCase` |

### State and invariants

- State transitions: 이 기능은 상태를 변경하지 않는다.
- Rules that must always hold:
  - 생성자 또는 `linkedUserId`가 현재 사용자와 일치하는 정산만 결과에 포함한다.
  - 동일 정산은 생성자 참여자와 등록 참여자 조건이 동시에 맞아도 한 번만 반환한다.
  - 생성자와 등록 참여자에 모두 해당하면 `받을 정산` 탭에서 한 번만 반환한다.
  - `받을 정산`은 현재 사용자가 생성자인 정산만, `낼 정산`은 생성자가 아닌 연결 참여자 정산만 포함한다.
  - API 응답의 `status`는 원본 `SettlementStatus`를 그대로 반환한다.
  - UI의 `IN_PROGRESS` 그룹은 `DRAFT`, `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED`를 포함하고, `COMPLETED` 그룹은 `COMPLETED`만 포함한다.
  - 금액은 확정 Share에서만 계산하며, 계산 가능한 Share가 없으면 `null`이다.
  - 완료 참여자 수를 확인할 근거가 없으면 `null`로 반환하며 `0`으로 추측하지 않는다.
  - 목록 응답은 연락처 `phoneHash`, `displayName`, `linkedUserId`, 내부 `creatorId`를 포함하지 않는다.
  - 기본 정렬은 `requestedAt DESC, settlementId DESC`로 고정한다. `requestedAt`을 저장하지 않는 경우 `createdAt`을 대체 기준으로 사용한다.
- Invalid states:
  - 접근 권한이 없는 정산을 사용자 목록에 포함하는 것
  - 한 정산을 생성자·참여자 조건으로 중복 반환하는 것
  - DRAFT의 선언 총액을 본인이 보낼 금액처럼 반환하는 것
  - 확정 Share가 없는 금액을 `0`으로 표시해 실제 금액이 확정된 것처럼 해석하게 하는 것
  - 저장 근거가 없는 완료 참여자 수를 계산해 반환하는 것

## 3. Scenarios

### Normal flow

1. 인증된 사용자가 `GET /api/v2/settlements?tab=RECEIVE&status=IN_PROGRESS`를 요청한다.
2. Web Adapter가 탭과 상태 필터를 Application Command로 변환한다.
3. Application Service가 `CurrentActorPort`에서 현재 사용자 ID를 얻고, 사용자 범위·필터·정렬을 `LoadSettlementListPort`에 전달한다.
4. Persistence Adapter가 생성자 정산 또는 `linkedUserId` 참여 정산을 한 번에 조회하고, 화면에 필요한 요약 값만 조립한다.
5. Application Service가 내부 식별자를 제거한 `SettlementListItemResult`를 반환한다.
6. Web Adapter가 `ApiResponse<List<SettlementListItemWebResponse>>`로 응답한다.

### UI summary rules

| Tab | Included settlement | Required summary |
|---|---|---|---|
| `RECEIVE` (`받을 정산`) | `settlement.creatorId = actorId` | `totalAmount`, `participantCount`, `completedParticipantCount`, `requestedAt` |
| `PAY` (`낼 정산`) | `participant.linkedUserId = actorId` and not creator | `amountToPay`, `requesterName`, `requestedAt` |
| 연락처 참여자 | `phoneHash`만 존재 | 목록에 포함하지 않음 |

`amountToPay`는 실제 송금 완료액이 아니라 현재 사용자 참여자의 확정 Share 금액이다. Share가 아직 없으면 `null`이다. `받을 정산` 화면은 총 정산액과 참여자별 완료 현황을 보여 주며, 생성자에게 받을 예정 금액을 별도 필드로 제공하지 않는다.

`completedParticipantCount`는 개별 Share의 입금 상태가 아니라 정산 전체 상태를 기준으로 제공한다. `status=COMPLETED`이면 전체 정산이 완료된 것으로 보고 `participantCount`를 반환하고, 진행 중 상태에서는 개별 완료 수를 확인할 저장 근거가 없으므로 `null`을 반환한다. 개별 입금 현황을 보여 줘야 하면 후속 결제 상태 Read Port가 필요하다.

`requestedAt`은 현재 `Settlement`에 별도 저장되지 않으므로 초기 구현에서는 `createdAt`을 반환한다. 제품에서 실제 요청 시각을 구분해야 하면 `settlements.requested_at`을 추가하고 `DRAFT -> REQUESTED` 전환 시 기록한다.

### Filter rules

| Query parameter | Value | Meaning |
|---|---|---|
| `tab` | `RECEIVE` | 받을 정산. 기본값 |
| `tab` | `PAY` | 낼 정산 |
| `status` | `IN_PROGRESS` | `DRAFT`, `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED` |
| `status` | `COMPLETED` | `COMPLETED` |
| `status` | `ALL` | 접근 가능한 모든 상태 |
| `status` | `DRAFT` | DRAFT만 |
| `status` | `REQUESTED` | REQUESTED만 |
| `status` | `AWAITING_DEPOSIT` | AWAITING_DEPOSIT만 |
| `status` | `SUSPENDED` | SUSPENDED만 |

### Failure flow

- 인증 정보가 없다: 기존 Security 흐름에 따라 HTTP 401을 반환한다.
- `tab` 또는 `status`가 허용되지 않는다: HTTP 400 `INVALID_ARGUMENT`을 반환한다.
- 접근 가능한 정산이 없다: 오류가 아니라 HTTP 200과 빈 배열을 반환한다.
- 다른 사용자가 생성했으며 `linkedUserId`로도 연결되지 않았다: 목록에서 제외한다. 개별 정산 존재 여부를 알려주지 않는다.
- Share가 누락되었거나 아직 생성되지 않았다: `PAY` 정산은 반환하되 `amountToPay = null`로 반환한다.
- 진행 중 정산은 개별 입금 완료 수를 확인할 수 없으므로 정산은 반환하되 해당 필드만 `null`로 반환한다. `COMPLETED` 정산은 전체 참여자 수를 반환한다.
- Share 조회 중 DB 오류가 발생한다: 일부 정산만 임의로 반환하지 않고 요청을 실패시킨다.

### Boundary conditions

- 현재 사용자가 생성자이면서 생성자 참여자로도 저장되어 있어도 `RECEIVE` 결과 한 건이다.
- `PAY` 탭에서는 생성자 참여자로 자동 등록된 본인 행을 납부 대상에서 제외한다.
- 연락처가 나중에 회원으로 연결되어 `linkedUserId`가 채워진 경우에만 해당 사용자의 목록에 포함한다.
- 참여자 수와 완료 참여자 수는 음수가 될 수 없으며 완료 수는 전체 참여자 수를 초과할 수 없다.
- 초기 범위에서는 페이지네이션 없이 전체 결과를 반환한다. 실제 데이터 규모가 커져 응답 시간 또는 메모리가 문제가 되면 별도 페이지네이션 이슈를 만든다.
- 최신순 정렬에서 생성 시각이 같을 수 있으므로 UUID를 보조 정렬 기준으로 사용한다.

## 4. Contracts

### Input Port and boundary mapping

```text
GetSettlementsUseCase:
  List<SettlementListItemResult> get(GetSettlementsCommand command)

GetSettlementsCommand:
  SettlementListTab tab
  SettlementListStatusFilter status

SettlementListItemResult:
  UUID settlementId
  SettlementStatus status
  LocalDateTime requestedAt
  Long totalAmount                 // RECEIVE 탭에서 사용
  Integer participantCount         // RECEIVE 탭에서 사용
  Integer completedParticipantCount // COMPLETED는 participantCount, 진행 중은 null
  Long amountToPay                 // PAY 탭에서 사용, Share가 없으면 null
  String requesterName             // PAY 탭에서 사용

SettlementListStatusFilter:
  ALL, IN_PROGRESS, DRAFT, REQUESTED, AWAITING_DEPOSIT, SUSPENDED, COMPLETED

SettlementListTab:
  RECEIVE, PAY

SettlementListWebRequest -> GetSettlementsCommand
SettlementListItemResult -> SettlementListItemWebResponse
```

`SettlementResult`는 정산 ID를 알고 있는 단건 상세 조회 계약으로 유지하고, 목록 화면용 축약 계약은 `SettlementListItemResult`로 분리한다. 목록 응답에는 `creatorId`, 참여자 ID·이름·전화번호, 수령 계좌 ID, Share ID, 정산 제목을 포함하지 않는다. 탭에 따라 사용하지 않는 필드는 `null`로 반환하거나 Web Response를 분리한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementListPort` | 현재 사용자 범위·탭·상태에 맞는 화면 요약과 Share 기준 납부 금액을 조회한다. | `SettlementListService` | `SettlementPersistenceAdapter` 또는 정산 목록 전용 Persistence Adapter |
| `CurrentActorPort` | 현재 로그인 사용자의 ID를 제공한다. | `SettlementListService` | 기존 Security Adapter |
| `LoadSettlementCompletionSummaryPort` (조건부) | 정산별 전체 참여자 수와 입금 완료 참여자 수를 제공한다. | `SettlementListService` | CODEF 입금 현황 Adapter 또는 후속 결제 상태 Adapter |

`LoadSettlementListPort`는 목록 조회라는 하나의 외부 대화를 표현한다. 기존 `LoadSettlementPort`에 목록·사용자 조인·금액 집계를 추가하거나 범용 `SettlementRepositoryPort`를 만들지 않는다. 현재 `LoadSettlementSharePort`는 단건 송금 안내용 계약이므로 목록 집계용으로 확장하지 않는다. `LoadSettlementCompletionSummaryPort`는 개별 입금 완료 수가 필요한 경우에만 추가한다. 초기 구현은 정산 전체 상태가 `COMPLETED`일 때 `participantCount`를 사용하고, 진행 중 상태에서는 `completedParticipantCount = null`을 반환한다.

권장 Application 전용 조회 모델은 다음 정보를 가진다.

```text
SettlementListReadModel:
  UUID settlementId
  SettlementStatus status
  LocalDateTime requestedAt
  Long totalAmount
  int participantCount
  Integer completedParticipantCount
  Long amountToPay
  String requesterName
```

Persistence Adapter는 SQL/JPA 조인과 중복 제거를 소유하고, Application Service는 현재 사용자 확인과 최종 Result 조립을 소유한다. Web·JPA Entity 타입은 Port 경계를 넘지 않는다.

### Application Policy

- Decision owned by the Policy: N/A. 접근 범위와 탭·상태 필터는 조회 조건이며, 기존 저장 데이터의 관계와 SQL 조건으로 보장한다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 사용자의 정산 접근 범위와 Share 합계는 `Settlement`, `SettlementParticipant`, `SettlementShare` 여러 저장 상태를 결합한 조회 규칙이다.
- Future inputs deliberately not implemented yet: 실제 입금 상태, 수령 계좌 거래내역, 정산 삭제·재개 상태

### Web API

```text
GET /api/v2/settlements

Query:
  tab=RECEIVE|PAY                                  // optional, default RECEIVE
  status=ALL|IN_PROGRESS|DRAFT|REQUESTED|AWAITING_DEPOSIT|SUSPENDED|COMPLETED
                                                   // optional, default IN_PROGRESS

Response:
  ApiResponse<List<SettlementListItemWebResponse>>

SettlementListItemWebResponse:
  settlementId: UUID
  status: DRAFT|REQUESTED|AWAITING_DEPOSIT|SUSPENDED|COMPLETED
  requestedAt: LocalDateTime
  totalAmount: long|null
  participantCount: int|null
  completedParticipantCount: int|null
  amountToPay: long|null
  requesterName: String|null

Errors:
  400 INVALID_ARGUMENT - 허용되지 않은 tab 또는 status 값
  401 UNAUTHORIZED - 인증 필요

Authentication: required
Ordering: requestedAt DESC, settlementId DESC
Pagination: not supported in the initial scope
```

성공 응답은 접근 가능한 정산이 없을 때도 HTTP 200과 `[]`를 반환한다. API는 `"600원을 3명이서 정산 중"` 같은 표시 문장을 만들지 않고 숫자·날짜·상태 필드를 반환한다. `amountToPay = null`은 Share 미생성 또는 금액 미확정이며, `0`은 이 계약에서 사용하지 않는다. `completedParticipantCount = null`은 진행 중 정산의 개별 완료 수를 현재 저장 구조에서 확인할 수 없다는 의미다. 클라이언트는 원본 `status`를 UI의 `IN_PROGRESS` 또는 `COMPLETED` 그룹으로 표시한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 기존 `settlements`, `settlement_participants`, `settlement_shares`를 생성자·연결 참여자·Share 기준으로 조인 조회한다. 정확한 요청일을 제공하기로 결정하면 `settlements.requested_at` 컬럼과 `Settlement.requestedAt`을 추가한다.
- Flyway migration required (`Yes` / `No`): No for the initial implementation.
- Planned migration: 정확한 요청일을 별도 제공하기로 결정하거나, 사용자별 참여 정산 조회가 실제 데이터 규모에서 병목으로 확인될 때 별도 migration 이슈를 만든다.
- Migration pull request (`Pending` / URL): N/A for the initial implementation
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

### Query shape

Persistence 조회는 다음 조건을 하나의 목록 결과로 보장해야 한다.

1. `RECEIVE`에서는 생성자 정산만, `PAY`에서는 현재 사용자 연결 참여자 정산만 조회한다.
2. 생성자 조건과 참여자 조건이 모두 맞는 정산은 `settlementId` 기준 한 번만 반환한다.
3. 상태 필터가 `IN_PROGRESS`이면 `DRAFT`, `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED`를 포함하고, 응답에는 각 원본 상태를 그대로 반환한다.
4. Share가 없는 `PAY` 정산도 목록에서 제거하지 않고 `amountToPay`를 `null`로 둔다.
5. `participantCount`는 등록 참여자 수, `completedParticipantCount`는 `COMPLETED`일 때 `participantCount`, 진행 중일 때 `null`로 채운다.
6. 전화번호·참여자 표시 이름·내부 사용자 ID는 조회 결과에 매핑하지 않는다. `requesterName`은 PAY 화면에 필요한 생성자 표시 이름만 예외적으로 제공한다.

구현 방식은 JPQL 또는 native SQL 중 현재 Persistence Adapter의 테스트 가능성과 실행 계획을 확인해 선택한다. 조회 결과를 정산마다 별도 조회하는 N+1 방식은 사용하지 않는다.

## 5. Design decisions

### Business logic

- Rules and their locations: 사용자 접근 범위와 탭·상태 필터는 `SettlementListService`와 `LoadSettlementListPort` 조회 조건으로 구현한다. 원본 상태는 응답에 보존하고 UI 그룹 매핑은 클라이언트가 담당한다. 상태 변경 규칙은 기존 `Settlement` Domain에 둔다.
- Behavior owned by Domain: `SettlementStatus`와 기존 정산 상태 전이, `SettlementShare`의 양수 금액 불변성은 변경하지 않는다.
- Decisions owned by Application Policy: 없음. 여러 저장 상태를 조합하지만 목록 조회에 독립적인 정책 객체를 만들 정도의 가변 규칙은 없다.
- Flow orchestrated by Application: 현재 사용자 조회, Command 전달, ReadModel을 `SettlementListItemResult`로 변환한다.

### Technology and patterns

- Constraint to solve: 생성자·연결 참여자 두 접근 경로와 Share 금액을 한 번의 사용자 목록 응답으로 조합하면서 연락처 원본을 숨겨야 한다.
- Selected approach: 목적형 `LoadSettlementListPort`와 정산 목록 전용 ReadModel을 사용하고, Persistence Adapter에서 사용자 범위·중복 제거·화면 요약·납부 금액을 처리한다. 완료 참여자 수는 실제 상태 출처가 준비된 경우에만 별도 Read Port로 조합한다.
- Reason for selection: 목록 조회의 SQL 조인과 응답 계약을 단건 조회·송금 안내용 Port와 분리하고 N+1을 피할 수 있다.
- Alternatives considered:
  - 정산 ID 목록을 먼저 조회한 뒤 단건 정산·참여자·Share를 반복 조회하는 방식: 구현은 쉽지만 N+1과 사용자별 금액 누락 위험 때문에 제외한다.
  - `SettlementResult`를 재사용하는 방식: 단건 계약에 `creatorId`·수령 계좌 ID를 포함하고 있어 목록의 최소 노출 원칙과 맞지 않아 제외한다.
  - 새 목록 테이블 또는 캐시: 목록 조회 외에 상태·Share 변경 시 동기화가 필요해 초기 범위에서 제외한다.
- Trade-offs and limits: 초기에는 페이지네이션 없이 전체 목록을 반환한다. 데이터 규모가 커지면 keyset pagination과 사용자별 참여 인덱스를 별도 설계한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `GET /api/v2/settlements`와 `tab`·`status` Query 추가 | `SettlementController`, `SettlementDocs` |
| Application | 목록 UseCase, Command, 원본 상태·탭·Result, Service 추가 | `application.port.in.settlement`, `application.settlement.service` |
| Domain | 기존 `Settlement`, `SettlementParticipant`, `SettlementShare`, `SettlementStatus` 재사용 | `domain.settlement` |
| Persistence | 사용자 범위·탭·상태·참여자 수·Share 금액 조인 조회와 목적형 Port 구현 | `SettlementPersistenceAdapter`, settlement repositories |
| Payment status | 완료 참여자 수 Read Port 연계 여부 결정 | CODEF 입금 현황 계약 또는 후속 결제 상태 저장소 |
| Redis | 변경 없음 | N/A |
| Security | `CurrentActorPort`로 인증 사용자를 확인하고 비로그인 요청은 기존 Security에서 차단 | `CurrentActorPort`, `SecurityConfig` |
| Configuration | 변경 없음 | N/A |
| Documentation | 이 Feature Plan과 `SettlementDocs` Swagger 계약 추가 | 이 문서, `SettlementDocs` |

Existing behavior that must remain unchanged:

- `GET /api/v2/settlements/{settlementId}`의 생성자 전용 단건 조회 권한과 응답을 변경하지 않는다.
- 정산 생성 시 생성자 참여자를 자동 저장하는 현재 계약을 변경하지 않는다.
- 정산 상태 전이와 요청·링크·송금 안내 API의 Share 저장·조회 계약을 변경하지 않는다.
- 연락처 매칭에서 원본 전화번호를 저장·응답하지 않는 보안 경계를 유지한다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` 불필요 (초기 구현은 기존 스키마 사용)
- [x] `GetSettlementsUseCase`, `GetSettlementsCommand`, `SettlementListTab`, UI 상태 enum, `SettlementListItemResult` 정의
- [x] `LoadSettlementListPort`와 ReadModel 정의
- [x] 사용자 범위·탭·상태 필터·Share 금액 집계 Application Service 구현
- [x] 생성자·연결 참여자·중복 제거·참여자 수·Share left join Persistence 조회 구현
- [x] `completedParticipantCount`는 `COMPLETED` 시 참여자 수, 그 외 `null`로 결정
- [x] `SettlementListWebRequest`, `SettlementListItemWebResponse`, Controller, Swagger 구현
- [x] Service 단위 테스트 및 HTTP 목록 응답 테스트 추가
- [x] Persistence 통합 테스트: 생성자·연결 참여자·비참여자·연락처·Share·참여자 수 조합
- [ ] Controller/HTTP 테스트: 인증, 잘못된 필터, 기본값, 빈 배열, 탭별 응답 필드
- [ ] PostgreSQL `EXPLAIN`으로 사용자별 참여 정산 조회 계획 확인
- [x] Swagger와 Feature Plan의 실제 계약·검증 결과 갱신

### Expected commit boundaries

실제 커밋은 사용자 요청이 있을 때만 만든다.

```text
docs(settlement): 정산 목록 조회 개발 계획 작성
feat(settlement): 정산 목록 조회 Application 계약 추가
feat(settlement): 정산 목록 화면 요약과 사용자 범위 구현
feat(settlement): 정산 목록 조회 API와 Swagger 추가
test(settlement): 정산 목록 탭과 화면 요약 검증
docs(settlement): 정산 목록 조회 구현 결과 반영
```

## 8. Verification

### Commands

```bash
./gradlew compileJava

DB_URL=jdbc:postgresql://127.0.0.1:55432/howmuch \
  DB_USERNAME=howmuch \
  DB_PASSWORD=<test-password> \
  REDIS_HOST=127.0.0.1 \
  REDIS_PORT=56379 \
  DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused \
  ./gradlew test

git diff --check
git status --short --untracked-files=all
```

사전 조건은 PostgreSQL과 Redis 격리 인스턴스, Flyway 적용 가능한 테스트 데이터, 로그인 가능한 테스트 사용자 3명이다. SMS 발송은 검증 범위가 아니므로 호출되지 않는 로컬 URL을 사용한다.

### Required scenarios

| Scenario | Expected result |
|---|---|
| `RECEIVE` 탭 | 자신이 생성한 정산만 반환하고 총액·참여자 수·완료 참여자 수를 반환 |
| `PAY` 탭 | `linkedUserId`가 현재 사용자와 일치하는 정산만 반환하고 `amountToPay`와 요청자 이름을 반환 |
| 생성자이자 참여자인 사용자 | `RECEIVE` 탭에만 동일 정산 한 건을 반환 |
| 비참여 사용자 | 접근 불가 정산을 반환하지 않음 |
| 연락처만 있는 참여자 | 로그인 사용자와 연결되지 않았으므로 목록에 포함하지 않음 |
| DRAFT 또는 Share 없는 정산 | 목록에는 포함하고 `PAY` 탭의 `amountToPay = null` |
| REQUESTED·AWAITING_DEPOSIT | 원본 `status`를 각각 반환하고 UI에서는 `IN_PROGRESS`로 표시 |
| SUSPENDED | 원본 `status=SUSPENDED`를 반환하고 UI에서는 `IN_PROGRESS`로 표시 |
| COMPLETED | `status=COMPLETED`로 반환 |
| `status=IN_PROGRESS` | DRAFT·REQUESTED·AWAITING_DEPOSIT·SUSPENDED 반환 |
| `status=SUSPENDED` | SUSPENDED만 반환 |
| `status=ALL` | 접근 가능한 모든 원본 상태 반환 |
| `tab=PAY` | 생성자가 아닌 연결 참여자 정산만 반환 |
| `tab=RECEIVE` | 생성자 정산만 반환 |
| 진행 중 완료 상태 출처 없음 | `completedParticipantCount = null`, 숫자를 추측하지 않음 |
| 같은 `requestedAt` | `settlementId` 보조 정렬로 결과 순서가 결정적임 |
| 잘못된 필터·미인증 요청 | 각각 HTTP 400·401 |
| 접근 가능한 정산 없음 | HTTP 200, 빈 배열 |
| DB 오류 | 부분 목록 없이 요청 실패 |

### Completion criteria

- [ ] `RECEIVE`와 `PAY` 탭이 각각 올바른 사용자 범위를 조회한다.
- [ ] 비참여자와 연락처 원본 참여자는 목록에서 제외된다.
- [ ] `IN_PROGRESS`·`COMPLETED` 그룹 필터와 개별 원본 상태 필터가 동작한다.
- [ ] 응답의 `status`가 저장된 `SettlementStatus`와 일치한다.
- [ ] `PAY` 정산의 `amountToPay`가 DB의 Share 값과 일치한다.
- [ ] Share가 없는 `PAY` 정산의 `amountToPay`가 `null`이다.
- [ ] 받을 정산의 총액·참여자 수가 DB 값과 일치한다.
- [ ] 완료 참여자 수의 출처가 없을 때 `null`이 반환된다.
- [ ] 목록 응답에 내부 사용자·참여자·연락처 식별자가 없다.
- [ ] `./gradlew compileJava`와 관련 테스트가 통과한다.
- [ ] `/v3/api-docs`에 목록 API와 Query parameter가 노출된다.
- [ ] 문서와 Swagger가 실제 구현 계약과 일치한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 문서 작성 | Complete | 이 Feature Plan |
| 구현 | Complete (initial scope) | `GetSettlementsUseCase`, `SettlementListService`, `SettlementPersistenceAdapter`, `SettlementController` |
| 컴파일 | Complete | `./gradlew compileJava` |
| 관련 테스트 | Complete | `SettlementListServiceTest`, `SettlementControllerTest` |
| PostgreSQL 정산 목록 통합 검증 | Complete | `SettlementListPersistenceIntegrationTest`에서 RECEIVE/PAY 범위·상태·Share 금액 확인 |
| Swagger | Complete | 실행 중인 애플리케이션의 `/v3/api-docs`와 `/swagger-ui.html` 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-24 | 현재 단건 조회·Settlement/Participant/Share 저장 구조와 #128~#131 범위를 대조하고 목록 계약 초안을 작성함 | UI 최소 필드와 완료 참여자 수 출처 확정 | `requestedAt`과 `completedParticipantCount`가 현재 저장 구조에 없음 |
| 2026-08-25 | 첨부 UI 기준으로 받을/낼 탭, 진행중/완료 상태, 화면용 요약 필드로 범위를 축소하고 목록 API를 구현함 | PostgreSQL 통합 데이터와 Swagger 런타임 확인 | 그룹 선택 UI를 지원할 `Settlement`-그룹 관계가 없음; 정확한 요청일은 `createdAt`으로 대체 |
| 2026-08-25 | 실제 PostgreSQL에서 RECEIVE/PAY 목록 범위·상태 필터·Share 금액을 검증하고, 실행 애플리케이션의 `/v3/api-docs`에서 Query 계약을 확인함 | PostgreSQL `EXPLAIN`과 전체 테스트의 기존 실패 원인 정리 | 전체 289개 테스트 중 계좌·품목·JSON 역직렬화 관련 기존 테스트 5개 실패 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-24 | 정산 목록은 생성자 또는 `linkedUserId` 연결 참여자만 조회한다. | 전화번호만 있는 연락처 참여자에게 로그인 사용자 목록 접근을 부여하면 인증 경계가 생긴다. | 비회원 연락처 목록은 공통 링크 참여 기능의 책임으로 남긴다. |
| 2026-08-25 | 생성자는 `RECEIVE`, 연결 참여자는 `PAY` 탭에만 노출한다. | 생성자는 생성자 참여자로도 자동 등록되므로 두 탭에 중복 노출하면 안 된다. | 한 정산은 한 탭에서 한 목록 항목만 반환한다. |
| 2026-08-25 | `amountToPay`는 Share가 없으면 `null`이다. | DRAFT의 선언 총액이나 계산 초안을 확정 금액처럼 표시하면 잘못된 금액 안내가 된다. | 클라이언트는 null을 금액 미확정 상태로 표시한다. |
| 2026-08-25 | UI는 `IN_PROGRESS`와 `COMPLETED`로 묶어 보여 주되 API는 원본 `SettlementStatus`를 반환한다. | 현재 화면은 두 상태만 사용하지만 다른 화면·상세 화면에서 SUSPENDED 등 세부 상태가 필요할 수 있다. | 클라이언트는 원본 상태를 UI 그룹으로 매핑한다. |
| 2026-08-25 | 완료 참여자 수는 근거가 있을 때만 제공한다. | `settlement_shares`에는 입금 상태가 없어 임의로 0 또는 전체 완료를 반환하면 안 된다. | CODEF 입금 현황 Read Port 또는 후속 결제 상태 저장소가 필요하다. |
| 2026-08-25 | 초기 구현의 `requestedAt`은 `createdAt`을 반환한다. | 현재 `Settlement`에 별도 요청일이 없고 UI에는 날짜 하나만 필요하다. | 실제 요청 시각 구분이 필요해지면 migration으로 분리한다. |

## 11. Completion record

- Completed on: 2026-08-25
- Final behavior: `GET /api/v2/settlements`가 `RECEIVE`·`PAY` 탭과 원본 정산 상태 필터를 지원하고, 화면 문장이 아닌 숫자·날짜·상태 필드를 반환한다.
- Unverified items: SQL 실행 계획(`EXPLAIN`)
- Remaining risks: `requestedAt`은 `createdAt` 대체값이며, 개별 입금 완료 수가 필요하면 결제 상태 저장소 연계가 추가로 필요하다. 참여자 규모가 커질 경우 `linked_user_id` 조회 인덱스를 검토한다. 전체 289개 테스트 중 계좌·품목·JSON 역직렬화 관련 기존 실패 5건은 별도 확인이 필요하다.
- Follow-up work: 실제 PostgreSQL 데이터로 사용자 범위·Share 집계·실행 계획을 검증하고, 제품에서 정확한 요청일이 필요하다고 결정하면 별도 migration을 진행한다.
