# Feature Plan: 정산 지출 및 금액 배분

- Status: Draft
- Last updated: 2026-08-24
- Related issue: [#29 정산 지출 입력 및 금액 배분](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/29)
- Related ADR: N/A
- Product requirement: [일회성 정산 PRD](../../product/settlement-prd.md)
- Prerequisites: [정산 초안 및 기본 정보 Spec](../completed/settlement-core-spec.md), [정산 참여자 선택 및 관리 Spec](settlement-participants-spec.md)

## 1. Purpose

### Problem

정산 초안에는 총액과 참여자만 있을 뿐, 어떤 지출을 합산하는지와 참여자별 부담액을 어떻게 계산하는지 정하는 계약이 없다. 더치페이와 영수증 품목별 배분은 계산 규칙과 확정 시점이 달라 별도 모델이 필요하다.

### Why now

요청 링크가 송금 금액을 안내하려면 지출 총액과 참여자별 배분 결과가 먼저 준비되어야 한다. 특히 품목별 배분은 모든 참여자의 선택이 끝난 뒤에만 금액을 확정할 수 있다.

### Success criteria

- [ ] 생성자는 DRAFT 정산에 수기 지출을 추가·수정·삭제하고, 결제 내역·OCR 결과를 지출 후보로 불러올 수 있다.
- [ ] 더치페이는 총액을 참여자 수로 나누고 나머지를 생성자에게 더해 총액과 정확히 일치한다.
- [ ] 품목별 배분에서 참여자는 같은 품목을 동시에 선택할 수 있고, 각 선택은 1·1/2·1/3·1/4만 허용한다.
- [ ] 모든 참여자가 품목 선택을 제출했고 각 품목의 배분 합계와 총액이 정확하면, 요청·링크 기능이 불변 Share를 생성할 수 있는 계산 결과를 받는다.
- [ ] 모든 참여자의 제출 뒤에 합계가 맞지 않으면 Settlement는 SUSPENDED로 전환되고 생성자에게 수정·재요청이 요구된다.

### Out of scope

- 영수증 이미지 업로드, OCR 엔진 구현, 결제 내역 동기화의 실제 외부 연동
- Toss 앱 호출과 입금 완료 판정
- 품목별 배분에서 1/5 등 1·1/2·1/3·1/4 이외의 분할
- SUSPENDED 상태의 재오픈 UX와 알림 발송

### Current increment: 수동 정산 지출 CRUD

- Scope: DRAFT 정산 생성자의 수동 지출 등록·목록 조회·수정·삭제와 누적 지출액 조회
- Related issues: [#41 정산 지출 수정·삭제 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/41), [#42 수동 정산 지출 등록 UseCase 및 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/42)
- Persistence prerequisite: `V11__create_settlement_expense_schema.sql`이 `develop`에 병합되어 추가 Flyway 변경은 없다.
- Excluded from this increment: 결제 내역·OCR 가져오기, 배분 방식 선택과 계산, 품목 선택·제출, 요청 링크 연동
- Observable result: 생성자는 지출과 최신 `totalExpenseAmount`를 확인하며 DRAFT에서만 수동 지출을 변경할 수 있다.

### Current increment: 배분 유형 선택과 더치페이 초안

- Scope: DRAFT 정산의 더치페이 유형 선택, 기본 N빵 계산, 참여자별 금액 수정, 현재 합계 검증. ITEM_ALLOCATION enum과 다음 단계 계약은 예약하되 실제 OCR 품목 저장 전까지 선택을 거부한다.
- Related issues: [#45 정산 유형 선택 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/45), [#46 더치페이 참여자별 금액 수정 및 합계 검증 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/46), [#47 더치페이 기본 N빵 계산 정책 구현](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/47)
- Persistence prerequisite: `V12__create_settlement_dutch_pay_schema.sql`이 `develop`에 병합되어 추가 Flyway 변경은 없다.
- Excluded from this increment: OCR 품목 저장·선택, 요청 확정, Share 생성, 수령 계좌와 공통 링크
- Observable result: 생성자는 지출 합계와 기준 총액이 일치하는 정산의 더치페이를 선택하고, 기본 금액을 확인·수정하면서 현재 합계와 차액을 확인할 수 있다. 품목별 할당은 출처값만으로 성공시키지 않고 실제 OCR 품목 저장 기능이 완료될 때까지 명시적으로 거부한다.

### Current increment: 기존 결제내역 가져오기

- Scope: DRAFT 정산 생성자가 본인 연결 계좌의 월별 출금 거래를 후보로 조회하고 하나를 정산 지출로 가져오거나 다시 제거한다.
- Related issue: [#43 기존 결제내역 정산 지출 변환](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/43)
- Persistence prerequisite: [#109](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/109)로 병합된 `V13__add_settlement_expense_source_reference.sql`
- Excluded from this increment: 새로운 CODEF API 호출 구현, 입금 거래 가져오기, 여러 거래 일괄 가져오기, OCR 결과 변환
- Observable result: 생성자는 거래 월과 계좌를 기준으로 출금 후보와 이미 가져온 여부를 확인하고, 같은 거래를 한 정산에 중복 저장하지 않으면서 정산 지출 합계에 반영한다.

### Current increment: OCR 영수증 지출·품목 가져오기

- Scope: DRAFT 정산 생성자가 외부 OCR 결과 참조를 전달해 영수증 지출과 품목을 원자적으로 가져오고, 저장된 품목을 조회한 뒤 ITEM_ALLOCATION을 선택한다.
- Related issues: [#44 OCR 결과 정산 지출·품목 변환 Output Port 및 Stub 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/44), [#49 OCR 정산 품목 및 품목 선택 영속 모델 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/49)
- Persistence prerequisite: `V14__create_settlement_item_allocation_schema.sql`이 `develop`에 병합되어 추가 Flyway 변경은 없다.
- Excluded from this increment: 영수증 이미지 업로드·OCR 엔진, 운영 OCR Adapter, 참여자의 품목 선택·제출, 품목 수정, 요청 링크 연동
- Observable result: 생성자는 로컬·개발 OCR Stub 결과를 `RECEIPT_OCR` 지출과 순서가 보존된 품목으로 가져오고 목록을 조회하며, 품목 합계가 전체 지출 합계와 일치할 때 ITEM_ALLOCATION을 선택할 수 있다.

### Current increment: 공개 품목 선택·제출과 Share 확정

- Scope: 공통 링크에서 확정된 참여 문맥으로 자기 품목 비율을 조회·선택·취소하고 제출한다. 마지막 참여자 제출 시 전체 배분을 검증해 Share를 고정하거나 정산을 `SUSPENDED`로 전환한다.
- Related issues: [#48 품목별 할당 고정 비율 정책](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/48), [#49 품목 선택 영속 모델](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/49), [#50 할당 미달 시 정산 중단](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/50), [#52 참여자별 품목 선택·금액 계산](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/52)
- Persistence prerequisite: `V14__create_settlement_item_allocation_schema.sql`의 `settlement_item_selections`, `settlement_item_submissions`와 `V16__create_settlement_request_schema.sql`의 `settlement_shares`를 그대로 사용하며 추가 Flyway migration은 필요하지 않다.
- Excluded from this increment: 일반 반올림·잔여금 보정, 제출 취소, `SUSPENDED` 재개, Toss 송금 안내, CODEF 입금 확인
- Observable result: 각 참여자는 자신의 선택과 현재 예정 금액을 확인하고 제출한다. 전체 품목이 정확히 배분되면 `AWAITING_DEPOSIT`과 불변 Share가 생성되고, 미달이면 Share 없이 `SUSPENDED`가 된다.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: Settlement와 SettlementParticipant를 배분의 소유 경계로 사용한다.
- Reason a new domain is required, if applicable: 지출, 영수증 품목, 참여자별 품목 선택은 Settlement 하위 Entity로 새로 필요하다.
- Relationships to other domains: account의 기존 거래 조회 UseCase와 Port가 결제내역 후보를 제공하고, receipt OCR은 후속 외부 경계이며, request-link은 확정된 계산 결과를 Share로 고정한다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 지출 | 정산 총액을 이루는 한 건의 비용 | SettlementExpense |
| 외부 참조 | 같은 정산에 동일 거래를 중복으로 가져오지 않기 위한 원본 키 | SettlementExpense.sourceReference |
| 배분 방식 | 더치페이 또는 품목별 배분 | AllocationType |
| 영수증 품목 | OCR 결과에서 분리된 하나의 가격 항목 | SettlementItem |
| 품목 선택 | 참여자가 품목의 부담 비율을 고르는 행위 | ItemSelection |
| 선택 제출 | 더 바꿀 내용이 없음을 참여자가 표시하는 행위 | ItemAllocationSubmission |
| 배분 결과 | Share 고정 직전의 참여자별 계산 금액 | AllocationResult |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 생성자 | 수기 지출을 만들고 배분 방식을 선택한다. | ManageSettlementExpenseUseCase |
| 생성자 | 본인 계좌의 결제 내역 후보를 보고 정산 지출로 가져온다. | GetSettlementPaymentExpenseCandidatesUseCase, ImportSettlementPaymentExpenseUseCase |
| 생성자 | 더치페이 예상 금액을 확인한다. | CalculateDutchPayUseCase |
| 링크로 식별된 참여자 | 자기 몫의 품목과 비율을 선택·제출한다. | SaveItemSelectionUseCase, SubmitItemAllocationUseCase |
| 요청·링크 기능 | 검증이 끝난 배분 결과로 Share를 생성한다. | FinalizeAllocationUseCase |

### State and invariants

- State transitions: 지출과 배분 방식은 DRAFT에서만 생성자가 변경한다. 품목 선택은 요청 링크가 열린 REQUESTED 상태에서만 해당 참여자가 변경·제출한다. 모든 제출 후 검증 성공이면 AWAITING_DEPOSIT으로, 실패면 SUSPENDED로 전환한다.
- Rules that must always hold: 지출 금액은 양수다. `totalExpenseAmount`는 저장된 지출의 합계다. 요청 확정 시 `totalExpenseAmount`는 Settlement의 기준 `totalAmount`와 같아야 한다. 더치페이 배분액의 합은 총액과 같아야 한다. ItemSelection은 참여자와 품목의 조합마다 하나만 존재하며 허용 비율만 가진다. 한 품목의 최종 선택 비율 합은 정확히 1이어야 한다.
- Invalid states: DRAFT가 아닌 상태에서 지출 수정, 선택하지 않은 참여자의 품목 제출, 허용되지 않은 비율, 전체 제출 전 Share 생성, 하나라도 비율 합이 1이 아닌 상태에서 Share 생성.

## 3. Scenarios

### Normal flow: 더치페이

1. 생성자는 DRAFT 정산에 하나 이상의 수기 지출을 등록하고 배분 방식을 DUTCH_PAY로 선택한다.
2. Application Service는 지출 총액을 활성 참여자 수로 나눈다.
3. 나눈 몫의 정수 금액을 모든 참여자에게 배분하고, 나머지 금액은 생성자 참여자에게 더한다.
4. 계산 결과의 합이 총액과 일치하는지 검증한다.
5. 요청·링크 기능이 결과를 불변 Share로 저장하고 공통 링크를 발급한다.

### Normal flow: 품목별 배분

1. 생성자는 OCR 결과의 품목을 확인하고 배분 방식을 ITEM_ALLOCATION으로 선택한 뒤 공통 링크를 요청한다.
2. 링크로 식별된 각 참여자는 자기 품목과 1·1/2·1/3·1/4 중 하나의 비율을 저장하고 제출한다. 같은 품목은 여러 참여자가 각각 선택할 수 있다.
3. 마지막 참여자 제출 시 Application Service가 모든 품목의 선택 비율 합과 참여자별 금액 합을 계산한다.
4. 모든 품목의 비율 합이 1이고 참여자별 금액 합이 지출 총액과 일치하면 AllocationResult를 request-link 기능에 전달한다.
5. request-link 기능이 Share를 고정하고 Settlement를 AWAITING_DEPOSIT으로 바꾼다.

### Failure flow

- 지출이 없거나 참여자가 2명 미만이다: 요청 확정을 거부한다.
- OCR 결과에 품목이 없는데 ITEM_ALLOCATION을 선택한다: 품목별 배분 요청을 거부한다.
- 모든 참여자가 제출했지만 어떤 품목의 비율 합이 1이 아니다: Settlement를 SUSPENDED로 전환하고 미배분·과배분 품목을 생성자에게 반환한다.
- 제출 전 또는 SUSPENDED 이후 참여자가 선택을 변경한다: 선택 변경을 거부한다.
- OCR·결제 내역 Port가 개발 Stub에서 결과를 주지 못한다: 외부 후보 없음으로 처리하며 수기 지출 흐름은 계속 제공한다.
- 다른 사용자의 계좌·거래를 조회하거나 가져온다: 계좌 또는 거래를 찾을 수 없는 것으로 처리한다.
- 입금 거래, 소수 원 금액, 이미 가져온 거래를 선택한다: 정산 지출로 저장하지 않고 명시적인 오류를 반환한다.

### Boundary conditions

- 1원 단위 금액도 더치페이 계산에 포함한다. 잔여금은 생성자에게 배분한다.
- 지출 누적액이 Java `long` 원 단위 범위를 초과하는 등록·수정·조회는 `INVALID_SETTLEMENT_EXPENSE_TOTAL`로 거부하며 변경을 저장하지 않는다.
- 결제내역 금액은 양의 정수 원 단위만 정산 지출로 변환하며 `BigDecimal.longValueExact()`가 실패하면 가져오기를 거부한다.
- 같은 원본 거래는 한 정산에 한 번만 가져올 수 있고, 서로 다른 정산에서는 각각 가져올 수 있다.
- 가져온 결제내역은 원본 의미를 보존하기 위해 설명·금액 PATCH는 막되 DELETE로 선택을 취소할 수 있다.
- 한 품목을 선택하지 않은 참여자는 제출할 수 있다. 전체 품목을 반드시 모든 사람이 선택할 필요는 없고, 품목별 비율 합이 1이면 된다.
- 한 참여자는 동일 품목을 하나의 비율로만 선택한다. 기존 선택을 바꾸면 행을 갱신한다.
- 품목별 금액 계산 중 원 단위 반올림이 필요한 경우, 합계 보정 규칙은 PRD 확정 전 별도 결정으로 남긴다. MVP에서는 입력 품목 가격과 허용 분수의 조합으로 정수 합계가 보장되는 데이터만 요청 확정한다.

## 4. Contracts

### Input Port

~~~
CreateSettlementExpenseUseCase.create(settlementId, CreateSettlementExpenseCommand)
  -> SettlementExpenseMutationResult
GetSettlementExpensesUseCase.get(settlementId)
  -> SettlementExpenseListResult
UpdateSettlementExpenseUseCase.update(settlementId, expenseId, UpdateSettlementExpenseCommand)
  -> SettlementExpenseMutationResult
RemoveSettlementExpenseUseCase.remove(settlementId, expenseId)
  -> SettlementExpenseTotalResult

GetSettlementPaymentExpenseCandidatesUseCase.get(settlementId, accountId, yearMonth)
  -> List<SettlementPaymentExpenseCandidateResult>
ImportSettlementPaymentExpenseUseCase.importExpense(
  settlementId, ImportSettlementPaymentExpenseCommand
) -> SettlementExpenseMutationResult

ImportSettlementReceiptExpenseUseCase.importExpense(
  settlementId, ImportSettlementReceiptExpenseCommand
) -> SettlementReceiptExpenseMutationResult
GetSettlementItemsUseCase.get(settlementId) -> SettlementItemListResult

SelectAllocationTypeUseCase.select(settlementId, allocationType) -> SettlementResponse
CalculateDutchPayUseCase.calculate(settlementId) -> AllocationResult

SaveItemSelectionUseCase.save(
  settlementId, participantId, itemId, AllocationFraction
) -> ItemSelectionResponse
RemoveItemSelectionUseCase.remove(
  settlementId, participantId, itemId
) -> ItemAllocationStatusResponse
SubmitItemAllocationUseCase.submit(settlementId, participantId) -> ItemAllocationStatusResponse
FinalizeAllocationUseCase.finalizeWhenComplete(settlementId) -> AllocationResult
~~~

AllocationFraction은 ONE, HALF, THIRD, QUARTER만 허용한다. 품목 선택 UseCase의 participantId는 request-link이 해석한 공통 링크 참여자 문맥에서만 전달받는다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
| --- | --- | --- | --- |
| LoadSettlementExpensePort | 정산의 지출 목록·개별 지출·누적액·다음 표시 순서를 조회한다. | 수동 지출 관리 UseCase | JPA Persistence Adapter |
| SaveSettlementExpensePort | 지출을 생성하거나 변경한다. | 수동 지출 생성·수정 UseCase | JPA Persistence Adapter |
| DeleteSettlementExpensePort | 저장된 지출을 삭제한다. | 수동 지출 삭제 UseCase | JPA Persistence Adapter |
| LoadSettlementParticipantsPort | 활성 참여자와 생성자 참여자를 조회한다. | 계산·제출 검증 UseCase | JPA Persistence Adapter |
| GetAccountTransactionsUseCase | 소유권 검증과 필요 시 기존 CODEF 동기화를 거친 월별 계좌 거래를 제공한다. | GetSettlementPaymentExpenseCandidatesUseCase | 기존 AccountQueryService |
| LoadAccountPort | 선택 거래를 가져올 때 계좌가 현재 사용자 소유인지 확인한다. | ImportSettlementPaymentExpenseUseCase | 기존 AccountPersistenceAdapter |
| LoadAccountTransactionPort | 선택한 계좌의 거래 한 건을 조회한다. | ImportSettlementPaymentExpenseUseCase | 기존 AccountTransactionPersistenceAdapter |
| ExtractReceiptItemsPort | 요청자 ID와 외부 OCR 결과 참조로 소유권이 확인된 영수증 총액·품목을 제공한다. | ImportSettlementReceiptExpenseUseCase | 운영 receipt OCR Adapter 또는 로컬·개발 Stub |
| LoadSettlementItemPort | 정산에 저장된 OCR 품목 목록과 합계를 조회한다. | GetSettlementItemsUseCase, SelectAllocationTypeUseCase | JPA Persistence Adapter |
| SaveSettlementItemPort | OCR 품목을 지출과 같은 트랜잭션에 저장한다. | ImportSettlementReceiptExpenseUseCase | JPA Persistence Adapter |
| DeliverAllocationResultPort | 검증된 배분 결과를 Share 고정 흐름에 전달한다. | FinalizeAllocationUseCase | request-link Application Adapter |

### Web API

~~~
GET /api/v2/settlements/{settlementId}/expenses
POST /api/v2/settlements/{settlementId}/expenses
PATCH /api/v2/settlements/{settlementId}/expenses/{expenseId}
DELETE /api/v2/settlements/{settlementId}/expenses/{expenseId}
Authentication: required, creator only

POST Request: { description: string, amount: positive integer }
PATCH Request: { description?: string, amount?: positive integer }
GET Response: { expenses: SettlementExpense[], totalExpenseAmount: integer }
POST/PATCH Response: { expense: SettlementExpense, totalExpenseAmount: integer }
DELETE Response: { totalExpenseAmount: integer }

GET /api/v2/settlements/{settlementId}/expense-candidates/payment-history?accountId={accountId}&yearMonth=YYYY-MM
Response: [{ transactionId, description, amount, transactedAt, imported }]

POST /api/v2/settlements/{settlementId}/expenses/payment-history
Request: { accountId, transactionId }
Response: { expense, totalExpenseAmount }
Authentication: required, creator only

POST /api/v2/settlements/{settlementId}/expenses/receipt-ocr
Request: { receiptReference }
Response: { expense, items, totalExpenseAmount }
Authentication: required, creator only

GET /api/v2/settlements/{settlementId}/items
Response: { items: [{ itemId, expenseId, name, quantity, amount, displayOrder }], totalItemAmount }
Authentication: required, creator only

PUT /api/v2/settlements/{settlementId}/allocation-type
Request: { allocationType: DUTCH_PAY|ITEM_ALLOCATION }
Authentication: required, creator only
ITEM_ALLOCATION: 저장된 품목이 있고 품목 합계와 전체 지출 합계가 일치할 때 선택 가능

GET /api/v2/settlements/{settlementId}/dutch-pay-allocations
Authentication: required, creator only

PATCH /api/v2/settlements/{settlementId}/dutch-pay-allocations/{participantId}
Request: { amount: nonnegative integer }
Authentication: required, creator only

Allocation type response: { settlementId, allocationType, nextStep }
Dutch pay response: {
  settlementId,
  totalAmount,
  allocatedAmount,
  differenceAmount,
  balanced,
  participants: [{ participantId, displayName, amount, creator }]
}

GET /api/v2/settlement-links/{token}/participants/{participantId}/items
Header: Link-Participant-Context

PUT /api/v2/settlement-links/{token}/participants/{participantId}/items/{itemId}
Request: { fraction: ONE|HALF|THIRD|QUARTER }
Header: Link-Participant-Context

DELETE /api/v2/settlement-links/{token}/participants/{participantId}/items/{itemId}
Header: Link-Participant-Context

POST /api/v2/settlement-links/{token}/participants/{participantId}/item-submission
Header: Link-Participant-Context
Authentication: public link and resolved participant context required
~~~

공개 품목 선택 API의 토큰 해석·참여자 자동 매칭·브라우저 이름 선택은 request-link Spec이 소유한다. 이 기능은 그 결과로 확인된 participantId만 받는다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 병합된 V14의 `settlement_item_selections`·`settlement_item_submissions`를 JPA에 매핑하고 V16 `settlement_shares`에 검증된 참여자별 금액을 저장한다. 추가 마이그레이션은 없다.
- Redis key, TTL, or serialization changes: 기존 `settlement:link-participant-context:{contextId}`를 조회하는 기능을 추가하고 키·직렬화·TTL 형식은 바꾸지 않는다.
- External API or message changes: `ExtractReceiptItemsPort` 계약만 추가하고 실제 OCR 엔진·이미지 처리는 구현하지 않는다. `receiptReference`는 외부 OCR 기능이 발급한 불투명 참조로 취급한다.
- Environment variable or profile changes: local·dev는 결정적인 OCR Stub을 사용하고, prod는 실제 Adapter가 연결되기 전 503 오류를 반환한다.

## 5. Design decisions

### Business logic

- Rules and their locations: 허용 비율·같은 참여자의 중복 선택 방지·품목 합계 검증은 Domain에 둔다. DRAFT 수정 권한과 마지막 제출 뒤 상태 전환은 Application Service가 Settlement Domain 규칙과 함께 조정한다.
- Behavior owned by Domain: 지출 양수 검증, 더치페이 나머지 생성자 배분, 품목별 비율 합계 검증, AllocationResult 합계 검증.
- Flow orchestrated by Application: 정산·계좌 소유권 확인, 출금 거래 필터링, 정수 원 변환, 중복 확인, 배분 초안 무효화, 외부 후보 변환, 링크 참여자 문맥 확인, 전체 제출 여부 확인, SUSPENDED 전환과 request-link 전달.

### Technology and patterns

- Constraint to solve: #43 작성 뒤 account 거래 조회가 develop에 먼저 병합되어 별도 PaymentTransactionLookupPort·Stub을 만들면 같은 외부 경계를 중복 표현한다.
- Selected approach: 후보 목록은 기존 GetAccountTransactionsUseCase를, 선택 거래와 계좌 소유권은 기존 LoadAccountTransactionPort·LoadAccountPort를 재사용하고 Settlement Application에서 변환 규칙만 소유한다.
- Reason for selection: 외부 기능이 개발 Stub이어도 배분 핵심 규칙과 API 계약을 먼저 검증할 수 있다.
- Alternatives considered: OCR·결제 내역 기능이 완성될 때까지 지출 등록 전체를 보류하는 방식은 최소 정산 흐름을 막으므로 제외한다.
- Trade-offs and limits: MVP의 제한된 분수에서 발생하는 원 단위 나눗셈 문제는 요청 확정 시 검증 오류로 노출한다. 일반적인 반올림·잔여금 배분 정책은 후속 결정이 필요하다.
- Exact-won rule: 품목 금액을 선택 비율의 분모로 나눈 결과가 정수 원이 아니면 해당 선택을 저장하지 않는다. 서버가 임의 반올림하지 않아 표시 금액과 최종 Share가 달라지지 않게 한다.

### OCR 가져오기 트랜잭션 경계

- OCR 호출 전에 현재 사용자가 생성자인지, 정산이 DRAFT인지, 같은 참조를 이미 가져왔는지 읽기 검증한다.
- 요청자 ID를 OCR Adapter에 전달해 실제 연동이 결과 소유권을 검증할 수 있게 한다.
- `ExtractReceiptItemsPort` 호출은 DB 쓰기 트랜잭션 밖에서 수행한다.
- 결과를 받은 뒤 별도 쓰기 컴포넌트가 정산 행을 잠그고 권한·상태·중복을 다시 검증한다.
- 영수증 지출과 모든 품목은 하나의 트랜잭션으로 저장하며 하나라도 유효하지 않으면 전부 롤백한다.
- OCR 지출 금액은 품목 금액 합과 정확히 같아야 하고, 같은 `receiptReference`는 한 정산에 한 번만 가져온다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | 지출 관리·후보 조회·배분 방식·공개 품목 선택 API | SettlementExpenseController, request-link Controller |
| Application | 지출·배분 UseCase, OCR·결제내역·결과 전달 Port | application.settlement |
| Domain | Expense, Item, ItemSelection, AllocationResult 규칙 | domain.settlement |
| Persistence | 지출·품목·선택·제출 테이블과 제약 | adapter.out.persistence.settlement |
| Redis | 공통 링크에서 발급된 참여 문맥 조회 | SettlementParticipantContextRedisAdapter |
| Security | 생성자 수정 권한과 링크 참여자 문맥 확인 | Security Adapter, request-link |
| Configuration | OCR·결제내역 개발 Stub | dev profile |
| Documentation | 공통 링크와 Share 고정 순서 | PRD, request-link Spec |

Existing behavior that must remain unchanged:

- 기존 user, auth, group 기능의 저장 모델을 지출 모델로 변경하지 않는다.
- 실제 OCR·결제 내역·CODEF 호출을 이 기능에서 추가하지 않는다.
- Share는 이 기능의 중간 계산 결과가 아니라 request-link이 최종 확정 시 생성한다.

## 7. Implementation plan

### Current increment

- [x] SettlementExpense Domain과 MANUAL 원본·설명·금액·표시 순서 불변식을 구현한다.
- [x] Load·Save·Delete 지출 Port와 JPA Entity·Repository·Mapper·Persistence Adapter를 구현한다.
- [x] 생성자 소유권과 DRAFT 상태를 검증하는 생성·조회·수정·삭제 UseCase와 Service를 구현한다.
- [x] 지출 목록과 각 변경 뒤 최신 `totalExpenseAmount`를 반환하는 Web API·Swagger 계약을 구현한다.
- [x] 정산 행 잠금으로 지출 변경과 상태 확정이 겹치지 않게 하고 동시 표시 순서 충돌을 방지한다.
- [x] Domain·Application·Web·Persistence 테스트와 PostgreSQL 기반 실제 HTTP 검증을 수행한다.

### Current allocation increment

- [x] Settlement에 AllocationType 선택·초기화 규칙을 추가한다.
- [x] 기본 N빵과 생성자 잔여금 배분을 Domain Policy로 구현한다.
- [x] 더치페이 초안 Entity·Port·JPA Adapter를 V12 스키마에 연결한다.
- [x] 배분 유형 선택·조회·참여자 금액 수정 UseCase와 API를 구현한다.
- [x] 기준 총액·지출 합계·참여자 구성 변경 시 기존 배분 초안을 무효화한다.
- [x] Domain·Application·Web·Persistence 테스트와 PostgreSQL 기반 실제 HTTP·Swagger 검증을 수행한다.

### Current payment import increment

- [x] SettlementExpense에 `sourceReference`와 PAYMENT_HISTORY 생성 규칙을 추가하고 V13 JPA 매핑을 연결한다.
- [x] 본인 계좌의 월별 출금 후보와 이미 가져온 여부를 반환하는 UseCase를 구현한다.
- [x] 선택 거래의 계좌 소유권·출금 유형·정수 원 금액·중복을 검증하고 지출로 저장한다.
- [x] 가져온 지출은 PATCH를 막고 기존 DELETE로 제거할 수 있게 한다.
- [x] 후보 조회·가져오기 API와 Swagger 계약을 추가한다.
- [x] Domain·Application·Web·Persistence 테스트와 PostgreSQL 기반 실제 HTTP·Swagger 검증을 수행한다.

### Current receipt import increment

- [x] SettlementItem Domain과 OCR 지출 생성 규칙을 구현한다.
- [x] 품목 Load·Save Port와 V14 JPA Entity·Repository·Mapper·Persistence Adapter를 구현한다.
- [x] OCR 호출과 쓰기 트랜잭션을 분리한 가져오기 UseCase를 구현한다.
- [x] 로컬·개발 OCR Stub과 운영 미연결 Adapter를 구현한다.
- [x] 영수증 가져오기·품목 목록 API와 Swagger 계약을 구현한다.
- [x] 품목 존재와 전체 지출 합계 일치를 검증해 ITEM_ALLOCATION 선택을 활성화한다.
- [x] Domain·Application·Web·Persistence 테스트와 PostgreSQL 기반 실제 HTTP·Swagger 검증을 수행한다.

### Current public item allocation increment

- [x] AllocationFraction·ItemSelection·ItemAllocationSubmission Domain과 정수 원·비율 합계 규칙을 구현한다.
- [x] V14 선택·제출 Entity·Repository·Mapper·Store Adapter를 구현한다.
- [x] Redis 참여 문맥을 조회하고 링크 hash·정산·참여자를 교차 검증한다.
- [x] 참여자의 품목 상태 조회·선택·취소 UseCase를 구현한다.
- [x] 제출을 저장하고 마지막 제출에서 Share 확정 또는 `SUSPENDED` 전환을 하나의 트랜잭션으로 처리한다.
- [x] 공개 품목 배분 API·Swagger·Security 계약을 구현한다.
- [x] Domain·Application·Web·Persistence·Redis 테스트와 실제 PostgreSQL·Redis HTTP 흐름을 검증한다.

### Follow-up increments

- [ ] 지출·배분 방식·품목·선택·제출 Domain 모델과 불변식을 정의한다.
- [ ] 지출 저장, 참여자 조회, OCR·결제 내역 후보, 배분 결과 전달 Output Port를 정의한다.
- [ ] 수기 지출 관리, 후보 가져오기, 더치페이 계산, 품목 선택·제출·최종 검증 UseCase를 구현한다.
- [ ] JPA Entity·Repository·Mapper·Persistence Adapter와 OCR·결제내역 개발 Stub을 구현한다.
- [ ] 생성자용 Web API와 request-link 참여자 문맥을 받는 공개 품목 선택 API를 구현한다.
- [ ] 요청 링크 확정과 SUSPENDED 재요청 정책을 통합 검증한다.

### Expected commit boundaries

~~~
docs(settlement): 더치페이 구현 범위 구체화
feat(settlement): 정산 배분 유형과 더치페이 규칙 추가
feat(settlement): 더치페이 초안 영속 기능 추가
feat(settlement): 더치페이 배분 관리 기능 추가
feat(settlement): 더치페이 배분 API 추가
refactor(settlement): 정산 입력 변경 시 배분 초안 무효화
test(settlement): 더치페이 배분 기능 검증 추가
docs(settlement): 더치페이 구현 결과 반영
fix(settlement): 배분 무효화 잠금 순서 통일
fix(settlement): 미구현 품목별 할당 선택 차단
docs(settlement): 예정 커밋 제목 규칙 정렬
docs: 결제내역 가져오기 구현 범위 구체화
feat: 결제내역 정산 지출 모델과 영속 매핑 추가
feat: 결제내역 후보 조회와 가져오기 기능 추가
feat: 결제내역 정산 지출 API 추가
test: 결제내역 가져오기 기능 검증 추가
fix: 결제내역 API 검증 계약 충돌 수정
docs: 결제내역 가져오기 구현 결과 반영
docs: OCR 영수증 가져오기 구현 범위 구체화
feat: OCR 영수증 지출과 품목 도메인 추가
feat: 정산 품목 영속 기능 추가
feat: OCR 영수증 가져오기 기능 추가
feat: OCR 영수증 가져오기와 품목 조회 API 추가
feat: 품목별 할당 유형 선택 활성화
test: OCR 영수증 가져오기 기능 검증 추가
docs: OCR 영수증 가져오기 구현 결과 반영
docs: 공개 품목 선택 구현 범위 구체화
feat: 품목 선택 비율과 제출 도메인 추가
feat: 품목 선택과 제출 영속 기능 추가
feat: 정산 링크 참여 문맥 조회 기능 추가
feat: 공개 품목 선택과 취소 기능 추가
feat: 품목 선택 제출과 Share 확정 기능 추가
feat: 공개 품목 배분 API 추가
test: 공개 품목 배분 시나리오 검증
docs: 공개 품목 배분 구현 결과 반영
~~~

## 8. Verification

### Commands

~~~
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
~~~

수동 검증 전제조건: PostgreSQL·Redis, 최소 2명의 참여자, 수기 지출 또는 OCR 개발 Stub 품목, 공통 링크 참여자 문맥이 필요하다.

### Completion criteria

- [x] DRAFT 생성자만 본인 계좌의 출금 거래 후보를 조회하고 가져온다.
- [x] 이미 가져온 거래가 후보 응답에 표시되고 같은 정산으로 중복 가져오기는 거부된다.
- [x] 입금·소수 원·다른 사용자 계좌 거래는 정산 지출로 저장되지 않는다.
- [x] 가져온 결제내역을 삭제하면 합계에서 빠지고 같은 거래를 다시 가져올 수 있다.
- [x] DRAFT 생성자는 OCR 결과를 지출·품목으로 원자 저장하고 같은 참조의 중복 가져오기를 거부한다.
- [x] 저장된 품목은 부모 지출·품목 표시 순서를 보존하며 지출 삭제 시 함께 삭제된다.
- [x] 저장 품목 합계와 전체 지출 합계가 일치할 때 ITEM_ALLOCATION을 선택할 수 있다.
- [x] DRAFT 생성자만 수기 지출과 배분 방식을 변경한다.
- [x] 더치페이 계산 결과의 합은 항상 지출 총액과 일치하고 나머지는 생성자 몫이다.
- [x] 같은 품목을 여러 참여자가 허용 비율로 선택할 수 있고, 같은 참여자의 중복 행은 생기지 않는다.
- [x] 전체 제출 후 품목별 비율과 총액이 일치하면 Share 고정에 필요한 결과가 생성된다.
- [x] 전체 제출 뒤 불일치하면 정산은 SUSPENDED이고 Share는 생성되지 않는다.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| 문서 작성 | Complete | 이 Spec |
| 구현·컴파일 | Complete | `./gradlew compileJava` 성공 |
| 자동 테스트 | Complete | 격리 PostgreSQL 스키마와 더미 Discord URL을 사용한 `./gradlew test` 159개 성공 |
| PostgreSQL 영속 검증 | Complete | V12 기반 더치페이 초안 교체·조회·수정·참여자 삭제 Cascade 통합 테스트 성공 |
| 실제 HTTP | Complete | JWT 로그인 뒤 2명 500원 기본 배분, 400원 수정 시 차액 100원, 생성자 600원 수정 시 재균형, 지출 변경 뒤 배분 무효화 409 검증 성공 |
| Swagger | Complete | `/v3/api-docs`의 배분 유형·더치페이 조회·수정 경로와 `/swagger-ui.html` 200 응답 확인 |
| 결제내역 가져오기 자동 테스트 | Complete | Domain·Application·Web 계약과 V13 중복 제약 테스트 및 실제 PostgreSQL·Redis 연결 전체 `./gradlew clean test` 181개 성공 |
| 결제내역 가져오기 PostgreSQL 영속 검증 | Complete | `PAYMENT_HISTORY`, 64자 `source_reference`, 금액·표시 순서 저장과 같은 정산 중복 409 변환 확인 |
| 결제내역 가져오기 실제 HTTP | Complete | 로그인 뒤 출금 후보 조회, 가져오기, `imported=true`, 중복 409, PATCH 409, DELETE, 재가져오기 흐름 확인 |
| 결제내역 가져오기 Swagger | Complete | `/v3/api-docs`의 후보 조회·가져오기 계약과 `/swagger-ui.html` 리다이렉트 응답 확인 |
| OCR 영수증 가져오기 자동 테스트 | Complete | Domain·Application·Web·Stub·V14 영속·배분 유형 검증을 포함한 실제 PostgreSQL·Redis 연결 전체 테스트 205개 성공 |
| OCR 영수증 가져오기 PostgreSQL 영속 검증 | Complete | 지출·품목 저장, 부모 지출·품목 순서 조회, 중복 참조 409 변환, 지출 삭제 Cascade 확인 |
| OCR 영수증 가져오기 실제 HTTP | Complete | DRAFT 생성, Stub 영수증 30,000원 가져오기, 품목 2개·합계 조회, ITEM_ALLOCATION 선택, 중복 409 확인 |
| OCR 영수증 가져오기 Swagger | Complete | `/v3/api-docs`의 영수증 가져오기·품목 조회 경로와 `/swagger-ui.html` 200 응답 확인 |
| 공개 품목 배분 자동 테스트 | Complete | Domain·Policy·Application·Web·Redis와 V14 영속 통합을 포함한 실제 PostgreSQL·Redis 연결 전체 테스트 성공 |
| 공개 품목 배분 실제 HTTP | Complete | 무인증 링크 진입·본인 선택·문맥 발급, 1/2 선택 500원, 취소 0원, 전체 선택 1,000원, 제출 후 `WAITING_FOR_OTHERS`, 제출 뒤 수정 409 확인 |
| 공개 품목 배분 Swagger | Complete | `/swagger-ui.html` 리다이렉트와 `/v3/api-docs`의 조회·선택·취소·제출 네 경로 및 `Link-Participant-Context` 헤더 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-21 | 두 배분 방식과 전체 제출 검증 설계 기록 | 요청 링크에서 Share·송금 정보 고정 | 분수·원 단위 반올림 정책 미정 |
| 2026-08-23 | 첫 구현 증분을 수동 지출 CRUD로 제한하고 V11 병합을 확인 | 지출 Domain·Application·Web 구현 | 기준 총액과 누적 지출액의 불일치 처리 시점 필요 |
| 2026-08-23 | 수동 지출 CRUD와 테스트·실제 HTTP·Swagger 검증 완료 | 배분 방식과 더치페이 계산 | 후속 요청 확정 흐름도 정산 행 잠금을 사용해야 함 |
| 2026-08-23 | 리뷰에서 발견된 누적액 `long` 초과를 저장 전 400으로 변환하고 실제 HTTP 롤백 상태 검증 | 배분 방식과 더치페이 계산 | 금액 타입을 확장하면 API·DB 계약을 함께 변경해야 함 |
| 2026-08-23 | V12 병합 뒤 배분 유형·더치페이 초안 구현 범위와 API 계약 확정 | Domain·영속·Application·Web 구현 | 입력 변경 뒤 기존 계산이 남지 않도록 무효화가 필요함 |
| 2026-08-23 | 배분 유형 선택·기본 N빵·금액 수정·합계 응답과 입력 변경 무효화를 구현하고 159개 테스트·실제 HTTP·Swagger 검증 완료 | 외부 지출 후보 또는 품목별 할당 구현 | ITEM_ALLOCATION은 실제 OCR 품목을 검증할 저장 기능이 완료될 때까지 선택을 거부함 |
| 2026-08-23 | V13 병합과 account 거래 조회 구현을 확인하고 결제내역 가져오기 계약 확정 | Domain·Application·Web 구현 | 예전 #43의 Stub 설계 대신 중복 Port 없이 현재 account 경계를 재사용해야 함 |
| 2026-08-23 | 결제내역 후보 조회·가져오기·삭제와 중복 방지를 구현하고 자동 테스트·실제 HTTP·Swagger 검증 완료 | OCR 영수증 지출 가져오기 | 실제 HTTP에서 발견한 Validator 인터페이스 계약 충돌을 제약 상속 방식으로 수정함 |
| 2026-08-23 | V14 품목 매핑, OCR Stub·가져오기·조회, ITEM_ALLOCATION 선택을 구현하고 205개 테스트·실제 HTTP·Swagger 검증 완료 | 공용 링크 참여자 문맥에서 품목 선택·제출 | 운영 OCR Adapter와 링크 참여자 식별이 아직 필요함 |
| 2026-08-24 | 공개 링크 참여 문맥과 V14 선택·제출 스키마를 확인하고 한 PR 구현 범위 확정 | Domain·Persistence·Application·Web 구현 | 반올림 정책이 없으므로 정수 원이 되지 않는 비율 선택은 저장 전 거부해야 함 |
| 2026-08-24 | 공개 링크 참여자의 품목 조회·비율 선택·취소·제출과 마지막 제출의 Share 확정·중단 전환을 구현하고 실제 PostgreSQL·Redis HTTP·Swagger 검증 완료 | 송금 안내 또는 `SUSPENDED` 재개 정책 | 1/3처럼 정수 원이 되지 않는 선택은 반올림 정책 확정 전까지 거부함 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
| --- | --- | --- | --- |
| 2026-08-21 | 더치페이 잔여금은 생성자에게 배분한다. | 정산 금액 합계를 원 단위로 맞춘다. | 균등 분산 정책은 제공하지 않는다. |
| 2026-08-21 | 하나의 품목을 여러 참여자가 선택할 수 있다. | 공동 구매한 품목을 허용 비율로 나누기 위해서다. | 단일 소유자 방식은 제공하지 않는다. |
| 2026-08-21 | 품목별 Share는 모든 제출과 합계 검증 뒤에만 고정한다. | 미완료 상태에서 송금액이 바뀌지 않도록 한다. | 중간 금액으로 송금하는 기능은 제공하지 않는다. |
| 2026-08-23 | 지출 CRUD는 Settlement `totalAmount`를 자동 변경하지 않고 `totalExpenseAmount`를 별도로 반환한다. | 부분 입력 중 기준 총액이 의도치 않게 바뀌지 않게 한다. | 요청 확정 시 두 합계의 일치를 검증한다. |
| 2026-08-23 | 지출 변경은 정산 행을 쓰기 잠금한 뒤 수행한다. | DRAFT 확인과 표시 순서 계산을 상태 확정·동시 등록과 직렬화한다. | 후속 요청 확정 흐름도 같은 잠금 계약을 사용해야 한다. |
| 2026-08-23 | 배분 시작 전에 기준 총액과 지출 합계 일치를 검증한다. | 서로 다른 금액을 기준으로 기본 배분과 최종 검증이 갈라지는 것을 막는다. | 지출 입력 중에는 배분 유형을 선택할 수 없다. |
| 2026-08-23 | 참여자·지출·기준 총액이 바뀌면 배분 유형과 더치페이 초안을 무효화한다. | 이전 입력으로 계산한 금액을 화면이나 요청 확정에서 재사용하지 않게 한다. | 생성자는 변경 뒤 배분 유형을 다시 선택해야 한다. |
| 2026-08-23 | 개별 더치페이 금액 수정 중 불균형을 저장하고 차액을 응답한다. | 여러 필드를 순서대로 편집할 수 있게 하되 서버가 현재 불균형을 숨기지 않는다. | 요청 확정 단계는 `balanced=true`만 허용한다. |
| 2026-08-23 | 결제내역 가져오기는 병합된 account UseCase·Port를 재사용한다. | 현재 코드에 실거래 조회와 동기화 경계가 있어 별도 Stub Port가 중복되기 때문이다. | #43의 초기 제안과 달리 새 외부 Adapter는 만들지 않는다. |
| 2026-08-23 | 가져온 결제내역은 수정하지 못하지만 삭제할 수 있다. | 원본 금액을 임의 변경하지 않으면서 잘못 선택한 거래는 취소할 수 있어야 한다. | 다시 가져오기는 삭제 트랜잭션 완료 후 허용한다. |
| 2026-08-23 | OCR 호출은 쓰기 트랜잭션 밖에서 수행하고 저장 시 정산 행을 다시 잠근다. | 느린 외부 호출이 DB 연결·잠금을 점유하지 않으면서 상태 변경과 중복 저장을 직렬화한다. | 외부 호출 전 읽기 검증과 잠금 뒤 쓰기 검증을 모두 수행한다. |
| 2026-08-23 | OCR Adapter에 요청자 ID와 결과 참조를 함께 전달한다. | 운영 연동이 결과 소유권을 검증하고 다른 사용자의 결과 참조 사용을 막을 수 있어야 한다. | 로컬·개발 Stub은 요청자 존재만 확인하고 고정 결과를 제공한다. |
| 2026-08-23 | 품목별 할당 선택 시 전체 지출 합계와 저장 품목 합계를 비교한다. | 수기·결제내역처럼 품목이 없는 지출까지 포함된 금액을 품목 선택으로 누락하지 않게 한다. | 합계가 다르면 생성자가 지출 구성을 수정해야 한다. |
| 2026-08-24 | 품목 금액이 선택 비율로 정확히 나누어지지 않으면 선택을 거부한다. | 합의된 반올림·잔여금 배분 정책이 없어 서버가 표시 금액을 임의로 변경하지 않아야 한다. | 예: 1,000원 품목은 `THIRD`로 선택할 수 없고 후속 정책 확정 시 확장한다. |
| 2026-08-24 | 같은 품목의 선택 비율 합은 저장 시 1을 초과할 수 없고, 모든 제출 후 1에 미달하면 정산을 중단한다. | 과배분을 즉시 막고 미배분은 전체 제출 전까지 허용해 참여자가 독립적으로 선택할 수 있게 한다. | 성공 시만 Share를 생성하고 실패 시 `SUSPENDED`로 전환한다. |

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: 전체 구현 전
- Remaining risks: 운영 OCR 공급자 계약, 품목 분수의 원 단위 반올림 정책, `SUSPENDED` 재개 UX가 확정돼야 전체 운영 흐름을 완료할 수 있다.
- Follow-up work: 확정 Share의 송금 안내와 `SUSPENDED` 수정·재요청 정책을 구현한다.
