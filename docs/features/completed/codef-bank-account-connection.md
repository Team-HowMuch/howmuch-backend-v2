# 기능 계획: CODEF 은행 연결과 결제계좌 연동

- 상태: 완료
- 최종 수정일: 2026-08-28
- 관련 이슈: [#20 외부 결제수단 API 연동](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/20), [#102 동일 은행 재연결 시 CODEF 인증정보 갱신](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/102), [codef-mock-server #1 기관별 로그인 매핑 수정](https://github.com/Team-HowMuch/codef-mock-server/issues/1)
- 관련 명세: [Notion `SYNC-001`](https://app.notion.com/p/35a6e559a65082788f0d81a89b1c221f)
- 관련 ADR: [목적 중심 Port](../../adr/0001-purpose-oriented-ports.md), [Framework-neutral StatusCode](../../adr/0002-framework-neutral-status-code.md), [Capability 중심 Output Port](../../adr/0003-capability-oriented-output-ports.md)

## 1. 목적

현재 구현은 은행 자격증명을 CODEF에 등록하고 계좌 후보를 조회하면서 전체 경계를 `payment`로 표현한다. 하지만 CODEF `connectedId`와 등록 기관은 외부기관 연결 정보이고, 조회된 실제 결제계좌는 사용자가 선택한 뒤 별도의 `account`로 저장되어야 한다.

이번 변경은 다음 두 사용자 결과를 분리한다.

1. 사용자가 은행을 CODEF에 연결하고 계좌 후보를 확인한다.
2. 사용자가 후보 중 원하는 계좌를 선택해 HowMuch `Account`로 연동한다.

### 성공 기준

- [x] 기존 `payment` 연결 패키지·타입·테이블·API 명칭이 남지 않는다.
- [x] 은행 연결은 `external` 연결 상태로 저장되고 실제 계좌는 `account`로 저장된다.
- [x] 연결 응답은 원본 계좌번호 대신 마스킹 정보와 위조하기 어려운 `candidateKey`만 반환한다.
- [x] 계좌 연동 요청은 CODEF 후보를 다시 조회해 선택 Key를 검증한 뒤 저장한다.
- [x] 원본 외부 계좌 식별자는 AES-GCM 암호문으로만 저장한다.
- [x] 동일 후보 재연동은 새 행을 만들지 않고 기존 계좌를 갱신·활성화한다.
- [x] CODEF 설정 지문이 달라지면 연결과 그 연결의 계좌를 사용할 수 없게 한다.
- [x] 새 기능은 Web Request→Command→UseCase→Result→Web Response와 capability 중심 Port 규칙을 따른다.
- [x] Domain의 상태 코드가 Spring `HttpStatus`에 의존하지 않는다.
- [x] 동일 기관의 인증정보를 다른 가상 사용자로 수정하면 이후 계좌·거래 조회가 수정된 사용자 데이터를 반환한다.
- [x] 하나의 `connectedId`에 기관별로 서로 다른 가상 사용자를 연결해도 데이터가 섞이지 않는다.

### 제외 범위

- 카드, 증권, 보험 연결
- 거래내역 수집과 자동 동기화
- 연결 해지와 CODEF 2-way 인증 완료
- 수동 계좌 등록, 기본 계좌, 색상·정렬 등 화면 설정

## 2. 근거와 재사용 범위

### CODEF 계약

- 한 사용자의 `connectedId` 아래 여러 기관을 등록한다.
- 최초 기관은 `/v1/account/create`, 추가 기관은 `/v1/account/add`를 사용한다.
- 이미 등록된 동일 기관의 인증정보 변경은 `/v1/account/update`를 사용한다.
- `/v1/kr/bank/p/account/account-list`에서 `resDepositTrust`, `resForeignCurrency` 계좌를 조회한다.
- 비밀번호는 CODEF public key로 RSA 암호화하고 평문을 저장·로그하지 않는다.

### 기존 `howmuch-backend` 참고 결과

재사용할 동작:

- 기관 연결과 결제계좌 선택 저장을 두 단계로 분리한다.
- 선택 요청에서 서버가 후보를 다시 조회하고 `candidateKey`를 검증한다.
- 이미 연동된 후보는 중복 생성하지 않는다.
- 연결 정보와 실제 사용자 결제수단 저장 모델을 분리한다.

그대로 복사하지 않을 부분:

- `/api/v1`, Controller의 `JwtPrincipal` 직접 해석, `Payment` 중심 명명
- `connectedId`와 외부 계좌 Key 평문 저장
- 카드·거래 동기화·Scheduler까지 포함한 큰 Service

## 3. 도메인 경계

| 경계 | 책임 | 주요 타입 |
|---|---|---|
| `external` | 사용자-CODEF 연결과 등록 은행 상태 | `ExternalConnection`, `ConnectedInstitution` |
| `account` | 사용자가 선택해 연동한 결제계좌 | `Account`, `AccountType` |
| `adapter.out.codef` | OAuth, RSA, create/add/account-list 프로토콜 | `CodefBankConnectionAdapter` |

- 사용자당 활성 `ExternalConnection`은 최대 1개다.
- 한 연결에 같은 `organization`은 중복될 수 없다.
- 한 연결에 같은 `candidateKey` 계좌는 중복될 수 없다.
- `Account`는 원본 외부 계좌 Key를 Application/Domain에서만 일시적으로 다루고 Persistence Adapter가 암호화한다.
- 연결 설정이 호환되지 않으면 복호화나 CODEF 조회를 시도하지 않는다.

## 4. 계약

### Input Port와 경계 매핑

```text
ConnectBankUseCase
ConnectBankWebRequest -> ConnectBankCommand
BankConnectionResult -> BankConnectionWebResponse

LinkBankAccountsUseCase
LinkBankAccountsWebRequest -> LinkBankAccountsCommand
LinkedAccountsResult -> LinkedAccountsWebResponse

GetBankCatalogUseCase
요청 본문 없음
List<BankCatalogResult> -> List<BankCatalogWebResponse>
```

### Output Port

| Port | Capability | Caller | Adapter |
|---|---|---|---|
| `BankConnectionPort` | CODEF 기관 create/add/update와 은행 계좌 후보 조회 | `BankAccountService` | `CodefBankConnectionAdapter` |
| `ExternalConnectionStorePort` | 연결·기관 상태 저장과 호환성 비활성화 | Service, startup runner | JPA Adapter |
| `AccountStorePort` | 후보 중복 확인, 계좌 저장·재활성화, 비호환 계좌 비활성화 | Service, startup runner | JPA Adapter |
| `LoadUserPort` | 현재 사용자 조회 | `BankAccountService` | 기존 User Persistence Adapter |

### Web API

```text
GET /api/v2/accounts/banks
Request: 없음
Response: [{ organization, name, iconUrl }]

POST /api/v2/accounts/connections/banks
Request: organization, loginId, password, birthDate?
Response: connectionId, organization, status, candidates[]
Candidate: candidateKey, type, name, maskedNumber, balance, currency, alreadyLinked

POST /api/v2/accounts/external
Request: connectionId, organization, candidateKeys[], birthDate?
Response: accounts[]
Account: id, organization, type, name, maskedNumber, balance, currency
```

세 API 모두 인증이 필요하다. 연결·계좌 연동 요청의 `organization`은 활성 `BANK` 카탈로그에 존재해야 한다. `connectedId`, 원본 계좌번호, 외부 계좌 Key와 CODEF 원본 응답은 반환하지 않는다.

## 5. 영속화

### `external_connections`

- `id`, `user_id`, `connected_id_ciphertext`, `configuration_fingerprint`, `active`, UTC 생성/수정 시각
- `user_id` unique

### `connected_institutions`

- `id`, `external_connection_id`, `organization`, `status`, `last_result_code`, UTC 생성/수정 시각
- `(external_connection_id, organization)` unique

### `accounts`

- `id`, `user_id`, `external_connection_id`, `organization`, `candidate_key`
- `external_account_key_ciphertext`, `type`, `name`, `masked_number`, `balance`, `currency`, `active`
- `(external_connection_id, candidate_key)` unique
- 원본 외부 계좌 Key는 `CODEF_CONNECTED_ID_ENCRYPTION_KEY`로 AES-GCM 암호화한다.

이번 재연결 수정은 Backend 영속화 구조를 바꾸지 않으므로 Flyway 마이그레이션이 필요하지 않다. Mock SQLite의 `institutions.login_id`는 Mock 서버 시작 시 자체 backfill한다.

설정 지문 불일치 시 `external_connections.active=false`로 전환하고 연결된 `accounts.active=false`로 전환한다. 재연결 후 사용자가 같은 후보를 다시 선택하면 현재 암호문과 표시 정보로 갱신한다.

## 6. 설계 결정

- `provider` registry나 추상 factory는 실제 두 번째 제공자가 없으므로 만들지 않는다.
- `BankCatalog`가 `BANK` 공통 코드를 계좌 도메인 값으로 해석하며, 범용 공통 코드 Controller는 만들지 않는다.
- 연결·계좌 연동은 CODEF 호출 전에 `BankCatalog`로 지원 기관 여부를 검증한다.
- 연결과 계좌 연동은 하나의 `BankAccountService`가 두 UseCase를 구현한다. 두 흐름이 같은 연결·후보 검증 규칙을 공유하기 때문이다.
- 후보 Key는 서버만 아는 `connectedId`와 기관코드·원본 계좌 Key를 SHA-256으로 묶어 생성한다. 클라이언트 입력을 신뢰하지 않고 연동 시 후보를 다시 조회한다.
- 동일 사용자의 최초 연결 생성은 사용자 행 `PESSIMISTIC_WRITE` 잠금으로 CODEF `create` 호출 전부터 직렬화한다.
- 동일 외부 연결의 계좌 relink는 연결 행 `PESSIMISTIC_WRITE` 잠금으로 후보 조회부터 upsert까지 직렬화한다.
- 위 두 흐름은 외부 호출과 저장을 하나의 transaction 경계에서 처리해 중복 연결·Unique Constraint 500을 방지한다.
- 계좌 잔액은 연동 시점 snapshot이며 거래 동기화가 구현되기 전에는 자동 갱신하지 않는다.

## 7. 영향 범위와 구현 순서

- [x] 기존 payment 연결 코드를 external/account 경계로 교체
- [x] Command/Result와 Web Response 분리
- [x] capability 중심 Output Port와 Service 구현
- [x] Domain Account와 연결 모델 구현
- [x] entity/mapper/repository 하위 패키지로 JPA Adapter 구현
- [x] CODEF adapter를 계좌 후보 계약으로 변경
- [x] Flyway V2를 `external_connections`, `connected_institutions`, `accounts`로 갱신
- [x] CODEF 설정 변경 startup invalidation에 account 비활성화 포함
- [x] StatusCode를 framework-neutral 숫자 계약으로 전환
- [x] 문서·Mock 실행 계약 동기화
- [x] 은행 카탈로그 조회 API와 지원 기관 검증 연결

### 예상 commit 경계

실제 commit은 요청받은 경우에만 만든다.

```text
refactor(common): 상태 코드의 Spring 의존 제거
feat(account): CODEF 은행 연결과 결제계좌 연동 구현
feat(account): 은행 카탈로그를 계좌 연동에 연결
build(database): 외부 연결과 계좌 Flyway 스키마 추가
docs(account): CODEF 계좌 연동 계약 갱신
```

## 8. 검증

```bash
./gradlew clean compileJava --console=plain
./gradlew test --console=plain
./gradlew bootJar --console=plain
python3 tools/codef-mock/server.py
git diff --check
git status --short --untracked-files=all
```

수동 시나리오:

1. 최초 은행 연결 → 후보 반환, 계좌 DB 저장 없음
2. 후보 선택 연동 → `accounts` 저장, 원본 외부 Key 평문 없음
3. 같은 후보 재연동 → 같은 Account ID 갱신
4. 위조·다른 사용자 candidateKey → 저장 없이 오류
5. 설정 지문 변경 → 연결·계좌 비활성화
6. 같은 기관 재연결 → `/v1/account/update` 호출 후 수정된 인증정보의 후보 재조회
7. 서로 다른 기관에 다른 Mock `loginId` 등록 → 기관별 해당 사용자 계좌 반환

실제 CODEF 자격증명이 없으면 Mock으로 외부 계약을 검증하고 실제 CODEF 호출은 미검증으로 명시한다.

## 9. 진행 기록

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-20 | 기존 V2 payment 구현, 최신 아키텍처 규칙, 기존 backend 연결·후보·저장 흐름 재분석 | external/account 수직 기능 구현 | 로컬 PostgreSQL·Docker 중지, 실제 CODEF 자격증명 없음 |
| 2026-08-20 | external/account 경계, 2단계 후보 검증·연동, 암호화 영속성, 설정 변경 비활성화, Mock 프로필 구현 | PostgreSQL 가동 환경에서 Flyway·Context 테스트 | 실제 CODEF 자격증명 없음 |
| 2026-08-22 | `BankCatalog` 조회 API와 연결·계좌 연동 전 지원 은행 검증 추가 | PostgreSQL에서 카탈로그 조회와 미지원 코드 오류 확인 | 실제 CODEF 자격증명 없음 |
| 2026-08-23 | 운영 Mock의 `connectedId`가 최초 `loginId`로 고정되는 원인 확인, 공식 `/v1/account/update` 계약 확정 | 기관별 매핑·재연결 구현과 회귀 검증 | 기존 Mock SQLite 매핑은 최초 연결 사용자로 backfill 필요 |
| 2026-08-23 | Mock 기관별 `loginId` 매핑·SQLite backfill·`/v1/account/update`, Backend 재연결 `update` 호출 구현 | 배포 후 실제 `troll`/`warstroll`/`yong` 재연결 확인 | 실제 CODEF 자격증명으로는 미검증 |

## 11. 검증 결과

- `./gradlew clean compileJava --console=plain`: 성공
- `./gradlew bootJar --console=plain`: 성공, JAR에 Flyway V1/V2 포함 확인
- 격리 SQLite Mock OAuth → account/create → account-list: 성공
- `./gradlew compileJava --no-daemon --console=plain`: 성공 (`BankCatalog` 연결 포함)
- `./gradlew test --console=plain`: 로컬 PostgreSQL 5432 중지로 Flyway 연결 단계에서 실패
- 실제 CODEF 호출: 자격증명이 없어 미수행
- `python3 -m unittest -v`: 5개 성공. 기관별 사용자, 동일 기관 수정, 계좌·거래 조회, 기존 SQLite backfill 확인
- `./gradlew test --tests com.amateur.howmuchbackend.application.account.service.BankAccountServiceTest --console=plain`: 성공
- 로컬 PostgreSQL·Redis와 테스트 설정을 주입한 `./gradlew test --console=plain`: 104개 성공
- `./gradlew compileJava --console=plain`: 성공

## 10. 참고 자료

- [CODEF Connected ID](https://developer.codef.io/common-guide/connected-id/cid)
- [CODEF 계정 등록](https://developer.codef.io/common-guide/connected-id/register)
- [CODEF 은행 보유계좌](https://developer.codef.io/products/bank/common/p/account)
- 기존 backend `PaymentExternalConnectionService`
- 기존 backend `CodefPaymentExternalAdapter`
