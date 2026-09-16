# Feature Plan: 정산 자동 리마인드 12시간 반복과 심야 이월

- Status: Completed
- Last updated: 2026-09-16
- Related issue: [#397](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/397)
- Related ADR: N/A

## 1. Purpose

### Problem

현재 자동 리마인드는 확정 Share 생성 24시간 뒤 미납 회원에게 한 번만 생성된다. ALM-STL-003은 미납 상태가 이어지는 동안 12시간마다 정산 요청 알림을 반복하고, 한국 시간 03:00~06:00에는 알림을 보내지 않도록 요구한다.

### Why now

한 번의 자동 리마인드만으로는 장기 미납 상태를 다시 알릴 수 없고, 현재 구현은 심야 방해 금지 시간도 표현하지 못한다. 이미 회원 알림함과 Outbox, 자동 후보 조회, 정산 단위 잠금이 있으므로 저장 구조를 늘리지 않고 정책을 보완할 수 있다.

### Success criteria

- [x] 확정 Share가 있는 활성 연결 회원의 미납 정산에 최초 12시간 후 자동 리마인드를 생성한다.
- [x] 마지막 실제 자동 리마인드 생성 시각으로부터 12시간이 지나면 다음 리마인드를 생성한다.
- [x] KST 03:00 이상 06:00 미만에는 자동 리마인드를 생성하지 않고, 미뤄진 후보를 06:00 이후 첫 배치에서 처리한다.
- [x] 동시 배치와 재시작에도 같은 반복 주기의 알림을 중복 생성하지 않는다.
- [x] 기존 수동·자동 공통 30분 쿨다운과 회원·미납·정산 상태 검증을 유지한다.

### Out of scope

- 비회원·미연결 참여자 리마인드
- 품목 선택 미제출자 자동 리마인드
- 사용자별 알림 시간 설정
- 스케줄러 기본 활성화 또는 운영 환경 변수 변경
- 기존 알림 이력의 수정·삭제·백필

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`, `notification`
- Reason a new domain is required, if applicable: N/A. 기존 자동 독촉 정책과 회원 알림 이력을 확장한다.
- Relationships to other domains: settlement가 대상과 미납 상태를 결정하고 notification이 생성 시각과 중복키를 보존하며 Outbox가 푸시 전달을 담당한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 반복 기준 시각 | 첫 회는 Share 확정 시각, 이후는 마지막 자동 리마인드 생성 시각 | `SettlementShare.createdAt`, `user_notifications.created_at` |
| 자동 리마인드 간격 | 반복 기준 시각부터 다음 자동 리마인드까지의 최소 간격 | `SettlementReminderPolicy.AUTOMATIC_INTERVAL` |
| 방해 금지 시간 | 자동 리마인드를 생성하지 않는 KST 03:00 이상 06:00 미만 | `SettlementReminderPolicy.isAutomaticQuietHours` |
| 반복 중복키 | 같은 반복 기준 시각에서 한 번만 저장되게 하는 키 | `AUTO_REMINDER:<shareId>:<anchorEpochSecond>` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 참여 회원 | 장기 미납 정산을 심야를 피해 정기적으로 안내받는다. | 기존 `SendAutomaticSettlementRemindersUseCase` |

### State and invariants

- State transitions: 정산과 Share 상태는 변경하지 않고 `UserNotification`과 Outbox만 추가한다.
- Rules that must always hold:
  - 최초·반복 자동 리마인드는 이전 기준 시각부터 12시간이 지난 뒤에만 생성한다.
  - KST 03:00 이상 06:00 미만에는 생성하지 않는다. 06:00은 허용한다.
  - 기존 요청 알림이 있는 활성 연결 회원의 확정 Share이며 확인 입금액이 0인 대상만 허용한다.
  - 수동·자동 리마인드는 같은 30분 Redis 쿨다운을 공유한다.
  - 정산 잠금 뒤 최신 자동 리마인드 시각과 대상 상태를 다시 확인한다.
- Invalid states: 완료 정산, 미연결·비활성·생성자 참여자, Share 없음, 확인 입금액 존재, 품목 선택 미제출, 방해 금지 시간.

## 3. Scenarios

### Normal flow

1. 스케줄러가 UTC 현재 시각을 얻고 KST 방해 금지 시간인지 확인한다.
2. 방해 금지 시간이 아니면 Share 확정 12시간 경과, 최근 자동 리마인드 12시간 경과, 전체 리마인드 30분 경과 후보를 조회한다.
3. Writer가 정산을 잠근 뒤 대상 상태와 마지막 자동 리마인드 시각을 다시 확인한다.
4. 반복 기준 시각을 포함한 중복키로 알림과 Outbox를 저장한다.
5. 다음 자동 리마인드는 실제 저장 시각부터 12시간 뒤 후보가 된다.

### Failure flow

- 방해 금지 시간: 후보를 조회하거나 알림을 생성하지 않고 0건을 반환한다.
- 후보 조회 뒤 입금·상태·회원 연결 변경: 기존 검증으로 생성하지 않는다.
- 최근 수동 독촉으로 30분 쿨다운 중: 이번 배치에서 건너뛰고 이후 배치에서 재평가한다.
- 동시 배치: 정산 잠금과 최신 이력 재확인, 반복 중복키로 한 건만 저장한다.

### Boundary conditions

- 12시간 정각은 발송 가능하고 1초 전은 발송하지 않는다.
- KST 03:00은 차단하고 06:00은 허용한다.
- 방해 금지 시간에 예정된 알림은 별도 예약 레코드 없이 기존 후보 조건을 유지해 06:00 이후 첫 배치에서 처리한다.
- 기존 `AUTO_REMINDER:<shareId>` 이력도 마지막 자동 리마인드로 인식해 배포 직후 중복을 막는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 SendAutomaticSettlementRemindersUseCase.sendDue(int batchSize)
Command: N/A
Result: 생성된 자동 리마인드 수 int
Web Request DTO -> Command: N/A
Result -> Web Response DTO: N/A
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `SettlementAutomaticReminderTargetPort` | 반복 후보 조회, 요청 알림 존재와 마지막 자동 리마인드 시각 조회 | 자동 Service·Writer | settlement Persistence Adapter |
| 기존 `SettlementReminderCooldownPort` | 수동·자동 공통 30분 예약 | Writer | Redis Adapter |
| 기존 notification/Outbox Ports | 반복 알림과 푸시 작업 원자 저장 | `NotificationRecorder` | notification Persistence Adapter |

### Application Policy

- Decision owned by the Policy: 12시간 도래 여부, KST 방해 금지 시간, 허용 정산 상태.
- Facts loaded by the Service and passed to the Policy: Share 확정 시각, 마지막 자동 리마인드 생성 시각, UTC 현재 시각.
- Why the rule is not an Entity-local invariant: 알림 이력과 현재 시각, 정산 상태를 함께 판단한다.
- Future inputs deliberately not implemented yet: 사용자별 시간대·수신 주기.

### Web API

```text
변경 없음. 자동 스케줄러 내부 동작과 기존 알림함 조회 계약을 재사용한다.
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: Entity·schema 변경 없이 `user_notifications` 후보/최신 시각 쿼리만 변경한다.
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음. 기존 참여자별 30분 쿨다운을 재사용한다.
- External API or message changes: 없음. 기존 알림/FCM payload를 재사용한다.
- Environment variable or profile changes: 없음. 기존 `SETTLEMENT_AUTOMATIC_REMINDER_ENABLED`, poll interval, batch size를 유지한다.

## 5. Design decisions

### Business logic

- Rules and their locations: 12시간·방해 금지 판단은 `SettlementReminderPolicy`, 배치 중단은 `SettlementAutomaticReminderService`, 잠금 뒤 재검증과 반복 중복키 생성은 `SettlementReminderWriter`.
- Behavior owned by Domain: 기존 정산·Share·회원 상태.
- Decisions owned by Application Policy: 현재 시각에 자동 리마인드를 생성해도 되는지.
- Flow orchestrated by Application: 후보 조회, 정산 잠금, 최신 이력 재조회, 쿨다운 예약, 알림·Outbox 저장.

### Technology and patterns

- Constraint to solve: 기존 1회 중복키를 반복 가능하게 바꾸면서 동시 실행 중복을 막고 심야 예정분을 06:00으로 이월한다.
- Selected approach: 마지막 실제 자동 리마인드 `created_at`을 반복 기준으로 사용하고, 기준 시각 epoch second를 중복키에 포함한다. 방해 금지 시간에는 배치가 후보를 소비하지 않는다.
- Reason for selection: 기존 테이블·고유 제약·정산 잠금을 재사용하며 추가 예약 저장 없이 06:00 이월과 재시작 복구가 가능하다.
- Alternatives considered: Share 시각 기준 고정 12시간 슬롯은 심야 이월 뒤 다음 알림까지 12시간보다 짧아질 수 있어 제외한다. 별도 next reminder 컬럼은 현재 이력으로 계산 가능해 migration 비용이 불필요하다.
- Trade-offs and limits: 06:00 실제 생성은 기본 1분 polling 지연만큼 늦을 수 있다. 자동 스케줄러는 운영 설정으로 활성화해야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 변경 없음 | 기존 notification API |
| Application | 12시간·KST 정책과 반복 Writer 재검증 | reminder Policy/Service/Writer |
| Domain | 변경 없음 | 기존 Settlement/Share/UserNotification |
| Persistence | 반복 후보와 마지막 자동 리마인드 시각 조회 | automatic reminder repository/adapter |
| Redis | 변경 없음 | 기존 30분 cooldown |
| Security | 변경 없음 | 스케줄러 내부 UseCase |
| Configuration | 기본 비활성·1분 polling 유지 | automatic reminder properties |
| Documentation | 현재 자동 리마인드 계약 갱신 | 이 문서와 notification client contract |

Existing behavior that must remain unchanged:

- 수동 단건·일괄 독촉과 30분 응답 계약.
- 요청 알림이 없는 과거 정산 백필 방지.
- 미납·활성 회원·정산 상태 재검증과 Outbox 전달 흐름.
- 품목 선택 미제출자는 자동 리마인드 대상에서 제외.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: 기존 알림 이력과 고유 제약 재사용)
- [x] UseCase and Application Command/Result (`N/A`: 기존 UseCase 재사용)
- [x] Required Output Ports
- [x] Domain models and rules (`N/A`: Domain 변경 없음)
- [x] Application Policies
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger (`N/A`: API 변경 없음)
- [x] Infra configuration and Bean wiring (`N/A`: 기존 설정·스케줄러 재사용)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 자동 리마인드 반복 정책 설계 기록
feat(settlement): 자동 리마인드 12시간 반복과 심야 이월 적용
test(settlement): 자동 리마인드 반복 일정 검증
docs(settlement): 자동 리마인드 반복 정책 완료 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew compileTestJava
./gradlew test --tests '*SettlementReminderServiceTest' --tests '*NotificationInboxIntegrationTest'
git diff --check
git status --short --untracked-files=all
```

PostgreSQL·Redis가 필요한 통합 테스트는 로컬 서비스와 테스트 환경 변수를 확인한 뒤 실행한다. 기존 테스트 케이스를 12시간 경계·반복·KST 03:00/06:00·동시 실행 시나리오로 갱신하며 새 테스트 케이스는 추가하지 않는다.

### Completion criteria

- [x] Purpose의 최초·반복·심야 이월 결과를 검증한다.
- [x] 정상·경계·동시 실행 흐름을 검증한다.
- [x] 현재 계약 문서와 실제 정책이 일치한다.
- [x] 미실행 검증과 이유를 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Migration 판단 | Passed | 기존 `user_notifications.created_at`, 중복키 고유 제약, 정산 잠금 재사용으로 schema 변경 없음 |
| Java 컴파일 | Passed | `./gradlew compileJava`, `./gradlew compileTestJava` |
| 12시간·심야 경계 단위 검증 | Passed | `SettlementReminderServiceTest` |
| PostgreSQL·Redis 통합 검증 | Passed | 격리 schema에서 `NotificationInboxIntegrationTest` 23건 통과 |
| 리마인드 관련 회귀 | Passed | 격리 schema에서 관련 6개 클래스 50건 통과 |
| Java 전체 회귀 | Partial | 1,164건 중 1,163건 통과. 범위 밖 `AccountTransactionPersistenceIntegrationTest.loadsHistoryAfterActiveAccountDeletion` 1건은 공유 DB의 다른 테스트 schema가 `information_schema` 결과에 포함되어 실패 |
| 실제 운영 스케줄·FCM 수신 | Not run | 운영 자동 스케줄러 설정과 실제 앱 기기 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-16 | ALM-STL-003, 최신 `origin/develop`, 기존 24시간 1회 흐름과 이력 구조 확인 | Port·Policy·Service·Adapter 구현 | 심야 이월 뒤 원래 슬롯을 유지하면 다음 간격이 12시간보다 짧아질 수 있음 |
| 2026-09-16 | 12시간 반복, KST 방해 금지, 반복 중복키와 legacy 이력 호환 구현 | 단위·통합·회귀 검증 | 공유 기본 schema는 기존 V16 checksum이 달라 격리 schema 필요 |
| 2026-09-16 | 관련 50건 통과, 전체 1,164건 중 1,163건 통과, 클라이언트 계약 갱신 | PR 리뷰와 운영 활성화 확인 | 전체 실패 1건은 공유 DB의 범위 밖 schema 간섭 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-16 | 최초는 Share 확정 시각, 이후는 마지막 실제 자동 리마인드 생성 시각을 12시간 기준으로 사용 | 심야 이월 뒤에도 수신 간격을 최소 12시간으로 유지 | Share 기준 고정 슬롯과 달리 이월 뒤 cadence가 실제 발송 시각에 맞춰 이동 |
| 2026-09-16 | KST 03:00 이상 06:00 미만에는 배치를 중단 | 후보를 소비하지 않아 06:00 이후 기존 조건으로 자연스럽게 이월 | 별도 예약 시각 저장 불필요 |
| 2026-09-16 | 기존 이력·중복키 고유 제약과 정산 잠금 재사용 | migration 없이 반복과 동시성 보장 | 중복키에 반복 기준 epoch second 추가 |

## 11. Completion record

- Completed on: 2026-09-16
- Final behavior: 요청 알림 이력이 있는 활성 연결 회원의 확정 Share가 미납이면 최초 12시간 후, 이후 마지막 실제 자동 리마인드부터 12시간마다 반복한다. KST 03:00 이상 06:00 미만에는 생성하지 않고 06:00 이후 첫 배치로 이월한다. 수동·자동 공통 30분 쿨다운과 기존 대상 재검증은 유지한다.
- Unverified items: 실제 운영 스케줄러 실행과 Firebase 기기 수신.
- Remaining risks: 기본 1분 polling 때문에 06:00 또는 12시간 정각보다 최대 polling 주기만큼 늦게 생성될 수 있다. 전체 회귀의 범위 밖 1건은 공유 DB에 남은 다른 테스트 schema 때문에 실패했다.
- Follow-up work: 운영에서 `SETTLEMENT_AUTOMATIC_REMINDER_ENABLED=true` 여부를 확인하고 실제 앱 알림 수신 시간을 점검한다.
