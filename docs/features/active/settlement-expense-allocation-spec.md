# Feature Plan: 정산 지출 및 금액 배분

- Status: Draft
- Last updated: 2026-08-23
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

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: Settlement와 SettlementParticipant를 배분의 소유 경계로 사용한다.
- Reason a new domain is required, if applicable: 지출, 영수증 품목, 참여자별 품목 선택은 Settlement 하위 Entity로 새로 필요하다.
- Relationships to other domains: payment-history와 receipt OCR은 후보 데이터를 제공하는 Output Port이고, request-link은 확정된 계산 결과를 Share로 고정한다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 지출 | 정산 총액을 이루는 한 건의 비용 | SettlementExpense |
| 배분 방식 | 더치페이 또는 품목별 배분 | AllocationType |
| 영수증 품목 | OCR 결과에서 분리된 하나의 가격 항목 | SettlementItem |
| 품목 선택 | 참여자가 품목의 부담 비율을 고르는 행위 | ItemSelection |
| 선택 제출 | 더 바꿀 내용이 없음을 참여자가 표시하는 행위 | ItemAllocationSubmission |
| 배분 결과 | Share 고정 직전의 참여자별 계산 금액 | AllocationResult |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 생성자 | 수기 지출을 만들고 배분 방식을 선택한다. | ManageSettlementExpenseUseCase |
| 생성자 | 결제 내역 또는 OCR 결과에서 지출을 가져온다. | ImportSettlementExpenseUseCase |
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

### Boundary conditions

- 1원 단위 금액도 더치페이 계산에 포함한다. 잔여금은 생성자에게 배분한다.
- 지출 누적액이 Java `long` 원 단위 범위를 초과하는 등록·수정·조회는 `INVALID_SETTLEMENT_EXPENSE_TOTAL`로 거부하며 변경을 저장하지 않는다.
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

ImportSettlementExpenseUseCase.loadCandidates(settlementId, source, criteria)
  -> List<ExpenseCandidateResponse>
ImportSettlementExpenseUseCase.importCandidate(settlementId, candidateId, source)
  -> SettlementExpenseResponse

SelectAllocationTypeUseCase.select(settlementId, allocationType) -> SettlementResponse
CalculateDutchPayUseCase.calculate(settlementId) -> AllocationResult

SaveItemSelectionUseCase.save(
  settlementId, participantId, itemId, AllocationFraction
) -> ItemSelectionResponse
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
| LoadPaymentHistoryPort | 결제 내역 후보를 제공한다. | ImportSettlementExpenseUseCase | payment-history Adapter 또는 개발 Stub |
| ExtractReceiptItemsPort | OCR 영수증 품목 후보를 제공한다. | ImportSettlementExpenseUseCase | receipt OCR Adapter 또는 개발 Stub |
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

GET /api/v2/settlements/{settlementId}/expense-candidates?source=paymentHistory|receiptOcr
POST /api/v2/settlements/{settlementId}/expenses/import
Authentication: required, creator only

PUT /api/v2/settlements/{settlementId}/allocation-type
Request: { allocationType: DUTCH_PAY|ITEM_ALLOCATION }
Authentication: required, creator only
ITEM_ALLOCATION: OCR 품목 저장 기능 완료 전 SETTLEMENT_ITEM_ALLOCATION_NOT_AVAILABLE

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

PUT /api/v2/settlement-links/{token}/participants/{participantId}/items/{itemId}
Request: { fraction: ONE|HALF|THIRD|QUARTER }
POST /api/v2/settlement-links/{token}/participants/{participantId}/item-submission
Authentication: public link and resolved participant context required
~~~

공개 품목 선택 API의 토큰 해석·참여자 자동 매칭·브라우저 이름 선택은 request-link Spec이 소유한다. 이 기능은 그 결과로 확인된 participantId만 받는다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 이번 증분은 병합된 V12의 `settlements.allocation_type`과 `settlement_dutch_pay_allocations`를 매핑하며 새 마이그레이션을 만들지 않는다. 후속 증분에서 settlement_items, settlement_item_selections, settlement_item_submissions를 추가한다. item selection에는 settlement_id, participant_id, item_id, fraction을 저장하고 participant_id와 item_id의 복합 Unique 제약을 둔다.
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 결제 내역·OCR은 Port와 개발 Stub으로만 우선 연결한다. 실제 API 명세·환경 변수는 외부 공급자 확정 뒤 별도 Feature Plan으로 추가한다.
- Environment variable or profile changes: dev profile에서 빈 후보를 반환하는 Stub을 제공한다.

## 5. Design decisions

### Business logic

- Rules and their locations: 허용 비율·같은 참여자의 중복 선택 방지·품목 합계 검증은 Domain에 둔다. DRAFT 수정 권한과 마지막 제출 뒤 상태 전환은 Application Service가 Settlement Domain 규칙과 함께 조정한다.
- Behavior owned by Domain: 지출 양수 검증, 더치페이 나머지 생성자 배분, 품목별 비율 합계 검증, AllocationResult 합계 검증.
- Flow orchestrated by Application: 외부 후보 변환, 생성자 권한 확인, 링크 참여자 문맥 확인, 전체 제출 여부 확인, SUSPENDED 전환과 request-link 전달.

### Technology and patterns

- Constraint to solve: 실제 OCR·결제 내역 연동 전에도 생성자는 수기 입력과 더치페이를 사용할 수 있어야 한다.
- Selected approach: 지출 원본별 후보 조회는 Output Port로 분리하고, 수기 지출은 Settlement가 직접 소유한다.
- Reason for selection: 외부 기능이 개발 Stub이어도 배분 핵심 규칙과 API 계약을 먼저 검증할 수 있다.
- Alternatives considered: OCR·결제 내역 기능이 완성될 때까지 지출 등록 전체를 보류하는 방식은 최소 정산 흐름을 막으므로 제외한다.
- Trade-offs and limits: MVP의 제한된 분수에서 발생하는 원 단위 나눗셈 문제는 요청 확정 시 검증 오류로 노출한다. 일반적인 반올림·잔여금 배분 정책은 후속 결정이 필요하다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | 지출 관리·후보 조회·배분 방식·공개 품목 선택 API | SettlementExpenseController, request-link Controller |
| Application | 지출·배분 UseCase, OCR·결제내역·결과 전달 Port | application.settlement |
| Domain | Expense, Item, ItemSelection, AllocationResult 규칙 | domain.settlement |
| Persistence | 지출·품목·선택·제출 테이블과 제약 | adapter.out.persistence.settlement |
| Redis | 없음 | N/A |
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

- [ ] DRAFT 생성자만 수기 지출과 배분 방식을 변경한다.
- [ ] 더치페이 계산 결과의 합은 항상 지출 총액과 일치하고 나머지는 생성자 몫이다.
- [ ] 같은 품목을 여러 참여자가 허용 비율로 선택할 수 있고, 같은 참여자의 중복 행은 생기지 않는다.
- [ ] 전체 제출 후 품목별 비율과 총액이 일치하면 Share 고정에 필요한 결과가 생성된다.
- [ ] 전체 제출 뒤 불일치하면 정산은 SUSPENDED이고 Share는 생성되지 않는다.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| 문서 작성 | Complete | 이 Spec |
| 구현·컴파일 | Complete | `./gradlew compileJava` 성공 |
| 자동 테스트 | Complete | 격리 PostgreSQL 스키마와 더미 Discord URL을 사용한 `./gradlew test` 159개 성공 |
| PostgreSQL 영속 검증 | Complete | V12 기반 더치페이 초안 교체·조회·수정·참여자 삭제 Cascade 통합 테스트 성공 |
| 실제 HTTP | Complete | JWT 로그인 뒤 2명 500원 기본 배분, 400원 수정 시 차액 100원, 생성자 600원 수정 시 재균형, 지출 변경 뒤 배분 무효화 409 검증 성공 |
| Swagger | Complete | `/v3/api-docs`의 배분 유형·더치페이 조회·수정 경로와 `/swagger-ui.html` 200 응답 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-21 | 두 배분 방식과 전체 제출 검증 설계 기록 | 요청 링크에서 Share·송금 정보 고정 | 분수·원 단위 반올림 정책 미정 |
| 2026-08-23 | 첫 구현 증분을 수동 지출 CRUD로 제한하고 V11 병합을 확인 | 지출 Domain·Application·Web 구현 | 기준 총액과 누적 지출액의 불일치 처리 시점 필요 |
| 2026-08-23 | 수동 지출 CRUD와 테스트·실제 HTTP·Swagger 검증 완료 | 배분 방식과 더치페이 계산 | 후속 요청 확정 흐름도 정산 행 잠금을 사용해야 함 |
| 2026-08-23 | 리뷰에서 발견된 누적액 `long` 초과를 저장 전 400으로 변환하고 실제 HTTP 롤백 상태 검증 | 배분 방식과 더치페이 계산 | 금액 타입을 확장하면 API·DB 계약을 함께 변경해야 함 |
| 2026-08-23 | V12 병합 뒤 배분 유형·더치페이 초안 구현 범위와 API 계약 확정 | Domain·영속·Application·Web 구현 | 입력 변경 뒤 기존 계산이 남지 않도록 무효화가 필요함 |
| 2026-08-23 | 배분 유형 선택·기본 N빵·금액 수정·합계 응답과 입력 변경 무효화를 구현하고 159개 테스트·실제 HTTP·Swagger 검증 완료 | 외부 지출 후보 또는 품목별 할당 구현 | ITEM_ALLOCATION은 실제 OCR 품목을 검증할 저장 기능이 완료될 때까지 선택을 거부함 |

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

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: 전체 구현 전
- Remaining risks: OCR·결제 내역 공급자 계약, 품목 분수의 원 단위 반올림 정책이 확정돼야 운영 연동을 완료할 수 있다.
- Follow-up work: 요청 링크·수금 계좌·Share 고정 Spec을 구현한다.
