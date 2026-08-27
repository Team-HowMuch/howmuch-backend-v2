# 기능 계획: 연결 계좌 조회와 소비내역

- 상태: 구현 및 검증 완료, migration PR merge 대기
- 최종 수정일: 2026-08-23
- 상위 이슈: [#87 연결 계좌 조회와 소비내역 제공](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/87)
- 하위 이슈: [#88 스키마](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/88), [#89 기본 계좌·순서](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/89), [#90 목록](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/90), [#91 상세](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/91), [#92 CODEF 거래 Adapter](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/92), [#93 거래 영속화](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/93), [#94 월 요약](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/94), [#95 거래 조회](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/95), [#96 검증](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/96), [#97 삭제 정책](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/97)
- 관련 기능: [CODEF 은행 연결과 결제계좌 연동](codef-bank-account-connection.md)
- 관련 ADR: [목적 중심 Port](../../adr/0001-purpose-oriented-ports.md), [Capability 중심 Output Port](../../adr/0003-capability-oriented-output-ports.md)

## 1. 목적

### 문제

현재 사용자는 CODEF에 은행을 연결하고 계좌 후보를 선택해 `accounts`에 저장할 수 있지만, 저장한 계좌를 HowMuch 결제수단으로 조회하거나 계좌별 소비내역을 확인할 API가 없다. `accounts`에는 기본 계좌와 표시 순서를 표현할 필드가 없고, CODEF 거래내역을 중복 없이 저장하거나 월별 소비 현황을 재사용할 구조도 없다.

### 지금 필요한 이유

정산 수령 계좌 선택과 개인 소비 화면이 연결 계좌를 공통으로 사용하려면 CODEF 연결 절차와 서비스 내부 조회 계약을 분리해야 한다. 월별 집계를 Spring Batch로 선계산하는 작업은 아직 이르므로, 조회 시 저장된 요약이 있으면 반환하고 없으면 해당 월 거래를 가져와 계산·저장한 뒤 반환하는 최소 흐름을 먼저 제공한다.

### 성공 기준

- [x] 인증 사용자가 자신의 활성 계좌 목록을 금융기관으로 필터링하고 기본 계좌 우선 또는 최근 사용순으로 조회한다.
- [x] 계좌 상세에서 계좌명, 금융기관, 마스킹 계좌번호, 기본 여부, 표시 순서와 요청 월 소비 금액·건수·최근 사용일을 확인한다.
- [x] 계좌별 요청 월 거래내역이 저장되지 않았다면 CODEF 개인 수시입출 거래내역을 한 번 조회하고 중복 없이 저장한다.
- [x] 월 요약이 있으면 그대로 반환하고, 없으면 저장된 거래에서 계산해 저장한 뒤 반환한다.
- [x] 타인의 계좌 ID는 계좌가 없는 것과 같은 `404 ACCOUNT_NOT_FOUND`로 처리한다.
- [x] 동일 거래를 다시 가져와도 거래와 월 요약이 중복되지 않는다.

### 제외 범위

- 카드, 증권, 보험 거래내역
- Spring Batch, Scheduler, 주기 동기화, 수동 새로고침 API
- 계좌 삭제와 CODEF 기관 인증수단 삭제, 삭제 후 재연결 정책
- 수동 결제수단 등록
- 카테고리 분류, 예산, 고정지출 탐지
- 적금·대출·펀드·보험 등 수시입출이 아닌 계좌의 거래내역
- 원계좌번호의 Web API 노출

## 2. 도메인 분석

### 도메인 경계

- 기존 `account` 도메인을 재사용한다.
- `external`은 CODEF 연결 상태와 `connectedId`를 소유한다.
- `account`는 사용자가 선택한 서비스 내 결제계좌, 표시 속성, 거래내역, 월 소비 요약을 소유한다.
- `commoncode`의 `BANK` 코드는 기관 표시명과 아이콘 조회에만 사용한다.
- 정산 도메인은 이 기능의 API나 JPA Repository를 직접 사용하지 않고 후속 이슈에서 목적에 맞는 계좌 조회 Port를 사용한다.

### 공통 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 연결 계좌 | CODEF 후보 중 사용자가 HowMuch에 저장한 활성 계좌 | `Account` |
| 기본 계좌 | 사용자 계좌 목록에서 우선 표시되는 하나의 활성 계좌 | `Account.isDefault` |
| 표시 순서 | 기본 정렬에서 계좌를 배치하는 0 이상의 값 | `Account.displayOrder` |
| 계좌 거래 | CODEF 계좌에서 가져와 서비스에 저장한 입출금 기록 | `Transaction` |
| 소비 | 계좌 거래 중 출금인 기록 | `AccountTransactionType.EXPENSE` |
| 월 소비 요약 | 계좌와 월별 소비 금액·건수·최근 사용일 캐시 | `AccountMonthlySummary` |

### 사용자와 UseCase

| 사용자 | 목표 | UseCase |
|---|---|---|
| 인증 사용자 | 연결 계좌 목록 조회 | `GetAccountsUseCase` |
| 인증 사용자 | 요청 월 소비 현황이 포함된 계좌 상세 조회 | `GetAccountDetailUseCase` |
| 인증 사용자 | 요청 월의 계좌 거래내역 조회 | `GetAccountTransactionsUseCase` |

### 상태와 불변식

- 사용자별 활성 기본 계좌는 최대 하나다.
- `displayOrder`는 0 이상이며 기본 정렬은 `isDefault DESC, displayOrder ASC, id ASC`다.
- 계좌 목록과 상세는 현재 사용자가 소유한 `active = true` 계좌만 반환한다.
- 원계좌 식별자는 기존 AES-GCM 암호문으로 저장하고 CODEF 호출 직전에만 복호화한다.
- 외부 거래 Key는 원계좌 식별자, 거래일시, 입출금 구분, 금액, 거래후 잔액, 설명을 SHA-256으로 묶어 생성한다.
- 월 소비 요약은 `EXPENSE` 거래만 합산한다.
- CODEF 거래일시 `Asia/Seoul`은 저장 전에 UTC `LocalDateTime`으로 변환한다.

## 3. 시나리오

### 계좌 목록

1. 사용자가 `GET /api/v2/accounts`를 호출한다.
2. 서버는 현재 사용자 ID로 활성 계좌를 조회한다.
3. `organization`이 있으면 해당 기관만 반환한다.
4. `sort=DEFAULT`는 기본 계좌와 표시 순서를, `sort=RECENT`는 마지막 거래일시를 우선한다.
5. CODEF를 호출하지 않고 저장된 계좌 정보만 반환한다.

### 계좌 상세와 월 요약 적중

1. 사용자가 `yearMonth=2026-08`로 계좌 상세를 조회한다.
2. 서버는 계좌 소유권과 활성 상태를 확인한다.
3. `account_monthly_summaries`에 계좌·월 행이 있으면 외부 호출과 재계산 없이 반환한다.

### 계좌 상세와 월 요약 미적중

1. 요청 월 요약이 없으면 서버는 저장된 연결과 계좌 식별자를 내부에서 읽는다.
2. 요청 월의 CODEF 개인 수시입출 거래내역을 조회한다.
3. 거래를 외부 거래 Key로 중복 제거해 저장한다.
4. 저장된 해당 월 `EXPENSE` 거래를 집계해 월 요약을 저장한다.
5. 계좌 상세와 월 요약을 반환한다. 거래가 0건이어도 0원·0건 요약을 저장해 같은 월의 반복 외부 호출을 막는다.

### 거래내역 조회

1. 사용자가 `GET /api/v2/accounts/{accountId}/transactions?yearMonth=2026-08`을 호출한다.
2. 월 요약이 없으면 계좌 상세와 같은 월 적재 흐름을 먼저 실행한다.
3. 서버는 저장된 거래를 최신순으로 반환한다.

### 실패 흐름

| 조건 | 결과 |
|---|---|
| 계좌가 없거나 다른 사용자 소유 또는 비활성 | `404 ACCOUNT_NOT_FOUND` |
| `yearMonth` 형식 오류 | `400` 요청 검증 오류 |
| 수시입출이 아닌 계좌 | `422 ACCOUNT_TRANSACTION_NOT_SUPPORTED` |
| 연결이 비활성 또는 설정 지문 불일치 | `404 EXTERNAL_CONNECTION_NOT_FOUND` |
| CODEF 추가 인증 요구 | `409 CODEF_ADDITIONAL_AUTH_REQUIRED` |
| CODEF 장애 | `503 CODEF_UPSTREAM_UNAVAILABLE` |

### 경계 조건

- `yearMonth`가 없으면 `Asia/Seoul` 기준 현재 월을 사용한다.
- 미래 월은 `400 INVALID_ACCOUNT_QUERY`로 거절한다.
- CODEF가 날짜 또는 시간이 비어 있는 거래를 주면 저장하지 않고 건너뛴다.
- 금액이 없거나 0 이하인 거래는 저장하지 않는다.
- 같은 월의 동시 최초 조회는 CODEF를 중복 호출할 수 있지만 DB Unique 제약으로 데이터 중복은 막는다. 외부 호출 직렬화는 실제 비용 문제가 확인될 때 추가한다.

## 4. 계약

### Input Port와 경계 매핑

```text
GetAccountsUseCase
  GetAccountsQuery(organization, sort)
  -> List<AccountSummaryResult>

GetAccountDetailUseCase
  getAccountDetail(accountId, yearMonth)
  -> AccountDetailResult

GetAccountTransactionsUseCase
  getAccountTransactions(accountId, yearMonth)
  -> List<AccountTransactionResult>

Web query/path -> Application query/value
Application Result -> Web Response DTO
```

### Output Port

| Port | 목적 | 호출자 | Adapter |
|---|---|---|---|
| `LoadAccountPort` | 사용자 계좌 목록·단건·정렬 조회 | 계좌 조회 Service | `AccountPersistenceAdapter` |
| `SaveAccountPort` | 계좌 연결 시 기본 여부·표시 순서를 포함해 저장 | `BankAccountService` | `AccountPersistenceAdapter` |
| `LoadAccountTransactionPort` | 계좌·월 거래와 소비 집계 조회 | 계좌 조회 Service | `AccountTransactionPersistenceAdapter` |
| `SaveAccountTransactionPort` | 외부 거래 중복 저장과 월 요약 저장 | 계좌 조회 Service | `AccountTransactionPersistenceAdapter` |
| `BankTransactionPort` | 연결 계좌의 요청 기간 거래 조회 | 계좌 조회 Service | `CodefBankTransactionAdapter` |

현재 `AccountStorePort`는 조회와 저장이 섞인 전환 지점이다. 이번 기능에서 실제 호출자가 생기므로 `LoadAccountPort`와 `SaveAccountPort`로 나누되, 하나의 `AccountPersistenceAdapter`가 둘 다 구현한다. 거래 저장 Port는 거래와 그 파생 월 요약을 같은 영속 경계에서 처리한다.

### Web API

```text
GET /api/v2/accounts?organization=0020&sort=DEFAULT
Authentication: bearerAuth
Response item:
{
  "id": 1,
  "organization": "0020",
  "institutionName": "우리은행",
  "institutionIconUrl": "https://...",
  "name": "우리 주거래 통장",
  "maskedAccountNumber": "****6789",
  "isDefault": true,
  "displayOrder": 0,
  "lastUsedAt": "2026-08-20T00:15:00"
}
```

```text
GET /api/v2/accounts/{accountId}?yearMonth=2026-08
Authentication: bearerAuth
Response:
{
  "id": 1,
  "organization": "0020",
  "institutionName": "우리은행",
  "institutionIconUrl": "https://...",
  "name": "우리 주거래 통장",
  "maskedAccountNumber": "****6789",
  "isDefault": true,
  "displayOrder": 0,
  "yearMonth": "2026-08",
  "monthlyExpenseAmount": 12500.0000,
  "expenseCount": 1,
  "lastUsedAt": "2026-08-20T00:15:00"
}
```

```text
GET /api/v2/accounts/{accountId}/transactions?yearMonth=2026-08
Authentication: bearerAuth
Response item:
{
  "id": 10,
  "type": "EXPENSE",
  "amount": 12500.0000,
  "transactedAt": "2026-08-20T00:15:00",
  "description": "편의점 체크카드 생활비 테스트지점",
  "afterBalance": 1250000.0000
}
```

계좌번호는 `maskedAccountNumber`만 반환한다. CODEF 호출에 필요한 원계좌 식별자는 Web Result에 포함하지 않는다.

### 영속성과 외부 연동

- Flyway migration required: `Yes`
- 계획된 변경:
  - `accounts.product_code`, `accounts.is_default`, `accounts.display_order` 추가와 기존 행 결정적 backfill
  - 활성 기본 계좌를 사용자별 하나로 제한하는 PostgreSQL partial unique index
  - `account_transactions` 생성과 `(account_id, external_transaction_key)` Unique 제약
  - `account_monthly_summaries` 생성과 `(account_id, year_month)` Unique 제약
- Migration pull request: [#99 build(database): 계좌 표시 정보와 거래 요약 스키마 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/99)
- Redis 변경: 없음
- CODEF:
  - `POST /v1/kr/bank/p/account/transaction-list`
  - 입력: `connectedId`, `organization`, `account`, `startDate`, `endDate`, `orderBy=1`, `inquiryType=1`
  - 응답: `resTrHistoryList`의 거래일·시각, 출금, 입금, 거래후 잔액, 설명 필드
- 새 환경변수와 의존성: 없음

## 5. 설계 결정

### 비즈니스 로직

- 기본 여부와 표시 순서는 계좌 자체의 서비스 내 표시 상태이므로 `Account`가 소유한다.
- 목록 필터·정렬과 소유권 확인은 Application Service가 `LoadAccountPort`를 통해 수행한다.
- CODEF 응답 필드와 경로는 `adapter.out.codef`에만 둔다.
- 월 요약 미적중 시 거래 적재와 요약 계산을 하나의 Application 흐름으로 조정한다.

### 선택한 최소 구현

- 별도 `payment_method` 도메인을 만들지 않고 이미 연결 저장에 쓰이는 `account`를 확장한다.
- Spring Batch와 Scheduler를 도입하지 않는다.
- 월 요약 행을 조회 완료 표식으로 함께 사용해 거래 0건인 월도 반복 CODEF 호출을 막는다.
- 거래 원문 전체 JSON을 저장하지 않고 조회와 요약에 필요한 표준 필드만 저장한다.
- 외부 거래 Key는 CODEF가 안정적인 거래 ID를 제공하지 않는 응답을 고려해 결정적 SHA-256으로 만든다.

### 한계

- 월 요약이 생성된 뒤 새 거래가 생겨도 이번 단계에서는 자동 갱신하지 않는다. 수동 새로고침 또는 Batch 동기화가 필요해질 때 갱신 정책을 추가한다.
- 동시 최초 조회는 외부 호출을 중복 실행할 수 있다. 데이터는 Unique 제약으로 보호한다.
- 기존 `accounts` 행은 `product_code`가 없으므로 수시입출 거래 조회를 시도하고, CODEF가 지원하지 않으면 `ACCOUNT_TRANSACTION_NOT_SUPPORTED`로 변환한다.

## 6. 영향 범위

| 영역 | 영향 | 확인한 현재 파일 또는 계약 |
|---|---|---|
| API | 목록·상세·거래내역 GET 추가 | `AccountController`, `AccountDocs` |
| Application | 조회 UseCase/Result와 최초 월 적재 흐름 추가 | `BankAccountService`, `CurrentActorPort` |
| Domain | 계좌 표시 속성, 거래, 월 요약 추가 | `domain.account.Account` |
| Persistence | 계좌 조회/정렬, 거래·요약 저장 | `adapter.out.persistence.account` |
| External | CODEF 수시입출 거래내역 매핑 | `CodefClient`, `CodefBankConnectionAdapter` |
| Security | 현재 사용자 소유권 확인, 원계좌번호 비노출 | `CurrentActorPort`, `CodefSecretCipher` |
| Configuration | 새 설정 없음 | `CodefProperties` 재사용 |
| Documentation | Feature Plan·Swagger·이슈 연결 | 이 문서와 `AccountDocs` |

변경하지 않는 기존 동작:

- `GET /api/v2/accounts/banks`
- `POST /api/v2/accounts/connections/banks`
- `POST /api/v2/accounts/external`
- CODEF 연결 암호화와 설정 지문 무효화
- 정산 도메인의 현재 Stub/Port

## 7. 구현 계획

- [x] Migration-only pull request to `develop` (`Required`)
- [x] `GetAccountsUseCase`, `GetAccountDetailUseCase`, `GetAccountTransactionsUseCase`와 Result
- [x] `LoadAccountPort`, `SaveAccountPort`, 거래 영속 Port, `BankTransactionPort`
- [x] `Account` 표시 상태와 `Transaction`, `AccountMonthlySummary`
- [x] 계좌 목록·상세·거래 조회 Application Service
- [x] 계좌·거래·월 요약 Persistence Adapter
- [x] CODEF 개인 수시입출 거래내역 Adapter
- [x] Web Response DTO, Controller, Swagger
- [x] 계좌 소유권, 거래 중복, 월 요약 적중·미적중, CODEF 응답 매핑 테스트
- [x] 문서와 검증 결과 반영

### 예상 커밋 경계

```text
docs(account): 연결 계좌 조회와 소비내역 설계 문서화
build(database): 계좌 표시 정보와 거래 요약 스키마 추가
feat(account): 계좌 목록과 상세 조회 구현
feat(account): CODEF 계좌 거래내역 적재 구현
test(account): 계좌 조회와 월 소비 계산 검증
docs(account): 구현과 검증 결과 반영
```

Flyway migration PR은 SQL 한 파일만 포함한다. 해당 PR을 생성하면 현재 구현 작업은 즉시 종료하고, 나머지는 별도 후속 작업에서 진행한다.

## 8. 검증

### 명령

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

실행 검증은 PostgreSQL과 Redis를 준비하고, CODEF 개발 Mock을 `http://dev.howmuch.wars.p-e.kr:18080/` 또는 로컬 Mock으로 설정해 수행한다. 실제 금융기관 credential은 Mock에 입력하지 않는다.

### 완료 기준

- [x] 목록 필터와 두 정렬 기준을 자동 테스트와 HTTP 호출로 확인했다.
- [x] 타인·비활성 계좌 접근이 차단되는 것을 확인했다.
- [x] 최초 월 조회에서 Mock 거래가 저장되고 같은 요청의 재호출에서 중복되지 않는 것을 확인했다.
- [x] 거래가 없는 월도 0원·0건 요약이 저장되는 것을 확인했다.
- [x] 서울 거래일시가 UTC `LocalDateTime`으로 변환되는 것을 확인했다.
- [x] Swagger 응답 필드와 실제 응답이 일치하는 것을 확인했다.

### 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 설계 근거 조사 | 완료 | 현재 v2 연결 흐름, v1 참고 구현, CODEF 공식 샘플, 개발 Mock 비교 |
| Flyway migration | 완료 | migration-only PR [#99](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/99), PostgreSQL V1~V10 적용 확인 |
| Java 구현 | 완료 | 계좌 목록·상세·거래 API, CODEF Adapter, 거래·요약 Persistence 구현 |
| 자동 검증 | 완료 | 계좌 Application·Web·CODEF 단위 테스트와 PostgreSQL 통합 테스트, 전체 92개 테스트 통과 |
| 실행 검증 | 완료 | 개발 Mock과 임시 PostgreSQL에서 연결·연동·목록·상세·거래·반복 조회와 OpenAPI 확인 |

## 9. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견한 위험 |
|---|---|---|---|
| 2026-08-23 | 요구사항, 기존 v2, v1 참고 구현, CODEF 공식 샘플, 개발 Mock 조사와 계약 설계 | 이슈 분리와 migration-only PR | 월 요약 갱신 정책과 계좌 삭제 정책은 후속 결정 필요 |
| 2026-08-23 | migration PR #99 기반 구현, 자동 테스트와 실제 Mock HTTP 검증 | migration PR merge 후 구현 PR 통합 | 월 요약 생성 후 갱신은 Batch 또는 새로고침 정책 필요 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 | 대안 또는 영향 |
|---|---|---|---|
| 2026-08-23 | 기존 `account` 도메인 확장 | CODEF 후보 선택 결과가 이미 `Account`로 저장됨 | 별도 `payment_method` 중복 모델 미도입 |
| 2026-08-23 | 원계좌번호 비노출 | 연결 단계의 마스킹 계약과 민감정보 경계 유지 | 정산 송금에 원계좌가 필요하면 별도 최소 권한 계약 검토 |
| 2026-08-23 | 월 요약 행을 조회 완료 표식으로 사용 | 거래 0건 월의 반복 외부 호출 방지 | 별도 sync-state 테이블 미도입 |
| 2026-08-23 | Batch 없이 조회 시 계산·저장 | 현재 요청 범위와 운영 복잡도 최소화 | Batch는 갱신 주기·비용이 정해질 때 추가 |
| 2026-08-23 | 계좌 삭제 미구현 | 거래 보존과 CODEF 재연결 의미가 확정되지 않음 | 정책 확정 이슈로 분리 |

## 11. 참고 자료

- [CODEF Java 공식 샘플: 개인 수시입출 거래내역](https://github.com/codef-io/codef-java/blob/master/codefTest/src/main/java/com/codef/io/sandbox/KR_BK_1_P_002.java)
- [CODEF Java 공식 샘플 응답](https://github.com/codef-io/codef-java/blob/master/codefTest/src/test/resources/json/bk/KR_BK_1_P_002.json)
- [CODEF 개인 자산관리 패키지 소개](https://blog.hectodata.co.kr/money-management-package/)
- [HowMuch CODEF 개발 Mock](http://dev.howmuch.wars.p-e.kr:18080/)
- `Team-HowMuch/codef-mock-server`의 `README.md`
- 기존 `howmuch-backend`의 `docs/09_CODEF_RESTCLIENT_USAGE.md`

## 12. 완료 기록

- 완료일: 2026-08-23
- 최종 동작: 인증 사용자가 연결 계좌를 필터·정렬해 조회하고, 요청 월 요약이 없을 때만 CODEF 수시입출 거래를 적재·집계한 뒤 상세와 최신순 거래내역을 조회한다.
- 검증: 임시 PostgreSQL에 V1~V10 적용, 전체 92개 테스트, 개발 Mock 기반 실제 HTTP 흐름과 OpenAPI 확인.
- 배포 전제: migration PR #99가 먼저 `develop`에 merge되어야 한다.
- 미검증 항목:
- 남은 위험:
- 후속 작업:
