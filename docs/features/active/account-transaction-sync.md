# Feature Plan: 비동기 계좌 거래 동기화 작업 접수

- Status: Active
- Last updated: 2026-09-10
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/337
- Related ADR: N/A

## 1. 목적

### 문제

현재 계좌 거래 조회는 현재 월 cache miss 시 Backend가 CODEF를 동기 호출하고, 외부 응답을 기다리는 동안 요청 transaction과 계좌 row lock을 유지한다. V32 schema와 Connector RC2 경로가 준비됐지만 비동기 job 접수 API와 command 발행 경로가 없다.

### 지금 필요한 이유

Connector C3/C4가 `SyncAccountTransactions`를 접수하고 결과 event를 발행할 수 있으므로 Backend V2가 사용자 요청을 멱등 job으로 전환해야 서버 분리 경계를 실제로 연결할 수 있다.

### 성공 기준

- [x] 소유한 활성 CODEF KRW 입출금계좌와 최대 31일 범위만 `202 Accepted` job으로 접수한다.
- [x] 동일 `Idempotency-Key`와 동일 요청은 기존 job을 반환하고 다른 요청은 충돌시킨다.
- [x] 동일 계좌의 활성 job과 카나리 비허용 계좌를 명시적인 `409` 코드로 거부한다.
- [x] Connector에 대상 기관의 활성 계좌를 등록한 뒤 secret 없는 RC2 command를 `accountRef` key로 발행한다.
- [x] 요청 사용자는 자신의 job 상태만 조회한다.

### 제외 범위

- event chunk 수신과 거래 원자 반영(B2)
- SWR 조회와 정산 pending 보호(B3)
- command outbox 복구 정책과 terminal 정리(C5/B3)
- 실제 비어 있지 않은 계좌 카나리

## 2. 도메인 분석

### 도메인 경계

- 기존 도메인 재사용: `account`, `external`, `CurrentActorPort`, V32 sync schema
- 신규 도메인 개념: 계좌 거래 동기화 job, 금융 연결/계좌 공개 참조
- 다른 도메인과의 관계: Connector는 opaque ref와 RC2 command만 받고 Backend account ID나 CODEF secret을 저장하지 않는다.

### 보편 언어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| Sync Job | 한 계좌·날짜 범위의 비동기 거래 동기화 요청 | `AccountTransactionSyncJob` |
| Sync Version | 계좌별로 단조 증가하며 command/event를 현재 job과 대조하는 버전 | `syncVersion` |
| Connection Ref | Connector와 공유하는 임의 UUID 연결 식별자 | `connectionRef` |
| Account Ref | Connector와 공유하는 임의 UUID 계좌 식별자 | `accountRef` |

### 액터와 UseCase

| 액터 | 목표 | UseCase |
|---|---|---|
| 인증 사용자 | 계좌 거래 동기화 job 접수 | `RequestAccountTransactionSyncUseCase` |
| 인증 사용자 | 자신의 job 상태 조회 | `GetAccountTransactionSyncJobUseCase` |
| outbox relay | RC2 command 발행 | `PublishAccountTransactionSyncCommandUseCase` |

### 상태와 불변식

- 상태 전이: B1은 `QUEUED` 생성만 수행한다.
- 같은 `(userId, idempotencyKey)`는 한 요청 의미만 갖는다.
- 한 계좌에는 `QUEUED/COLLECTING/APPLYING` job이 최대 하나다.
- `syncVersion`은 계좌별 양의 정수이며 job에 저장된 값과 wire event 값이 같아야 한다.
- CODEF secret은 Connector 등록 HTTP 외부로 전달하거나 Kafka/로그에 넣지 않는다.

## 3. 시나리오

### 정상 흐름

1. 사용자가 UUID idempotency key와 날짜 범위로 job을 요청한다.
2. Application이 소유권·계좌 종류·통화·카나리 여부를 확인한다.
3. Backend가 opaque refs를 확보하고 Connector loopback API에 활성 계좌 전체를 등록한다.
4. job과 outbox를 원자 생성하고 `202` 결과를 반환한다.
5. relay가 RC2 command를 Kafka에 발행한다.

### 실패 흐름

- 비허용 계좌: `ACCOUNT_TRANSACTION_SYNC_NOT_ENABLED`
- 같은 key의 다른 요청: `ACCOUNT_TRANSACTION_SYNC_IDEMPOTENCY_CONFLICT`
- 다른 활성 job: `ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING`
- 비지원 계좌: 기존 `ACCOUNT_TRANSACTION_NOT_SUPPORTED`
- Connector 등록 실패: job을 만들지 않고 안전한 외부 연동 오류를 반환한다.

### 경계 조건

- `fromDate <= toDate <= Asia/Seoul 오늘`, 양 끝 포함 최대 31일
- 동일 요청 재전달은 Connector PUT이 멱등하며 기존 job을 반환한다.
- 계좌 삭제 후에도 조회 이력은 `account_records` FK로 보존된다.

## 4. 계약

### Input Port와 경계 매핑

```text
RequestAccountTransactionSyncUseCase
RequestAccountTransactionSyncCommand
AccountTransactionSyncJobResult
AccountTransactionSyncWebRequest -> RequestAccountTransactionSyncCommand
AccountTransactionSyncJobResult -> AccountTransactionSyncJobWebResponse
```

### Output Ports

| Port | 목적 | 호출자 | 예상 Adapter |
|---|---|---|---|
| `AccountTransactionSyncJobStorePort` | refs 준비, job 멱등 생성과 소유 조회 | sync Application Service | PostgreSQL JDBC |
| `FinanceConnectorRegistrationPort` | Connector 연결 전체 멱등 등록 | sync Application Service | loopback RestClient |
| `AccountTransactionSyncCommandOutboxPort` | ready command 선점·발행 완료 | command relay | PostgreSQL JDBC |
| `AccountTransactionSyncCommandPublisherPort` | RC2 command를 Kafka 발행 | command relay | KafkaTemplate |
| `AccountTransactionSyncEligibilityPort` | 런타임 카나리 allowlist 확인 | sync Application Service | configuration adapter |

### Application Policy

- 결정: 날짜 범위와 지원 계좌 조건은 Application Service가 현재 Account fact로 검증한다.
- 사실: `Account`, `ExternalConnection`, 카나리 활성 여부
- 별도 Policy가 아닌 이유: 한 UseCase의 고정 입력 검증이며 재사용 결정 지점이 아직 없다.
- 향후 입력: 운영 rollout/다중 기관 조건은 이번 범위에서 도입하지 않는다.

### Web API

```text
POST /api/v2/accounts/{accountId}/transaction-sync-jobs
Header: Idempotency-Key: UUID
Request: { "fromDate": "YYYY-MM-DD", "toDate": "YYYY-MM-DD" }
Response: 202 ApiResponse<AccountTransactionSyncJobWebResponse>
Errors: ACCOUNT_TRANSACTION_SYNC_IDEMPOTENCY_CONFLICT,
        ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING,
        ACCOUNT_TRANSACTION_SYNC_NOT_ENABLED
Authentication: bearerAuth

GET /api/v2/account-transaction-sync-jobs/{syncJobId}
Response: 200 ApiResponse<AccountTransactionSyncJobWebResponse>
Authentication: bearerAuth; owner only
```

### 영속성과 외부 연동

- Database 변경: V32 테이블 재사용 예정이나 `syncVersion` 저장/할당 column 누락 발견
- Flyway migration required (`Yes` / `No`): Yes
- Planned migration: V33에 계좌별 sync version counter와 job snapshot 추가
- Migration pull request: https://github.com/Team-HowMuch/howmuch-backend-v2/pull/339 (Merged)
- Redis 변경: B1 없음
- External API/message: `PUT /internal/v1/connections/{connectionRef}`, `finance.sync.command.v1`
- Environment: Connector base URL/service token, Kafka bootstrap, canary account ID allowlist

## 5. 설계 결정

### 비즈니스 로직

- Web 경계는 날짜와 UUID 형식을 검증하고 Application이 오늘/31일/계좌 상태를 검증한다.
- refs와 계좌별 `syncVersion` 발급, job/outbox 생성은 PostgreSQL transaction에서 직렬화한다.
- Connector 등록 외부 호출은 DB transaction 밖에서 수행한다.
- Connector RC2의 연결당 `organizationCode` 계약에 맞춰 대상 기관에서 Connector 필수 정보가 완전한 활성 계좌를 등록한다. 다중 기관을 한 snapshot에 담는 계약 확장은 카나리 이후로 미룬다.

### 기술과 패턴

- 제약: V32에 `syncVersion`을 안전하게 할당하고 결과 event와 대조할 저장 위치가 없다.
- 선택: `finance_account_refs.sync_version` counter와 `account_transaction_sync_jobs.sync_version` snapshot을 V33으로 추가한다.
- 이유: 연결 version 재사용이나 임의 UUID 변환은 두 번째 job 충돌과 stale event 판정을 해결하지 못한다.
- 대안: connectionVersion 재사용, UUID bit 변환은 계좌별 version 의미를 보장하지 못해 제외한다.
- 한계: C5/B3 전까지 relay retry와 terminal 복구는 제한적이다.

## 6. 영향 범위

| 영역 | 영향 | 확인 파일 또는 계약 |
|---|---|---|
| API | POST/GET 신규 | `adapter.in.web.account` |
| Application | sync UseCase/Service/Result | `application.account` |
| Domain | job/status | `domain.account` |
| Persistence | V32 refs/job/outbox 사용, V33 필요 | `db/migration/V32...` |
| Redis | B1 없음 | `AccountTransactionRefreshStorePort` 유지 |
| Security | 기존 `/api/**` 인증 적용 | `SecurityConfig` |
| Configuration | Connector/Kafka/canary 설정 | `infra`와 profile yaml |
| Documentation | 본 Feature Plan과 Swagger | 이 문서, `AccountDocs` |

기존 동작 유지:

- 기존 `GET /api/v2/accounts/{accountId}/transactions` 응답과 비허용 계좌의 동기 CODEF 경로는 B1에서 변경하지 않는다.
- 기존 계좌 연결 API와 정산 경로는 B1에서 변경하지 않는다.

## 7. 구현 계획

- [x] Migration-only pull request to `develop` (`Required` - V33)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules
- [x] Application Policies (`N/A` - B1 고정 검증은 Service가 조율)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring
- [x] Documentation and verification results

### 예상 커밋 경계

```text
build(account): 계좌 동기화 버전 저장 스키마 보완
docs(account): 비동기 거래 동기화 접수 설계 기록
feat(account): 계좌 거래 동기화 작업 접수 구현
feat(account): Connector 등록과 command outbox 발행 연결
test(account): 계좌 거래 동기화 접수 검증
docs(account): 비동기 거래 동기화 접수 검증 기록
```

## 8. 검증

### 명령

```bash
bash scripts/test.sh
./gradlew clean build --no-daemon
git diff --check
git status --short --untracked-files=all
```

### 완료 조건

- [x] 목적의 사용자 결과를 검증했다.
- [x] 정상·실패·경계 흐름을 검증했다.
- [x] Swagger와 RC2 command가 실제 계약과 일치한다.
- [x] 실행하지 못한 검증과 이유를 기록했다.

### 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| V32 schema gap | Resolved | V33에서 `syncVersion` counter와 job snapshot을 추가함 |
| V33 migration | Passed | PR #339 병합, 계좌별 version backfill·제약·전체 build 검증 |
| Application/Web | Passed | focused test 10건 통과: 권한·날짜·멱등성·충돌·카나리·Connector 요청·HTTP 계약 |
| PostgreSQL persistence | Passed | 실제 PostgreSQL Testcontainers로 ref 재사용, job/outbox 원자 생성과 조회 검증 |
| Kafka RC2 command | Passed | `apache/kafka:4.2.1` Testcontainers에서 schema 검증 후 `accountRef` key 발행 확인 |
| 전체 테스트 | Passed | `bash scripts/test.sh clean test` Java 전체 suite와 JavaScript 35건 통과 |
| GitHub Packages | Local limitation | 로컬 토큰에 `read:packages` 권한이 없어 발행 완료된 RC2를 Maven Local로 검증; PR CI의 `GITHUB_TOKEN`으로 재확인 필요 |
| 개발환경 카나리 | Not run | allowlist 기본값을 비워 두었으며 배포와 실제 계좌 검증은 Q1 범위 |

## 9. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견 위험 |
|---|---|---|---|
| 2026-09-09 | C4 Connector merge, Backend V2 전체 호출 경로와 V32 조사 | V33 migration-only PR | 두 번째 job부터 재사용 불가능한 `syncVersion` 누락 |
| 2026-09-09 | V33 PR #339 병합 확인, B1 작업 재개 | API·Application·Adapter 구현 | Connector RC2가 연결당 단일 기관 snapshot만 표현함 |
| 2026-09-09 | B1 API, Connector 등록, job/outbox, RC2 Kafka 발행 구현과 통합 검증 | PR CI와 머지 | 로컬 GitHub 토큰은 Packages 읽기 권한 없음 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 | 대안 또는 영향 |
|---|---|---|---|
| 2026-09-09 | B1 구현 전 V33 migration gate로 전환 | 안전한 version 발급/대조 없이는 RC2 contract를 구현할 수 없음 | migration PR 후 별도 후속 작업에서 Java 구현 |
| 2026-09-09 | 등록 snapshot을 대상 기관의 활성 계좌로 제한 | Connector RC2의 연결 저장 계약이 `organizationCode`를 연결 단위로 가짐 | 다중 기관 snapshot 계약은 카나리 이후 별도 변경 필요 |

## 11. 완료 기록

- 완료일: B1 PR 머지 후 기록
- 최종 동작: 카나리 계좌의 비동기 동기화 요청을 멱등 job으로 저장하고 Connector 등록 후 RC2 command를 발행하며 사용자 소유 job 상태를 조회함
- 미검증 항목: 개발환경 실제 계좌 카나리, GitHub Actions의 private contract package 접근
- 남은 위험: Connector 실제 응답의 안정 provider transaction ID 미확인
- 후속 작업: B2 결과 수신·원자 반영, B3 상태 전이·SWR·정산 보호, Q1 카나리
