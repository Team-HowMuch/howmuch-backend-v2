# Feature Plan: 결제내역 정산의 상호명·결제일 표시 수정

- Status: In Progress
- Last updated: 2026-09-05
- Related issue: [#282](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/282), [#283](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/283), [#284](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/284)
- Related ADR: N/A (기존 구조 유지)

## 1. Purpose

### Problem / Why now

결제내역에서 가져온 정산은 결제일을 저장하지 않고 상세·참여 링크는 영수증만 표시 대상으로 삼아 앱에서 상호명과 결제일을 표시할 수 없다.

### Success criteria

- [ ] 신규 가져오기는 UTC Transaction.transactedAt을 paidAt에 그대로 저장한다.
- [ ] 상세·참여 링크가 단일 OCR 또는 결제내역 지출의 상호명·결제일을 반환한다.
- [ ] 복수 대상·수동 지출 및 기존 null 결제일 계약을 유지한다.

### Out of scope

기존 데이터 결제일 복구, 앱, 시간대 변환, 신규 API·Port, 스키마·설정 변경, 자동 병합·배포.

## 2. Domain analysis

기존 settlement Domain과 account Transaction을 재사용한다. 생성자는 ImportSettlementPaymentExpenseUseCase로 본인 거래를 가져온다. 상세·참여 링크 조회자는 같은 결제 표시 정보를 받는다.

| 용어 | 코드 | 의미 |
|---|---|---|
| 거래 시각 | Transaction.transactedAt | UTC 변환이 끝난 실제 거래 시각 |
| 결제일 | SettlementExpense.paidAt | 생성 시각과 구분되는 nullable 표시 정보 |
| 표시 대상 | RECEIPT_OCR, PAYMENT_HISTORY | MANUAL을 제외하고 정확히 한 건일 때만 대표 정보 결정 |

인증·소유권·중복 가져오기·금액·상태 전이 규칙은 유지한다.

## 3. Scenarios

1. 본인 출금 거래를 가져오고 description과 transactedAt을 지출에 저장한다.
2. 상세 및 유효한 참여 링크에서 같은 상호명·결제일을 조회한다.
3. 대상 0건 또는 2건 이상이면 두 필드는 null이다. 대상 1건+MANUAL은 표시한다.
4. 기존 PAYMENT_HISTORY의 paidAt=null이면 상호명만 표시한다.

기존 권한·중복·가져올 수 없는 거래의 오류 계약은 유지한다.

## 4. Contracts

### Input Port and boundary mapping

기존 ImportSettlementPaymentExpenseCommand → ImportSettlementPaymentExpenseUseCase → SettlementExpenseMutationResult → SettlementExpenseMutationWebResponse 흐름 유지.
상세 SettlementDetailResult.PaymentInfo와 SettlementLinkEntryResult의 storeName/paidAt 필드와 Web 매핑 유지.

### Output Ports / Application Policy

기존 LoadAccountTransactionPort, SaveSettlementExpensePort, LoadSettlementExpensePort와 Persistence Adapter 재사용. 새 Port·Policy 없음. Application의 package-private SettlementPaymentInfo가 표시 규칙을 공유한다.

### Web API

- POST /api/v2/settlements/{settlementId}/expenses/payment-history: 입력·응답 구조 유지.
- GET /api/v2/settlements/{settlementId}: paymentInfo.storeName/paidAt 값 보강.
- GET /api/v2/settlement-links/{token}: storeName/paidAt 값 보강.
- 필드 타입·nullable·인증·오류 계약 유지.

### Persistence and external integrations

- Flyway migration required: No. 기존 paid_at 및 JPA 매핑 재사용.
- Planned migration / Migration PR: N/A. 적용된 V28 SQL·주석 유지.
- Redis·외부 API·환경 변수·프로필 변경: 없음.

## 5. Design decisions

paymentHistory의 amount 다음에 nullable LocalDateTime paidAt을 추가한다. 구 시그니처 overload 없이 모든 호출부를 수정한다. Service는 거래 시각을 추가 변환 없이 전달한다.
SettlementReceiptInfo를 SettlementPaymentInfo로 바꾸고 두 출처를 필터링한 뒤 size()!=1 규칙을 유지한다. receipt 지역 변수와 Swagger 설명도 결제 정보로 정리한다.
기존 데이터는 보정하지 않으므로 과거 결제일 null은 유지된다.

## 6. Impact surface

| 영역 | 영향 |
|---|---|
| Domain | paymentHistory factory 및 호출 fixture |
| Application | 가져오기 저장값, 상세·링크 공통 표시 helper |
| Web | 기존 DTO Swagger 설명 및 응답 회귀 검증 |
| Persistence | 운영 코드 변경 없이 저장·재조회 테스트 보강 |
| Redis/Security/Configuration | 없음 |
| Documentation | Feature Plan 및 이슈·PR |

## 7. Implementation plan

- [x] Migration-only PR: N/A (기존 컬럼 사용)
- [x] UseCase / Command / Result: N/A (계약 유지)
- [x] Output Ports: N/A (기존 경계 사용)
- [ ] Domain factory와 호출부
- [x] Application Policies: N/A (기존 helper 확장)
- [ ] Application Service·표시 helper
- [x] Output Adapters: N/A (기존 매핑 사용, 테스트 보강)
- [ ] Web DTO Swagger·Controller 회귀 검증
- [x] Infra: N/A (기술 설정 변경 없음)
- [ ] 문서 및 검증 결과

### Expected commit boundaries

사용자의 실행 요청은 이슈·커밋·푸시·PR 생성을 포함한다. 최신 origin/develop에서 fix/settlement-payment-info를 생성하며 각 수정 커밋은 관련 테스트를 포함한다.

```text
docs(settlement): 결제내역 표시 정보 수정 계획 수립
fix(settlement): 결제내역 지출에 거래 결제일 저장
fix(settlement): 정산 상세와 참여 링크의 결제 정보 표시 보강
```

GitHub 실제 서브 이슈 관계와 상위 체크리스트를 연결한다. PR은 develop 대상 하나이며 조직 PR 템플릿과 Resolves #282/#283/#284를 사용한다.

## 8. Verification

- Domain·가져오기 Service: UTC 값·description 보존, createdAt 대체/9시간 재변환 방지, nullable 허용.
- 표시 helper: 각 단일 출처, 결제내역 2건, OCR+결제내역, MANUAL만, 대상 1건+MANUAL, 빈/null 목록, 기존 null 결제일.
- 상세·링크 Service 및 Controller: 두 응답 계약·기존 권한·금액 유지.
- PostgreSQL·Redis·환경 전제 확인 후 영속 재조회·OpenAPI 통합 테스트.
- 환경이 갖춰지면 가져오기→상세→유효 링크 HTTP 검증.

```bash
./gradlew compileJava
# 관련 테스트 선택 실행, 필요 서비스 확인 후 통합 테스트 실행
 git diff --check
 git status --short --untracked-files=all
```

문서 링크도 검사한다. 하위 workflow의 오래된 테스트 금지 문구보다 루트 AGENTS.md의 변경 동작 테스트 규칙을 우선한다.

### Results

검증 대기. 완료 시 실제 명령·결과·미검증 범위를 기록한다.

## 9. Progress log

- 2026-09-05: 최신 develop에서 브랜치 생성, 코드·저장 매핑 확인, 중복 이슈 확인 후 #282~#284 등록.

## 10. Decision log

- 2026-09-05: 기존 데이터 보정 제외는 사용자 확정 사항. migration 없이 신규 저장과 표시만 수정한다.
- 2026-09-05: 공통 버그 템플릿의 본문을 사용하되 저장소 관례에 맞춰 [Fix]·bug 적용. 담당자 임의 지정 없음.

## 11. Completion record

- 완료일·최종 결과·미검증·후속 작업: 검증 후 기록.
