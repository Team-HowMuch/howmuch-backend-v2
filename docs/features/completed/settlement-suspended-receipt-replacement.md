# Feature Plan: 중단 정산 영수증 교체와 더치페이 수정

- 상태: 구현·로컬 검증 완료 (PR CI 대기)
- 최종 갱신: 2026-10-13
- 관련 이슈: [#515](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/515)

## 1. 목적과 완료 기준

중단된 정산 생성자가 영수증을 새 지출로 추가하지 않고 수정한다. 품목·수량·행 금액·품목/전체 할인·배달비·상호명·결제일을 교체하고 기존 지출 ID와 참조를 유지한다. 더치페이 배분도 중단 상태에서 수정한다.

- [x] 반복 교체에 지출·품목 중복이 없고 다른 지출을 보존한다.
- [x] DRAFT·SUSPENDED 생성자만 교체한다. POST 신규 등록은 DRAFT 전용을 유지한다.
- [x] 수정 후 SUSPENDED를 유지한다. 정산 총액은 교체 후 전체 지출 합계로 갱신한다.
- [x] 기존 품목 선택은 교체된 품목에 한해 제거하고 전체 제출·Share를 초기화한다.
- [x] 입금 확인 기록이 있는 정산의 영수증·더치페이 금액 수정은 거부한다.
- [x] 더치페이 조회·PATCH·PUT은 SUSPENDED에서도 동작한다. 재개 시 합계를 검증하고 새 Share를 저장해 AWAITING_DEPOSIT으로 전환한다.
- [x] 품목 정산은 명시적 재개로 REQUESTED가 되고 다시 선택·제출한다.

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

- [x] compileJava
- [x] 신규 서비스/HTTP/DB 정상·권한·상태·입금 기록·원자 실패·반복 요청 검증
- [x] 정산 회귀 및 전체 Java/JavaScript suite
- [x] 문서 상대 링크·git diff --check
- [ ] PR CI

### 검증 결과

- `bash scripts/test.sh compileJava test bootJar`: Java 233 classes / 1,771개와 JavaScript 35개 통과. failures/errors/skipped 0.
- `SettlementReceiptReplacementIntegrationTest`: 21개. 반복 PUT·동시 교체·결제일 변경/삭제·옵션/선택 CASCADE·다른 영수증 보존·제출/Share 초기화·교체 후 실패 원자 롤백·상태/권한/입금 기록 거부·더치페이 GET/PATCH/PUT/재개 검증.
- `SecurityFilterChainIntegrationTest`: 실제 HTTP 서버와 JWT·Redis 세션으로 PUT 무인증 401, 생성자 200, 타인 403 확인. 전체 23개 통과.
- 전체 suite 이후 품목 교체 → 재개 → 새 품목 선택/제출 → AWAITING_DEPOSIT의 검증을 보강하고 해당 21개 재실행과 JavaScript 35개가 모두 통과했다.
- 컴파일·bootJar·문서 링크·공백 검사 완료. 실제 모바일 화면과 운영 배포는 실행하지 않는다.

## 8. 클라이언트 적용 계약

기존 영수증 지출의 `expenseId`를 경로에 사용한다. 신규 POST와 구분하며 PUT 본문은 영수증 전체 정보다. `receiptReference`는 전송하지 않으며 서버가 기존 값을 보존한다. 응답의 `items[].itemId`는 새 품목 ID이므로 교체 후 재조회/응답으로 로컬 품목 목록을 교체한다.

```http
PUT /api/v2/settlements/{settlementId}/expenses/receipt/{expenseId}
Content-Type: application/json

{
  "storeName": "수정 상호명",
  "paidAt": "2026-10-12T12:30:00",
  "items": [
    {
      "name": "품목",
      "quantity": 2,
      "amount": 12000,
      "discount": 1000,
      "options": [{"name": "추가 옵션", "price": 2000}]
    }
  ],
  "discount": 500,
  "deliveryFee": 3000
}
```

이 예시의 지출 금액은 13,500원이다. 옵션 가격과 수량을 행 금액에 다시 곱하거나 더하지 않는다. 다른 지출이 있으면 정산 총액은 그 금액을 함께 합산한다. `paidAt`은 UTC이며 null로 기존 결제일을 지운다. 할인·배달비의 생략/null은 0이다.

수정 직후 상태는 SUSPENDED다. 품목 정산은 `POST /api/v2/settlements/{settlementId}/resume` 이후 REQUESTED에서 다시 품목을 선택·제출한다. 더치페이는 기존 `GET/PUT /dutch-pay-allocations`, `PATCH /dutch-pay-allocations/{participantId}`로 금액을 수정하고, 합계가 맞는 상태에서 재개하면 AWAITING_DEPOSIT으로 전환한다.

`SETTLEMENT_REVISION_PAYMENT_EXISTS`는 입금 확인 기록이 있어 영수증·배분 수정 또는 더치페이 재개를 거부하는 409다. 영수증이 아닌 지출은 `SETTLEMENT_RECEIPT_REQUIRED` 409다. 기존 참여자·유형·수령 계좌·링크는 유지한다. 등록 POST와 일반 지출 수정은 DRAFT 전용이다.

## 9. 완료 기록

- 구현 완료: 2026-10-13
- Flyway/환경 변수 변경 없음.
- 로컬 격리 환경에서 계약·동시성·실패 원자성·회귀를 검증했다.
- 남은 확인: PR CI, 앱에서 수정 화면 연결. 운영 배포는 이 작업의 범위가 아니다.


## 10. CI 정밀도 오류 수정 계획 (2026-10-13)

PR #516은 develop에 병합됐으나 PR 실행과 병합 후 실행 모두 1,771개 중 11개 테스트가 실패해 이미지 빌드·배포가 건너뛰어졌다. 개발 서버의 공개 `/v3/api-docs`에도 영수증 PUT이 없고 더치페이 설명은 DRAFT 전용으로 남아 있음을 확인했다.

원인은 감사 시각의 정밀도다. Java 반환 객체의 나노초 시각과 PostgreSQL에서 읽은 마이크로초 시각을 record 전체 동등성으로 비교했다. 저장 후 DB에서 다시 읽은 객체를 검증 기준으로 사용해 원자 롤백·다른 영수증 보존의 전체 필드 검증을 유지한다. 테스트용 DateTimeProvider에 나노초를 고정해 OS와 무관하게 문제를 재현하고 회귀를 보장한다. 제품 코드·schema·API 계약은 변경하지 않는다.

예상 커밋:
- `test(settlement): 영수증 교체 검증의 DB 시각 정밀도 차이 보정`
- `docs(settlement): CI 배포 차단 원인과 정밀도 검증 기록`

검증: 나노초 강제 조건에서 수정 전 11건 실패 재현 → 수정 후 해당 21건 → PR CI의 전체 suite 확인. 후속 PR 병합·개발 서버 배포는 이 수정의 결과와 구분한다.


정밀도 수정 검증 결과:
- 나노초 `123456789` 강제 주입: 수정 전 21개 중 11개 실패, 수정 후 21개 모두 통과.
- `bash scripts/test.sh compileJava test --tests '*SettlementReceiptReplacementIntegrationTest'`: 컴파일·Java 21개·JavaScript 35개 통과.
- DB 기준 전체 객체 비교를 유지하므로 품목·옵션·시각·다른 영수증 보존과 롤백 검증을 약화하지 않았다.
- PR CI 전체 suite: 대기. 별도 로컬 전체 suite 재실행은 제품 코드 변경이 없는 테스트 기준 보정이므로 수행하지 않았다.
