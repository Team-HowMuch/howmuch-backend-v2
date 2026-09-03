# Feature Plan: 정산 참여 알림 Transactional Outbox

- Status: In Progress
- Last updated: 2026-09-03
- Related issue: [#251](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/251), [#252](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/252), [#253](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/253), [#254](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/254), [#255](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/255)
- Related ADR: N/A — 정산 참여 알림 내부의 전달 신뢰성 변경으로 한정하며 다른 도메인의 메시징 표준으로 일반화하지 않는다.

## 1. Purpose

### Problem

정산 생성자가 `POST /api/v2/settlements/{settlementId}/request`를 호출하면 트랜잭션 커밋 뒤 같은 요청 스레드가 연결 참여자별 FCM 발송을 직렬로 수행한다. 외부 지연이 대상자 수만큼 응답시간에 누적되고, 일부 발송 실패나 프로세스 종료가 발생해도 발송 의도와 진행 상태가 영속화되지 않아 자동 복구할 수 없다.

개선 전 실제 API baseline에서 10명·외부 지연 200ms의 p50은 2,050.0ms, 30명·500ms는 15,138.7ms였다. `대상자 수 × 외부 지연`과 p50의 회귀 기울기는 1.008, R²는 0.99995였다. 매 3번째 발송 실패 시 API는 HTTP 200이고 정산은 `AWAITING_DEPOSIT`였지만 성공 20건·실패 10건·재시도 0건으로 끝났다. 7건 발송 뒤 프로세스를 종료하면 재기동 후에도 나머지 23건은 복구되지 않았고 동일 요청은 409로 거절됐다.

### Why now

기존 Feature Plan은 참여 알림을 best-effort로 제한하고 Outbox를 후속 범위로 남겼다. 실제 측정으로 API 지연 종속성과 복구 불가능한 전달 유실이 확인됐으므로, 정산 상태와 알림 발송 의도 사이의 원자성을 확보하고 외부 발송을 요청 경로에서 분리해야 한다.

### Success criteria

- [ ] 정산 상태·Share·링크·계좌 스냅샷과 수신자별 Outbox 이벤트가 하나의 트랜잭션으로 커밋된다.
- [ ] 정산 요청 API가 FCM 응답을 기다리지 않고 기존 응답·상태 계약을 유지한다.
- [ ] 다중 Worker가 처리 가능한 이벤트를 중복 선점하지 않는다.
- [ ] 일시 실패는 설정된 backoff와 최대 시도 횟수에 따라 `RETRY` 또는 `FAILED`로 전환된다.
- [ ] 처리 중 Worker가 종료되면 timeout 뒤 `PROCESSING` 이벤트를 복구한다.
- [ ] 개선 전과 같은 지연·부분 실패·프로세스 종료 조건에서 성능 분리와 자동 복구를 확인한다.

### Out of scope

- FCM 수락과 실제 기기 표시의 exactly-once 보장
- Kafka, RabbitMQ 등 별도 메시지 브로커
- 알림함, 읽음 상태, 운영 대시보드와 수동 재처리 API
- 기존 정산 독촉 알림의 Outbox 전환
- 실제 운영 FCM 지연 분포와 운영 인프라 최대 처리량 측정

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `SettlementLink`, `User`
- Reason a new domain is required, if applicable: 발송 의도와 처리 상태를 영속적으로 표현하는 `SettlementNotificationOutbox`를 정산 도메인에 추가한다.
- Relationships to other domains: settlement가 발송 시점과 수신자를 결정하고 user가 처리 시점의 활성 FCM installation ID를 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 참여 알림 이벤트 | 정산 요청과 함께 저장되는 수신자 한 명의 발송 의도 | `SettlementNotificationOutbox` |
| 처리 가능 이벤트 | 시각 조건을 만족한 `PENDING` 또는 `RETRY` 이벤트 | `claimReady` |
| 선점 | 한 Worker가 이벤트를 `PROCESSING`으로 바꾸고 외부 전달 책임을 얻는 동작 | `FOR UPDATE SKIP LOCKED` |
| stale 이벤트 | Worker 종료 등으로 timeout을 넘겨 `PROCESSING`에 남은 이벤트 | `processingStartedAt` 기준 복구 |
| 전달 실패 코드 | 외부 오류 원문 대신 저장하는 제한된 실패 분류 | `SettlementNotificationFailureCode` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 정산 요청을 완료하고 참여 알림 발송 의도를 원자적으로 남긴다. | 기존 `RequestSettlementUseCase` |
| 참여 알림 Worker | 저장된 이벤트를 선점하고 FCM 전달 결과를 영속 상태로 기록한다. | 내부 `SettlementNotificationOutboxWorker` |

### State and invariants

- State transitions: `PENDING -> PROCESSING -> SUCCEEDED`, `PROCESSING -> RETRY -> PROCESSING`, `PROCESSING -> FAILED`.
- stale 복구: timeout을 넘은 `PROCESSING`은 시도 횟수가 최대 미만이면 `RETRY`, 최대 이상이면 `FAILED`로 전환한다.
- Rules that must always hold:
  - 정산과 이벤트 저장은 같은 DB 트랜잭션에 참여한다.
  - 동일 `settlementId`·`recipientUserId` 이벤트는 하나만 존재한다.
  - 외부 발송은 DB 선점 트랜잭션이 커밋된 뒤 수행한다.
  - 완료 갱신은 현재 `PROCESSING`이며 선점 당시 `attemptCount`가 같은 경우에만 성공한다.
  - raw 링크 토큰, FCM installation ID와 외부 오류 원문은 Outbox에 저장하지 않는다.
- Invalid states: `PROCESSING`인데 `processingStartedAt`이 없음, `PENDING/RETRY`인데 `nextAttemptAt`이 없음, terminal 상태인데 `completedAt`이 없음.

## 3. Scenarios

### Normal flow

1. 생성자가 기존 정산 요청 API를 호출한다.
2. Application이 정산·참여자·배분·계좌를 검증하고 정산 상태, Share, 링크와 계좌 스냅샷을 저장한다.
3. 활성 회원이며 FCM 대상이 등록된 연결 참여자별 `PENDING` 이벤트를 같은 트랜잭션에 저장한다.
4. API는 외부 FCM 호출 없이 기존 응답을 반환한다.
5. Worker가 처리 가능한 이벤트를 batch로 선점해 `PROCESSING`으로 커밋한다.
6. Worker가 현재 사용자 FID와 저장된 링크를 조회해 FCM을 호출하고 `SUCCEEDED`로 전환한다.

### Failure flow

- 정산 요청 저장 실패: 정산과 Outbox 이벤트가 함께 롤백된다.
- Firebase 일시 실패: 시도 횟수가 남으면 `RETRY`와 다음 처리 시각을 저장하고, 최대 시도에 도달하면 `FAILED`로 전환한다.
- 사용자 비활성·FID 없음·잘못된 FID·유효 링크 없음: 재시도하지 않고 제한된 실패 코드와 함께 `FAILED`로 전환한다.
- Worker 종료: 커밋된 `PROCESSING` 이벤트가 timeout을 넘으면 다른 poll에서 `RETRY`로 복구된다.
- 상태 갱신 소유권 상실: 조건부 update가 0건이면 이전 Worker는 이후 상태를 덮어쓰지 않는다.

### Boundary conditions

- PostgreSQL `FOR UPDATE SKIP LOCKED`로 여러 인스턴스의 동일 행 선점을 방지한다.
- DB 커밋과 FCM 호출은 원자적으로 묶을 수 없으므로 timeout 경계에서는 중복 발송 가능성이 남는다. 전달 의미는 at-least-once다.
- 이벤트에는 사용자 ID만 저장하고 처리 시점의 FID를 사용하므로 앱 재등록 후에는 최신 대상으로 보낸다.
- 링크는 기존 암호화 ciphertext를 복호화해 현재 URL을 구성하며 Outbox payload에 원문을 복제하지 않는다.

## 4. Contracts

### Input Port

```text
UseCase: 기존 RequestSettlementUseCase.request(UUID settlementId)
Request/Response: 기존 계약 유지

Internal worker: SettlementNotificationOutboxWorker.processBatch()
Input/Output: 없음 / 처리·복구 건수
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SettlementNotificationOutboxPort` | 이벤트 저장, 선점, 완료, 재시도, 실패와 stale 복구 | 정산 요청 Service, Worker | PostgreSQL Persistence Adapter |
| 기존 `LoadUserPort` | 처리 시점의 사용자 활성 상태와 FID 조회 | Worker | `UserPersistenceAdapter` |
| 기존 `SettlementShareLinkStorePort` | 암호화 저장된 현재 정산 링크 조회 | Worker | `SettlementLinkPersistenceAdapter` |
| 기존 `SettlementLinkTokenPort` | 복호화된 raw token으로 공유 URL 구성 | Worker | `SecureSettlementLinkTokenAdapter` |
| 기존 `SettlementPushSenderPort` | FCM 참여 알림 전달 | Worker | `FirebaseSettlementPushSenderAdapter` |

### Web API

```text
POST /api/v2/settlements/{settlementId}/request
Request/Response/Errors/Authentication: 변경 없음
Observable change: 응답 경로에서 외부 FCM 호출 제거
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: `settlement_notification_outbox`와 ready/stale 부분 index, 정산·수신자 Unique constraint 추가.
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 기존 Firebase payload 유지.
- Environment variable or profile changes: Worker enable, poll interval, batch size, processing timeout, max attempts, retry base/max delay 추가.

## 5. Design decisions

### Business logic

- Rules and their locations: 상태 전이는 `SettlementNotificationOutbox`, backoff 계산은 `SettlementNotificationRetryPolicy`.
- Behavior owned by Domain: 선점, 성공, 재시도, 최종 실패, stale 복구 전이와 불변식.
- Flow orchestrated by Application: 대상 이벤트 생성, batch 처리, 사용자·링크 조회, FCM 호출과 결과 전환.

### Technology and patterns

- Constraint to solve: DB 커밋과 외부 FCM 호출 사이의 전달 유실, 요청 지연 결합, 다중 Worker 경쟁.
- Selected approach: PostgreSQL Transactional Outbox와 polling Worker, `FOR UPDATE SKIP LOCKED`, 조건부 완료 update.
- Reason for selection: 현재 PostgreSQL 트랜잭션에 발송 의도를 함께 기록하면서 별도 브로커 없이 복구 가능한 최소 구조를 만든다.
- Alternatives considered: `@Async`는 요청 지연만 줄이고 프로세스 종료 시 유실을 해결하지 못한다. 외부 브로커는 현재 규모에서 운영 복잡도가 크다. 트랜잭션 내부 FCM 호출은 오발송과 긴 DB lock을 만든다.
- Trade-offs and limits: polling 지연과 DB 부하가 생기며 exactly-once가 아닌 at-least-once다. timeout은 외부 발송 최대시간보다 충분히 길어야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 외부 응답 계약 유지, 응답 경로에서 FCM 제거 | `SettlementRequestController` |
| Application | 이벤트 생성과 Worker orchestration | `SettlementRequestService`, 신규 Worker·Policy |
| Domain | Outbox 상태와 전이 추가 | 신규 domain model·enum |
| Persistence | V29 테이블, 선점·조건부 전이 Adapter | 신규 migration·JPA 구성 |
| Redis | 영향 없음 | N/A |
| Security | 사용자·링크 원문 비복제 | 기존 암호화 링크 저장과 사용자 조회 재사용 |
| Configuration | scheduling과 Worker 정책 값 추가 | 신규 Properties·Config·Scheduler |
| Documentation | baseline, 설계와 개선 후 결과 | 이 문서 |

Existing behavior that must remain unchanged:

- 정산 요청 API의 성공 응답, 오류, `DRAFT -> REQUESTED/AWAITING_DEPOSIT` 상태 전이.
- Share·정산 링크·계좌 스냅샷 저장과 링크 payload.
- 정산 독촉 API와 30분 Redis cooldown.

## 7. Implementation plan

- [x] UseCase and Application Request/Response (`N/A`: 공개 계약 유지)
- [ ] Required Output Ports
- [ ] Domain models and rules
- [ ] Application Service
- [ ] Output Adapters
- [x] Web DTOs, Controllers, and Swagger (`N/A`: 계약 변경 없음)
- [ ] Infra configuration and Bean wiring
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 참여 알림 Outbox 개선 계획 작성
feat(settlement): 참여 알림 Outbox 스키마 추가
feat(settlement): 참여 알림 Outbox 발행과 복구 Worker 구현
docs(settlement): 참여 알림 Outbox 검증 결과 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementRequestServiceTest' --tests '*FirebaseSettlementPushSenderAdapterTest'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

격리 PostgreSQL·Redis와 Fake Sender를 사용해 실제 로그인·정산 요청 API를 실행한다. 개선 전과 동일한 k6 matrix, 매 3번째 실패, 장애 barrier, Worker 강제 종료·재기동, stale 복구를 확인한다. 실제 Firebase와 기존 로컬 DB·Redis에는 쓰지 않는다.

### Completion criteria

- [ ] Purpose에서 정의한 트랜잭션 원자성과 API 지연 분리를 확인한다.
- [ ] 정상·부분 실패·최대 실패·다중 Worker·프로세스 종료 경계를 확인한다.
- [ ] 기존 API와 Firebase payload 계약이 유지된다.
- [ ] 미검증 범위와 at-least-once 한계를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 기존 관련 테스트 | Passed | 개선 전 `SettlementRequestServiceTest` 4개, `FirebaseSettlementPushSenderAdapterTest` 3개 |
| 개선 전 성능 baseline | Passed | 대상·지연 16조건, `p50 ~ 대상×지연` R² 0.99995 |
| 개선 전 장애 baseline | Passed | 부분 실패 재시도 0, 종료 후 미발송 23건 복구 0 |
| 개선 후 검증 | Pending | 구현 후 동일 조건 재실행 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-03 | 실제 API 기반 개선 전 성능·부분 실패·프로세스 종료 baseline 확립 | Outbox migration과 구현 | 커밋 후 direct 발송은 성공 응답과 전달 유실을 분리해 관측하지 못함 |
| 2026-09-03 | #251~#255 생성·연결, 최신 develop 기준 설계 확정 | V29와 Domain·Persistence 구현 | timeout 경계 중복은 exactly-once 없이 제거할 수 없음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-03 | Outbox에는 정산 ID와 수신자 사용자 ID만 저장 | raw 링크와 FID 중복 저장·노출 방지, 최신 FID 사용 | Worker가 사용자·링크를 추가 조회함 |
| 2026-09-03 | `FOR UPDATE SKIP LOCKED` 뒤 `PROCESSING` 커밋 후 외부 발송 | 다중 Worker 중복 선점과 긴 DB lock 방지 | 커밋 후 종료 시 stale 복구 필요 |
| 2026-09-03 | 완료 전이는 `PROCESSING + attemptCount` 조건부 갱신 | stale Worker가 새 선점 결과를 덮어쓰는 문제 방지 | timeout 경계 외부 중복 가능성은 남음 |
| 2026-09-03 | 제한된 실패 enum만 저장 | 외부 오류 원문과 민감정보 영속화 방지 | 상세 원인은 구조화 로그·외부 관측 도구가 필요함 |
| 2026-09-03 | at-least-once 전달을 명시 | DB와 FCM을 원자 커밋할 수 없음 | 클라이언트가 동일 type·settlementId 알림을 허용해야 함 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
