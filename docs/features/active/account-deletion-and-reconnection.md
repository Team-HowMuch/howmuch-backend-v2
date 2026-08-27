# 기능 계획: 계좌 물리 삭제와 재연결 이력 분리

- 상태: 진행 중
- 최종 수정일: 2026-08-24
- 관련 이슈: [#97 계좌 삭제와 CODEF 재연결 정책 결정](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/97)
- 관련 ADR: [ADR-0004 Active Account와 Account Record 수명주기 분리](../../adr/0004-separate-active-account-and-account-record-lifecycles.md)
- 관련 문서: [CODEF 은행 연결과 결제계좌 연동](codef-bank-account-connection.md), [계좌 조회와 거래 동기화](account-query-and-transactions.md), [정산 수령 계좌](../completed/settlement-receiving-account.md), [정산 요청 링크](../completed/settlement-request-link-spec.md)

## 1. 목적

### 문제

현재 `accounts.active=false`는 설정 불일치 계좌를 숨기는 용도이고, 같은 `candidateKey`를 다시 연동하면 기존 `accounts.id`를 갱신·재활성화한다. 또한 `account_transactions`, `account_monthly_summaries`, `settlements.receiving_account_id`가 `accounts`를 참조하므로 계좌 행을 물리 삭제할 수 없다.

사용자가 계좌를 삭제한 뒤 같은 외부 계좌를 다시 연결할 때는 다음 의미가 필요하다.

- 삭제한 결제수단의 CODEF 식별정보와 현재 계좌 상태는 `accounts`에서 제거한다.
- 기존 거래, 월 요약, 정산 지출, 확정된 수령 계좌 스냅샷은 보존한다.
- 다시 연결한 계좌는 새 `accounts.id`를 받으며 삭제 전 기록을 새 계좌 기록으로 합치지 않는다.

### 지금 결정하는 이유

삭제 정책 없이 API부터 추가하면 `ON DELETE RESTRICT` 오류, 기존 계좌 재활성화, 확정 정산의 수령 정보 손실 중 하나가 발생한다. 스키마와 재연결 경계를 먼저 확정해야 마이그레이션과 구현을 분리할 수 있다.

### 성공 기준

- [ ] 본인 활성 계좌 삭제 시 해당 행과 `external_account_key_ciphertext`, `candidate_key`, 잔액·기본 계좌 설정이 `accounts`에서 물리 삭제된다.
- [ ] 삭제 전 거래·월 요약·정산 지출·확정 정산 스냅샷은 삭제되지 않는다.
- [ ] 삭제한 외부 계좌를 다시 연결하면 새 `accounts.id`가 생성된다.
- [ ] 새 계좌의 거래 조회는 연결 시각과 무관하게 사용자가 요청한 조회 기간 전체를 동기화한다.
- [ ] 삭제 계좌를 수령 계좌로 선택한 DRAFT 정산이 하나라도 있으면 계좌 삭제를 거부한다.
- [ ] 삭제가 기본 계좌를 제거하면 남은 계좌 중 `displayOrder`, `id` 순 첫 계좌가 기본 계좌가 된다.
- [ ] 다른 사용자의 계좌와 이미 삭제한 계좌는 동일하게 `404 ACCOUNT_NOT_FOUND`를 반환한다.
- [ ] 계좌 삭제는 CODEF 기관 인증정보를 삭제하지 않으며 외부 장애와 무관하게 완료된다.

### 제외 범위

- 삭제 계좌의 거래를 조회하는 별도 History API
- 사용자가 모든 금융기관 연결을 해지하는 API
- CODEF `/v1/account/delete` 호출과 `ConnectedInstitution`/`ExternalConnection` 삭제
- 법적 보존기간 또는 개인정보 삭제 요청에 따른 `account_records` 파기 정책
- 삭제 전 거래 행을 재연결 계좌로 이관하거나 기존 행의 `accountId`를 변경하는 작업

## 2. 도메인 분석

### 도메인 경계

- 기존 `account` 도메인을 재사용한다.
- `Account`는 현재 연결되어 사용할 수 있는 결제수단이다.
- `AccountRecord`는 거래와 정산의 출처를 잃지 않기 위한 비민감 수명주기 기록이다. 현재 조회·수정 가능한 `Account`가 아니며 재연결 대상도 아니다.
- `external`은 CODEF `connectedId`와 기관 인증정보를 소유한다. 로컬 `Account` 하나의 삭제와 기관 인증정보 삭제는 서로 다른 사용자 목표다.
- `settlement`은 DRAFT에서 활성 `Account`를 선택하지만, 요청 확정 이후에는 `SettlementReceivingAccountSnapshot`을 진실의 원천으로 사용한다.

### 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 활성 계좌 | 현재 목록·상세·거래 동기화에 사용할 수 있는 결제수단 | `Account`, `accounts` |
| 계좌 기록 | 한 번 연결된 계좌 수명주기의 최소 비민감 출처 정보 | `account_records` |
| 로컬 계좌 삭제 | `accounts` 행만 물리 삭제하고 기관 인증정보는 유지하는 동작 | `DeleteAccountUseCase` |
| 기관 연결 해지 | CODEF 기관 계정 정보를 삭제하는 별도 동작 | 현재 범위 밖 |
| 거래 조회 기간 | 연결 시각과 무관하게 CODEF에서 가져올 거래 기간 | API가 요청한 `YearMonth`의 시작일과 종료일 |

### Actor와 UseCase

| Actor | 목표 | UseCase |
|---|---|---|
| 인증 사용자 | 본인 결제수단을 서비스에서 삭제한다 | `DeleteAccountUseCase` |
| 인증 사용자 | 같은 외부 계좌를 새 결제수단으로 연결한다 | 기존 `ConnectBankUseCase`, `LinkBankAccountsUseCase` |

### 상태와 불변식

- `accounts` 행이 존재하면 현재 결제수단이고, 삭제하면 같은 행을 재활성화하지 않는다.
- 모든 `accounts.id`에는 같은 ID의 `account_records`가 하나 존재한다.
- `account_records`에는 `external_connection_id`, `candidate_key`, `external_account_key_ciphertext`, 잔액, 기본 여부, 표시 순서를 저장하지 않는다.
- `account_transactions.account_id`, `account_monthly_summaries.account_id`, `settlement_receiving_account_snapshots.source_account_id`는 `account_records.id`를 참조한다.
- 삭제된 Account Record의 `deleted_at`은 필수이며 행 자체는 후속 보존 정책 전까지 삭제하지 않는다.
- 새 계좌는 새 ID와 새 `linked_at`을 가지지만, `linked_at`은 거래 조회·저장 필터로 사용하지 않는다.
- 재연결 계좌가 과거 기간을 조회하면 해당 기간의 거래를 새 `accountId` 아래 새 행으로 저장할 수 있으며, 삭제 전 거래 행을 이관하거나 연결하지 않는다.
- 활성 계좌가 하나 이상이면 기본 계좌는 정확히 하나다. 삭제 후 `displayOrder` 간격은 재정렬하지 않는다.
- DRAFT 정산이 수령 계좌로 참조 중인 Account는 삭제할 수 없다.

## 3. 시나리오

### 정상 흐름: 계좌 삭제

1. 사용자가 `DELETE /api/v2/accounts/{accountId}`를 호출한다.
2. Application이 현재 사용자 소유의 활성 계좌인지 확인한다.
3. 같은 `ExternalConnection`의 연동·동기화 요청과 직렬화한 뒤 계좌를 다시 확인한다.
4. 해당 계좌를 수령 계좌로 선택한 DRAFT 정산이 없는지 확인한다.
5. 요청이 완료된 정산의 활성 계좌 참조만 분리하고 `SettlementReceivingAccountSnapshot`과 Share는 유지한다.
6. PostgreSQL이 Account Record를 보존하고 `accounts` 행을 삭제한다.
7. 기본 계좌였다면 남은 첫 계좌를 기본 계좌로 승격한다.
8. 서버는 빈 성공 응답을 반환한다.

### 정상 흐름: 같은 외부 계좌 재연결

1. 기존 `POST /api/v2/accounts/connections/banks`가 기관 자격증명을 갱신하고 후보를 반환한다.
2. 삭제한 계좌의 `candidateKey`는 활성 계좌 목록에 없으므로 `alreadyLinked=false`다.
3. `POST /api/v2/accounts/external`이 새 `accounts` 행과 새 Account Record를 만든다.
4. 거래 동기화는 연결 시각과 무관하게 사용자가 요청한 월 전체를 CODEF에서 조회한다.
5. 같은 외부 거래가 삭제 전 계좌에도 있었더라도 재연결 계좌에는 새 `accountId`의 별도 거래로 저장되며 기존 행을 이관하지 않는다.

### 실패 흐름

- 계좌가 없거나 현재 사용자 소유가 아니다: `404 ACCOUNT_NOT_FOUND`이며 어떤 행도 변경하지 않는다.
- 같은 삭제 요청을 반복한다: 두 번째 요청은 `404 ACCOUNT_NOT_FOUND`다.
- DRAFT 정산이 해당 계좌를 수령 계좌로 사용한다: `409 ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT`이며 정산과 계좌를 변경하지 않는다.
- 삭제와 거래 동기화가 겹친다: 먼저 `ExternalConnection` 잠금을 얻은 요청이 완료된 뒤 다른 요청이 계좌 존재를 재검증한다. 삭제가 먼저면 동기화는 `ACCOUNT_NOT_FOUND`로 끝난다.
- 삭제와 재연결이 겹친다: 기존 연결 행 잠금으로 직렬화하여 삭제된 ID가 다시 활성화되지 않게 한다.
- 삭제와 정산 수령 계좌 선택이 겹친다: 두 흐름이 계좌 행을 잠근 뒤 상태를 확인한다. 선택이 먼저 완료되면 삭제는 409, 삭제가 먼저 완료되면 선택은 `ACCOUNT_NOT_FOUND`다.
- Account Record 보존, 기본 계좌 승격, 계좌 삭제 중 하나라도 실패한다: 전체 트랜잭션을 롤백한다.

### 경계 조건

- 마지막 활성 계좌를 삭제하면 기본 계좌는 없다.
- 기본 계좌가 아닌 계좌를 삭제하면 기존 기본 계좌는 유지한다.
- 하나의 계좌를 여러 DRAFT 정산이 참조하더라도 하나라도 남아 있으면 삭제할 수 없다.
- 확정된 정산은 활성 Account가 아니라 암호화된 수령 계좌 스냅샷을 계속 사용한다.
- 가져온 정산 지출은 `description`, `amount`, `sourceReference`를 자체 보존하므로 계좌 삭제 대상이 아니다.

## 4. 계약

### Input Port와 경계 매핑

```text
UseCase: DeleteAccountUseCase.delete(Long accountId)
Command: N/A - 단일 양수 식별자만 사용
Result: N/A
Web Request DTO -> Command: N/A
Result -> Web Response DTO: N/A
```

### Output Ports

| Port | 목적 | Caller | 예상 Adapter |
|---|---|---|---|
| 기존 `LoadAccountPort` | 소유 계좌 조회와 삭제·수령 계좌 선택 시 행 잠금 | `AccountDeletionService`, `SettlementReceivingAccountService`, `AccountQueryService` | `AccountPersistenceAdapter` |
| `DeleteAccountPort` | 기본 계좌 승격과 Account 물리 삭제를 하나의 저장 capability로 수행 | `AccountDeletionService` | `AccountPersistenceAdapter` |
| 기존 `ExternalConnectionStorePort` | 연결·재연결·거래 동기화·삭제를 같은 연결 단위로 직렬화 | Account Application Services | `ExternalConnectionPersistenceAdapter` |
| `SettlementReceivingAccountUsagePort` | DRAFT 참조 존재 여부를 확인하고 요청 완료 정산의 활성 계좌 참조만 분리 | `AccountDeletionService` | Settlement Persistence Adapter |

별도 `AccountRecordPort`는 현재 Application caller가 없으므로 만들지 않는다. Account Record 생성·갱신·삭제 시각 기록은 마이그레이션의 PostgreSQL trigger가 책임진다.

### Application Policy

- 별도 Policy 클래스: N/A.
- 다음 기본 계좌는 기존 정렬 규칙인 `displayOrder`, `id` 순으로 Service가 선택한다.
- DRAFT 참조 여부는 `SettlementReceivingAccountUsagePort`로 조회하고 Service가 삭제 가능 여부를 결정한다.
- 기관 연결 해지 여부는 로컬 계좌 삭제의 입력 사실만으로 결정할 수 없으므로 이번 UseCase에 섞지 않는다.

### Web API

```text
DELETE /api/v2/accounts/{accountId}
Request: 없음
Response: HTTP 200, ApiResponse<Void>
Errors: 404 ACCOUNT_NOT_FOUND, 409 ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT
Authentication: required
```

### 영속화와 외부 연동

- Database Entity, constraint, or query changes:
  - `account_records(id, user_id, organization, type, name, masked_number, currency, linked_at, deleted_at)` 추가
  - 기존 `accounts`를 `account_records`로 backfill
  - `accounts` INSERT/표시정보 UPDATE 시 Account Record를 upsert하고 DELETE 시 `deleted_at`을 기록하는 PostgreSQL trigger 추가
  - `account_transactions.account_id`, `account_monthly_summaries.account_id` FK를 `account_records(id)`로 변경
  - 거래·월 요약 JPA Entity는 활성 `AccountJpaEntity` 연관관계 대신 scalar `Long accountId`를 사용하고 무결성은 PostgreSQL FK로 보장
  - `settlement_receiving_account_snapshots.source_account_id` FK를 `account_records(id)`로 변경하고 `settlement_id`는 `settlements(id) ON DELETE CASCADE`로 독립
  - `settlements(creator_id, receiving_account_id)`의 기존 소유자 복합 FK와 삭제 제한을 유지
  - 계좌 삭제 전에 요청 완료 정산의 `receiving_account_id`만 명시적으로 `NULL` 처리하고 DRAFT 참조는 삭제 제한의 최종 방어선으로 유지
  - `linked_at`은 연결 이력 표시에만 사용하고 거래 조회 범위를 제한하는 조건에는 사용하지 않음
- Flyway migration required: `Yes`
- Planned migration: `V17__preserve_account_records_for_deletion.sql`
- Migration pull request: [#126](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/126) (`Open`, 의존 문제 해결 전 병합 보류)
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 계좌 삭제에서는 없음
- Environment variable or profile changes: 없음

마이그레이션 trigger는 migration-only PR이 Java 배포보다 먼저 적용되어도 기존 애플리케이션이 생성한 계좌에 Account Record가 빠지지 않게 한다. trigger를 사용하지 않으려면 expand → Java dual-write → contract의 2단계 마이그레이션이 필요하다.

## 5. 설계 결정

### 비즈니스 로직

- `accounts`는 현재 결제수단만 보관하고 삭제된 행을 재활성화하지 않는다.
- Account Record는 기록의 출처와 소유권만 보존하며 현재 계좌 기능에 노출하지 않는다.
- 같은 외부 계좌의 재연결은 새 수명주기이므로 새 ID를 사용하되, 거래는 사용자가 요청한 조회 기간 전체를 다시 가져올 수 있다.
- 과거 거래를 다시 가져오는 것은 기존 거래 행의 연결이나 이관이 아니라 새 Account 수명주기에 대한 별도 동기화다.
- DRAFT 정산이 참조 중인 계좌는 사용자가 정산에서 다른 계좌를 선택하거나 정산을 종료하기 전까지 삭제하지 않는다.
- 기본 계좌 승격과 외부 연결 잠금 순서는 Application Service가 조정한다.

### CODEF 경계

CODEF 공식 `/v1/account/delete`는 선택한 은행계좌 한 건이 아니라 `connectedId`에 등록된 기관 계정 정보를 삭제한다. 등록된 기관 정보를 모두 삭제하면 `connectedId`도 삭제된다. 따라서 로컬 `Account` 한 건 삭제에서는 호출하지 않는다.

기관 연결 해지는 별도 사용자 확인과 별도 UseCase가 필요하다. 구현 시 해당 기관의 모든 활성 Account 처리, `ConnectedInstitution` 상태, 마지막 기관 삭제 시 `ExternalConnection` 상태를 함께 결정한다.

### 기술과 패턴

- 제약: 거래·월 요약·정산 FK를 유지하면서 `accounts` 행을 물리 삭제해야 하고, migration-only PR이 Java보다 먼저 배포될 수 있다.
- 선택: 현재 Account와 별도 Account Record를 두고 PostgreSQL trigger로 배포 순서 사이의 dual-write를 보장한다.
- 선택 이유: 민감한 CODEF 연결정보를 제거하면서 기존 기록의 FK와 소유권을 유지하고, 재연결을 새 ID로 자연스럽게 분리한다.
- 제외한 대안:
  - `active=false` soft delete: 물리 삭제 요구와 맞지 않고 실수로 기존 ID를 재활성화할 수 있다.
  - 거래마다 계좌 표시정보 복제: 중복 데이터가 커지고 월 요약·정산에 같은 복제가 필요하다.
  - FK 제거 후 숫자 ID만 유지: 고아 기록을 DB가 막지 못한다.
- 한계: 삭제 계좌 전용 조회 API가 없으므로 보존된 거래는 현재 `GET /api/v2/accounts/{accountId}/transactions`로 조회하지 않는다.

## 6. 영향 범위

| 영역 | 영향 | 확인한 파일 또는 계약 |
|---|---|---|
| API | 계좌 삭제 endpoint와 Swagger 추가 | `AccountController`, `adapter.in.web.account.docs.AccountDocs` |
| Application | 삭제 UseCase·Service, DRAFT 사용 확인, 잠금 후 계좌 재검증, 기존 월 단위 거래 동기화 유지 | `BankAccountService`, `AccountQueryService`, `SettlementReceivingAccountService` |
| Domain | 필요 시 기본 계좌 선택 규칙 재사용 | `domain.account.Account` |
| Persistence | Account Record, FK 전환, 거래·월 요약 scalar ID 매핑, trigger, 물리 삭제와 기본 승격 | V2, V10, V15, V16의 후속 migration과 account persistence |
| Redis | 없음 | N/A |
| Security | 본인 소유 계좌만 삭제, 타인 계좌도 404 | `CurrentActorPort`, `LoadAccountPort` |
| Configuration | 없음 | N/A |
| Documentation | 연결·조회·정산 문서에 삭제 수명주기 연결 | 관련 Feature Plan과 ADR-0004 |

기존 동작 중 유지할 항목:

- 활성 계좌의 목록·상세·월별 거래 API 계약은 바꾸지 않는다.
- 같은 활성 계좌를 다시 연동하면 현재 행의 표시정보와 잔액을 갱신한다.
- CODEF 연결 생성·기관 추가·기관 인증정보 수정 흐름은 유지한다.
- 정산 지출과 확정된 수령 계좌 스냅샷은 계좌 삭제로 변경하지 않는다.

## 7. 구현 계획

- [x] Migration-only pull request to `develop` (`Required`, PR #126 병합 보류)
- [x] `DeleteAccountUseCase` 추가 (`Command`/`Result`는 N/A)
- [x] `LoadAccountPort` 잠금 조회, `DeleteAccountPort`, `SettlementReceivingAccountUsagePort` 추가
- [x] 거래 조회는 기존 `AccountQueryService`의 요청 월 전체 조회를 유지하고 `linkedAt` 필터를 추가하지 않음
- [x] Application Policy (`N/A` - Service의 기존 정렬 규칙으로 충분)
- [x] `AccountDeletionService`에서 연결 잠금 → 계좌 재검증 → DRAFT 참조 확인 → 요청 완료 참조 분리 → 기본 계좌 결정 → 삭제 조정
- [x] Account Persistence Adapter의 물리 삭제·기본 계좌 승격
- [x] 거래·월 요약 JPA Entity를 scalar `accountId`로 변경하고 Account 삭제 후 이력 조회 검증
- [x] 계좌 삭제 Controller·Swagger 추가
- [x] Infra configuration and Bean wiring (`N/A` - 기존 component scan 사용)
- [x] 관련 Feature Plan과 검증 결과 갱신

구현 요청이 시작되면 Flyway gate에 따라 migration-only PR을 먼저 생성하고 그 작업을 즉시 종료한다. Java 구현은 별도 후속 작업에서 진행한다.

### 예상 commit 경계

실제 commit은 요청받은 경우에만 만든다. Flyway gate의 migration-only commit은 예외다.

```text
docs(account): 계좌 삭제와 재연결 수명주기 결정
build(database): 계좌 기록 보존과 물리 삭제 스키마 추가
feat(account): 계좌 물리 삭제 기능 추가
test(account): 계좌 삭제와 재연결 기록 분리 검증
docs(account): 계좌 삭제 구현 결과 반영
```

## 8. 검증

### 명령

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

실행 검증에는 PostgreSQL, Redis, CODEF Mock, 삭제 전 거래가 있는 계좌, DRAFT·요청 완료 정산이 필요하다.

### 완료 기준

- [x] 계좌 삭제 뒤 `accounts` 행은 없고 Account Record는 남는다.
- [x] 삭제 계좌의 CODEF 암호문·candidate key·잔액·기본 설정은 Account Record에 남지 않는다.
- [x] DRAFT 정산이 수령 계좌로 참조하면 409로 삭제를 거부하고 어떤 삭제 작업도 호출하지 않는다.
- [x] DRAFT 참조가 없으면 요청 완료 정산의 활성 계좌 참조만 분리된다.
- [ ] 삭제한 계좌 후보를 다시 연결하면 새 ID가 생기고, 요청한 과거 기간 거래도 새 ID의 별도 거래로 저장한다.
- [ ] 기본 계좌 삭제, 마지막 계좌 삭제, 반복 삭제, 타인 계좌 삭제를 검증한다.
- [ ] 삭제와 거래 동기화·재연결·정산 계좌 선택의 동시 요청에서 500과 고아 참조가 생기지 않는다.
- [x] 계좌 삭제 UseCase가 CODEF Port에 의존하지 않는다.
- [x] Swagger와 관련 문서가 실제 계약과 일치한다.

### 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 열린 이슈·현재 코드·Flyway 관계 분석 | 완료 | Issue #97, V2/V10/V15/V16, Account·Settlement 서비스 확인 |
| CODEF 계정 삭제 계약 확인 | 완료 | 공식 `cid-delete` 문서의 `/v1/account/delete` 입력·삭제 범위 확인 |
| 거래 조회 기간 정책 수정 | 완료 | 기존 `AccountQueryService`가 요청 월 전체를 조회하며 `linkedAt` 필터가 없음을 확인 |
| 계좌 삭제 Application·Web 구현 | 완료 | UseCase, 연결·Account 잠금, DRAFT 충돌, 요청 완료 참조 분리, 기본 계좌 승격, DELETE API |
| 자동 테스트 | 완료 | V17 PostgreSQL·Redis에서 전체 281개 통과 후 삭제 영속 통합 테스트 별도 통과 |
| 과거 이력 FK·JPA 매핑 | 완료 | Account 삭제 후 거래·월 요약 조회 및 두 FK의 `account_records` 참조 확인 |
| 남은 런타임 검증 | 대기 | 재연결 새 ID·과거 월 거래 조회와 실제 동시 요청 검증 |

## 9. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견 위험 |
|---|---|---|---|
| 2026-08-24 | 최신 `origin/develop`, Issue #97, Account/거래/정산 FK와 CODEF 삭제 계약을 분석하고 정책 초안 작성 | 팀 리뷰 후 migration-only 작업 | 현재 물리 삭제는 V10·V15·V16 FK로 차단되며 재연결은 같은 ID를 재활성화함 |
| 2026-08-24 | 재연결 계좌의 거래 조회에서 `linked_at` 하한을 사용하지 않도록 정책 수정 | 계좌 삭제 Java 구현 | 동일 외부 거래가 삭제 전후 Account에 각각 저장될 수 있음 |
| 2026-08-24 | 계좌 삭제 UseCase·API·영속 처리와 V17 통합 검증 완료 | 재연결·동시성 시나리오 검증 | 구현 브랜치는 병합 보류된 PR #126 스키마에 의존함 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 | 대안 또는 영향 |
|---|---|---|---|
| 2026-08-24 | `accounts`는 물리 삭제하고 Account Record만 보존 | 현재 결제수단과 과거 기록의 수명주기를 분리 | soft delete·재활성화는 사용하지 않음 |
| 2026-08-24 | 재연결은 새 ID를 사용하되 요청 기간의 과거 거래도 다시 조회 | 최초 연결과 재연결 모두 과거 거래 조회가 필요함 | 기존 행은 이관하지 않고 새 Account의 별도 거래로 저장 |
| 2026-08-24 | 로컬 계좌 삭제에서 CODEF delete를 호출하지 않음 | CODEF 삭제 단위는 개별 은행계좌가 아니라 기관 인증정보임 | 기관 연결 해지는 별도 기능 |
| 2026-08-24 | DRAFT 정산이 수령 계좌로 사용하는 Account 삭제를 거부 | 진행 중인 정산의 사용자 선택을 암묵적으로 변경하지 않음 | 사용자가 다른 수령 계좌를 선택하거나 DRAFT를 종료한 뒤 삭제 |

## 11. 완료 기록

- 완료일: 미완료
- 최종 동작: 정책 초안만 작성
- 미검증 항목: migration, Java 구현, PostgreSQL·CODEF Mock 실행
- 남은 위험: trigger 허용 여부와 보존 기간은 팀 합의 필요
- 후속 작업: ADR 승인, migration-only PR, 별도 Java 구현
