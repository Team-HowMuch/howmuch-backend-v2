# Feature Plan: 정산 참여 알림 Transactional Outbox

- Status: Completed
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

- [x] 정산 상태·Share·링크·계좌 스냅샷과 수신자별 Outbox 이벤트가 하나의 트랜잭션으로 커밋된다.
- [x] 정산 요청 API가 FCM 응답을 기다리지 않고 기존 응답·상태 계약을 유지한다.
- [x] 다중 Worker가 처리 가능한 이벤트를 중복 선점하지 않는다.
- [x] 일시 실패는 Application이 소유한 backoff와 최대 시도 횟수에 따라 `RETRY` 또는 `FAILED`로 전환된다.
- [x] 처리 중 Worker가 종료되면 timeout 뒤 `PROCESSING` 이벤트를 복구한다.
- [x] 개선 전과 같은 지연·부분 실패·프로세스 종료 조건에서 성능 분리와 자동 복구를 확인한다.

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
| 처리 가능 이벤트 | 시각 조건을 만족한 `PENDING` 또는 `RETRY` 이벤트 | `claimNextReady` |
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
5. Worker가 batch 처리 상한 안에서 다음 이벤트 1건을 발송 직전에 선점해 `PROCESSING`으로 커밋한다.
6. Worker가 현재 사용자 FID와 저장된 링크를 조회해 FCM을 호출하고 `SUCCEEDED`로 전환한 뒤 다음 이벤트를 선점한다.

### Failure flow

- 정산 요청 저장 실패: 정산과 Outbox 이벤트가 함께 롤백된다.
- Firebase 일시 실패: 시도 횟수가 남으면 `RETRY`와 다음 처리 시각을 저장하고, 최대 시도에 도달하면 `FAILED`로 전환한다.
- 사용자 비활성·FID 없음·잘못된 FID·유효 링크 없음: 재시도하지 않고 제한된 실패 코드와 함께 `FAILED`로 전환한다.
- Worker 종료: 커밋된 `PROCESSING` 이벤트가 timeout을 넘으면 다른 poll에서 `RETRY`로 복구된다.
- 상태 갱신 소유권 상실: 조건부 update가 0건이면 이전 Worker는 이후 상태를 덮어쓰지 않는다.

### Boundary conditions

- PostgreSQL `FOR UPDATE SKIP LOCKED`로 여러 인스턴스의 동일 행 선점을 방지한다.
- batch 전체를 먼저 선점하지 않고 1건의 전달이 끝난 뒤 다음 행을 선점해, 뒤쪽 이벤트의 lease가 앞선 발송 대기시간 때문에 만료되지 않게 한다.
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
- Environment variable or profile changes: Worker enable, poll interval, batch size 추가. processing timeout, 최대 시도 횟수와 retry base/max delay는 Application 고정 정책으로 둔다.

## 5. Design decisions

### Business logic

- Rules and their locations: 상태 전이는 `SettlementNotificationOutbox`, backoff 계산은 `SettlementNotificationRetryPolicy`.
- Behavior owned by Domain: 선점, 성공, 재시도, 최종 실패, stale 복구 전이와 불변식.
- Flow orchestrated by Application: 대상 이벤트 생성, 처리 상한 안에서 1건씩 선점, 사용자·링크 조회, FCM 호출과 결과 전환.

### Technology and patterns

- Constraint to solve: DB 커밋과 외부 FCM 호출 사이의 전달 유실, 요청 지연 결합, 다중 Worker 경쟁.
- Selected approach: PostgreSQL Transactional Outbox와 polling Worker, `FOR UPDATE SKIP LOCKED`, 조건부 완료 update.
- Reason for selection: 현재 PostgreSQL 트랜잭션에 발송 의도를 함께 기록하면서 별도 브로커 없이 복구 가능한 최소 구조를 만든다.
- Alternatives considered: `@Async`는 요청 지연만 줄이고 프로세스 종료 시 유실을 해결하지 못한다. 외부 브로커는 현재 규모에서 운영 복잡도가 크다. 트랜잭션 내부 FCM 호출은 오발송과 긴 DB lock을 만든다.
- Trade-offs and limits: polling 지연과 이벤트별 선점 트랜잭션의 DB 부하가 생기며 exactly-once가 아닌 at-least-once다. timeout은 단일 외부 발송 최대시간보다 충분히 길어야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 외부 응답 계약 유지, 응답 경로에서 FCM 제거 | `SettlementRequestController` |
| Application | 이벤트 생성과 Worker orchestration | `SettlementRequestService`, 신규 Worker·Policy |
| Domain | Outbox 상태와 전이 추가 | 신규 domain model·enum |
| Persistence | V29 테이블, 선점·조건부 전이 Adapter | 신규 migration·JPA 구성 |
| Redis | 영향 없음 | N/A |
| Security | 사용자·링크 원문 비복제 | 기존 암호화 링크 저장과 사용자 조회 재사용 |
| Configuration | scheduling 기술 값 추가, Worker 정책은 Application 고정 규칙 | 신규 Properties·Config·Scheduler·Policy |
| Documentation | baseline, 설계와 개선 후 결과 | 이 문서 |

Existing behavior that must remain unchanged:

- 정산 요청 API의 성공 응답, 오류, `DRAFT -> REQUESTED/AWAITING_DEPOSIT` 상태 전이.
- Share·정산 링크·계좌 스냅샷 저장과 링크 payload.
- 정산 독촉 API와 30분 Redis cooldown.

## 7. Implementation plan

- [x] UseCase and Application Request/Response (`N/A`: 공개 계약 유지)
- [x] Required Output Ports
- [x] Domain models and rules
- [x] Application Service
- [x] Output Adapters
- [x] Web DTOs, Controllers, and Swagger (`N/A`: 계약 변경 없음)
- [x] Infra configuration and Bean wiring
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 참여 알림 Outbox 개선 계획 작성
feat(settlement): 참여 알림 Outbox 스키마 추가
feat(settlement): 참여 알림 Outbox 발행과 복구 Worker 구현
docs(settlement): 참여 알림 Outbox 검증 결과 기록
refactor(settlement): Outbox 재시도 정책 소유권 정리
fix(settlement): 알림 발송 직전 Outbox 이벤트 선점
test(settlement): Outbox lease 경계 회귀 검증
docs(settlement): Outbox 리뷰 반영 결과 기록
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

- [x] Purpose에서 정의한 트랜잭션 원자성과 API 지연 분리를 확인한다.
- [x] 정상·부분 실패·최대 실패·다중 Worker·프로세스 종료 경계를 확인한다.
- [x] 기존 API와 Firebase payload 계약이 유지된다.
- [x] 미검증 범위와 at-least-once 한계를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 관련 회귀 테스트 | Passed | 기존 관련 테스트 7개와 신규 Policy·Worker lease 회귀 테스트 2개 통과 |
| 스키마 검증 | Passed | 빈 PostgreSQL 18.3에서 V1~V29 29개 migration과 Hibernate validate 통과 |
| 개선 전 성능 baseline | Passed | 대상·지연 16조건, `p50 ~ 대상×지연` R² 0.99995 |
| 개선 전 장애 baseline | Passed | 부분 실패 재시도 0, 종료 후 미발송 23건 복구 0 |
| 개선 후 API 성능 | Passed | 대상·지연 16조건×100건, HTTP 실패 0; 10명·200ms p50 5.95ms, 30명·500ms p50 21.63ms |
| 트랜잭션 원자성 | Passed | Outbox unique 충돌 주입 시 HTTP 500, 정산 `DRAFT/version 0`, Share·링크·스냅샷 0건으로 함께 rollback |
| 부분 실패·최대 실패 | Passed | 매 3번째 실패: 44회 시도 후 30건 전부 성공; 장애 유지: 5건 모두 5회 후 `FAILED` |
| 장애 해제 복구 | Passed | 최초 5건 `RETRY/attempt 1`, barrier 해제 후 30초에 5건 `SUCCEEDED/attempt 2` |
| 프로세스 종료 복구 | Passed | 7번째 외부 수락 직후 exit 137, `SUCCEEDED` 6건·`PROCESSING` 24건; 재기동 후 recovered 24건, 최종 성공 30건 |
| 다중 Worker | Passed | batch 10인 Worker 2개가 30건을 10/20으로 분할 처리, 최종 `attempt 1` 30건 |
| 리뷰 지적 회귀 | Passed | 첫 알림 발송·완료 전이 뒤에만 두 번째 이벤트를 선점하는 호출 순서 검증 |
| 격리 런타임 | Passed | 빈 PostgreSQL 18.3·Redis에서 애플리케이션 기동, V1~V29 validate와 빈 Outbox 대상 200ms polling query 확인 |
| 전체 테스트 | Partial | 깨끗한 격리 DB에서 592개 중 589개 통과. develop에서도 변경되지 않은 기존 테스트 3개가 누락 mock 2개·0원 Domain 생성 1개로 실패 |

#### 개선 후 응답시간 matrix

요청 경로만 분리하기 위해 Worker를 정지하고, 조건별 warm-up 10건 후 VU 5로 100건을 측정했다. 지연은 Fake Sender에 설정했지만 요청 중 Sender 호출은 0건이었다.

| 대상자 | 외부 지연 | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|
| 1 | 0ms | 3.864ms | 13.305ms | 17.584ms |
| 1 | 50ms | 3.581ms | 8.098ms | 44.571ms |
| 1 | 200ms | 3.912ms | 7.530ms | 16.260ms |
| 1 | 500ms | 4.128ms | 9.016ms | 15.028ms |
| 5 | 0ms | 4.909ms | 8.198ms | 19.648ms |
| 5 | 50ms | 6.444ms | 11.834ms | 17.874ms |
| 5 | 200ms | 5.061ms | 9.259ms | 15.073ms |
| 5 | 500ms | 4.765ms | 9.691ms | 17.516ms |
| 10 | 0ms | 6.579ms | 14.574ms | 21.528ms |
| 10 | 50ms | 7.327ms | 9.580ms | 22.843ms |
| 10 | 200ms | 5.947ms | 11.340ms | 19.829ms |
| 10 | 500ms | 8.303ms | 10.240ms | 15.911ms |
| 30 | 0ms | 15.532ms | 29.292ms | 52.562ms |
| 30 | 50ms | 17.129ms | 22.261ms | 30.896ms |
| 30 | 200ms | 14.249ms | 28.360ms | 29.765ms |
| 30 | 500ms | 21.634ms | 27.379ms | 31.404ms |

다중 회귀 `p50 = 절편 + 대상자 수 + 외부 지연`에서 대상자 1명당 0.466ms, 외부 지연 1ms당 0.00324ms, R² 0.9316이었다. 즉 외부 지연 누적은 제거됐고, 남은 증가는 수신자별 Outbox insert 비용으로 설명된다. 10명·200ms p50은 2,050.0ms에서 5.95ms로 99.71%, 30명·500ms는 15,138.7ms에서 21.63ms로 99.86% 감소했다.

#### VU별 처리량

참여자 10명, Worker 정지, warm-up 10건 후 VU별 1,000건을 요청했다.

| VU | p50 | p95 | p99 | 처리량 |
|---:|---:|---:|---:|---:|
| 1 | 5.189ms | 9.086ms | 15.204ms | 166.0 req/s |
| 5 | 6.296ms | 8.978ms | 11.176ms | 654.8 req/s |
| 10 | 14.392ms | 32.695ms | 120.020ms | 501.3 req/s |

VU 10은 VU 5보다 처리량이 낮고 tail latency가 커졌다. 외부 I/O는 분리됐지만 이 환경의 DB connection·쓰기 경쟁 임계점이 VU 5~10 사이에 있음을 나타낸다.

#### 장애·경계 검증

- 매 3번째 발송을 실패시킨 30명 정산은 최초 성공 20건·실패 10건에서 재시도를 거쳐 44회 시도, 성공 30건·실패 14건으로 종료했다. 최종 시도 분포는 1회 20건, 2회 7건, 3회 2건, 4회 1건이었다.
- 전체 장애를 계속 유지하면 5건 모두 최대 5회 시도 후 `FAILED`로 수렴했다. 다시 장애 barrier를 해제한 실험에서는 `RETRY/attempt 1` 5건이 30초 후 `SUCCEEDED/attempt 2` 5건으로 자동 복구됐다.
- 30건 선점 후 7번째 외부 수락 직후 프로세스를 exit 137로 종료하면 DB에 `SUCCEEDED` 6건·`PROCESSING` 24건이 남았다. 재기동 후 Worker가 stale 24건을 복구해 모두 성공시켰고, 동일 요청은 기존과 같이 409 `INVALID_SETTLEMENT_STATUS_TRANSITION`을 반환했다.
- 외부에서는 종료 전 7건과 재기동 후 24건, 총 31건이 수락됐다. 종료 직전 1건의 중복은 DB와 FCM 사이에 exactly-once를 보장할 수 없는 at-least-once 한계를 실제로 확인한 결과다.
- batch 10인 Worker 2개는 30건을 10건과 20건으로 나눠 처리했고, 모든 이벤트의 `attemptCount`는 1이었다.
- 기존 Outbox 행을 미리 삽입해 unique 충돌을 발생시키면 HTTP 500이 반환됐고, 정산은 `DRAFT/version 0`, Share·링크·계좌 스냅샷은 0건으로 유지돼 원자적 rollback을 확인했다.

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-03 | 실제 API 기반 개선 전 성능·부분 실패·프로세스 종료 baseline 확립 | Outbox migration과 구현 | 커밋 후 direct 발송은 성공 응답과 전달 유실을 분리해 관측하지 못함 |
| 2026-09-03 | #251~#255 생성·연결, 최신 develop 기준 설계 확정 | V29와 Domain·Persistence 구현 | timeout 경계 중복은 exactly-once 없이 제거할 수 없음 |
| 2026-09-03 | V29, Outbox Domain·Port·Adapter, polling Worker·Scheduler 구현 | 격리 성능·장애 재측정 | Outbox insert가 대상자 1명당 약 0.47ms 증가하는 새 DB 비용이 됨 |
| 2026-09-03 | 16조건 k6, VU, 부분·지속 장애, 30초 복구, crash, 다중 Worker, rollback 검증 | 완료 | VU 10 DB 경쟁과 at-least-once 중복 1건을 계량함 |
| 2026-09-03 | PR 리뷰의 Policy 소유권과 batch lease 만료 지적 반영, 관련 9개·전체 592개 회귀 재검증 | 완료 | 이벤트별 선점 트랜잭션 비용과 단일 외부 호출 timeout 경계는 남음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-03 | Outbox에는 정산 ID와 수신자 사용자 ID만 저장 | raw 링크와 FID 중복 저장·노출 방지, 최신 FID 사용 | Worker가 사용자·링크를 추가 조회함 |
| 2026-09-03 | `FOR UPDATE SKIP LOCKED` 뒤 `PROCESSING` 커밋 후 외부 발송 | 다중 Worker 중복 선점과 긴 DB lock 방지 | 커밋 후 종료 시 stale 복구 필요 |
| 2026-09-03 | 완료 전이는 `PROCESSING + attemptCount` 조건부 갱신 | stale Worker가 새 선점 결과를 덮어쓰는 문제 방지 | timeout 경계 외부 중복 가능성은 남음 |
| 2026-09-03 | 제한된 실패 enum만 저장 | 외부 오류 원문과 민감정보 영속화 방지 | 상세 원인은 구조화 로그·외부 관측 도구가 필요함 |
| 2026-09-03 | at-least-once 전달을 명시 | DB와 FCM을 원자 커밋할 수 없음 | 클라이언트가 동일 type·settlementId 알림을 허용해야 함 |
| 2026-09-03 | 재시도·timeout 규칙을 Application 고정 정책으로 소유 | Infra가 Application Policy를 생성하지 않는 저장소 경계 준수 | 운영 중 설정 변경이 필요하면 ADR로 cross-layer 계약을 먼저 결정함 |
| 2026-09-03 | batch 상한만 유지하고 이벤트를 발송 직전 1건씩 선점 | 정상적인 느린 batch에서 대기 중인 이벤트 lease가 만료되는 중복 방지 | 이벤트마다 별도 선점 트랜잭션이 발생함 |

## 11. Completion record

- Completed on: 2026-09-03
- Final behavior: 정산 요청은 정산 데이터와 수신자별 Outbox를 함께 커밋한 뒤 외부 호출 없이 응답한다. Worker는 처리 상한 안에서 발송 직전 1건씩 `SKIP LOCKED`로 선점하고 Application 고정 지수 backoff·최대 5회·stale timeout 복구를 적용한다.
- Unverified items: 실제 운영 FCM 지연 분포, 기기 표시 성공, 운영 인프라의 최대 처리량은 검증하지 않았다.
- Remaining risks: 단일 FCM 호출이 lease timeout을 넘거나 FCM 수락 후 DB 완료 기록 전에 종료되면 중복 발송될 수 있다. 이벤트별 선점 트랜잭션 비용이 추가됐고, 요청 API는 VU 10에서 DB 쓰기 경쟁으로 p99 120.0ms와 처리량 하락이 관측됐다.
- Follow-up work: 운영 지표·경보, 수동 재처리 UI/API, Outbox retention 정책, 필요 시 insert batching을 별도 이슈로 다룬다.
