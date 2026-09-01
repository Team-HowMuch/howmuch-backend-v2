# Feature Plan: 앱 OCR 영수증 지출 등록과 결제 정보 조회

- Status: Complete
- Last updated: 2026-09-01
- Related issue: 없음
- Related ADR: 없음

## 1. Purpose

### Problem

앱은 OCR 서버에서 영수증 결과 JSON을 직접 받지만, 기존 서버 API는 외부 OCR 결과 참조만 받아 서버가 다시 OCR 결과를 조회한다. 이 구조에서는 앱이 읽은 실제 품목을 저장할 수 없고 운영 환경에서는 OCR Adapter 미연결 오류가 발생한다. 또한 정산 상세 응답에 영수증 결제 상호명과 결제일이 없다.

### Why now

실제 영수증 품목을 정산 지출과 품목 배분에 사용하고, 상세 화면의 결제 정보 계약을 완성해야 한다.

### Success criteria

- [x] 앱이 `POST /api/v2/settlements/{settlementId}/expenses/receipt`로 영수증 지출과 품목을 원자적으로 저장한다.
- [x] 서버가 품목 행 `amount` 합계로 지출 총액을 계산한다.
- [x] 동일 `receiptReference` 재시도는 중복 저장되지 않는다.
- [x] 정산 상세가 `paymentInfo { amount, storeName, paidAt }`를 반환한다.
- [x] 영수증 지출이 정확히 한 건이 아니면 `storeName`과 `paidAt`은 `null`이다.

### Out of scope

- 기존 `POST /expenses/receipt-ocr` 제거
- 외부 OCR Adapter 연동
- 여러 영수증을 하나의 대표 상호명·결제일로 합치는 규칙

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `SettlementExpense`, `SettlementItem`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 없음

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 영수증 참조 | 앱 재시도 중복을 막는 스캔 식별자 | `SettlementExpense.sourceReference` |
| 결제일 | 영수증에 기록된 결제 시각 | `SettlementExpense.paidAt` |
| 품목 금액 | 수량을 반영한 한 행의 전체 금액 | `SettlementItem.amount` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 앱이 읽은 영수증 결과를 등록한다 | `CreateSettlementReceiptExpenseUseCase` |
| 정산 조회자 | 결제 금액·상호명·결제일을 본다 | 기존 `GetSettlementUseCase` |

### State and invariants

- State transitions: 지출 등록은 기존과 동일하게 `DRAFT`에서만 가능하다.
- Rules that must always hold: 생성자만 등록할 수 있고 품목 이름·수량·금액은 유효해야 하며 지출 금액은 품목 행 금액 합계다.
- Invalid states: 빈 품목, 0 이하 수량·금액, 합계 overflow, 동일 참조 중복, 수정 불가능 상태 등록.

## 3. Scenarios

### Normal flow

1. 앱이 OCR 결과와 앱 생성 참조를 전송한다.
2. 서버가 입력을 검증하고 품목 금액을 합산한다.
3. 기존 `SettlementReceiptExpenseWriter`가 지출과 품목을 한 트랜잭션으로 저장한다.
4. 상세 조회는 단일 영수증 지출의 상호명과 결제일을 반환한다.

### Failure flow

- 잘못된 입력: `400 INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST`
- 동일 참조 중복: `409 SETTLEMENT_RECEIPT_ALREADY_IMPORTED`
- 수정 불가능한 정산: `409 SETTLEMENT_NOT_EDITABLE`

### Boundary conditions

- `paidAt`은 nullable이며 없는 값을 등록 시각으로 보정하지 않는다.
- 여러 영수증 또는 영수증이 없는 정산은 대표 상호명·결제일을 만들지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: CreateSettlementReceiptExpenseUseCase
Command: CreateSettlementReceiptExpenseCommand
Result: SettlementReceiptExpenseMutationResult
Web Request DTO -> Command: CreateSettlementReceiptExpenseWebRequest.toCommand()
Result -> Web Response DTO: SettlementReceiptExpenseMutationWebResponse.from(result)
```

### Output Ports

기존 `LoadSettlementExpensePort`, `SaveSettlementExpensePort`, `SaveSettlementItemPort`를 재사용하며 새 Output Port는 없다.

### Application Policy

- N/A: 기존 Writer의 권한·상태·중복·합계 규칙을 재사용한다.

### Web API

```text
POST /api/v2/settlements/{settlementId}/expenses/receipt
Request: { receiptReference, storeName, paidAt?, items: [{ name, quantity, amount }] }
Response: 기존 SettlementReceiptExpenseMutationWebResponse
Errors: 400, 401, 403, 404, 409
Authentication: bearerAuth, 정산 생성자

GET /api/v2/settlements/{settlementId}
Response addition: paymentInfo { amount, storeName?, paidAt? }
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: `settlement_expenses.paid_at` JPA 매핑
- Flyway migration required (`Yes` / `No`): Yes
- Planned migration: `V28__add_settlement_expense_paid_at.sql`
- Migration pull request: https://github.com/Team-HowMuch/howmuch-backend-v2/pull/232
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 입력 검증과 합계 계산은 Application Service, 원자 저장과 중복 검증은 기존 Writer.
- Behavior owned by Domain: 영수증 생성 시 nullable `paidAt`을 보존한다.
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 현재 사용자 확인, 입력 정규화, 서버 합계 계산, Writer 호출, 상세 결제 정보 조립.

### Technology and patterns

- Constraint to solve: OCR 서버는 결과 참조를 발급하지 않고 JSON을 직접 반환한다.
- Selected approach: 앱이 스캔별 `receiptReference`를 생성하고 결과 JSON을 서버에 전달한다.
- Reason for selection: 기존 DB 중복 제약과 Writer를 유지하면서 재시도를 안전하게 처리한다.
- Alternatives considered: 서버 생성 참조는 같은 요청 재시도 중복을 막지 못해 제외한다.
- Trade-offs and limits: 여러 영수증의 대표 결제 정보는 정의하지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 앱 영수증 등록, 상세 `paymentInfo` | Receipt Controller/Docs, Settlement response |
| Application | 새 Command/UseCase, 기존 Service/Writer 재사용 | settlement application package |
| Domain | nullable `paidAt` | `SettlementExpense` |
| Persistence | V28 칼럼 JPA 매핑 | SettlementExpense Entity/Mapper |
| Redis | N/A | N/A |
| Security | 기존 생성자 권한 재사용 | `SettlementReceiptExpenseWriter` |
| Configuration | N/A | N/A |
| Documentation | Feature Plan, Swagger | 이 문서와 `SettlementReceiptExpenseDocs` |

Existing behavior that must remain unchanged:

- 기존 참조 기반 `/expenses/receipt-ocr`와 품목 조회 API는 유지한다.
- 수기·결제내역 지출의 `paidAt`은 `null`이다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`Required`: #232)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports (`N/A`: 기존 Port 재사용)
- [x] Domain models and rules
- [x] Application Policies (`N/A`)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] Documentation and verification results

### Expected commit boundaries

```text
feat(settlement): 앱 OCR 영수증 등록과 결제 정보 조회 추가
test(settlement): 영수증 등록과 상세 결제 정보 검증
docs(settlement): 앱 OCR 영수증 계약 완료 기록
```

실제 커밋은 사용자 요청이 없으므로 생성하지 않는다.

## 8. Verification

### Commands

```bash
./gradlew test --tests '*SettlementReceiptExpense*' --tests '*SettlementDetailServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementExpensePersistenceIntegrationTest' --tests '*SettlementExpenseTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [x] Purpose의 사용자 결과를 검증했다.
- [x] 정상·실패·경계 흐름을 검증했다.
- [x] Swagger와 실제 계약이 일치한다.
- [x] 미실행 검증과 이유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 관련 테스트 | Passed | 영수증 Controller/Service/Writer, 상세 Service/Controller, Domain, V28 영속, OpenAPI 통과 |
| compileJava | Passed | `./gradlew compileJava` |
| repository checks | Passed | `git diff --check`, `git status --short --untracked-files=all` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-01 | 현재 흐름, #232, 계약과 영향 범위 확인 | 구현 및 검증 | 여러 영수증의 대표 결제 정보 규칙 없음 |
| 2026-09-01 | 앱 영수증 등록, `paidAt` 영속, 상세 `paymentInfo`, 테스트 완료 | 완료 보고 | 전체 테스트는 실행하지 않고 영향 테스트만 실행 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-01 | 앱 생성 `receiptReference`를 필수로 받는다 | 재시도 중복 방지 | 서버 생성 참조는 멱등성 없음 |
| 2026-09-01 | 단일 영수증일 때만 결제 메타데이터를 반환한다 | 임의 대표값 방지 | 여러 영수증 규칙은 후속 정의 |
| 2026-09-01 | 없는 `paidAt`을 보정하지 않는다 | 결제일을 지어내지 않음 | 기존 행과 OCR 미인식은 null |

## 11. Completion record

- Completed on: 2026-09-01
- Final behavior: 앱 OCR 결과를 서버 합계로 저장하고 상세에 결제 정보를 반환한다.
- Unverified items: 전체 테스트 스위트와 실제 앱 연동은 실행하지 않았다.
- Remaining risks: 여러 영수증의 대표 상호명·결제일은 정의하지 않아 null로 반환한다.
- Follow-up work: 앱은 스캔마다 안정적인 `receiptReference`를 생성해 재시도에 재사용해야 한다.
