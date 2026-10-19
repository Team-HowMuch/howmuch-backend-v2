# Feature Plan: 정산 목록 응답에 storeName 필드 추가

- Status: Draft
- Last updated: 2026-10-19
- Related issue: [#586 [Feature] 정산 목록 응답에 storeName 필드 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/586)
- Related ADR: N/A

## 1. Purpose

### Problem

`GET /api/v2/settlements` 목록 응답(`SettlementListItemWebResponse`)에는 현재 상호명(`storeName`)이 없다. 앱의 정산 목록과 그룹 내역에서 상호명을 표시하려면 프론트가 각 정산의 상세 API를 추가로 호출해야 하며, 이미 상세 응답(`SettlementDetailWebResponse.paymentInfo.storeName`)에서 날려주고 있는 값이라 목록 계약에 누락되어 있다.

### Why now

프론트에서 목록/그룹 내역에 상호명 표시를 요청했고, 추가 호출 없이 동일한 값을 낼 수 있다. 상세와 목록의 상호명 판정 규칙을 맞추면 중복 호출과 UI 불일치를 모두 줄일 수 있다.

### Success criteria

- [ ] `GET /api/v2/settlements` 응답에 `storeName: string | null`이 포함된다.
- [ ] `storeName`은 상세 응답과 동일한 규칙으로 계산된다.
  - 표시 대상 지출(`RECEIPT_OCR` 또는 `PAYMENT_HISTORY`)이 정확히 1건이고, 그 유일한 지출이 `RECEIPT_OCR`이면 `description`을 반환한다.
  - 그 외에는 `null`이다.
- [ ] OpenAPI schema에서 `storeName`은 nullable이며 `requiredProperties`에 포함되지 않는다.
- [ ] 기존 목록 조회의 다른 필드와 동작은 변경되지 않는다.

### Out of scope

- 상세 응답의 `paymentInfo.storeName` 계약 변경
- 영수증/결제내역 이외의 상호명 판정 규칙 추가
- 목록 쿼리 성능 최적화(별도 이슈로 분리)

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementExpense`, `SettlementExpenseSource`
- Reason a new domain is required, if applicable: N/A. 목록은 기존 정산과 지출 데이터를 사용자 관점으로 조합하는 조회 기능이다.
- Relationships to other domains:
  - `settlement`: 정산 상태, 총액, 생성 시각을 제공한다.
  - `settlement expense`: 영수증/결제내역/수동 지출의 소스, 상호명, 결제일을 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 상호명 | 영수증 지출의 `description`을 표시 이름으로 사용한 값 | `storeName` |
| 표시 대상 지출 | `RECEIPT_OCR` 또는 `PAYMENT_HISTORY` 소스의 지출 | `SettlementExpenseSource` |
| 단일 영수증 지출 | 표시 대상 지출이 1건이며 그 소스가 `RECEIPT_OCR`인 경우 | `SettlementPaymentInfo.resolve` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 정산 목록에서 상호명을 확인한다. | `GetSettlementsUseCase` |
| 앱 클라이언트 | 목록 항목에 상호명을 렌더링한다. | `GetSettlementsUseCase` |

### State and invariants

- `storeName`은 조회 시점에 계산되는 파생 값이다.
- 저장된 데이터가 동일하면 상세와 목록의 `storeName`이 항상 같아야 한다.
- 지출이 0건이거나 2건 이상이면 `storeName`은 `null`이다.
- 유일한 지출이 `PAYMENT_HISTORY`면 `storeName`은 `null`이다.

## 3. Scenarios

### Normal flow

1. 인증된 사용자가 `GET /api/v2/settlements`를 요청한다.
2. Web Adapter가 탭과 상태 필터를 Application Command로 변환한다.
3. Application Service가 `CurrentActorPort`에서 현재 사용자 ID를 얻고, `LoadSettlementListPort`를 통해 목록 ReadModel을 조회한다.
4. Persistence Adapter가 정산 목록과 함께 각 정산의 `settlement_expenses`를 조인하여 상호명을 계산한다.
5. Application Service가 ReadModel을 `SettlementListItemResult`로 변환한다.
6. Web Adapter가 `SettlementListItemWebResponse`로 매핑하여 응답한다.

### Boundary conditions

- 영수증 지출 1건: `storeName` = 해당 지출의 `description`
- 결제내역 지출 1건: `storeName` = `null`
- 영수증 + 결제내역 지출: `storeName` = `null`
- 수동 지출만 있는 경우: `storeName` = `null`
- 지출이 없는 경우: `storeName` = `null`

## 4. Contracts

### Input Port and boundary mapping

```text
GetSettlementsUseCase:
  List<SettlementListItemResult> get(GetSettlementsCommand command)

SettlementListItemResult:
  UUID settlementId
  SettlementStatus status
  LocalDateTime requestedAt
  LocalDateTime endedAt
  LocalDateTime completedAt
  Long totalAmount
  SettlementAllocationType allocationType
  Integer participantCount
  Integer payableParticipantCount
  Integer completedParticipantCount
  Long amountToPay
  String requesterName
  String storeName           // 추가
  List<GroupSummary> groups

SettlementListWebRequest -> GetSettlementsCommand
SettlementListItemResult -> SettlementListItemWebResponse
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementListPort` | 목록 조회 시 상호명을 포함한 화면 요약을 반환한다. | `SettlementListService` | `SettlementPersistenceAdapter` |

### Application Policy

- Policy 객체 추가 없이 `SettlementPaymentInfo.resolve`와 동일한 규칙을 SQL 서브쿼리로 구현한다.
- 여러 지출 중 단일 영수증인지 판정하는 규칙은 Application이 아닌 Persistence 조회 규칙으로 표현한다.

### Web API

```text
GET /api/v2/settlements

Request:
  tab=RECEIVE|PAY                                  // optional, default RECEIVE
  status=ALL|IN_PROGRESS|DRAFT|REQUESTED|AWAITING_DEPOSIT|SUSPENDED|COMPLETED
                                                   // optional, default IN_PROGRESS
  groupId=Long                                     // optional, positive

Response:
  ApiResponse<List<SettlementListItemWebResponse>>

SettlementListItemWebResponse:
  settlementId: UUID
  status: DRAFT|REQUESTED|AWAITING_DEPOSIT|SUSPENDED|COMPLETED
  requestedAt: LocalDateTime
  endedAt: LocalDateTime | null
  completedAt: LocalDateTime | null
  totalAmount: long | null
  allocationType: SettlementAllocationType | null
  participantCount: int | null
  payableParticipantCount: int | null
  completedParticipantCount: int | null
  amountToPay: long | null
  requesterName: string | null
  storeName: string | null                         // 추가
  groups: [{ groupId, groupName, groupImageUrl | null }]

Errors:
  400 INVALID_ARGUMENT - 허용되지 않은 tab 또는 status 값
  401 UNAUTHORIZED - 인증 필요

Authentication: required
Ordering: requestedAt DESC, settlementId DESC
Pagination: not supported
```

### Persistence and external integrations

- Database Entity, constraint, or query changes:
  - `SettlementJpaRepository.findAllForUser` native query에 `settlement_expenses` 조인/서브쿼리 추가
  - `SettlementListProjection`에 `getStoreName()` 추가
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- `storeName` 판정은 `SettlementPaymentInfo.resolve`와 동일하게 한다.
- Persistence Adapter가 SQL로 단일 영수증 여부를 판정하고 상호명을 조회한다.
- Application Service는 ReadModel의 `storeName`을 Result에 그대로 전달한다.

### Technology and patterns

- Constraint to solve: 기존 복잡한 목록 집계 쿼리에 추가 조인을 최소화하면서 상호명을 계산해야 한다.
- Selected approach: `settlement_expenses`를 서브쿼리로 한 번 조인하여 조건에 맞는 상호명을 계산한다.
- Reason for selection: 마이그레이션 없이 기존 스키마를 사용하고, Application 계약 변경만으로 해결한다.
- Alternatives considered:
  - 정산 엔티티에 `store_name` 컬럼 추가: 조회는 단순해지지만 마이그레이션과 쓰기 로직 변경이 필요하다.
  - Application Service에서 지출 목록을 별도로 조회: N+1 위험이 있다.
- Trade-offs and limits: 목록 쿼리가 추가 조인을 포함하므로 실행 계획 확인이 필요하다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `SettlementListItemWebResponse`에 `storeName` 추가, nullable 문서화 | `SettlementDocs`, `SettlementListItemWebResponse` |
| Application | `SettlementListItemResult`에 `storeName` 추가 | `application.port.in.settlement.result` |
| Persistence | `SettlementListReadModel`, `SettlementListProjection`, `findAllForUser` 쿼리 변경 | `SettlementPersistenceAdapter`, `SettlementJpaRepository` |
| Domain | 변경 없음 | N/A |
| Redis | 변경 없음 | N/A |
| Security | 변경 없음 | N/A |
| Configuration | 변경 없음 | N/A |
| Documentation | Feature Plan과 Swagger 계약 갱신 | 이 문서, `SettlementDocs` |
| Test | Controller, Service, Persistence, OpenAPI 테스트 갱신 | `SettlementControllerTest`, `SettlementListServiceTest`, `SettlementListPersistenceIntegrationTest`, `SettlementOpenApiIntegrationTest` |

Existing behavior that must remain unchanged:

- 기존 목록 응답의 모든 필드와 탭·필터 동작
- 상세 응답의 `paymentInfo.storeName` 계약
- 정산 지출 저장 로직

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`Required` / `N/A`): N/A
- [ ] UseCase and Application Command/Result: 기존 재사용, `SettlementListItemResult`에 `storeName` 추가
- [ ] Required Output Ports: `LoadSettlementListPort` ReadModel에 `storeName` 추가
- [ ] Domain models and rules: N/A
- [ ] Application Policies: N/A
- [ ] Application Service: `SettlementListService` Result 조립에 `storeName` 전달
- [ ] Output Adapters: `SettlementPersistenceAdapter`와 `SettlementJpaRepository` 쿼리 및 projection 변경
- [ ] Web Request/Response DTOs, Controllers, and Swagger: `SettlementListItemWebResponse` 필드 및 `SettlementDocs` 갱신
- [ ] Infra configuration and Bean wiring: N/A
- [ ] Documentation and verification results: Feature Plan 갱신, 테스트 결과 기록

### Expected commit boundaries

```text
feat(settlement): 정산 목록 응답에 storeName 추가
```

## 8. Verification

### Commands

```bash
./gradlew compileJava

./gradlew test --tests '*SettlementListServiceTest' \
  --tests '*SettlementControllerTest' \
  --tests '*SettlementListPersistenceIntegrationTest' \
  --tests '*SettlementOpenApiIntegrationTest'

git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [ ] `GET /api/v2/settlements` 응답에 `storeName`이 포함된다.
- [ ] 영수증 지출 1건일 때만 `storeName`이 반환된다.
- [ ] 결제내역, 복수 지출, 수동 지출, 지출 없음의 경우 `storeName`이 `null`이다.
- [ ] OpenAPI schema에서 `storeName`이 nullable로 표시된다.
- [ ] 상세 응답의 `paymentInfo.storeName`과 동일한 값이 반환된다.
- [ ] `./gradlew compileJava`와 관련 테스트가 통과한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Feature Plan | Complete | 이 문서 |
| Compile | Pending | `./gradlew compileJava` |
| Service/Controller/Persistence/OpenAPI tests | Pending | 관련 테스트 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-19 | GitHub issue #586 생성 및 Feature Plan 초안 작성 | 구현 및 테스트 | 목록 쿼리에 추가 조인으로 인한 성능 영향 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-19 | `storeName`을 nullable로 추가 | 상호명이 없는 경우가 다수 존재함 | 필수 필드로 두면 기존 데이터 응답이 깨짐 |
| 2026-10-19 | 상세 응답과 동일한 판정 규칙 적용 | 목록과 상세의 UI 일관성 유지 | 별도 규칙 적용 시 클라이언트 혼란 |
| 2026-10-19 | SQL 서브쿼리로 상호명 계산 | 마이그레이션 없이 기존 스키마 재사용 | 컬럼 추가 시 마이그레이션과 쓰기 로직 변경 필요 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
