# Feature Plan: 정산 푸시 FCM 토큰 발송 수정

- Status: Implemented
- Last updated: 2026-09-03
- Related issue: N/A
- Related ADR: N/A

## 1. Purpose

### Problem

앱과 `users.fcm_token`은 FCM registration token을 저장하지만 Firebase 발송 Adapter가 이를 Firebase Installation ID로 전달한다. Firebase가 유효한 FCM token을 `INVALID_ARGUMENT`로 거부하고, 일괄 독촉은 해당 대상을 건너뛰어 `sentCount = 0`을 반환한다.

### Why now

`AWAITING_DEPOSIT` 정산과 유효한 미납 참여자가 있어도 실제 독촉이 전송되지 않는다.

### Success criteria

- [x] 참여 요청과 독촉 푸시가 저장된 값을 FCM registration token으로 발송한다.
- [x] 기존 앱의 `firebaseInstallationId` 요청 JSON은 호환성을 유지한다.
- [x] Firebase의 만료·잘못된 token 처리 계약은 유지한다.

### Out of scope

- 공개 요청 필드 `firebaseInstallationId` 이름 변경
- DB 컬럼 이름 변경과 다중 기기 지원
- 일괄 독촉의 실패 대상 건너뛰기 정책 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`, `user`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: user의 푸시 대상 값을 settlement 알림 발송에 사용한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| FCM registration token | FCM이 앱 인스턴스에 발급한 메시지 수신 대상 값 | `users.fcm_token` |
| legacy request field | 배포된 앱 호환을 위해 유지하는 기존 JSON 이름 | `firebaseInstallationId` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 연결된 참여자에게 참여·독촉 알림을 보낸다. | 기존 정산 참여 요청·독촉 UseCase |
| 로그인 회원 | 현재 앱의 FCM token을 등록한다. | `ManageMyPushTargetUseCase` |

### State and invariants

- State transitions: 없음.
- Rules that must always hold: 저장된 FCM token은 Firebase의 registration token 대상 필드로 전달한다.
- Invalid states: 만료되거나 잘못된 token은 기존처럼 `INVALID_TARGET`으로 처리한다.

## 3. Scenarios

### Normal flow

1. 앱이 `firebaseInstallationId` 필드로 FCM registration token을 등록한다.
2. 정산 Service가 연결된 참여자의 저장 값을 조회한다.
3. Firebase Adapter가 `Message.Builder.setToken`으로 발송한다.
4. 성공한 알림은 기존처럼 발송 성공 수에 포함된다.

### Failure flow

- Condition: Firebase가 token을 `UNREGISTERED` 또는 `INVALID_ARGUMENT`로 거부한다.
- Returned error or state transition: 기존 `INVALID_TARGET` 결과를 반환하며 상태는 바뀌지 않는다.

### Boundary conditions

- 기존 요청 JSON 이름은 바꾸지 않아 배포된 클라이언트를 깨뜨리지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 정산 참여 요청·독촉 UseCase
Command: N/A
Result: 기존 SendResult
Web Request DTO -> Command: 기존 계약 유지
Result -> Web Response DTO: 기존 계약 유지
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `SettlementPushSenderPort` | 정산 푸시 발송 | 정산 Services | `FirebaseSettlementPushSenderAdapter` |

### Application Policy

- Decision owned by the Policy: N/A
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: Firebase 대상 형식은 외부 기술 경계다.
- Future inputs deliberately not implemented yet: 다중 기기 token.

### Web API

```text
PUT /api/v2/user/me/push-target
Request: { "firebaseInstallationId": "<FCM registration token>" }
Response: 기존 계약 유지
Errors: 기존 계약 유지
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음.
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: Firebase 메시지 대상을 FID에서 registration token으로 수정한다.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 기존 대상 선정·오류 처리 규칙을 유지한다.
- Behavior owned by Domain: 변경 없음.
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 변경 없음.

### Technology and patterns

- Constraint to solve: 저장값과 Firebase 메시지 대상 종류가 일치해야 한다.
- Selected approach: 공용 Firebase Adapter의 두 발송 경로에서 `setToken`을 사용한다.
- Reason for selection: 참여 요청과 독촉의 공통 원인을 한 경계에서 수정한다.
- Alternatives considered: 앱·DB를 FID로 전환하면 배포 클라이언트와 저장 데이터 마이그레이션이 필요해 제외한다.
- Trade-offs and limits: Firebase Admin SDK 9.10.0의 `setToken`은 deprecated이지만 현재 저장 계약과 일치하는 registration token API다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | JSON 이름 유지, 설명만 바로잡음 | `RegisterPushTargetWebRequest` |
| Application | 변경 없음 | 기존 Services/Ports |
| Domain | 변경 없음 | `User.MAX_FCM_TARGET_LENGTH` |
| Persistence | 변경 없음 | `users.fcm_token` |
| Redis | 변경 없음 | 기존 reminder cooldown |
| Security | 변경 없음 | 기존 인증·인가 |
| Configuration | 변경 없음 | 기존 Firebase 설정 |
| Documentation | 저장값을 FCM registration token으로 명시 | 이 문서와 클라이언트 계약 |

Existing behavior that must remain unchanged:

- 정산 대상 선정, cooldown, payload, Firebase 오류 매핑.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: schema 변경 없음)
- [x] UseCase and Application Command/Result (`N/A`: 기존 계약 재사용)
- [x] Required Output Ports (`N/A`: 기존 Port 재사용)
- [x] Domain models and rules (`N/A`: 변경 없음)
- [x] Application Policies (`N/A`: 변경 없음)
- [x] Application Service (`N/A`: 변경 없음)
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`: 변경 없음)
- [x] Documentation and verification results

### Expected commit boundaries

```text
fix(settlement): FCM 등록 토큰으로 푸시를 발송한다
```

## 8. Verification

### Commands

```bash
./gradlew test --tests '*FirebaseSettlementPushSenderAdapterTest' --tests '*ManageMyPushTargetServiceTest' --tests '*UserControllerTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| Focused tests | Pass | Adapter·Service·Controller 12 tests 통과 |
| Compile | Pass | `./gradlew compileJava` 성공 |
| Static checks | Pass | `git diff --check` 성공, 변경 파일 확인 |
| Actual device delivery | Not run | Firebase credential과 앱 기기 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-03 | 운영 데이터·로그와 Firebase message target 불일치 확인 | Adapter 수정과 테스트 | 공개 JSON 이름은 실제 의미와 다름 |
| 2026-09-03 | 공용 Adapter 수정과 관련 12 tests·compile·정적 검사 완료 | 실제 기기 수신 확인 | SDK 9.10.0의 `setToken` deprecation |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-03 | `firebaseInstallationId` JSON 이름 유지 | 배포 앱 호환 | 추후 버전 계약에서 `fcmToken`으로 전환 가능 |
| 2026-09-03 | `setToken` 사용 | 저장값이 FCM registration token임 | FID 전환은 앱·데이터 변경 필요 |

## 11. Completion record

- Completed on: 2026-09-03
- Final behavior: 기존 `firebaseInstallationId` JSON으로 등록된 FCM registration token을 참여 요청과 독촉 모두 `setToken`으로 발송한다.
- Unverified items: 실제 기기 FCM 수신.
- Remaining risks: legacy JSON 이름이 실제 의미와 다르다.
- Follow-up work: 공개 API 버전 변경 시 `fcmToken` 이름 전환 검토.
