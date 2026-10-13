# Feature Plan: 중단 정산 영수증 교체와 더치페이 수정

- 상태: 구현 준비
- 최종 갱신: 2026-10-13
- 관련 이슈: [#515](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/515)

## 1. 목적과 완료 기준

중단된 정산 생성자가 영수증을 새 지출로 추가하지 않고 수정한다. 품목·수량·행 금액·품목/전체 할인·배달비·상호명·결제일을 교체하고 기존 지출 ID와 참조를 유지한다. 더치페이 배분도 중단 상태에서 수정한다.

- [ ] 반복 교체에 지출·품목 중복이 없고 다른 지출을 보존한다.
- [ ] DRAFT·SUSPENDED 생성자만 교체한다. POST 신규 등록은 DRAFT 전용을 유지한다.
- [ ] 수정 후 SUSPENDED를 유지한다. 정산 총액은 교체 후 전체 지출 합계로 갱신한다.
- [ ] 기존 품목 선택은 교체된 품목에 한해 제거하고 전체 제출·Share를 초기화한다.
- [ ] 입금 확인 기록이 있는 정산의 영수증·더치페이 금액 수정은 거부한다.
- [ ] 더치페이 조회·PATCH·PUT은 SUSPENDED에서도 동작한다. 재개 시 합계를 검증하고 새 Share를 저장해 AWAITING_DEPOSIT으로 전환한다.
- [ ] 품목 정산은 명시적 재개로 REQUESTED가 되고 다시 선택·제출한다.

제외: OCR 재호출, 참여자·배분 유형 변경, 입금 완료 금액의 환불·정정, 신규 알림, 배포.

## 2. 도메인과 불변식

기존 settlement 도메인을 사용한다. 영수증은 RECEIPT_OCR 지출과 그 지출의 품목·옵션이다. 수정 주체는 생성자다. 지출 ID·sourceReference·displayOrder를 보존하고 paidAt은 null로 지울 수 있다. 행 금액은 수량을 다시 곱하지 않으며 옵션을 포함한다.

정산 행 잠금으로 교체·배분·재개·제출·입금 대사를 직렬화한다. 입금 확인 사실은 기존 LoadSettlementDepositSummaryPort로 읽고 Application Policy가 수정 가능 여부를 판단한다. 입력 검증과 실패는 쓰기 전 실행하고 모든 쓰기는 한 트랜잭션에서 수행한다.

## 3. 계약과 시나리오

`PUT /api/v2/settlements/{settlementId}/expenses/receipt/{expenseId}`

- ReplaceSettlementReceiptExpenseUseCase / ReplaceSettlementReceiptExpenseCommand / SettlementReceiptExpenseMutationResult
- ReplaceSettlementReceiptExpenseWebRequest → Command → UseCase → Result → 기존 MutationWebResponse
- 본문: storeName, paidAt, items(name/quantity/amount/discount/options), discount, deliveryFee. receiptReference는 서버의 기존 값을 보존한다.
- 금액: Σ(amount − 품목 discount) − 전체 discount + deliveryFee.
- 오류: 인증 401, 타인 403, 정산/지출 없음 404, 비영수증·허용 외 상태·입금 기록 409, 잘못된 입력/합계 초과 400.

정상 흐름: 정산 잠금 → 생성자/상태/입금 기록/영수증 확인 → 검증 및 새 전체 합계 계산 → 기존 품목 삭제(옵션·해당 선택 CASCADE) → 지출 갱신 → 새 품목 저장 → 전체 제출·미입금 Share 초기화 → 정산 총액 갱신. 다른 영수증 선택은 보존하지만 재제출해야 한다.

더치페이는 기존 배분 값을 보존해 수정할 수 있도록 하고, 중단 상태 수정마다 기존 Share를 제거한다. GET은 쓰기를 하지 않는다. 재개는 기존 SettlementRequestPolicy로 지출·전원 배분 합계를 검증하며 새 Share 저장과 상태 변경을 원자 처리한다.

## 4. 저장소와 외부 경계

- Flyway migration required: **No**. 기존 컬럼과 CASCADE를 사용한다. paidAt의 JPA 수정 제한만 해제한다.
- Load/SaveSettlementExpensePort, SaveSettlementItemPort(지출별 교체 추가), DeleteSettlementSharePort(실제 초기화 호출), SettlementItemAllocationStorePort, LoadSettlementDepositSummaryPort를 사용한다.
- Redis·환경 변수·외부 API 변경 없음. 기존 링크·수령 계좌 snapshot을 보존한다.

## 5. 영향과 설계 선택

- Domain: 특정 영수증 교체/더치페이 수정 상태 규칙과 총액 변경. 기존 일반 지출·참여자·유형 변경은 DRAFT 전용.
- Application: 영수증 교체 서비스, 입금 확인 수정 Policy 및 초기화 협력자, 더치페이 수정/재개.
- Persistence: 지출 paidAt 갱신, 지출별 품목 교체, Share 삭제.
- Web: 전용 PUT과 Swagger, 더치페이/재개 상태 설명.
- 검증: 서비스·HTTP 계약·실제 PostgreSQL 교체/롤백/CASCADE·기존 정산 회귀 및 전체 suite.

삭제 후 지출을 신규 생성하는 대안은 지출 ID와 순서가 바뀌어 제외했다. 자동 재개는 사용자의 수정 중 상태를 노출하므로 명시적 재개를 유지한다. 입금 기록이 있으면 초기화할 수 없으므로 409로 거부한다.

## 6. 구현·커밋 계획

1. `feat(settlement): 중단 정산의 기존 영수증 교체 API 추가` — 계약·Domain·Policy·서비스·Adapter·Web·계획 및 영수증 회귀.
2. `feat(settlement): 중단 더치페이 배분 수정과 재개 금액 검증` — 배분 조회·개별/일괄 수정·재개 및 회귀.
3. `docs(settlement): 중단 정산 수정 계약과 검증 결과 정리` — 완료 계획·사용 계약·검증 기록.

각 기능 커밋은 사용하지 않는 Port 없이 컴파일 가능하게 묶는다.

## 7. 검증과 진행

격리된 compose.test.yml PostgreSQL 18·Redis 8과 scripts/test.sh를 사용한다. 실제 계좌·인증 정보는 사용하지 않는다.

- [ ] compileJava
- [ ] 신규 서비스/HTTP/DB 정상·권한·상태·입금 기록·원자 실패·반복 요청 검증
- [ ] 정산 회귀 및 전체 Java/JavaScript suite
- [ ] 문서 상대 링크·git diff --check
- [ ] PR CI

결과: 대기. 구현 완료 후 완료 문서로 이동한다.
