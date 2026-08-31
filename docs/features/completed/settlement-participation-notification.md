# Feature Plan: 정산 참여 알림

- Status: Completed
- Last updated: 2026-08-31
- Related issue: ALM-STL-001
- Related ADR: N/A

## 1. Purpose

### Problem

정산 생성자가 `POST /api/v2/settlements/{settlementId}/request`로 정산을 확정해도 연결된 참여자 앱에는 새 정산이 도착했다는 알림이 가지 않는다. 참여자는 앱을 직접 열어 목록을 확인하거나 공유 링크를 별도로 전달받아야 한다.

### Why now

ALM-STL-001은 정산 요청이 완료된 대상자에게 정산 참여 알림을 발송하도록 요구한다. 기존 앱 푸시 대상 등록과 Firebase 발송 기반은 있으므로 별도 알림 저장소 없이 요청 완료 시점을 연결한다.

### Success criteria

- [x] 정산 요청 트랜잭션이 커밋된 뒤 활성 회원이며 Firebase installation ID가 등록된 참여자에게 한 번씩 알림을 보낸다.
- [x] 생성자, 비회원 참여자, 탈퇴 회원, 푸시 대상 미등록 회원은 발송 대상에서 제외한다.
- [x] 알림에 `type`, `settlementId`, `shareLink`를 포함하고 이름·금액·전화번호·계좌번호는 포함하지 않는다.
- [x] Firebase 실패가 이미 완료된 정산 요청 응답과 상태를 실패로 바꾸지 않는다.

### Out of scope

- 비회원 SMS 또는 링크 자동 발송
- 알림함, 읽음 상태, 발송 이력 저장, 재시도 큐
- ALM-STL-002 독촉 및 ALM-STL-003 예약 알림 변경
- 정산 참여 완료 후 생성자에게 보내는 별도 납부 완료 알림

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `User`
- Reason a new domain is required, if applicable: N/A. 정산 요청 완료와 외부 푸시 전달을 조합하는 Application 기능이다.
- Relationships to other domains: settlement가 요청 완료 시점과 대상 사용자를 제공하고 user가 Firebase installation ID를 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 참여 알림 대상 | 생성자가 아니며 활성 회원과 푸시 대상이 연결된 정산 참여자 | `SettlementParticipant.linkedUserId`, `User.active`, `User.fcmToken` |
| 공유 링크 | 정산 요청 시 발급되어 앱의 참여 화면으로 진입하는 URL | `IssuedSettlementLinkToken.url` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 정산을 요청하고 연결 참여자에게 참여 알림을 보낸다. | 기존 `RequestSettlementUseCase` |
| 연결 참여자 | 알림을 눌러 해당 정산 참여 흐름으로 진입한다. | 기존 링크 해석·참여 UseCase |

### State and invariants

- State transitions: 기존 `DRAFT -> REQUESTED` 또는 `DRAFT -> AWAITING_DEPOSIT`만 사용한다.
- Rules that must always hold: DB 커밋 전에는 알림을 보내지 않고, 같은 사용자 ID는 한 번만 발송하며, 푸시 실패는 정산 상태를 되돌리지 않는다.
- Invalid states: 요청 검증 또는 저장이 실패했는데 참여 알림이 발송되는 상태.

## 3. Scenarios

### Normal flow

1. 생성자가 기존 정산 요청 API를 호출한다.
2. Application이 참여자·배분·계좌를 검증하고 정산 링크와 Share를 저장한다.
3. 트랜잭션이 커밋된다.
4. Application이 연결 참여자 사용자를 일괄 조회하고 발송 가능한 FID에 Firebase 알림을 보낸다.
5. API 응답 계약은 기존과 동일하게 정산 요청 결과를 반환한다.

### Failure flow

- 정산 요청 검증 또는 저장 실패: 기존 오류를 반환하며 알림을 보내지 않는다.
- 푸시 대상 없음 또는 비활성 사용자: 해당 참여자만 건너뛴다.
- Firebase 비활성·전송 실패·잘못된 FID: 실패를 기록하고 다른 대상 발송을 계속하며 정산 요청은 성공으로 유지한다.

### Boundary conditions

- 한 사용자가 참여자 목록에 중복 연결되어 있어도 사용자 ID 기준 한 번만 발송한다.
- 현재 트랜잭션 동기화가 없는 단위 실행에서는 즉시 같은 발송 로직을 실행한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 RequestSettlementUseCase.request(UUID settlementId)
Command: N/A
Result: 기존 SettlementRequestResult
Web Request DTO -> Command: N/A
Result -> Web Response DTO: 기존 SettlementRequestWebResponse
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `LoadUserPort` | 대상 회원과 FID 일괄 조회 | `SettlementRequestService` | `UserPersistenceAdapter` |
| `SettlementPushSenderPort` | 정산 참여·독촉 앱 푸시 발송 | 정산 요청·독촉 Service | 기존 Firebase Adapter를 일반화한 `FirebaseSettlementPushSenderAdapter` |

### Application Policy

- Decision owned by the Policy: N/A. 대상 조건은 생성자 제외, 연결 여부, 활성 상태, FID 존재 여부의 단순 필터다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 참여자·회원·외부 Firebase 상태를 결합한다.
- Future inputs deliberately not implemented yet: 비회원 전달 수단, 영구 발송 이력, 재시도 정책.

### Web API

```text
POST /api/v2/settlements/{settlementId}/request
Request: none
Response: 기존 SettlementRequestWebResponse
Errors: 기존 정산 요청 오류와 동일
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음. 기존 `users.fcm_token`과 정산 링크를 재사용한다.
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: Firebase data payload `type=SETTLEMENT_PARTICIPATION`, `settlementId`, `shareLink` 추가.
- Environment variable or profile changes: 없음. 기존 Firebase 설정을 재사용한다.

## 5. Design decisions

### Business logic

- Rules and their locations: 발송 대상 선택과 커밋 후 실행은 `SettlementRequestService`.
- Behavior owned by Domain: 기존 정산 요청 상태 전이.
- Decisions owned by Application Policy: N/A.
- Flow orchestrated by Application: 정산 요청 저장, 커밋 후 사용자 일괄 조회, Firebase 발송.

### Technology and patterns

- Constraint to solve: 외부 푸시와 DB 트랜잭션은 원자적으로 묶을 수 없다.
- Selected approach: 기존 Spring transaction synchronization의 `afterCommit` 패턴과 Firebase Adapter를 재사용한다.
- Reason for selection: 실패한 정산의 오발송을 막고 새 큐·테이블·의존성 없이 현재 요구를 충족한다.
- Alternatives considered: 트랜잭션 안에서 즉시 발송은 커밋 실패 오발송 위험 때문에 제외한다. Outbox·재시도 큐는 영구 전달 보장 요구가 없어 제외한다.
- Trade-offs and limits: 커밋 후 프로세스 종료 또는 Firebase 장애 시 자동 재시도하지 않는 best-effort 전달이다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 변경 없음 | `SettlementRequestController` |
| Application | 커밋 후 대상 선택·발송 추가 | `SettlementRequestService` |
| Domain | 변경 없음 | `Settlement`, `SettlementParticipant`, `User` |
| Persistence | 기존 사용자 일괄 조회 재사용 | `LoadUserPort.findAllByIds` |
| Redis | 변경 없음 | N/A |
| Security | 생성자 권한과 기존 링크 보안 유지 | 기존 `RequestSettlementUseCase` 흐름 |
| Configuration | 기존 Firebase 설정 재사용 | `FirebaseMessagingConfig` |
| Documentation | ALM-STL-001 설계·검증 기록 | 이 문서 |

Existing behavior that must remain unchanged:

- 정산 요청 응답, 상태 전이, Share·링크·계좌 스냅샷 저장 계약.
- 독촉 API의 30분 제한과 기존 오류 코드.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: 저장 구조 변경 없음)
- [x] UseCase and Application Command/Result (`N/A`: 기존 계약 재사용)
- [x] Required Output Ports
- [x] Domain models and rules (`N/A`: 변경 없음)
- [x] Application Policies (`N/A`: 단순 필터)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger (`N/A`: API 변경 없음)
- [x] Infra configuration and Bean wiring (`N/A`: 기존 Bean 자동 주입)
- [x] Documentation and verification results

### Expected commit boundaries

```text
feat(settlement): 정산 요청 완료 후 참여 알림 발송
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementRequestServiceTest' --tests '*FirebaseSettlementPushSenderAdapterTest' --tests '*SettlementReminderServiceTest'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

실제 기기 수신은 Firebase credential, 테스트 앱 FID, 클라이언트의 `SETTLEMENT_PARTICIPATION` 라우팅이 필요하다.

### Completion criteria

- [x] Purpose의 커밋 후 연결 참여자 알림을 검증한다.
- [x] 정상·실패·경계 흐름을 검증한다.
- [x] 기존 API와 독촉 계약이 유지되는지 확인한다.
- [x] 실제 기기 미검증 범위를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 설계와 migration 판단 | Passed | 기존 `users.fcm_token`, 링크, Firebase Adapter 재사용으로 스키마 변경 불필요 |
| Java compile | Passed | `./gradlew compileJava` |
| 집중 테스트 | Passed | 요청 커밋 후 참여 알림, FCM payload, 기존 독촉 Service 테스트 통과 |
| 전체 회귀 | Passed | 임시 PostgreSQL 16·Redis 7에서 `./gradlew test` 506개 통과 |
| 실제 기기 수신 | Not run | Firebase credential과 테스트 앱 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-31 | 첨부 명세와 현재 요청·링크·푸시 흐름 분석, migration 불필요 확정 | 최소 구현과 회귀 검증 | 정산 요청 성공과 푸시 전달을 원자적으로 보장할 수 없어 after-commit best-effort 채택 |
| 2026-08-31 | 커밋 후 참여 알림과 공용 정산 푸시 Adapter 구현, 집중·전체 회귀 통과 | 실제 기기와 클라이언트 라우팅 확인 | Firebase 장애 시 자동 재시도 없음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-31 | 기존 정산 요청 API 완료를 ALM-STL-001 트리거로 사용 | STL-001-005의 실제 서버 완료 지점이며 새 API가 필요 없음 | 참여 완료 후 생성자 알림은 별도 요구로 제외 |
| 2026-08-31 | `shareLink`를 푸시 data에 포함 | 참여자가 알림에서 기존 링크 흐름으로 바로 진입해야 함 | 개인정보 필드는 포함하지 않음 |
| 2026-08-31 | 푸시 실패는 요청 성공을 변경하지 않음 | 외부 전송 실패로 커밋된 정산 요청을 실패처럼 응답하면 재호출이 불가능함 | 자동 재시도는 out of scope |

## 11. Completion record

- Completed on: 2026-08-31
- Final behavior: 정산 요청 커밋 후 발송 가능한 연결 참여자에게 `SETTLEMENT_PARTICIPATION` 푸시를 보내며 `settlementId`와 `shareLink`를 전달한다.
- Unverified items: Firebase 실제 credential·앱 FID를 이용한 실제 기기 수신과 클라이언트 딥링크 라우팅.
- Remaining risks: after-commit best-effort 방식이므로 프로세스 종료 또는 Firebase 장애 시 자동 재시도되지 않는다.
- Follow-up work: 전달 보장 요구가 생기면 outbox·재시도 정책을 별도 설계하고, 비회원 전달은 SMS/공유 링크 정책으로 분리한다.
