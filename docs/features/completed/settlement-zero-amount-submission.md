# Feature Plan: 품목 정산 0원 제출자 상태 보정

- Status: Completed
- Last updated: 2026-09-21
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/406
- Related ADR: N/A

## 1. Purpose

### Problem

품목 정산 참여자가 선택할 품목 없이 제출하면 금액이 0원이므로 `SettlementShare`가
생성되지 않는다. 이후 정산이 `AWAITING_DEPOSIT`이 되어도 상세 조회는 share가 없는
참여자를 제출 여부와 무관하게 `expectedAmount = null`, `paymentStatus = NOT_READY`로
응답해 제출 완료자를 미제출·미납 참여자로 표시한다.

### Why now

정산 상세와 독촉 대상이 실제 제출 상태와 달라져 생성자가 잘못된 미납 인원과 진행 상황을
확인하게 된다. 0원 납부 대상은 입금할 금액이 없으므로 제출 완료 상태를 상세 계약에 반영해야
한다.

### Success criteria

- [x] 품목 정산 참여자가 0원으로 제출하고 정산이 `AWAITING_DEPOSIT`이 되면 해당 행이
  `expectedAmount = 0`, `confirmedAmount = 0`, `paymentStatus = PAID`로 반환된다.
- [x] 아직 제출하지 않은 share 없는 참여자만 `expectedAmount = null`,
  `paymentStatus = NOT_READY`로 반환된다.
- [x] share가 있는 참여자, 더치페이, 기존 요약·독촉 계산은 변경되지 않는다.

### Out of scope

- 0원 `SettlementShare` 생성 또는 품목 배분 규칙 변경
- 정산 상태 전이, 데이터베이스 스키마, HTTP 응답 구조 변경
- 앱의 표시 문구 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 없음

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 0원 제출자 | 품목을 선택하지 않고 제출을 완료해 share가 없는 참여자 | `SettlementItemSubmission` without `SettlementShare` |
| 납부 완료 | 납부할 금액이 없거나 확인 입금이 share 금액 이상인 상태 | `PaymentStatus.PAID` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자·참여자 | 정산 상세의 납부 상태를 확인한다 | `GetSettlementUseCase` |

### State and invariants

- State transitions: 없음. 조회 투영만 보정한다.
- Rules that must always hold:
  - 제출 기록이 있고 share가 없으면 0원 납부 완료로 표현한다.
  - 제출 기록이 없고 share도 없으면 아직 준비되지 않은 상태로 표현한다.
  - share가 있는 참여자의 입금 상태 계산은 기존 규칙을 유지한다.
- Invalid states: 제출하지 않은 참여자를 `PAID`로 표시하거나 0원 제출자를 `NOT_READY`로 표시하는 것

## 3. Scenarios

### Normal flow

1. 참여자가 품목을 고르지 않고 제출한다.
2. 서버가 제출 기록을 저장하고, 전원 제출이면 정산을 `AWAITING_DEPOSIT`으로 전환한다.
3. 정산 상세 조회는 share 없는 제출자를 `0원`·`PAID`로, 미제출자를 `null`·`NOT_READY`로 반환한다.

### Failure flow

- Condition: 정산 조회 권한이 없거나 정산·수금 계좌가 없다.
- Returned error or state transition: 기존 접근 거부·404 계약을 유지한다.

### Boundary conditions

- share가 없는 제출자와 share가 있는 참여자가 함께 존재해도 각자의 기존 상태 계산을 유지한다.
- 더치페이에는 품목 제출 기록이 없으므로 기존 share 기준 계산을 유지한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetSettlementUseCase.get(UUID settlementId)
Command: N/A
Result: SettlementDetailResult (필드 구조 변경 없음)
Web Request DTO -> Command: N/A
Result -> Web Response DTO: SettlementDetailWebResponse.from(result) (변경 없음)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SettlementItemAllocationStorePort` | 참여자의 품목 제출 기록 조회 | `SettlementDetailService` | 기존 정산 품목 배분 Persistence Adapter |

새 Port나 Adapter는 추가하지 않는다.

### Application Policy

- Decision owned by the Policy: 없음. 제출 기록과 share 존재 여부를 결제 행으로 조합하는 조회 투영이다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 여러 조회 결과를 결합하는 상세 응답 계산이므로 Service가 조정한다.
- Future inputs deliberately not implemented yet: N/A

### Web API

```text
GET /api/v2/settlements/{settlementId}
Request: 변경 없음
Response: 구조 변경 없음; 참여자 결제 행의 값만 보정
Errors: 변경 없음
Authentication: required
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

- Rules and their locations: `SettlementDetailService.payment`
- Behavior owned by Domain: 0원 share를 만들지 않는 기존 규칙 유지
- Decisions owned by Application Policy: 없음
- Flow orchestrated by Application: 제출 기록과 share 조회 결과를 결제 행 계산에 함께 전달한다.

### Technology and patterns

- Constraint to solve: 0원 제출자는 share가 없어 기존 결제 행 계산만으로 제출 여부를 알 수 없다.
- Selected approach: 제출 기록이 있는 share 없는 참여자를 `0L`·`PAID`로 매핑하고, 나머지 share 없는 참여자는 기존 `NOT_READY`를 유지한다.
- Reason for selection: 기존 DB·API 구조를 재사용하면서 실제 제출 여부를 응답에 반영한다.
- Alternatives considered:
  - 0원 share 생성: Domain의 양수 share invariant와 충돌하고 저장 구조를 변경한다.
  - 모든 share 없는 참여자를 `PAID` 처리: 미제출자를 납부 완료로 오판한다.
- Trade-offs and limits: 제출 기록 조회가 상세 결제 행 계산에도 필요해진다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 구조 없이 0원 제출자의 참여자 상태 값 보정 | `SettlementDetailResult.ParticipantPayment` |
| Application | 제출 기록과 share를 조합한 결제 상태 계산 | `SettlementDetailService` |
| Domain | 변경 없음 | 기존 `SettlementItemSubmission`, `SettlementShare` |
| Persistence | 기존 제출 조회 Port 재사용 | `SettlementItemAllocationStorePort` |
| Redis | 없음 | N/A |
| Security | 기존 상세 조회 권한 유지 | `SettlementDetailService` |
| Configuration | 없음 | N/A |
| Documentation | 계획·결과 기록 | 이 문서 |

Existing behavior that must remain unchanged:

- 미제출 품목 참여자의 `NOT_READY` 상태
- share가 있는 참여자의 `UNPAID`·`PARTIALLY_PAID`·`PAID` 계산
- 더치페이와 기존 paymentSummary·독촉 쿨다운 계산

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result (`N/A` — 계약 구조 변경 없음)
- [x] Required Output Ports (`N/A` — 기존 Port 재사용)
- [x] Domain models and rules (`N/A` — 기존 0원 share 규칙 유지)
- [x] Application Policies (`N/A` — 조회 투영으로 기존 Policy 재사용 없음)
- [x] Application Service
- [x] Output Adapters (`N/A` — 기존 Adapter 재사용)
- [x] Web Request/Response DTOs, Controllers, and Swagger (`N/A` — 응답 구조 변경 없음)
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 0원 제출자 상태 보정 계획 작성
fix(settlement): 0원 제출자 정산 상세 상태 보정
test(settlement): 0원 제출자 정산 상세 계약 검증
docs(settlement): 0원 제출자 상태 보정 결과 기록
```

## 8. Verification

### Commands

```bash
./gradlew test --tests 'com.amateur.howmuchbackend.application.settlement.service.SettlementDetailServiceTest'
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

정산 상세 단위 테스트는 외부 DB·Redis 없이 실행한다. 전체 테스트는 필요한 PostgreSQL·Redis 환경이 준비될 때 실행한다.

### Completion criteria

- [x] 0원 제출자와 미제출자를 동시에 포함한 `AWAITING_DEPOSIT` 상세를 검증한다.
- [x] 기존 share 결제 상태와 요약·독촉 계산 회귀를 검증한다.
- [x] 컴파일·변경 내용·문서 상태를 확인한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 상세 서비스 집중 테스트 | Passed | `./gradlew test --tests 'com.amateur.howmuchbackend.application.settlement.service.SettlementDetailServiceTest'` |
| Java 컴파일 | Passed | `./gradlew compileJava` |
| 전체 Java 테스트 | Not run | `compose.test.yml` PostgreSQL·Redis를 사용할 Docker daemon이 실행 중이지 않음 |
| 변경 검사 | Passed | `git diff --check`, `git status --short --untracked-files=all` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-21 | 이슈·기존 PR·상세 조회 흐름 분석, Flyway 불필요 확인 | 서비스 수정과 회귀 테스트 | 제출 기록을 어느 상태·조회자에게 적용할지 테스트로 고정해야 한다. |
| 2026-09-21 | 제출 기록을 상세 결제 행에 반영하고 집중 테스트·컴파일 통과 | PR 게시 | 전체 테스트는 Docker daemon 부재로 실행하지 못했다. |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-21 | 제출 기록이 있는 share 없는 참여자를 0원 `PAID`로 반환 | 0원 제출 완료와 미제출을 구분해야 함 | 0원 share를 새로 저장하지 않음 |

## 11. Completion record

- Completed on: 2026-09-21
- Final behavior: 제출 기록이 있는 share 없는 참여자는 `expectedAmount = 0`, `confirmedAmount = 0`, `paymentStatus = PAID`로 반환하고, 미제출자는 기존 `null`·`NOT_READY`를 유지한다.
- Unverified items: 전체 Java 테스트는 Docker daemon 부재로 PostgreSQL·Redis를 기동할 수 없어 실행하지 못했다.
- Remaining risks: 없음
- Follow-up work: 없음
