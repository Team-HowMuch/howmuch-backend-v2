# Feature Plan: 품목 선택 대기 정산 독촉

- Status: Completed
- Last updated: 2026-09-14
- Related issue: [#374](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/374)
- Related ADR: N/A

## 1. Purpose

### Problem

품목별 정산은 참여자 전원이 품목 선택을 제출하기 전까지 `REQUESTED` 상태에 머문다. 현재 수동 독촉은 `AWAITING_DEPOSIT`에서만 허용하므로, 생성자는 아직 품목을 고르지 않은 참여자에게 앱 알림으로 선택을 요청할 수 없다.

### Why now

품목 선택 미제출은 품목별 정산 진행을 멈추게 하지만 현재 생성자에게 이를 해소할 서버 기능이 없다. 기존 독촉 API와 쿨다운을 재사용해 별도 클라이언트 계약 없이 이 구간을 지원한다.

### Success criteria

- [x] 생성자가 `REQUESTED` 품목별 정산에서 Share가 없는 연결 회원 참여자에게 수동 독촉할 수 있다.
- [x] 품목 미선택 독촉은 `<요청자>님이 정산 품목 선택을 기다리고 있어요` 문구를 저장한다.
- [x] 이미 품목을 제출해 Share가 생긴 참여자는 `REQUESTED` 수동 독촉 대상에서 제외한다.
- [x] 기존 `AWAITING_DEPOSIT` 미납 독촉 문구·쿨다운과 자동 독촉 동작을 유지한다.

### Out of scope

- `REQUESTED` 품목 미선택 참여자에 대한 자동 독촉
- 비회원 SMS 독촉
- API 경로·요청·응답 필드 변경
- 쿨다운 시간 또는 Redis 키 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`, `notification`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: `user`는 연결 회원과 요청자 이름을 제공하고, `notification`은 알림함·Outbox 저장을 담당한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 품목 선택 대기 | 품목별 정산 요청 후 참여자가 제출하지 않아 Share가 없는 상태 | `SettlementStatus.REQUESTED` + `SettlementShare == null` |
| 입금 대기 | Share가 확정됐고 확인 입금액이 0원인 상태 | `SettlementStatus.AWAITING_DEPOSIT` + confirmed amount `0` |
| 수동 독촉 | 생성자가 단건 또는 일괄 API로 요청하는 독촉 | `SettlementReminderWriter.manual` |
| 자동 독촉 | Share 확정 24시간 뒤 서버가 생성하는 독촉 | `SettlementReminderWriter.automatic` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 품목을 고르지 않은 참여자에게 선택을 요청한다. | 기존 `RemindSettlementParticipantUseCase`, `RemindAllSettlementParticipantsUseCase` |

### State and invariants

- State transitions: 독촉은 정산·참여자·Share 상태를 변경하지 않는다.
- Rules that must always hold: 생성자만 요청할 수 있고, 대상은 생성자가 아닌 활성 연결 회원이어야 하며 성공 독촉 뒤 30분 쿨다운을 적용한다.
- Invalid states: `REQUESTED`에서 Share가 이미 있는 참여자, `AWAITING_DEPOSIT`에서 Share가 없거나 입금액이 확인된 참여자, 그 밖의 정산 상태.

## 3. Scenarios

### Normal flow

1. 품목별 정산이 `REQUESTED`이고 대상 참여자는 아직 품목을 제출하지 않아 Share가 없다.
2. 생성자가 기존 단건 또는 일괄 독촉 API를 호출한다.
3. 서버가 권한·상태·대상·쿨다운을 검증하고 요청자 이름을 포함한 품목 선택 안내를 알림함과 Outbox에 저장한다.
4. 기존 응답으로 독촉 시각·다음 가능 시각 또는 생성 건수를 반환한다.

### Failure flow

- `REQUESTED`에서 Share가 이미 있음: `SETTLEMENT_REMINDER_TARGET_NOT_UNPAID`.
- 허용 상태가 아님: `SETTLEMENT_REMINDER_NOT_AVAILABLE`.
- 미연결·생성자 참여자 또는 비활성 회원: 기존 오류·일괄 건너뛰기 계약을 유지한다.

### Boundary conditions

- 일괄 독촉은 품목 미선택 참여자만 생성 건수에 포함하며, 이미 선택한 참여자는 건너뛴다.
- 수동 독촉 쿨다운은 상태와 관계없이 기존 `settlement:reminder:{settlementId}:{participantId}` 예약을 공유한다.
- 자동 독촉은 `REQUESTED`라도 Share가 있는 기존 대상과 경과 시간 문구를 유지한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 RemindSettlementParticipantUseCase.remind(settlementId, participantId)
Result: 기존 SettlementReminderResult(remindedAt, nextAvailableAt)
Result -> Web Response DTO: 기존 SettlementReminderWebResponse.from(result)

UseCase: 기존 RemindAllSettlementParticipantsUseCase.remindAll(settlementId)
Result: 기존 SettlementBulkReminderResult(sentCount, nextAvailableAt)
Result -> Web Response DTO: 기존 SettlementBulkReminderWebResponse.from(result)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 settlement/user Load Ports | 정산·참여자·Share·회원 조회 | `SettlementReminderWriter` | 기존 Persistence Adapters |
| 기존 `SettlementReminderCooldownPort` | 참여자별 30분 예약 | `SettlementReminderWriter` | 기존 Redis Adapter |
| 기존 알림 저장 경계 | 알림함과 Outbox 원자 저장 | `NotificationRecorder` | 기존 Persistence Adapters |

### Application Policy

- Decision owned by the Policy: 상태별 수동 독촉 허용과 대상의 Share·입금 조건.
- Facts loaded by the Service and passed to the Policy: 정산, 참여자, Share 존재 여부, 확인 입금액.
- Why the rule is not an Entity-local invariant: 정산·참여자·Share·입금처럼 여러 외부 조회 결과를 조합한다.
- Future inputs deliberately not implemented yet: 품목 미선택 자동 독촉 시각과 횟수.

### Web API

```text
POST /api/v2/settlements/{settlementId}/participants/{participantId}/reminders
POST /api/v2/settlements/{settlementId}/reminders
Request/Response/Authentication: 기존 계약 유지
Errors: 기존 코드 유지, 허용 상태와 오류 설명만 현행화
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음.
- Flyway migration required (`Yes` / `No`): No.
- Planned migration: N/A.
- Migration pull request (`Pending` / URL): N/A.
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 알림 저장 메시지를 상태·Share에 따라 분기한다.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 수동 상태·대상 판정은 `SettlementReminderPolicy`, 알림 문구 조립은 `SettlementReminderWriter`가 담당한다.
- Behavior owned by Domain: 기존 정산 상태와 Share 의미를 재사용하며 Domain 모델은 변경하지 않는다.
- Decisions owned by Application Policy: 수동 독촉은 `REQUESTED + Share 없음` 또는 `AWAITING_DEPOSIT + Share 있음 + 확인 입금 0원`만 허용한다.
- Flow orchestrated by Application: 조회, Policy 검증, 활성 회원 확인, 쿨다운 예약, 알림 저장, 응답 조립.

### Technology and patterns

- Constraint to solve: 수동과 자동 독촉이 현재 같은 대상 검증을 사용하지만 `REQUESTED`에서 대상 의미가 서로 다르다.
- Selected approach: 수동 대상 검증과 기존 Share 기반 자동 대상 검증을 명시적으로 분리한다.
- Reason for selection: 수동은 미선택자, 자동은 Share 확정자를 대상으로 하면서 기존 자동 독촉을 회귀 없이 보존한다.
- Alternatives considered: 공통 검증을 단순 완화하면 자동 독촉이 Share 없는 대상을 처리하며 NPE가 발생하거나, 이미 선택한 참여자에게 잘못된 품목 선택 문구를 보낼 수 있다.
- Trade-offs and limits: 기존 오류 코드 이름과 HTTP 상태는 호환성을 위해 유지하고 메시지만 두 단계에 맞게 넓힌다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 경로·응답 무변경, Swagger 허용 상태 설명 수정 | `SettlementReminderDocs` |
| Application | 수동 상태·대상 검증, 문구 분기, 상세 쿨다운 복원 | `SettlementReminderPolicy`, `SettlementReminderWriter`, `SettlementDetailService` |
| Domain | 오류 메시지 설명 현행화 | `SettlementStatusCode` |
| Persistence | 변경 없음 | 기존 Load Ports·알림 저장 |
| Redis | 변경 없음 | 기존 30분 cooldown |
| Security | 생성자 권한 유지 | `CurrentActorPort`, Policy |
| Configuration | 변경 없음 | N/A |
| Documentation | 설계·완료·검증 기록 | 이 문서 |

Existing behavior that must remain unchanged:

- `AWAITING_DEPOSIT` 미납 참여자 수동 독촉과 경과 시간 문구.
- Share 확정 24시간 뒤 자동 독촉과 중복 방지.
- 단건·일괄 API 응답 및 30분 쿨다운.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: 저장 구조 변경 없음)
- [x] UseCase and Application Command/Result (`N/A`: 기존 계약 재사용)
- [x] Required Output Ports (`N/A`: 기존 Port 재사용)
- [x] Domain models and rules (`N/A`: 모델 변경 없음)
- [x] Application Policies
- [x] Application Service
- [x] Output Adapters (`N/A`: 기존 Adapter 재사용)
- [x] Web Request/Response DTOs, Controllers, and Swagger (`DTO/Controller N/A`, Swagger 설명 수정)
- [x] Infra configuration and Bean wiring (`N/A`: 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 품목 선택 대기 독촉 설계 기록
feat(settlement): 품목 선택 대기 참여자 독촉 허용
test(settlement): 품목 선택 대기 독촉 회귀 검증
feat(settlement): 품목 선택 독촉 쿨다운 상세 복원
test(settlement): 품목 선택 독촉 쿨다운 복원 검증
docs(settlement): 품목 선택 대기 독촉 완료 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test \
  DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
  REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh test \
  --tests '*SettlementReminder*' \
  --tests '*NotificationInboxIntegrationTest' \
  --tests '*SettlementNotificationOutboxWorkerTest' \
  --tests '*FirebaseSettlementPushSenderAdapterTest'
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test \
  DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
  REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh test \
  --tests '*SettlementOpenApiIntegrationTest' \
  --tests '*SettlementControllerTest'
./gradlew test --tests '*SettlementDetailServiceTest'
git diff --check
git status --short --untracked-files=all
```

통합 테스트 전 `compose.test.yml`의 PostgreSQL·Redis를 기동하고 `scripts/test.sh`가 요구하는 환경을 사용한다. 외부 Firebase 발송은 Outbox 저장 범위와 mock Worker 검증으로 제한한다.

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| 설계 및 migration gate | Pass | 스키마·Redis·응답 변경 없음 확인 |
| 컴파일 | Pass | `./gradlew compileJava` |
| 관련 단위·통합 테스트 | Pass | Java 106건, JavaScript 35건, 실패·건너뜀 0 |
| 품목 미선택 알림 저장 | Pass | 실제 PostgreSQL에서 Share 없는 `REQUESTED` 알림 문구·배분 유형·Redis 쿨다운 확인 |
| 기존 자동 독촉 회귀 | Pass | `REQUESTED`에서 Share가 있는 자동 독촉은 기존 24시간 문구 유지 |
| 상세 쿨다운 복원 | Pass | `REQUESTED`의 연결 `NOT_READY` 참여자만 Redis TTL 후보로 조회 |
| 정적 검사 | Pass | `git diff --check`, 변경 문서 경로와 Git 상태 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 호출 흐름·기존 PR·상태별 Share 생성 시점 분석, migration 불필요 결정 | Policy와 Writer 구현 | 수동과 자동의 `REQUESTED` 대상 의미가 달라 검증 분리 필요 |
| 2026-09-14 | 수동 대상 검증·문구 분리, 단건·일괄·DB 저장 회귀 검증 | 상세 쿨다운 복원 확인 | 상세 조회가 기존 `AWAITING_DEPOSIT`만 후보로 계산하는 누락 발견 |
| 2026-09-14 | `REQUESTED + NOT_READY` 상세 쿨다운 복원과 관련 Java 106건·JavaScript 35건 검증 | PR 리뷰 | 실제 Firebase 기기 수신은 외부 자격 증명 없이 미검증 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | `REQUESTED + Share 없음`을 수동 품목 선택 독촉 대상으로 정의 | Share는 해당 참여자의 품목 제출 뒤 생성됨 | 별도 제출 조회 Port 없이 기존 사실로 정확히 판별 |
| 2026-09-14 | 자동 독촉 대상 검증은 기존 Share 기반 규칙을 유지 | 자동 독촉은 확정 Share의 24시간 경과를 기준으로 함 | 수동 검증과 무조건 공유하면 기존 자동 동작이 깨짐 |

## 11. Completion record

- Completed on: 2026-09-14
- Final behavior: 생성자는 `REQUESTED` 품목별 정산에서 Share가 없는 활성 연결 참여자에게 단건·일괄 수동 독촉을 보낼 수 있다. 알림은 요청자 이름과 품목 선택 안내를 저장하고 기존 30분 쿨다운을 응답과 상세 재조회에서 복원한다. `AWAITING_DEPOSIT` 수동 독촉과 Share 기반 자동 독촉은 기존 동작을 유지한다.
- Unverified items: 실제 Firebase 자격 증명과 기기를 이용한 푸시 수신·클라이언트 이동은 실행하지 않았다. 서버 알림함·Outbox 저장과 Firebase Adapter 단위 계약까지 검증했다.
- Remaining risks: 없음.
- Follow-up work: 품목 미선택 자동 독촉이 필요하면 Share 생성 시각 대신 제출 대기 시작 시각과 중복 정책을 별도 이슈에서 정의한다.
