# Feature Plan: 정산 수동 중단·재개

- Status: Completed
- Last updated: 2026-08-25
- Related issue: [#158 정산 수동 중단·재개 UseCase 및 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/158)
- Related parent issue: [#137 정산 수동 중단·재개·삭제 상태 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/137)
- Related issues: [#50 품목별 할당 미달 시 정산 자동 중단 처리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/50), [#136 정산 입금 상태 확인 및 종료 처리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/136)

## 1. 목적

### 문제

품목별 정산이 `REQUESTED` 상태에서 진행 중일 때 생성자가 참여자 입력을 잠시 멈추거나 다시 열 수 있는 UseCase와 API가 없다. 현재 자동 중단은 배분 합계가 맞지 않을 때만 `SUSPENDED`로 전환하며, 생성자의 수동 제어는 제공하지 않는다.

### 지금 필요한 이유

생성자가 잘못된 참여자 진행을 중단하거나 정산 내용을 확인한 뒤 다시 참여자 입력을 열 수 있어야 한다. 기존에는 `SUSPENDED` 정산을 새 요청으로만 처리하도록 문서화되어 있어, 이번 기능에서 재개 규칙을 명시적으로 갱신한다.

### 성공 기준

- [x] 정산 생성자만 `REQUESTED` 정산을 수동 중단할 수 있다.
- [x] 정산 생성자만 `SUSPENDED` 정산을 재개할 수 있다.
- [x] 수동 중단과 재개는 `SettlementResult`와 동일한 실제 상태를 반환한다.
- [x] 재개 후 참여자가 다시 제출할 수 있도록 기존 품목 선택은 보존하고 `settlement_item_submissions`만 초기화한다.
- [x] 자동 중단(`#50`)의 기존 `REQUESTED → SUSPENDED` 동작은 유지한다.
- [x] 반복·동시 요청은 행 잠금과 공통 상태 전이 오류로 일관되게 처리한다.
- [x] Swagger와 실제 API 응답·오류 계약이 일치한다.

### 범위 제외

- `AWAITING_DEPOSIT` 또는 `COMPLETED`의 수동 중단
- 입금 확인·정산 종료 정책(`#136`)
- 수동/자동 중단 원인의 별도 이력 테이블·감사 API
- 정산 삭제 정책(`#159`)과 통합 검증(`#160`)

## 2. 도메인 분석

### 도메인 경계

- Existing domain to reuse: `Settlement`, `SettlementItemSubmission`, `SettlementItemAllocationStorePort`
- Reason a new domain is required, if applicable: 없음. 상태 전이는 기존 Settlement가 소유한다.
- Relationships to other domains: 품목별 배분은 정산 상태와 제출 데이터를 사용한다. 입금 확인은 `AWAITING_DEPOSIT` 이후 흐름이므로 이번 변경에서 건드리지 않는다.

### 용어

| Term | Meaning | Code representation |
|---|---|---|
| 수동 중단 | 생성자가 참여 진행을 멈추는 상태 전이 | `Settlement.suspendManually()` |
| 자동 중단 | 품목별 배분 합계 오류로 시스템이 중단하는 기존 전이 | `Settlement.suspend()` |
| 재개 | 중단된 정산을 참여자 입력 상태로 되돌리는 전이 | `Settlement.resume()` |
| 제출 완료 표시 | 참여자가 더 이상 품목 선택을 변경하지 않겠다고 저장한 행 | `SettlementItemSubmission` |

### 행위자와 UseCase

| Actor | Goal | UseCase |
|---|---|---|
| 로그인한 생성자 | 진행 중인 품목별 정산을 수동 중단한다. | `SuspendSettlementUseCase` |
| 로그인한 생성자 | 중단된 정산을 다시 참여자 입력 상태로 연다. | `ResumeSettlementUseCase` |

### 상태와 불변식

- State transitions: `REQUESTED → SUSPENDED`(수동 또는 자동), `SUSPENDED → REQUESTED`(생성자 재개)
- `DRAFT`는 아직 요청되지 않았으므로 수동 중단 대상이 아니다.
- `AWAITING_DEPOSIT`는 Share가 확정된 입금 흐름이므로 이번 수동 중단 대상이 아니다.
- 재개 시 `SettlementItemSelection`은 수정 기반을 보존하고, `SettlementItemSubmission`은 참여자가 다시 선택·제출할 수 있도록 정산 단위로 삭제한다.
- `SUSPENDED`에서 `suspendManually()` 또는 `REQUESTED`에서 `resume()`을 호출하면 `INVALID_SETTLEMENT_STATUS_TRANSITION`을 반환한다.
- 자동·수동 중단은 Domain 메서드 진입점을 분리하되 저장 상태는 공통 `SUSPENDED`를 사용한다. 원인 영속화는 이번 범위에 포함하지 않는다.

## 3. 시나리오

### 정상 흐름

1. 생성자가 `POST /api/v2/settlements/{settlementId}/suspend`를 호출한다.
2. Application Service가 현재 사용자와 정산 생성자 여부를 확인하고 `findByIdForUpdate`로 정산을 잠근다.
3. Domain이 `REQUESTED → SUSPENDED`를 검증하고 저장한다.
4. 생성자가 `POST /api/v2/settlements/{settlementId}/resume`를 호출한다.
5. Application Service가 정산을 잠근 뒤 `SettlementItemSubmission`을 비우고 `SUSPENDED → REQUESTED`를 저장한다.
6. 응답은 갱신된 `SettlementWebResponse`를 반환한다.

### 실패 흐름

- 정산이 없으면 `SETTLEMENT_NOT_FOUND`.
- 생성자가 아니면 `SETTLEMENT_ACCESS_DENIED`.
- 허용되지 않은 상태에서 호출하면 `INVALID_SETTLEMENT_STATUS_TRANSITION`.
- 두 요청이 동시에 같은 정산을 변경하면 먼저 잠금을 얻은 요청만 성공하고 나머지는 최신 상태 기준의 상태 전이 오류를 반환한다.

### 경계 조건

- 제출 데이터가 없는 `SUSPENDED` 정산을 재개해도 성공하며 삭제 작업은 no-op이다.
- 일부 또는 전체 참여자의 제출 데이터가 있는 `SUSPENDED` 정산은 모든 제출 표시를 초기화한다.
- 선택 데이터는 재개 후에도 남아 참여자가 잘못된 품목 선택만 수정할 수 있다.

## 4. 계약

### Input Port

```text
SuspendSettlementUseCase.suspend(UUID settlementId) -> SettlementResult
ResumeSettlementUseCase.resume(UUID settlementId) -> SettlementResult
Web Request DTO: 없음
Result -> SettlementWebResponse
```

### Output Port

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SettlementItemAllocationStorePort.deleteAllSubmissions` | 재개 시 정산의 참여자 제출 완료 표시를 초기화 | `SettlementService` | `SettlementItemAllocationPersistenceAdapter` |

기존 `LoadSettlementPort.findByIdForUpdate`와 `SaveSettlementPort.update`를 재사용한다. 새 Port나 별도 상태 저장소는 만들지 않는다.

### Application Policy

- 별도 Policy 없음. 생성자 권한은 기존 `Settlement.isCreatedBy`, 상태 전이는 `Settlement` Domain, 제출 초기화는 재개 Application 흐름으로 처리한다.

### Web API

```text
POST /api/v2/settlements/{settlementId}/suspend
Request: 없음
Response: ApiResponse<SettlementWebResponse>, 200
Errors: 401, SETTLEMENT_ACCESS_DENIED(403), SETTLEMENT_NOT_FOUND(404), INVALID_SETTLEMENT_STATUS_TRANSITION(409)
Authentication: bearerAuth

POST /api/v2/settlements/{settlementId}/resume
Request: 없음
Response: ApiResponse<SettlementWebResponse>, 200
Errors: 401, SETTLEMENT_ACCESS_DENIED(403), SETTLEMENT_NOT_FOUND(404), INVALID_SETTLEMENT_STATUS_TRANSITION(409)
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 기존 `settlement_item_submissions` 삭제 파생 쿼리만 추가한다.
- Flyway migration required: No
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. 설계 결정

### Business logic

- Domain: `REQUESTED → SUSPENDED`, `SUSPENDED → REQUESTED`의 허용 여부와 시각 갱신
- Application: 현재 사용자·생성자 권한 확인, 행 잠금 조회, 재개 전 제출 초기화, 변경 저장
- 자동 중단: 기존 `Settlement.suspend()`와 `SettlementItemAllocationService` 호출을 유지한다.
- 수동 중단: 별도 `Settlement.suspendManually()`로 호출 경로를 분리한다.

### Technology and patterns

- Constraint to solve: 재개 뒤 기존 제출 표시가 남으면 참여자가 다시 선택할 수 없다.
- Selected approach: 기존 제출 행만 정산 단위로 삭제하고 선택 행은 보존한다.
- Reason for selection: 잘못된 선택을 전부 지우지 않고 참여자에게 수정 가능한 기준을 제공하며, 추가 스키마 없이 해결된다.
- Alternatives considered: 선택·제출 전체 삭제는 재입력 비용이 크고, 제출 상태를 별도 버전으로 추가하면 이번 기능에 불필요한 저장 구조가 생긴다.
- Trade-offs and limits: 수동/자동 중단 원인은 저장하지 않고 Domain 호출 경로로만 구분한다. 감사 이력이 필요해지면 별도 이력 설계를 추가한다.

## 6. 영향 범위

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 중단·재개 POST 2개 추가 | `SettlementController`, `SettlementDocs` |
| Application | UseCase 2개, 기존 `SettlementService` 확장 | `application.port.in.settlement`, `SettlementService` |
| Domain | 수동 중단·재개 전이 추가 | `Settlement` |
| Persistence | 제출 일괄 삭제 메서드 추가 | `SettlementItemAllocationStorePort`, `SettlementItemAllocationPersistenceAdapter` |
| Redis | 없음 | N/A |
| Security | 기존 bearer 인증·생성자 권한 재사용 | `CurrentActorPort`, `Settlement.isCreatedBy` |
| Configuration | 없음 | N/A |
| Documentation | Feature Plan 및 기존 상태 전이 문서 갱신 | `docs/features/active`, `docs/features/completed` |

기존에 유지할 동작:

- 자동 품목 배분 실패는 계속 `SUSPENDED`로 전환한다.
- `AWAITING_DEPOSIT → COMPLETED` 입금 종료 흐름은 변경하지 않는다.
- 참여자 품목 선택은 `REQUESTED`에서만 수정 가능하다.

## 7. 구현 계획

- [x] Migration-only pull request (`N/A`, 기존 상태·테이블 재사용)
- [x] `SuspendSettlementUseCase`, `ResumeSettlementUseCase`
- [x] Required Output Port 재사용 및 제출 초기화 메서드 추가
- [x] `Settlement` Domain 상태 전이
- [x] Application Policy (`N/A`, 별도 복합 사실 정책 없음)
- [x] `SettlementService`
- [x] Persistence Adapter와 Repository
- [x] Web Controller, Swagger, 응답 매핑
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] 관련 Domain·Application·Web 단위 테스트와 자동 중단 회귀 테스트
- [x] PostgreSQL 기반 제출 삭제 통합 테스트와 실제 Swagger 확인

### 예상 커밋 경계

커밋·push는 사용자가 별도로 요청하기 전까지 수행하지 않는다.

```text
feat(settlement): 정산 수동 중단·재개 상태 전이와 API 추가
test(settlement): 정산 수동 중단·재개 계약 검증 추가
```

## 8. 검증

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementTest' --tests '*SettlementServiceTest' --tests '*SettlementControllerTest'
git diff --check
git status --short --untracked-files=all
```

PostgreSQL·Redis가 필요한 통합 테스트는 서비스 상태를 확인한 뒤 실행하고, 실행하지 못하면 원인을 기록한다.

### 완료 기준

- [x] 생성자 중단·재개 정상 흐름 확인
- [x] 다른 사용자·없는 정산·잘못된 상태 오류 확인
- [x] 자동 중단 회귀 확인
- [x] 재개 후 제출 초기화·선택 보존 확인
- [x] Swagger 계약과 실제 응답 확인
- [x] 컴파일·관련 테스트·diff 검증 기록

### Results

| Item | Result | Evidence |
|---|---|---|
| Java compilation | Pass | `./gradlew compileJava` |
| Domain/Application/Web tests | Pass | `SettlementTest`, `SettlementServiceTest`, `SettlementControllerTest` |
| Automatic suspension regression | Pass | `SettlementItemAllocationServiceTest` |
| PostgreSQL persistence integration | Pass | `.env` DB 설정으로 관련 통합 테스트 통과 |
| Redis-dependent runtime verification | Pass | 전체 테스트 스위트 316건 통과 |
| Runtime Swagger | Pass | `/v3/api-docs` 200, Swagger UI redirect 후 200 |
| Diff validation | Pass | `git diff --check` |

## 9. 진행 로그

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-25 | 현재 상태 전이·자동 중단·API·잠금 흐름 조사. 마이그레이션 불필요로 결정. | UseCase·Domain·Application·Persistence·Web 구현 | 기존 완료 문서가 `SUSPENDED` 재개를 금지하므로 갱신 필요 |
| 2026-08-25 | 수동 중단·재개 API, 제출 초기화, Domain·Application·Web 테스트와 자동 중단 회귀 테스트 구현. PostgreSQL·Redis 통합 테스트와 실제 Swagger 확인 완료. | 없음 | 수동/자동 중단 원인 이력은 저장하지 않음 |

## 10. 결정 로그

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-25 | 수동 전이는 `REQUESTED → SUSPENDED → REQUESTED`로 한정 | 현재 자동 중단과 품목 선택 재진입 흐름을 그대로 활용하고, 확정 Share가 있는 `AWAITING_DEPOSIT`을 건드리지 않기 위해 | 입금 대기 중단은 #136의 입금 상태 계약 이후 별도 결정 |
| 2026-08-25 | 재개 때 제출만 삭제하고 선택은 보존 | 참여자가 잘못된 선택만 수정할 수 있게 하기 위해 | 제출·선택 전체 초기화는 UX 비용이 크므로 제외 |

## 11. 완료 기록

- Completed on: 2026-08-25
- Final behavior: 생성자는 REQUESTED 정산을 중단하고 SUSPENDED 정산을 재개할 수 있으며, 재개 시 제출만 초기화된다.
- Unverified items: 없음
- Remaining risks: 수동/자동 중단 원인의 영속 감사 이력은 제공하지 않는다.
- Follow-up work: #136 입금 상태 모델과 연결 시 수동 중단 범위를 재검토한다.
