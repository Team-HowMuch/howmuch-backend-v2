# Feature Plan: 앱 푸시 기반 정산 독촉

- Status: Completed
- Last updated: 2026-08-29
- Related issue: [#177](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/177)
- Related ADR: N/A

## 1. Purpose

### Problem

정산 생성자는 `AWAITING_DEPOSIT` 정산의 실제 미납자를 조회할 수 있지만, 해당 회원의 앱으로 정산 확인을 요청할 수 없다. 기존 코드에는 `users.fcm_token`과 Redis가 있으나 푸시 대상 등록 API, FCM 발송 경계, 동일 대상 30분 재요청 제한이 구현되어 있지 않다.

### Why now

STL-007과 ALM-STL-002를 충족하려면 앱 회원에게만 동작하는 수동 독촉 흐름이 필요하다. SMS·링크 발송까지 포함하면 비회원 전달 정책과 비용·개인정보 범위가 불필요하게 커지므로 앱 푸시만 먼저 제공한다.

### Success criteria

- [x] 정산 생성자가 `AWAITING_DEPOSIT` 정산의 실제 미납 회원에게 앱 푸시 독촉을 보낼 수 있다.
- [x] 동일 `settlementId`·`participantId`에는 마지막 성공 요청 후 30분 동안 다시 발송하지 않는다.
- [x] 반복·동시 요청 중 하나만 FCM 발송 권한을 얻는다.
- [x] 발송 실패 시 30분 제한을 남기지 않아 다시 요청할 수 있다.
- [x] 납부 완료·부분 납부·금액 미확정·미연결·푸시 대상 미등록 참여자는 발송하지 않는다.
- [x] 알림과 로그에 이름, 금액, 전화번호, 계좌번호를 포함하지 않는다.

### Out of scope

- 비회원 또는 미연결 참여자에 대한 SMS·링크 자동 발송
- ALM-STL-001 정산 참여 알림
- ALM-STL-003 12시간 자동 리마인드와 KST 03:00~06:00 방해 금지 시간
- 알림함, 읽음 상태, 발송 이력 영구 보관
- 다중 기기 푸시 대상 관리

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `SettlementShare`, `SettlementDeposit`, `User`
- Reason a new domain is required, if applicable: N/A. 독촉은 정산 생성자가 납부 상태와 외부 푸시를 조합하는 Application 기능이다.
- Relationships to other domains: user는 단일 앱 설치 식별자를 제공하고, settlement는 생성자 권한·대상·납부 상태를 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 독촉 대상 | 확정 금액이 있고 확인 입금액이 0이며 앱과 연결된 참여자 | `SettlementParticipant`, `SettlementShare`, confirmed amount |
| 푸시 대상 | 앱이 등록한 Firebase installation ID | 기존 `User.fcmToken` / `users.fcm_token` |
| 독촉 제한 | 같은 정산·참여자에 대한 성공 요청 후 30분 제한 | Redis `settlement:reminder:{settlementId}:{participantId}` |
| 예약 토큰 | 동시 요청 중 한 요청만 발송하도록 구분하는 임의 UUID | Redis value |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 회원 | 현재 앱 설치를 푸시 대상으로 등록하거나 해제한다. | `ManageMyPushTargetUseCase` |
| 정산 생성자 | 실제 미납 회원에게 앱 푸시 독촉을 보낸다. | `RemindSettlementParticipantUseCase` |

### State and invariants

- State transitions: 정산·참여자·Share 상태는 변경하지 않는다. Redis 독촉 예약은 `없음 -> 예약 -> 성공 시 30분 유지` 또는 `실패 시 삭제`로 바뀐다.
- Rules that must always hold:
  - 정산 생성자만 요청할 수 있다.
  - 정산은 `AWAITING_DEPOSIT`여야 한다.
  - 대상은 생성자가 아니며 `linkedUserId`와 확정 Share가 있어야 한다.
  - 확인 입금액이 0인 `UNPAID`만 대상이다. `PARTIALLY_PAID`는 금액 대조가 필요한 별도 상태이므로 제외한다.
  - 푸시 대상이 등록된 활성 회원에게만 보낸다.
  - Redis 예약을 얻은 요청만 외부 발송을 호출한다.
- Invalid states: `DRAFT`, `REQUESTED`, `SUSPENDED`, `COMPLETED`, 미연결 참여자, Share 없음, 확인 입금액 1원 이상, 푸시 대상 없음.

## 3. Scenarios

### Normal flow

1. 회원 앱이 FCM SDK 등록 콜백에서 받은 Firebase installation ID를 인증 후 등록한다.
2. 생성자가 미납 참여자 독촉 API를 호출한다.
3. Application이 정산·참여자·Share·확인 입금액·대상 회원을 조회해 권한과 미납 상태를 검증한다.
4. Redis Adapter가 `SET NX PX`로 30분 예약을 획득한다.
5. FCM Adapter가 개인정보 없는 notification/data payload를 전송한다.
6. 서버는 성공 응답을 반환하고 Redis key를 남긴다.

### Failure flow

- 생성자가 아님: `SETTLEMENT_ACCESS_DENIED`.
- 독촉 가능 상태가 아님: `SETTLEMENT_REMINDER_NOT_AVAILABLE`.
- 참여자가 없거나 미연결임: `SETTLEMENT_PARTICIPANT_NOT_FOUND` 또는 `SETTLEMENT_REMINDER_TARGET_NOT_LINKED`.
- Share가 없거나 일부·전부 납부됨: `SETTLEMENT_REMINDER_TARGET_NOT_UNPAID`.
- 푸시 대상이 없음: `SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND`.
- 30분 제한 중이거나 동시 요청이 이미 예약함: `SETTLEMENT_REMINDER_COOLDOWN_ACTIVE`.
- FCM이 요청을 거부하거나 사용할 수 없음: 예약 토큰이 일치할 때 key를 삭제하고 `SETTLEMENT_REMINDER_DELIVERY_FAILED` 또는 `SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE`.

### Boundary conditions

- Redis 예약과 조건부 삭제는 Lua script로 원자 처리한다. 실패한 이전 요청이 새 요청의 key를 삭제하지 못하도록 value를 예약 UUID로 비교한다.
- FCM이 수락한 직후 프로세스가 종료되면 실제 전달 여부와 서버 응답을 원자적으로 묶을 수 없다. FCM에 요청을 재전송하지 않고 Redis 예약을 먼저 남기는 방식으로 중복보다 일시적 재요청 제한을 선택한다.
- 30분은 Redis TTL 기준이며 서버 로컬 시간대에 의존하지 않는다.
- Firebase가 푸시 대상을 미등록 또는 잘못된 대상으로 판정하면 실패로 반환하고 예약을 해제한다. 저장값은 앱의 다음 등록 요청에서 교체한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ManageMyPushTargetUseCase.register(String firebaseInstallationId), clear()
Command: N/A, 단일 문자열과 무인자 호출
Result: void
Web Request DTO -> Command: RegisterPushTargetWebRequest.firebaseInstallationId -> String
Result -> Web Response DTO: ApiResponse<Void>

UseCase: RemindSettlementParticipantUseCase.remind(UUID settlementId, UUID participantId)
Command: N/A, 두 UUID 식별자
Result: SettlementReminderResult(remindedAt, nextAvailableAt)
Web Request DTO -> Command: N/A
Result -> Web Response DTO: SettlementReminderWebResponse
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `LoadSettlementPort` | 정산 조회 | 독촉 Service | 기존 settlement Persistence Adapter |
| 기존 `LoadSettlementParticipantPort` | 대상 참여자 조회 | 독촉 Service | 기존 participant Persistence Adapter |
| 기존 `LoadSettlementSharePort` | 확정 Share 조회 | 독촉 Service | 기존 share Persistence Adapter |
| 기존 `LoadSettlementDepositSummaryPort` | 실제 확인 입금액 조회 | 독촉 Service | 기존 deposit Persistence Adapter |
| 기존 `LoadUserPort` | 독촉 대상 회원과 FID 조회 | 독촉 Service | 기존 user Persistence Adapter |
| `UserPushTargetStorePort` | FID 소유권 이전·등록·해제 | 사용자 Service | 기존 user Persistence Adapter |
| `SettlementReminderCooldownPort` | 동시 요청 예약, 실패 예약 조건부 해제 | 독촉 Service | Redis Adapter |
| `SettlementPushSenderPort` | 앱 푸시 전달 | 독촉 Service | Firebase Admin Adapter |

### Application Policy

- Decision owned by the Policy: N/A. 생성자·상태 검증은 `Settlement`의 기존 사실과 Service의 단순 조건이며 별도 Policy를 만들 필요가 없다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 납부액·회원 푸시 대상·Redis 상태·FCM 결과는 여러 외부 경계의 사실이다.
- Future inputs deliberately not implemented yet: 비회원 전달 수단, 자동 스케줄, 다중 기기.

### Web API

```text
PUT /api/v2/user/me/push-target
Request: { "firebaseInstallationId": "..." }
Response: ApiResponse<Void>
Errors: 400 INVALID_ARGUMENT, 401 UNAUTHORIZED, 409 PUSH_TARGET_REGISTRATION_CONFLICT
Authentication: bearerAuth

DELETE /api/v2/user/me/push-target
Request: none
Response: ApiResponse<Void>
Errors: 401 UNAUTHORIZED
Authentication: bearerAuth

POST /api/v2/settlements/{settlementId}/participants/{participantId}/reminders
Request: none
Response: { "remindedAt": "UTC LocalDateTime", "nextAvailableAt": "UTC LocalDateTime" }
Errors: 401, 403, 404, 409, 422, 503
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 기존 `users.fcm_token VARCHAR(255)`과 V23 Unique constraint를 재사용한다. 등록 시 같은 FID의 기존 소유자를 해제한 뒤 현재 사용자에게 이전한다.
- Flyway migration required (`Yes` / `No`): Yes
- Planned migration: V23에서 빈 값을 `NULL`로 정리하고 중복 값은 소유자를 추정하지 않고 모두 해제한 뒤 `uk_users_fcm_token`을 추가한다.
- Migration pull request: [#197](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/197)
- Redis key, TTL, or serialization changes: `settlement:reminder:{settlementId}:{participantId}`, UUID 문자열, 30분 TTL.
- External API or message changes: Firebase Admin Java SDK `9.10.0`, FCM 단일 FID 발송. 클라이언트는 FCM FID 등록 모드를 활성화하고 등록 콜백 값을 전송해야 하며, 일반 Firebase Installations 조회값이나 deprecated registration token을 혼용하지 않는다.
- Environment variable or profile changes: 운영에서 Application Default Credentials를 위한 `GOOGLE_APPLICATION_CREDENTIALS`와 `FIREBASE_ENABLED=true`. Credential JSON은 저장소에 포함하지 않는다.

## 5. Design decisions

### Business logic

- Rules and their locations: 독촉 가능 상태와 납부액 검증은 `SettlementReminderService`, 푸시 대상 소유권 이전은 `UserPushTargetStorePort`와 user Persistence Adapter.
- Behavior owned by Domain: 기존 `Settlement.isCreatedBy`, `SettlementParticipant.isLinkedTo`.
- Decisions owned by Application Policy: N/A.
- Flow orchestrated by Application: 현재 사용자 조회, 정산/참여자/Share/입금/회원 조회, Redis 예약, FCM 발송, 실패 예약 해제.

### Technology and patterns

- Constraint to solve: 앱 회원 대상 푸시와 동일 대상 30분 중복 차단.
- Selected approach: 기존 단일 `users.fcm_token`에 Unique constraint를 추가하고 Redis `SET NX PX`, Firebase Admin SDK와 ADC를 사용한다.
- Reason for selection: 기존 컬럼과 Redis를 재사용하면서 계정 전환 시 같은 앱 설치가 두 사용자에게 남는 오발송을 DB에서 차단한다. 새 테이블·스케줄러·영구 알림 모델은 만들지 않는다.
- Alternatives considered: 독촉 이력 테이블은 영구 감사·조회 요구가 없어 제외한다. 다중 푸시 토큰 테이블은 현재 단일 세션 정책에 필요하지 않다. SMS Adapter는 앱 푸시 경계가 아니므로 재사용하지 않는다.
- Trade-offs and limits: 단일 앱 설치만 지원하고 영구 발송 이력은 없다. Firebase Admin `9.10.0`에서 registration token API가 deprecated이므로 기존 컬럼에는 FID를 저장하고 `setFid`를 사용한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 푸시 대상 등록·해제, 참여자 독촉 API 추가 | `UserController`, settlement Controller/Docs |
| Application | 푸시 대상 관리와 독촉 Service 추가 | user/settlement UseCase·Service |
| Domain | 기존 컬럼 길이에 맞춘 FID 최대 길이 | `domain.user.User` |
| Persistence | 푸시 대상 고유 제약과 기존 사용자·정산 조회 계약 재사용 | `users.fcm_token`, Flyway V23, 기존 Ports |
| Redis | 30분 원자 예약과 조건부 해제 | 신규 Redis Adapter |
| Security | 로그인 회원의 자기 푸시 대상, 생성자 전용 독촉 | `CurrentActorPort`, 기존 Spring Security |
| Configuration | Firebase Admin SDK와 ADC 조건부 활성화 | Gradle, application config, Firebase Config |
| Documentation | Feature Plan·Swagger·운영 환경 변수 | 이 문서와 `*Docs` |

Existing behavior that must remain unchanged:

- 인증 OTP SMS와 Discord/SOLAPI Adapter는 변경하지 않는다.
- 정산 상세 납부 상태와 입금 대조·완료 전이는 변경하지 않는다.
- 비회원 링크 참여 흐름은 변경하지 않는다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`Required`: `users.fcm_token` 고유 제약)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules
- [x] Application Policies (`N/A`: 별도 Policy 불필요)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 앱 푸시 정산 독촉 계획 작성
feat(notification): Firebase 앱 푸시 대상과 발송 경계 추가
feat(settlement): 미납 참여자 독촉과 30분 제한 구현
test(settlement): 앱 푸시 독촉 계약 검증
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*PushTarget*' --tests '*SettlementReminder*'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

Firebase 실제 전달 검증에는 운영 Firebase project, ADC credential, 테스트 앱의 FID가 필요하다. 자동 테스트는 Firebase SDK를 mock하여 payload·성공·실패·미등록 대상 처리를 검증하고, 운영 전 실제 기기에서 수신과 앱 라우팅을 별도로 확인한다.

### Completion criteria

- [x] Purpose의 생성자·미납 회원·30분 결과를 검증한다.
- [x] 정상·실패·동시 요청 흐름을 검증한다.
- [x] Swagger와 앱 payload 계약이 일치한다.
- [x] 실제 FCM 기기 검증 여부와 미검증 이유를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 설계와 migration 판단 | Passed | 기존 `users.fcm_token`, Redis, V1~V22와 열린 migration PR 없음 확인 후 계정 전환 오발송 방지를 위해 V23 필요 확정 |
| V23 migration | Passed | #197 병합, 중복 FID 전체 해제와 `uk_users_fcm_token` 적용 |
| 푸시 대상 소유권 이전 | Passed | PostgreSQL 16에서 기존 계정 해제 후 현재 계정 등록 통합 테스트 |
| 독촉·Redis·Firebase·Swagger | Passed | 집중 테스트와 payload/30분 조건부 해제/OpenAPI 검증 |
| 전체 회귀 | Passed | PostgreSQL 16·Redis 7에서 `./gradlew test` 500개 통과 |
| 실제 기기 수신 | Not run | Firebase credential과 테스트 앱 FID 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-29 | `origin/develop`과 #177, 정산 납부 상태, `users.fcm_token`, Redis 패턴, 열린 PR을 확인하고 앱 푸시 전용 계약 작성 | migration-only PR | FCM 외부 수락과 서버 응답은 원자적으로 묶을 수 없음 |
| 2026-08-29 | 구현 검증 중 동일 FID가 여러 사용자에게 남는 계정 전환 오발송 위험 확인 | V23 고유 제약 PR 후 중단 | 기존 중복 데이터 정리 기준 필요 |
| 2026-08-29 | #197 병합 후 FID 소유권 이전, 독촉 API, Redis 30분 제한, Firebase 발송, Swagger와 테스트 완료 | PR 생성 | 실제 기기 FCM 수신은 운영 credential과 테스트 FID 필요 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-29 | 앱 회원 FCM만 지원 | 사용자 결정과 ALM-STL-002의 앱 전달 범위 반영 | 비회원 링크·SMS는 제외 |
| 2026-08-29 | 수동 독촉과 30분 제한만 구현 | STL-007 범위에 집중 | 12시간 자동 리마인드는 ALM-STL-003 후속 |
| 2026-08-29 | 기존 `users.fcm_token`과 Redis 재사용 | 새 테이블 없이 단일 기기·임시 제한 요구 충족 | FID 고유 제약 migration은 필요 |
| 2026-08-29 | 같은 FID는 한 사용자만 소유 | 계정 전환 뒤 이전 계정 알림이 현재 기기로 오지 않게 함 | 중복 기존 값은 전부 해제하고 앱 재등록으로 복구 |
| 2026-08-29 | notification inbox를 만들지 않음 | 읽음·목록 요구가 없음 | 앱 알림함이 필요해질 때 별도 설계 |

## 11. Completion record

- Completed on: 2026-08-29
- Final behavior: 앱이 FID를 현재 계정에 등록하고, 정산 생성자가 `AWAITING_DEPOSIT`의 입금 전 회원에게 개인정보 없는 FCM 독촉을 30분 간격으로 보낸다.
- Unverified items: 실제 iOS·Android 앱의 FID로 FCM 수신과 `settlementId` 라우팅 확인.
- Remaining risks: FCM 수락 직후 서버 장애 시 실제 전달과 API 응답을 원자적으로 보장할 수 없다.
- Follow-up work: ALM-STL-001, ALM-STL-003, 비회원 수동 링크 전달, 필요 시 다중 기기 푸시 대상
