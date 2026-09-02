# Feature Plan: 정산 독촉 쿨다운 응답 계약 보강

- Status: Completed
- Last updated: 2026-09-02
- Related issue: [#245](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/245), [#246](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/246), [#247](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/247)
- Related ADR: N/A

## 1. Purpose

### Problem

정산 생성자가 미납 회원 전체를 독촉한 직후 일괄 응답에는 `sentCount`만 있어 앱이 30분 카운트다운을 시작할 기준이 없다. 정산 상세 응답도 Redis의 기존 독촉 쿨다운을 반환하지 않아 화면을 나갔다 들어오면 앱이 독촉 버튼을 다시 활성화한다. 개별 독촉 응답의 `nextAvailableAt`만으로는 일괄 독촉과 화면 재진입 흐름을 완성할 수 없다.

### Why now

앱이 서버가 강제하는 참여자별 쿨다운과 같은 버튼 상태를 표시하려면 일괄 요청 직후와 상세 재조회 양쪽에서 같은 정산 단위 시각을 받아야 한다. 이 계약이 없으면 앱 표시와 Redis 상태가 어긋나 불필요한 재요청과 `sentCount = 0` 응답이 발생한다.

### Success criteria

- [x] 일괄 독촉 응답이 `sentCount`와 nullable `nextAvailableAt`을 반환한다.
- [x] 정산 상세 응답이 nullable `reminderNextAvailableAt`을 반환한다.
- [x] 정산 단위 시각은 관련 참여자별 활성 쿨다운 만료 시각 중 가장 늦은 값이다.
- [x] 활성 쿨다운이 없으면 두 필드는 `null`이다.
- [x] offset 없는 응답 시각을 UTC로 해석해야 한다는 계약이 Swagger와 문서에 드러난다.

### Out of scope

- 일괄 독촉이 미연결·부분 납부·FID 미등록·쿨다운 중 참여자를 건너뛰는 기존 정책 변경
- 참여자별 쿨다운 목록 또는 건너뛴 사유 응답
- 30분 쿨다운 길이 변경 또는 운영 설정화
- FCM 발송 방식, 재시도 정책, 영구 발송 이력 변경
- offset이 포함된 `Instant` 또는 `OffsetDateTime`으로 전체 API 시간 계약을 마이그레이션

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`
- Reason a new domain is required, if applicable: N/A. 기존 독촉 Redis 상태를 조회해 Application 결과로 조립하는 계약 보강이다.
- Relationships to other domains: 기존 `user` 푸시 대상은 독촉 대상 선정에만 사용하며 이번 범위에서 변경하지 않는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 참여자 쿨다운 | 같은 정산·참여자의 성공 독촉 후 30분 제한 | Redis `settlement:reminder:{settlementId}:{participantId}` TTL |
| 정산 다음 독촉 가능 시각 | 관련 참여자별 활성 쿨다운이 모두 풀리는 UTC 시각 | participant expiration의 `max` |
| 상세 쿨다운 복원 | 화면 재진입 시 Redis 상태로 버튼 카운트다운을 재구성 | `reminderNextAvailableAt` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 전체 독촉 직후 다음 가능 시각을 확인한다. | 기존 `RemindAllSettlementParticipantsUseCase` |
| 정산 생성자 | 상세 재조회로 기존 쿨다운을 복원한다. | 기존 `GetSettlementUseCase` |

### State and invariants

- State transitions: 기존 Redis 예약의 `없음 -> 30분 TTL 예약 -> 만료` 흐름을 변경하지 않는다.
- Rules that must always hold: Redis TTL이 쿨다운의 원본 상태이며 정산 단위 시각은 양수 TTL의 만료 시각 중 `max`다. 서버와 앱은 offset 없는 `LocalDateTime`을 UTC로 해석한다.
- Invalid states: Redis key 없음, 만료된 TTL, 영구 TTL은 활성 쿨다운으로 응답하지 않는다.

## 3. Scenarios

### Normal flow

1. 생성자가 전체 독촉을 요청한다.
2. Application이 기존 규칙으로 각 참여자를 검증하고 가능한 대상에게 순차 발송한다.
3. Application이 이번 처리에서 독촉 후보였던 참여자들의 Redis TTL을 조회한다.
4. 활성 TTL의 만료 시각 중 `max`를 `nextAvailableAt`으로 반환한다.
5. 생성자가 상세 화면을 다시 열면 현재 미납·연결 참여자의 활성 TTL을 같은 방식으로 조회해 `reminderNextAvailableAt`으로 반환한다.

### Failure flow

- 활성 쿨다운 없음: 시각 필드를 `null`로 반환한다.
- 참여자 조회 중 일부가 기존 건너뛰기 조건에 해당: 기존대로 다른 참여자를 계속 처리하고 활성 후보 쿨다운만 집계한다.
- Redis 또는 FCM 사용 불가: 기존 예외 계약을 유지하며 성공처럼 `null`을 반환하지 않는다.

### Boundary conditions

- 여러 참여자의 발송 시각과 TTL은 순차 처리 때문에 다를 수 있으므로 최댓값을 사용한다.
- `sentCount = 0`이어도 모든 독촉 후보가 이미 쿨다운 중이면 기존 활성 TTL의 최댓값을 반환한다.
- 상세 조회는 생성자이면서 `AWAITING_DEPOSIT`인 경우에만 쿨다운을 노출한다. 참여자 조회자 또는 다른 정산 상태에서는 `null`이다.
- 상세 집계 대상은 현재 `linked = true`, `paymentStatus = UNPAID`인 참여자다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: RemindAllSettlementParticipantsUseCase.remindAll(settlementId)
Result: SettlementBulkReminderResult(sentCount, nextAvailableAt)
Result -> Web Response DTO: SettlementBulkReminderWebResponse.from(result)

UseCase: GetSettlementUseCase.get(settlementId)
Result: SettlementDetailResult(..., reminderNextAvailableAt, ...)
Result -> Web Response DTO: SettlementDetailWebResponse.from(result)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `SettlementReminderCooldownPort` | 참여자별 남은 쿨다운 조회 | `SettlementReminderCooldownReader` | 기존 `SettlementReminderCooldownRedisAdapter` |

기존 Port의 예약·조건부 해제와 같은 Redis 쿨다운 capability에 조회를 추가한다. 별도 조회 Port는 만들지 않는다.

### Application Policy

- Decision owned by the Policy: N/A. 쿨다운 대상과 최댓값 집계는 두 Application Service가 공유하는 Reader가 담당한다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 쿨다운은 Redis TTL이라는 외부 상태다.
- Future inputs deliberately not implemented yet: 참여자별 상세 쿨다운과 건너뛴 사유.

### Web API

```text
POST /api/v2/settlements/{settlementId}/reminders
Request: none
Response: { "sentCount": 3, "nextAvailableAt": "2026-09-02T03:30:00" }
Errors: 기존 401, 403, 404, 409, 503
Authentication: bearerAuth, creator only

GET /api/v2/settlements/{settlementId}
Request: none
Response: 기존 상세 + { "reminderNextAvailableAt": "2026-09-02T03:30:00" }
Errors: 기존 상세 조회 계약
Authentication: bearerAuth
```

두 시각 필드는 nullable이다. 값이 있으면 offset 없는 ISO-8601 UTC `LocalDateTime`이며 앱은 UTC로 해석한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음.
- Flyway migration required (`Yes` / `No`): No.
- Planned migration: N/A. 기존 Redis key와 TTL만 조회한다.
- Migration pull request (`Pending` / URL): N/A.
- Redis key, TTL, or serialization changes: 없음. 기존 key의 남은 TTL만 읽는다.
- External API or message changes: 없음.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 양수 Redis TTL만 활성 상태로 취급하고 만료 시각의 최댓값을 계산하는 규칙은 `SettlementReminderCooldownReader`에 둔다.
- Behavior owned by Domain: 기존 정산 생성자·상태·납부 상태 규칙을 재사용한다.
- Decisions owned by Application Policy: N/A.
- Flow orchestrated by Application: 일괄 Service는 독촉 후보를 수집한 뒤 쿨다운을 집계하고, 상세 Service는 생성자에게 보이는 현재 미납·연결 참여자를 집계한다.

### Technology and patterns

- Constraint to solve: Redis value는 예약 UUID이고 만료 절대 시각을 저장하지 않는다.
- Selected approach: `PTTL`에 해당하는 기존 `StringRedisTemplate.getExpire(..., MILLISECONDS)`로 남은 시간을 읽고 Application UTC 현재 시각에 더한다.
- Reason for selection: key 형식·value·TTL을 바꾸지 않고 현재 Redis 상태를 원본으로 사용할 수 있다.
- Alternatives considered: 발송 시각 영구 저장은 조회 요구보다 범위가 크고 migration이 필요하다. `KEYS`/`SCAN`은 이미 알고 있는 참여자 ID를 다시 전역 탐색하므로 사용하지 않는다.
- Trade-offs and limits: 상세 조회에 현재 관련 참여자 수만큼 TTL 조회가 추가된다. 참여자 규모와 30분 임시 key 특성상 우선 단순 조회를 사용하고 필요 시 pipeline으로 최적화한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 일괄·상세 nullable UTC 시각 필드 추가 | reminder/detail Results와 Web Responses |
| Application | 공유 쿨다운 Reader와 대상 집계 | reminder/detail Services |
| Domain | 변경 없음 | 기존 settlement 상태·납부 상태 |
| Persistence | 변경 없음 | DB schema 불변 |
| Redis | 기존 key의 남은 TTL 조회 | cooldown Port/Adapter |
| Security | 변경 없음 | 기존 creator/detail 권한 |
| Configuration | 변경 없음 | 기존 Redis 설정 |
| Documentation | Feature Plan, Swagger, 기존 클라이언트 계약 현행화 | 이 문서와 reminder docs |

Existing behavior that must remain unchanged:

- 개별 독촉의 `remindedAt`, `nextAvailableAt` 계약과 30분 계산.
- 일괄 독촉의 `sentCount`, 대상별 건너뛰기, FCM 실패 처리.
- 정산 상세의 viewer별 참여자 노출과 납부 집계.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: DB·Redis schema 변경 없음)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules (`N/A`: 기존 Domain 규칙 재사용)
- [x] Application Policies (`N/A`: 공유 Application Reader 사용)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`: 기존 Redis Bean 재사용)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 독촉 쿨다운 응답 계약 문서화
feat(settlement): 독촉 쿨다운 응답 계약 보강
test(settlement): 독촉 쿨다운 응답 복원 검증
docs(settlement): 독촉 쿨다운 계약 완료 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementReminderServiceTest' --tests '*SettlementReminderCooldownReaderTest' --tests '*SettlementDetailServiceTest' --tests '*SettlementReminderControllerTest' --tests '*SettlementControllerTest' --tests '*SettlementOpenApiIntegrationTest'
./gradlew test --tests '*SettlementReminderCooldownRedisIntegrationTest'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

Redis 통합 테스트와 전체 테스트는 로컬 PostgreSQL·Redis 및 필수 환경 변수가 준비된 경우 실행한다. 준비되지 않았으면 실패 원인과 미검증 범위를 기록한다.

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| Migration 판단 | Passed | 기존 Redis key TTL 조회만 추가하므로 Flyway 불필요 |
| Compile | Passed | `./gradlew compileJava`, `./gradlew compileTestJava` |
| Focused tests | Passed | Reader·Application·Redis·Web·OpenAPI 집중 테스트 통과 |
| Full regression | Environment blocked | 격리 schema에서 583개 실행, 테스트 DB 사용자에게 Flyway schema history 생성 권한이 없어 Spring Context 기반 65개 실패 |
| Static checks | Passed | `git diff --check`, status 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-02 | 최신 `origin/develop`과 #177 구현을 확인하고 부모 #245, sub-issue #246·#247 생성 및 연결 | Result·Port·Reader 구현 | offset 없는 `LocalDateTime`을 앱 로컬 시간으로 파싱하면 KST에서 9시간 오차 |
| 2026-09-02 | Redis TTL Reader, 일괄·상세 Result와 Web 계약, Swagger와 집중 테스트 완료 | PR 생성 | 전체 테스트용 임시 schema에 애플리케이션 DB 사용자 권한이 없어 전체 회귀 미완료 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-02 | 30분 쿨다운과 대상별 건너뛰기 유지 | 기존 #177 기획·구현 계약이며 이번 문제는 응답 상태 부재 | 길이·선정 정책 변경은 별도 기능 |
| 2026-09-02 | 정산 단위 시각은 참여자 만료 시각의 `max` | 전체가 다시 가능해지는 시점을 버튼에 표시 | 한 명이라도 가능해지는 시점은 `min`이지만 요청 UX와 다름 |
| 2026-09-02 | offset 없는 UTC `LocalDateTime` 유지 | 기존 개별 독촉 및 저장소 시간 계약과 호환 | 향후 전 API `Instant` 전환은 별도 ADR/마이그레이션 |
| 2026-09-02 | 기존 Port에 TTL 조회 추가 | 예약·해제·조회가 같은 쿨다운 capability | 기술별 조회 Port 분리는 과도함 |

## 11. Completion record

- Completed on: 2026-09-02
- Final behavior: 전체 독촉 직후 `nextAvailableAt`을 반환하고, 상세 재조회에서 `reminderNextAvailableAt`으로 Redis 쿨다운을 복원한다. 두 값은 참여자별 활성 TTL 만료 시각의 `max`이며 offset 없는 UTC다.
- Unverified items: 권한이 완비된 최신 migration 전용 PostgreSQL에서 전체 583개 회귀 테스트 재실행.
- Remaining risks: 상세 조회는 현재 미납·연결 참여자 수만큼 Redis TTL을 조회한다.
- Follow-up work: 실제 지연이 측정될 때만 Redis pipeline 조회를 검토한다.
