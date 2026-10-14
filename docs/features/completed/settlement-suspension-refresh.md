# 정산 수동 중단 전 입금 재조회

- 상태: 구현 및 검증 완료
- 최종 수정: 2026-10-14
- 관련 이슈: [#521](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/521) — 입금 재조회 기반 미납 정산 수동 중단
- 브랜치: `fix/521-settlement-suspension-refresh`
- 관련 ADR: 없음

## 1. 목적

### 문제

정산 생성자의 `POST /api/v2/settlements/{settlementId}/suspend`는 다른 참여자의 품목 선택 제출 기록이 있으면 실제 송금 여부와 관계없이 `SETTLEMENT_NOT_SUSPENDABLE`로 거절한다. 참여자가 제출 후 송금하지 않은 경우 생성자가 CODEF 거래내역을 다시 확인해 미납 정산을 중단할 수 있어야 한다.

### 지금 해결하는 이유

제출 기록은 송금 여부를 나타내지 않는다. 제출 후 송금하지 않은 정산까지 막으면 기존 수동 중단 흐름을 쓸 수 없다. 반대로 입금이 확인됐거나 해당 계좌의 입금 귀속이 불명확하면 중단을 허용해서는 안 된다.

### 성공 기준

- [x] 기존 중단 API와 응답 계약을 유지하면서 생성자만 `REQUESTED` 또는 `AWAITING_DEPOSIT` 정산을 중단할 수 있다.
- [x] Share 또는 제출 등 참여자 송금 가능성이 있으면 기존 CODEF 거래 조회와 입금 매칭을 다시 사용한 뒤, 부분·완납 입금이 확인되지 않은 경우에만 중단한다.
- [x] 양수 입금 중 어떤 정산에도 귀속되지 않은 거래, CODEF 조회 실패, 추가 인증, 정산/수금계좌/Share/제출 변경이 있으면 fail closed한다.
- [x] 외부 CODEF 호출은 DB 트랜잭션과 잠금 밖에서 실행하고, refresh 저장과 최종 중단은 account → settlement 잠금 순서로 짧게 처리한다.
- [x] 입금/선택 기록을 삭제하지 않는다. 수동 중단 후 이미 은행 앱에서 시작한 송금은 취소되지 않을 수 있음을 API 문서에 알린다.

### 범위 밖

- 기존 자동 `SUSPENDED` 전이 또는 SUSPENDED Share 송금 가능 여부의 재설계
- 새 상태, DB 테이블/컬럼/인덱스, 외부 provider 호출 방식, 송금 API 추가
- 은행 앱에서 시작된 송금을 취소하거나 CODEF 조회 시점 이후의 외부 송금을 원자적으로 막는 기능
- 기존 입금 매칭 규칙 완화 또는 입금/매칭 데이터 삭제

## 2. 도메인 분석

### 재사용 도메인

- 기존 정산, `SettlementShare`, `SettlementItemSubmission`, 수금 계좌 스냅샷, CODEF 입금 대조를 재사용한다.
- 수동 중단만 `REQUESTED`와 `AWAITING_DEPOSIT`에서 `SUSPENDED`로 전이 가능하게 한다. 자동 중단에서 쓰는 `Settlement.suspend()`는 `REQUESTED` 전이 그대로 유지한다.

### 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 수동 중단 | 정산 생성자가 기존 endpoint로 정산을 중단 | `SuspendSettlementUseCase`, `Settlement.suspendManually` |
| 입금 재조회 | 수금계좌 거래를 CODEF로 다시 읽고 기존 matcher/writer로 반영 | `SettlementBankTransactionFetchService`, `SettlementDepositWriter` |
| 미귀속 입금 | 기간 내 양수 INCOME 중 기존 deposit matcher로 어느 정산에도 연결되지 않은 거래 | 거래의 `externalTransactionKey`가 기존 matched-key 결과에 없음 |
| 송금 가능 몫 | 생성자가 아닌 참여자의 현재 Share 또는 제출 기록 | `SettlementShare`, `SettlementItemSubmission` |

### 사용자와 UseCase

| 사용자 | 목표 | UseCase |
|---|---|---|
| 정산 생성자 | 미납 여부를 다시 확인하고 정산 중단 | 기존 `SuspendSettlementUseCase` |

### 상태와 불변식

- 수동 전이: `REQUESTED → SUSPENDED`, `AWAITING_DEPOSIT → SUSPENDED`.
- 기존 자동 전이: `Settlement.suspend()`는 `REQUESTED → SUSPENDED`로 유지한다.
- 생성자 권한 확인은 조회 전과 최종 저장 시 유지한다.
- 참여자 Share 중 부분 또는 완납 입금이 하나라도 확인되면 수동 중단을 거절한다.
- 계좌 기간 내 양수 INCOME 중 기존 matcher로 정산에 귀속되지 않은 건도 미확정 입금으로 보고 거절한다. 이 보수적 정책은 계좌에 무관한 입금이 있어도 중단을 막을 수 있다.
- 재조회 후 `SettlementReceivingAccountSnapshot`, settlement status, participants, Shares, submissions가 바뀌면 최신 상태를 다시 판단하지 않고 `CONCURRENT_UPDATE`로 거절한다. 사용자는 최신 상태에서 다시 시도한다.
- CODEF 조회 시점 이후 새로 시작되거나 은행에 늦게 반영된 이체는 서버 잠금으로 완전히 차단할 수 없다. 열린 은행 앱 송금은 수동 중단으로 취소되지 않는다.
- 기존 `SettlementTransferInstructionReader`와 `SettlementLinkEntryService`는 `SUSPENDED`에서 확정된 Share의 송금 안내/진입을 허용한다. 이 기능은 그 동작을 재설계하지 않는다.

## 3. 시나리오

### 정상 흐름

1. 현재 사용자가 생성자인지 확인하고 settlement/participant/share/submission/snapshot 상태를 짧은 read-only 트랜잭션에서 읽는다.
2. 생성자 외 Share 또는 submission이 있어 송금 가능성이 있으면 CODEF를 트랜잭션 밖에서 조회한다. Share/submission이 모두 없고 송금 가능성이 없었던 기존 `REQUESTED` 직접 중단은 CODEF 설정 없이 유지한다.
3. 기존 deposit writer가 account → settlement 순서로 잠그고 조회 거래를 저장/대조한다. `REQUESTED`는 현재 Share의 매칭에 포함하되 자동 완료 전이는 하지 않는다.
4. 별도 짧은 최종 트랜잭션이 같은 lock 순서로 정산/계좌/참여몫 상태를 재검증하고, 해당 정산 입금 및 미귀속 양수 거래가 없을 때 `SUSPENDED`를 저장한다.
5. 실제 partial/full deposit 또는 미귀속 양수 거래가 있으면 `SETTLEMENT_NOT_SUSPENDABLE`로 거절한다. refresh가 먼저 커밋되므로 새로 발견한 계좌 거래와 명확한 매칭 기록은 남는다.

### 실패 흐름

- CODEF 실패, 추가 인증, 연결/스냅샷 누락: 조회 오류를 그대로 전달하고 중단하지 않는다.
- 다른 정산에 이미 명확히 귀속된 거래: 해당 정산 미납 판단에 쓰지 않는다.
- 어떤 정산에도 귀속되지 않은 양수 INCOME: `SETTLEMENT_NOT_SUSPENDABLE`.
- 현재 정산의 partial/full matched deposit: `SETTLEMENT_NOT_SUSPENDABLE`.
- refresh 전후 정산·수금계좌 스냅샷·참여자·Share·submission 변경: `CONCURRENT_UPDATE`.
- 비생성자 요청 또는 허용되지 않는 상태: 기존 권한/상태 오류를 반환한다.

### 경계 조건

- `REQUESTED`에서도 제출자의 Share와 부분 송금이 있을 수 있어 수동 중단 전 조회/입금대조가 필요하다.
- 제출이 모두 끝난 `AWAITING_DEPOSIT`도 미납이면 생성자가 중단할 수 있어야 한다.
- 매칭되지 않은 입금을 무관한 거래로 단정하지 않는다. 새 이름/금액 매칭 규칙은 만들지 않는다.
- manual suspend는 transaction 중복을 방지하기 위해 account row가 있으면 account → settlement 순으로 잠근다. reconcile도 같은 순서를 쓴다.
- `ITEM_ALLOCATION` 재개는 기존 미납 Share가 있으면 `SettlementRevisionGuard.ensureUnpaid()` 성공 후 기존 `DeleteSettlementSharePort`로 Share만 재생성 준비용으로 초기화한다. selection은 유지하고 입금 기록은 지우지 않는다. confirmed payment가 있으면 초기화 전에 거절한다. 이는 이번에 새로 허용되는 `Share 있음 → 수동 중단 → 재개 → 재제출` 경로에서 unique(settlement_id, payer_participant_id) 충돌과 0원 Share 잔존을 막는다.

영수증 OCR 정산은 별도 중단 API가 아니라 동일한 `ITEM_ALLOCATION` 제출·Share와 중단 흐름을 사용한다. 미납 확인 후 중단하면 기존 영수증 교체 API를 사용할 수 있고, 교체 후 재개·재선택·재제출로 이어진다. 더치페이 영수증 정산도 동일한 중단 흐름을 사용한다.

## 4. 계약

### Input Port와 경계 매핑

```text
UseCase: 기존 SuspendSettlementUseCase
Command: 없음
Result: 기존 SettlementResult
Web: POST /api/v2/settlements/{settlementId}/suspend, 요청/응답 shape 유지
```

### Output Ports

새 Output Port 없음. 기존 `BankTransactionPort`, `ExternalConnectionStorePort`, 계좌/정산 조회·저장 Port, `SettlementDepositStorePort`, `LoadAccountTransactionPort`, `SaveAccountTransactionPort`를 재사용한다.

| Port | 목적 | 호출자 | Adapter |
|---|---|---|---|
| `BankTransactionPort` | CODEF 거래 조회 | 기존 fetch service 경유 | 기존 CODEF Adapter |
| `SettlementDepositStorePort` | 기존 매칭 거래 및 payable 후보 조회, deposit 저장 | 기존 writer/policy 흐름 | 기존 settlement persistence Adapter |
| `LoadAccountTransactionPort`, `SaveAccountTransactionPort` | 계좌 기간 거래 재조회/저장 | deposit reader/writer | 기존 account persistence Adapter |

### Application Policy

- `SettlementSuspensionPolicy`는 현재 정산의 확인 입금 및 미귀속 양수 INCOME이 있는지 판단한다.
- 서비스가 기존 거래/매칭 Port로 facts를 로드하고 Policy에는 boolean/domain 값만 전달한다.
- Policy가 Port/Adapter/HTTP/CODEF를 호출하지 않는다.
- 기존 payer-name, amount, timestamp 매칭 기준은 `SettlementDepositMatchingPolicy` 그대로 재사용한다.

### Web API

```text
POST /api/v2/settlements/{settlementId}/suspend
Request: 없음
Response: 기존 SettlementWebResponse
Errors: 기존 SETTLEMENT_ACCESS_DENIED, INVALID_SETTLEMENT_STATUS_TRANSITION, SETTLEMENT_NOT_SUSPENDABLE, CONCURRENT_UPDATE; CODEF 오류 계약 재사용
Authentication: 생성자
```

Swagger와 `SETTLEMENT_NOT_SUSPENDABLE` 문구를 “입금 확인 또는 입금 귀속 불명확” 의미로 고친다. 이미 은행 앱에 열린 송금은 취소할 수 없다는 점도 문서화한다.

### 저장소와 외부 통합

- DB Entity/query/schema 변경: 없음
- Flyway migration 필요: No
- Migration PR: N/A
- CODEF: 기존 `BankTransactionPort`/credential reveal/monthly fetch 경로를 추출한 `SettlementBankTransactionFetchService`로 재사용
- 새로운 환경 변수나 profile 변경: 없음

## 5. 설계 결정

### 비즈니스 규칙

- Domain: 수동 중단만 `REQUESTED` 및 `AWAITING_DEPOSIT`에서 허용한다. automatic `suspend()`는 변경하지 않는다.
- Application Policy: 입금/미귀속 거래가 있으면 중단 불가. 생성자 외 제출기록 자체는 더 이상 거절 사유가 아니다.
- Application Service: 외부 조회 전 baseline을 기록하고, CODEF/fetch/writer commit 후 최종 transaction에서 baseline을 재검증한다.
- 입금 reconciliation으로 새로 발견한 거래/명확한 deposit은 suspension rejection과 관계없이 먼저 커밋해 사용자가 최신 입금 상태를 보게 한다.

### 기술과 패턴

- 제약: CODEF 호출은 외부 지연과 실패가 있는 네트워크 호출이며 DB transaction/row lock 안에서 수행하면 안 된다.
- 선택: 조회 전 baseline read → 무트랜잭션 fetch → 기존 writer의 짧은 refresh transaction → 별도 최종 lock/revalidation/suspend transaction.
- 이유: 기존 provider·계좌 비밀 복호화·중복 방지·매칭/저장 흐름을 재사용하면서 외부 조회와 DB 잠금을 분리한다.
- 대안: 기존 `SettlementDepositService.reconcile()` 직접 호출만으로 판단. 그러나 기존 서비스는 `AWAITING_DEPOSIT`/`COMPLETED`만 허용하고 명확한 매칭이 없는 양수 거래를 미납으로 해석할 수 있어 단독으로 안전하지 않다.
- 한계: refresh fetch 종료와 final transaction 사이, 은행 처리 지연, 이미 열린 앱 사이에 발생하는 외부 송금은 DB lock으로 막을 수 없다. 어떤 settlement에도 매칭되지 않은 양수 입금은 관련 여부를 증명하기 어려워 보수적으로 거절한다.

## 6. 영향 범위

| 영역 | 영향 | 확인한 코드 |
|---|---|---|
| API | endpoint/response 유지, 상태 및 오류 설명 갱신 | `SettlementController`, `SettlementDocs`, `SettlementStatusCode` |
| Application | 두 단계 suspension, 기존 CODEF fetch/deposit writer 재사용 | `SettlementService`, `SettlementDepositService`, `SettlementDepositWriter`, 새 suspension reader/writer/service |
| Domain | 수동 상태 전이만 AWAITING_DEPOSIT 허용 | `Settlement` |
| Persistence | schema/migration 불필요; 기존 payable candidate query가 REQUESTED와 AWAITING_DEPOSIT를 조회해 기존 matcher에 전달 | `SettlementDepositJpaRepository`, `SettlementDepositPersistenceAdapter` |
| Security | 생성자 확인 유지, 추가 권한 없음 | `CurrentActorPort`, Settlement service |
| Documentation | Feature Plan, Swagger, status message, 은행 앱 송금 한계 | `SettlementDocs`, `SettlementStatusCode` |
| Compatibility | resume 전 미납 guard 후 기존 Share 삭제, selections/submissions 흐름 보존 | `SettlementService`, `SettlementRevisionGuard`, `DeleteSettlementSharePort` |

기존 동작 유지:

- 기존 중단 URL과 response shape
- Share/submission/입금 매칭 자료 삭제 없음
- CODEF가 필요한 경우 실패·추가 인증을 안전하게 전달
- 자동 `Settlement.suspend()` 동작과 기존 SUSPENDED Share 송금 안내 허용 범위
- 기존 Dutch Pay resume 흐름

## 7. 구현 계획

- [x] Migration-only PR: N/A (기존 스키마와 Port/Adapter로 처리 가능)
- [x] UseCase/Command/Result: 기존 계약 유지
- [x] Required Output Ports: N/A (기존 Ports 재사용)
- [x] Domain rule: `suspendManually`만 허용 상태 확대
- [x] Application Policy: 입금 및 미귀속 양수 거래 fail-closed 검증
- [x] Application Service: 무트랜잭션 조회와 refresh/final transaction 분리
- [x] Output Adapters: 기존 Adapter 재사용; fetch orchestration 추출
- [x] Web/Swagger: 설명과 error message 수정, endpoint/response 유지
- [x] Resume 호환: confirmed payment guard 후 기존 ITEM Share 초기화
- [x] Tests/docs/verification

### 예상 commit boundary

사용자의 PR 게시 요청에 따라 `fix(settlement): 중단 전 입금을 재조회하고 미납 여부 확인`으로 구현·회귀 테스트·완료 문서를 함께 커밋한다. 기존 CODEF 조회 재사용과 transaction 경계 및 재개 호환이 하나의 동작 변경을 구성한다.

## 8. 검증

### 명령

```bash
DB_URL=jdbc:postgresql://127.0.0.1:15433/howmuch_suspension_test_20261014 DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16380 bash scripts/test.sh compileJava
DB_URL=jdbc:postgresql://127.0.0.1:15433/howmuch_suspension_test_20261014 DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16380 bash scripts/test.sh test --tests '*Settlement*'
git diff --check
git status --short --untracked-files=all
```

외부조회 proxy 경로에서 `BankTransactionPort.fetch` 진입 시 `TransactionSynchronizationManager.isActualTransactionActive()`가 false인지 integration test로 확인한다. 합성 transaction/CODEF mock만 쓰고 실제 은행·dev/prod·사용자 `.env`/token/credential을 사용하지 않는다.

### 완료 기준

- [x] 미제출/제출 후 무입금 suspension success
- [x] partial/full/ambiguous unmatched income suspension reject; refresh data commit preserved
- [x] CODEF error/additional auth, non-creator, invalid status, state/account/share/submission race rejection
- [x] `REQUESTED` Share match with no completion; `AWAITING_DEPOSIT` unpaid suspension success
- [x] refresh writer와 final writer가 account → settlement 순서로 잠금; 기존 settlement lock-order 테스트 통과
- [x] item resume deletes only unpaid shares and 0원/re-submit avoids stale Share; confirmed-payment resume does not delete
- [x] actual proxy path verifies external fetch has no active transaction
- [x] Swagger/error text and bank-app transfer limitation are documented

별도 동시 refresh/suspend interleaving integration test는 추가하지 않았다. 새 refresh/final writer 모두 기존 account → settlement 잠금 순서를 사용하며, 기존 lock-order 테스트만 검증 범위에 포함한다.

### 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 설계/schema 판단 | 완료 | 기존 테이블·Ports 재사용, migration 없음 |
| Settlement 테스트 | 통과 | `build/suspension-focused.log`; 격리 DB/Redis fixture로 786개 통과, 재조회 통합 시나리오 포함 |
| 최종 Java 검증 | 통과 | `build/suspension-final.log`; `compileJava`, 변경 영향 focused tests 24개, `bootJar` 통과 |
| PR 게시 전 영수증·중단 재검증 | 통과 | `compileJava`, `*SettlementReceipt*` 및 `*SettlementSuspension*` 67개 통과; `build/suspension-pr-receipt.log` |
| JavaScript 테스트 | 통과 | 35개 통과 |
| 외부조회 transaction 경계 | 통과 | 실제 Spring proxy 호출에서 CODEF fetch 시 transaction inactive 확인 |
| 문서/차이 검증 | 통과 | relative link 대상 없음, `git diff --check` 통과 |

## 9. 진행 로그

| 날짜 | 완료 | 다음 작업 | 위험 |
|---|---|---|---|
| 2026-10-14 | 기존 흐름을 재사용한 suspend refresh, REQUESTED 후보 조회, 안전한 ITEM Share 재개 초기화 구현; Settlement 786개·JavaScript 35개·최종 focused 24개 통과, `compileJava`/`bootJar` 통과 | 완료 | 별도 동시 interleaving test는 없고, 은행 반영 지연은 보수적 fail-closed와 문서화로 한정 |

## 10. 결정 로그

| 날짜 | 결정 | 이유 | 대안/영향 |
|---|---|---|---|
| 2026-10-14 | migration 없이 기존 CODEF/deposit Writer 사용, refresh commit 뒤 최종 suspension | 기존 DB 구조로 재조회/매칭/저장 가능하며 외부 호출을 lock 밖에 둔다 | 새 조회 Port/provider 없이 기존 fetch 흐름을 추출하고 REQUESTED payable 후보를 기존 query에 포함한다 |
| 2026-10-14 | 미귀속 양수 계좌 입금도 suspension reject | 기존 matcher의 `Optional.empty()`만으로 무입금이라 단정할 수 없다 | 계좌의 무관한 입금까지 막을 수 있는 보수적 한계를 허용한다 |
| 2026-10-14 | resume에서 guard 통과 후 기존 unpaid ITEM Share 삭제 | 기존 selections를 유지하며 Share를 다시 계산하고 0원 Share 잔존/unique 충돌 방지 | guard가 입금 이력 존재 시 재개를 막으며 deposit rows는 삭제하지 않는다 |
