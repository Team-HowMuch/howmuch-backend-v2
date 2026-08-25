# Feature Plan: 정산 입금 대조 및 종료

- Status: In Progress
- Last updated: 2026-08-25
- Related issues: [#136 정산 입금 상태 확인 및 종료 처리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/136), [#154 계약 및 상태 전이 설계](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/154), [#155 입금 상태 및 이력 스키마](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/155), [#156 입금 대조 및 완료 전환](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/156), [#157 Swagger 및 통합 검증](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/157)
- Related ADR: N/A
- Prerequisite: [정산 요청 링크 및 Share 확정](../completed/settlement-request-link-spec.md)

## 1. Purpose

### Problem

정산 참여자는 확정된 `SettlementShare` 금액과 수금 계좌를 조회해 Toss 송금 화면으로 이동할 수 있지만, 서버는 실제 입금 여부를 확인하지 않는다. 따라서 생성자는 누가 얼마를 납부했는지 알 수 없고, 모든 Share가 입금되어도 Settlement가 `AWAITING_DEPOSIT`에서 `COMPLETED`로 전환되지 않는다.

### Why now

정산 목록과 후속 상세 화면은 실제 완료 참여자 수, 납부 완료 금액과 진행도를 요구한다. 이 값을 추측하지 않으려면 CODEF 수입 거래와 불변 Share를 연결하는 검증 가능한 저장 근거가 먼저 필요하다.

### Success criteria

- [x] 생성자가 `AWAITING_DEPOSIT` 정산의 수금 계좌 거래를 다시 조회해 입금 대조를 실행할 수 있다.
- [x] 서버는 하나의 수입 거래를 하나의 Share에 최대 한 번만 반영한다.
- [x] 부분 입금은 누적하되 Share 금액을 초과하거나 참여자를 식별할 수 없는 거래는 자동 반영하지 않는다.
- [x] 생성자 본인을 제외한 모든 납부 대상 Share의 확인 금액이 예정 금액과 정확히 일치할 때만 Settlement를 `COMPLETED`로 전환한다.
- [x] 재조회와 중복 CODEF 응답이 입금 금액이나 완료 상태를 중복 반영하지 않는다.
- [x] 계좌번호, CODEF `connectedId`, 원본 거래 설명을 공개 응답이나 로그에 노출하지 않는다.

### Out of scope

- Toss 송금 실행 또는 Toss 성공 콜백을 입금 증명으로 사용하는 기능
- 식별 불가능하거나 중복 이름인 거래를 생성자가 수동 연결하는 관리 UI/API
- 초과 입금, 환불, 취소, 입금 취소와 출금 상계
- 주기적 스케줄러, webhook, 메시지 알림
- 연결이 해제된 CODEF 인증정보를 복원하는 기능
- 정산 상세 화면과 목록 응답 확장. 해당 범위는 #138, #161~#163에서 처리한다.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`, `SettlementShare`, `SettlementParticipant`, `SettlementReceivingAccountSnapshot`, `Transaction`을 재사용한다.
- New concept: `SettlementDeposit`은 하나의 실제 수입 거래가 하나의 Share에 확인 반영되었다는 불변 연결이다.
- Relationships to other domains:
  - account는 CODEF 거래 조회·저장과 `account_transactions` 원장을 제공한다.
  - external connection은 생성자의 활성 `connectedId`를 제공한다.
  - settlement는 Share별 예정 금액, 수금 계좌 snapshot과 완료 상태를 소유한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 예정 납부액 | 참여자에게 확정된 송금 금액 | `SettlementShare.amount` |
| 수입 거래 | 수금 계좌에 실제로 들어온 CODEF 거래 | `Transaction(type=INCOME)` |
| 입금 확인 | 수입 거래 하나를 Share 하나에 안전하게 연결한 사실 | `SettlementDeposit` |
| 확인 금액 | Share에 연결된 수입 거래 금액 합계 | `SUM(account_transactions.amount)` |
| 미확인 거래 | 수금 계좌 거래지만 참여자 식별 또는 금액 검증을 통과하지 못한 거래 | 연결되지 않은 `account_transactions` 행 |
| 정산 종료 | 모든 Share의 확인 금액이 예정 납부액과 정확히 일치한 상태 | `SettlementStatus.COMPLETED` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 최신 수입 거래를 조회하고 입금 현황을 갱신한다. | `ReconcileSettlementDepositsUseCase` |
| 정산 생성자 | 현재 Share별 확인 금액과 전체 진행도를 조회한다. | `GetSettlementDepositSummaryUseCase` |

초기 increment는 생성자의 명시적 요청으로만 대조한다. 같은 UseCase를 호출할 실제 운영 요구가 생길 때만 스케줄러를 추가한다.

### State and invariants

- Settlement 대조 시작 상태는 `AWAITING_DEPOSIT`뿐이다.
- `COMPLETED` 조회는 허용하되 CODEF를 재호출하거나 상태를 다시 변경하지 않는다.
- `DRAFT`, `REQUESTED`, `SUSPENDED`에서는 입금 대조를 실행하지 않는다.
- 하나의 실제 외부 거래를 나타내는 `externalTransactionKey`는 계좌 삭제·재연결 전후를 포함해 하나의 `SettlementDeposit`에만 연결된다.
- `SettlementDeposit.settlementId`는 Share와 수금 계좌 snapshot의 Settlement와 모두 일치해야 한다.
- 연결 거래의 `accountId`는 수금 계좌 snapshot의 `sourceAccountId`와 일치해야 한다.
- 연결 거래는 `INCOME`이고 snapshot 생성 시각 이후 거래여야 한다.
- Share별 확인 금액은 0 이상이며 예정 납부액을 초과할 수 없다.
- 생성자 본인 Share는 비용 배분에는 포함되지만 자기 수금 계좌로 송금하지 않으므로 입금 대사와 완료 분모에서 제외한다.
- 생성자 외 모든 납부 대상 Share의 확인 금액이 각각 예정 납부액과 정확히 같을 때만 `AWAITING_DEPOSIT -> COMPLETED`가 가능하다.

## 3. Scenarios

### Normal flow

1. 생성자가 `AWAITING_DEPOSIT` 정산의 입금 대조를 요청한다.
2. Application은 정산을 잠그고 Share, 참여자, 수금 계좌 snapshot과 기존 입금 연결을 조회한 뒤 생성자 본인 Share를 납부 대상에서 제외한다.
3. snapshot의 계좌번호와 생성자의 활성 `ExternalConnection`으로 CODEF 수입 거래를 조회한다.
4. 기존 `account_transactions` 원장에 같은 external transaction key를 중복 없이 저장한다.
5. snapshot 생성 시각 이후의 미연결 `INCOME` 거래를 참여자 표시 이름과 남은 금액으로 대조한다.
6. 안전하게 하나의 Share로 결정되는 거래만 `SettlementDeposit`으로 저장한다.
7. 모든 Share가 정확히 채워졌으면 Settlement를 `COMPLETED`로 변경하고, 아니면 `AWAITING_DEPOSIT`을 유지한다.
8. 생성자에게 예상 총액, 확인 총액, 완료 참여자 수와 현재 상태를 반환한다.

### Matching rule

자동 연결은 다음 조건을 모두 만족할 때만 수행한다.

1. 거래는 snapshot의 `sourceAccountId`에 저장된 `INCOME`이고 `transactedAt >= snapshot.createdAt`이다.
2. 거래 설명을 Unicode NFKC, 소문자, 공백 제거 형태로 정규화했을 때 미완료 참여자의 정규화된 `displayName` 하나만 포함한다.
3. 거래 금액은 해당 Share의 남은 금액 이하이며 KRW 정수 금액이다.
4. 같은 수금 계좌의 어느 생명주기에서도 동일 `externalTransactionKey` 거래가 기존 `SettlementDeposit`에 연결되지 않았다.

이름이 없거나 둘 이상의 참여자 이름과 일치하거나 금액이 남은 금액을 초과하면 거래를 연결하지 않는다. 이 보수적 실패는 잘못된 납부자를 완료 처리하는 것보다 우선한다.

### Failure flow

- 생성자가 아니거나 정산이 없다: `SETTLEMENT_ACCESS_DENIED` 또는 `SETTLEMENT_NOT_FOUND`.
- 상태가 대조 가능하지 않다: 상태 충돌 오류를 반환하고 저장하지 않는다.
- 활성 ExternalConnection이 없다: 기존 external connection 오류를 반환하고 기존 입금 확인은 유지한다.
- CODEF가 실패한다: 오류를 반환하고 신규 거래·입금 연결·Settlement 상태를 저장하지 않는다.
- 거래가 모호하다: 오류로 전체 요청을 실패시키지 않고 미확인 거래로 남긴다.
- 일부 Share만 채워졌다: 확인 행은 저장하고 Settlement는 `AWAITING_DEPOSIT`을 유지한다.

### Boundary conditions

- CODEF 조회 기간이 여러 달이면 월 단위로 나누어 호출하되 동일 external key는 DB unique 제약으로 한 번만 저장한다.
- 동일 시각·금액·설명 거래는 기존 account transaction key와 계좌별 unique 제약을 재사용한다.
- 같은 대조 요청이 동시에 들어오면 활성 Account row를 먼저 잠그고 Settlement row를 잠가 직렬화하며, 잠금 뒤 상태와 기존 연결을 다시 확인한다.
- 신규 거래 저장은 계좌 월 요약 재생성·계좌 삭제와 같은 `Account -> Settlement` 잠금 순서로 직렬화한다.
- 생성자·금융기관·복호화 계좌번호가 같은 다른 `AWAITING_DEPOSIT` 정산 중 거래 발생 전에 snapshot이 생성된 미완료 Share도 Account 생명주기 ID와 무관하게 후보에 포함하고, 둘 이상과 일치하는 거래는 어느 정산에도 자동 연결하지 않는다.
- 재연결 전후에 같은 외부 거래가 서로 다른 `account_transaction_id`로 저장되어도 안정적인 `externalTransactionKey`가 이미 정산에 연결되었다면 다시 연결하지 않는다.
- 활성 Account가 삭제되어도 snapshot의 복호화된 계좌번호와 사용자당 하나인 활성 ExternalConnection으로 조회한다. ExternalConnection 자체가 비활성화되면 자동 대조하지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ReconcileSettlementDepositsUseCase.reconcile(UUID settlementId)
Command: N/A
Result: SettlementDepositSummaryResult
Web Request DTO -> Command: N/A
Result -> Web Response DTO: SettlementDepositSummaryWebResponse.from(result)

UseCase: GetSettlementDepositSummaryUseCase.get(UUID settlementId)
Result: SettlementDepositSummaryResult
```

`SettlementDepositSummaryResult`는 최소한 `settlementId`, `status`, `expectedAmount`, `confirmedAmount`, `participantCount`, `completedParticipantCount`를 제공한다. 금액과 참여자 수는 생성자 본인을 제외한 납부 대상을 기준으로 한다. 참여자별 상세 응답은 #138 계약에서 확정한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `BankTransactionPort` | snapshot 계좌의 CODEF 거래를 기간별 조회한다. | 입금 대조 Service | 기존 `CodefBankTransactionAdapter` |
| 기존 `ExternalConnectionStorePort` | 생성자의 활성 `connectedId`를 조회한다. | 입금 대조 Service | 기존 external persistence Adapter |
| 기존 `SaveAccountTransactionPort` | CODEF 거래를 원장에 중복 없이 저장한다. | 입금 대조 Service | 기존 `AccountTransactionPersistenceAdapter` |
| 기존 `LoadAccountTransactionPort` | 수금 계좌의 기간별 수입 거래를 조회한다. | 입금 대조 Service | 기존 `AccountTransactionPersistenceAdapter` |
| 기존 `LoadSettlementSharePort` | 확정 Share를 조회한다. | 입금 대조·현황 Service | 기존 `SettlementSharePersistenceAdapter` |
| `SettlementDepositStorePort` | 확인된 거래 연결 저장과 Share별 확인 합계를 제공한다. | 입금 대조·현황 Service | settlement persistence Adapter |

`SettlementDepositStorePort`는 같은 수금 계좌를 사용하는 모든 입금 대기 정산의 미완료 후보도 함께 조회한다. `SaveAccountTransactionPort` 구현은 실제 신규 거래가 저장된 월의 `account_monthly_summaries`를 삭제해 다음 계좌 조회에서 다시 계산되도록 한다.

CODEF transaction key 생성은 현재 `AccountQueryService`의 private 구현을 두 번째로 복사하지 않는다. 두 실제 호출자가 생기는 시점에 Application 공용 helper로 이동한다.

### Application Policy

- Decision owned by the Policy: 거래 설명과 금액으로 안전하게 하나의 미완료 Share를 결정하고, 모호하거나 초과하는 거래를 거부한다.
- Facts loaded by the Service and passed to the Policy: Share, participant displayName, 기존 확인 금액, 수입 거래.
- Why the rule is not an Entity-local invariant: 하나의 거래와 여러 Share·Participant·기존 Deposit을 함께 비교해야 한다.
- Future inputs deliberately not implemented yet: 송금자 계좌 실명, Toss callback, 생성자 수동 연결.

### Web API

```text
POST /api/v2/settlements/{settlementId}/deposits/reconcile
Request: none
Response: SettlementDepositSummaryWebResponse
Errors: 401, 403, 404, 409, CODEF upstream errors
Authentication: required, creator only

GET /api/v2/settlements/{settlementId}/deposits
Request: none
Response: SettlementDepositSummaryWebResponse
Errors: 401, 403, 404
Authentication: required, creator only
```

### Persistence and external integrations

- Database changes:
  - `settlement_deposits`는 확인된 `account_transactions`와 Share의 불변 연결만 저장한다.
  - `(settlement_id, share_id)`는 같은 Settlement의 `settlement_shares`를 참조한다.
  - `(settlement_id, source_account_id)`는 같은 Settlement의 수금 계좌 snapshot을 참조한다.
  - `(account_transaction_id, source_account_id)`는 실제 거래와 계좌를 함께 참조한다.
  - `account_transaction_id` unique로 하나의 실제 거래가 두 Share에 연결되는 것을 금지한다.
  - 부분·완료 상태는 별도 status column 없이 연결 거래 금액 합계와 Share 예정 금액으로 계산한다.
- Flyway migration required: `Yes`.
- Planned migration: `V20__create_settlement_deposit_schema.sql`.
- Migration pull request: [#172](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/172) (`Merged`).
- Redis changes: `N/A`.
- External API changes: 기존 CODEF 개인 수시입출 거래내역 API를 재사용한다.
- Environment changes: `N/A`. 기존 CODEF 설정을 사용한다.

## 5. Design decisions

### Business logic

- Domain: `SettlementDeposit` 연결의 필수 ID와 확인 시각 불변식을 소유한다. `Settlement.complete`는 기존 상태 전이를 유지한다.
- Application Policy: 거래와 Share의 안전한 자동 매칭 및 초과 금액 거부를 판단한다.
- Application Service: 현재 사용자·권한, Settlement lock, CODEF 조회, 거래 저장, Policy 호출, Deposit 저장, 완료 전환과 Result 조립을 담당한다.

### Technology and patterns

- Constraint: 실제 입금 근거를 보존하면서 중복 CODEF 응답과 잘못된 참여자 매칭을 막아야 한다.
- Selected approach: 기존 `account_transactions`를 거래 원장으로 재사용하고 새 테이블에는 Share와 거래의 확인 연결만 저장한다.
- Reason: 거래 payload를 복제하지 않고 DB FK와 unique 제약으로 정산·계좌·Share 범위와 멱등성을 보장한다.
- Alternatives considered:
  - `settlement_shares`에 `paidAmount`만 저장: 어떤 거래로 확인했는지 잃어 재검증과 중복 방지가 불가능해 제외한다.
  - CODEF 조회 때마다 상태를 계산: 외부 응답 변화와 장애에 따라 완료 상태가 되돌아갈 수 있어 제외한다.
  - 금액만으로 자동 매칭: 동일 금액 Share가 흔해 잘못된 납부자를 완료 처리할 수 있으므로 제외한다.
- Trade-offs and limits: 거래 설명에 참여자 이름이 없거나 중복 이름이면 자동 연결되지 않는다. 실제 미매칭 빈도가 확인되기 전에는 수동 연결이나 별도 송금 식별자를 추가하지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 생성자 대조 실행·요약 조회 endpoint | `SettlementController`, `SettlementDocs` |
| Application | 입금 대조/조회 UseCase, Service, Policy | `application.settlement` |
| Domain | `SettlementDeposit`, 기존 `Settlement.complete` | `domain.settlement` |
| Persistence | Deposit JPA와 거래·Share·snapshot FK | V20, settlement persistence |
| Account | CODEF 거래 조회·저장과 transaction key 재사용 | `AccountQueryService`, `BankTransactionPort` |
| Redis | 변경 없음 | N/A |
| Security | 생성자 전용 JWT API | 기존 `CurrentActorPort` |
| Configuration | 새 설정 없음 | 기존 CODEF configuration |
| Documentation | Feature Plan, Swagger, 검증 결과 | 이 문서와 `SettlementDocs` |

Existing behavior that must remain unchanged:

- Toss 이동만으로 입금 완료 처리하지 않는다.
- Share와 수금 계좌 snapshot은 요청 확정 뒤 변경하지 않는다.
- 계좌 거래 목록·월 요약 API와 계좌 삭제 후 거래 이력 보존을 유지한다.
- 자동 품목 배분 실패의 `SUSPENDED` 상태와 수동 중단·재개·삭제 정책을 유지한다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`Required`)
- [x] UseCase and Application Result
- [x] 기존 Output Port 확장과 `SettlementDepositStorePort`
- [x] `SettlementDeposit` Domain과 자동 대조 Policy
- [x] Application Service
- [x] CODEF transaction key 공용화와 JPA Adapter
- [x] Web Response, Controller, Swagger
- [x] Infra configuration and Bean wiring (`N/A`: 기존 CODEF Bean 재사용)
- [x] Documentation and verification results

Flyway migration-only PR을 만든 직후 현재 작업을 중단한다. Java, 테스트와 이 Feature Plan은 해당 PR에 포함하지 않는다.

### Expected commit boundaries

```text
docs(settlement): 정산 입금 대조 설계 문서화
build(database): 정산 입금 확인 이력 스키마 추가
feat(settlement): 정산 입금 대조와 완료 전환 구현
test(settlement): 정산 입금 대조 시나리오 검증
docs(settlement): 정산 입금 대조 검증 결과 반영
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

Migration PR 단계에서 V1~V20 신규 적용, 기존 V19 데이터 위 V20 적용과 FK·unique·삭제 제한을 PostgreSQL에서 검증했다. Application 단계에서는 현재 V21까지 적용되는 임시 PostgreSQL과 Redis에서 전체 테스트를 실행한다.

### Completion criteria

- [x] 정상·부분·모호·중복·초과 입금 흐름을 검증했다.
- [x] 다른 Settlement, Share, 계좌 거래가 연결되지 않음을 검증했다.
- [x] 모든 납부 대상 Share가 정확히 확인된 경우에만 `COMPLETED`가 됨을 검증했다.
- [ ] Swagger와 실제 HTTP 응답이 일치한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 현재 코드·스키마 조사 | Complete | V16~V19, Share/snapshot, account transaction, CODEF Adapter 확인 |
| Migration 필요성 | Complete | 확인 거래 연결과 멱등 이력을 저장할 구조가 없어 V20 필요 |
| Migration PR | Complete | #172 merged, V20 applied before V21 |
| Java·HTTP 구현 | Complete | #156 범위의 대조·조회 UseCase, JPA Adapter, API와 Swagger 구현 |
| 핵심 단위 검증 | Complete | 정상 완료, 부분 입금, 모호·초과·소수 금액 거부, 완료 후 멱등 재조회 |
| 전체 회귀 검증 | Complete | 임시 PostgreSQL 18.3·Redis 7에서 404개 테스트 전체 통과 후 컨테이너 제거 |
| 전용 HTTP·Persistence 통합 시나리오 | Pending | #157에서 실제 행 저장, OpenAPI 경로와 오류 응답 고정 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-25 | 최신 `origin/develop`과 #136 흐름 조사, 안전한 자동 대조·저장 계약 확정 | V20 migration-only PR | 거래 설명에 참여자 이름이 없거나 중복이면 자동 매칭할 수 없음 |
| 2026-08-25 | #172 병합과 V20 적용 확인, 최신 `origin/develop`으로 구현 브랜치 갱신 | #156 Application·Persistence·Web 구현 | 생성자도 Share를 가지므로 본인 Share를 입금 완료 분모에서 제외해야 함 |
| 2026-08-25 | #156 입금 대조·완료 전환과 생성자 조회/실행 API 구현, 전체 테스트 통과 | #157 전용 Persistence·HTTP 통합 검증 | 실제 CODEF 거래 설명의 이름 포함률은 운영 데이터로 확인 필요 |
| 2026-08-25 | PR #175 리뷰 반영: 동일 계좌 교차 정산 후보 대조와 신규 거래 월 요약 무효화, 400개 테스트 통과 | 리뷰 재확인 | 재연결로 물리 계좌의 내부 ID가 바뀌는 경우는 별도 계좌 동일성 계약이 필요함 |
| 2026-08-25 | PR #175 추가 리뷰 반영: 거래 발생 뒤 생성된 정산 snapshot은 해당 거래의 경쟁 후보에서 제외, 401개 테스트 통과 | 리뷰 재확인 | 같은 이름·금액의 동시 진행 정산은 먼저 발생한 거래부터 순서대로 대조해야 함 |
| 2026-08-26 | PR #175 추가 리뷰 반영: 신규 거래 저장과 월 요약 재생성을 같은 활성 Account row lock으로 직렬화, 401개 테스트 통과 | 리뷰 재확인 | 삭제된 계좌는 상세·요약 재생성 대상이 아니므로 기존 snapshot 대조를 계속 허용함 |
| 2026-08-26 | 계좌 삭제와 입금 대조의 잠금 순서를 `Account -> Settlement`로 통일하고 잠금 뒤 상태 재검증, 402개 테스트 통과 | 리뷰 재확인 | CODEF 조회는 잠금 전에 수행하므로 같은 요청에서 최신 거래를 다시 저장·조회함 |
| 2026-08-26 | 재연결 전후 동일 수금 계좌 후보를 snapshot 계좌번호로 통합하고 이미 사용한 외부 거래 Key를 차단, 404개 테스트 통과 | 리뷰 재확인 | 거래 원장은 생명주기별로 유지하되 정산 입금 소비만 실제 외부 거래 단위로 제한함 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-25 | 생성자 명시적 대조 API부터 구현한다. | 스케줄러 없이도 사용자 흐름을 완성하고 운영 주기 설정을 미룰 수 있다. | 자동 주기 실행은 실제 사용 요구가 생길 때 추가한다. |
| 2026-08-25 | 거래 원장은 기존 `account_transactions`를 재사용한다. | CODEF 중복 제거와 계좌 삭제 후 이력 보존이 이미 구현되어 있다. | Deposit 테이블에 거래 payload를 복제하지 않는다. |
| 2026-08-25 | 모호한 거래는 연결하지 않는다. | 잘못된 참여자를 완료 처리하는 것보다 미확인 상태 유지가 안전하다. | 수동 연결은 실제 미매칭 빈도 확인 후 별도 이슈로 만든다. |
| 2026-08-25 | Deposit 상태 column을 두지 않는다. | 부분·완료는 불변 거래 연결 합계로 결정 가능하다. | 환불·취소가 필요해지면 reversal 모델을 별도 설계한다. |
| 2026-08-25 | 생성자 본인 Share는 입금 대사와 완료 분모에서 제외한다. | 생성자는 기존 PAY 목록에서도 송금 대상이 아니며 자기 수금 계좌로 본인 몫을 이체하지 않는다. | Share 자체는 총액 배분 근거로 유지한다. |
| 2026-08-25 | 같은 수금 계좌의 모든 입금 대기 정산 후보를 함께 비교한다. | 먼저 대조한 정산이 공통 거래를 잘못 선점하는 것을 막는다. | 여러 정산에 맞는 거래는 미확인으로 남긴다. |
| 2026-08-25 | 신규 계좌 거래가 실제 삽입된 월의 저장 요약을 삭제한다. | 거래 목록과 캐시된 월 합계·건수의 불일치를 막는다. | 중복 거래만 들어온 월은 기존 요약을 유지한다. |
| 2026-08-25 | 거래 발생 시각 이전에 생성된 snapshot의 Share만 자동 대조 후보로 취급한다. | 아직 시작되지 않은 정산이 과거 입금을 모호하게 만들어 정상 매칭을 막지 않게 한다. | snapshot 생성 시각은 후보 조회에서 함께 반환해 Policy가 판단한다. |
| 2026-08-26 | 정산 대조의 신규 거래 저장 전 활성 Account row를 잠근다. | 월 요약 재생성과 거래 저장·요약 삭제가 교차해 오래된 요약을 다시 만들지 않게 한다. | 삭제된 계좌에는 잠글 활성 행과 요약 조회 경로가 없으므로 대조를 중단하지 않는다. |
| 2026-08-26 | 입금 대조는 CODEF 조회 후 `Account -> Settlement` 순서로 잠그고 상태를 재검증한다. | 계좌 삭제의 `Account -> Settlement` 순서와 반대 잠금으로 생길 수 있는 PostgreSQL 교착을 제거한다. | 외부 조회는 잠금 전에 수행해 DB row lock 보유 시간을 늘리지 않는다. |
| 2026-08-26 | 동일 생성자·금융기관·복호화 계좌번호 snapshot을 하나의 정산 매칭 경계로 보고 `externalTransactionKey` 사용 여부를 생명주기 전체에서 확인한다. | Account 재연결로 내부 ID가 바뀌어도 하나의 실제 입금이 두 정산을 완료하지 못하게 한다. | 거래·요약 원장은 ADR-0004에 따라 생명주기별로 유지하며 별도 fingerprint column은 추가하지 않는다. |

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: 실제 CODEF 입금 설명 필드의 운영 형식과 이름 매칭 성공률
- Remaining risks: 이름이 없는 거래, 동명이인, 초과 입금은 MVP에서 자동 확인되지 않는다.
- Follow-up work: #138 정산 상세 납부 현황, 목록의 실제 완료 참여자 수 연결
