# Feature Plan: 결제내역 정산의 거래 설명을 상호명에서 제외

- Status: Completed
- Last updated: 2026-10-04
- Related issue: [#467](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/467)
- Related ADR: N/A (기존 표시 규칙 수정)

## 1. 목적

### 문제와 필요성

결제내역을 가져온 정산의 상세 정보에 이체 수단과 계좌 정보가 섞인 거래 설명이 상호명으로 표시된다.
거래 설명은 가게 이름을 보장하지 않으므로 앱이 상호명 행을 숨길 수 있도록 null을 반환한다.

### 성공 기준

- [x] 단일 PAYMENT_HISTORY 지출의 상세·링크 진입 응답은 storeName=null을 반환한다.
- [x] 해당 거래의 description 저장과 UTC paidAt, 정산 금액을 유지한다.
- [x] 단일 RECEIPT_OCR 지출의 실제 상호명과 결제일을 유지한다.
- [x] 표시 대상 0건·복수 건, 수동 지출 혼합 및 결제일 없는 기존 데이터의 규칙을 유지한다.

### 제외 범위

거래 설명 추정·정제, 저장된 지출 수정, 신규 API·Port·Policy, 스키마 변경, 앱 변경 및 배포.

## 2. 도메인 분석

기존 settlement Domain과 account Transaction을 재사용한다. 정산 생성자가 거래를 가져오고,
인증된 상세 조회자 또는 유효한 참여 링크 조회자가 결제 표시 정보를 받는다.

| 용어 | 코드 | 의미 |
|---|---|---|
| 거래 설명 | Transaction.description / SettlementExpense.description | 지출 목록에 사용하는 거래 원문 |
| 상호명 | storeName | 단일 영수증에서 확인한 가게 이름 |
| 결제일 | paidAt | 단일 영수증·결제내역 지출의 UTC 결제 시각 |
| 표시 대상 | RECEIPT_OCR / PAYMENT_HISTORY | 수동 지출을 제외한 대표 결제 정보 후보 |

인증·소유권·중복 가져오기·금액·정산 상태 전이는 변경하지 않는다.

## 3. 시나리오

1. 결제내역 한 건을 가져오면 거래 설명과 거래 시각을 기존대로 저장한다.
2. 상세 paymentInfo.storeName과 링크 storeName은 null이고 paidAt은 저장된 거래 시각이다.
3. 영수증 한 건이면 두 응답 모두 기존 상호명과 결제일을 반환한다.
4. 대상 0건·2건 이상이면 상호명과 결제일 모두 null이다.
5. 수동 지출은 대상 수에 포함하지 않는다. 결제내역 한 건과 수동 지출이면 결제일만 반환한다.
6. 기존 PAYMENT_HISTORY의 paidAt=null이면 두 표시값 모두 null이다.

권한·유효하지 않은 링크 및 가져올 수 없는 거래의 오류 계약은 유지한다.

## 4. 계약

### Input Port 및 매핑

- ImportSettlementPaymentExpenseUseCase / ImportSettlementPaymentExpenseCommand / SettlementExpenseMutationResult를 유지한다.
- GetSettlementUseCase → SettlementDetailResult.PaymentInfo → SettlementDetailWebResponse.PaymentInfo를 유지한다.
- ResolveSettlementLinkUseCase → SettlementLinkEntryResult → SettlementLinkEntryWebResponse를 유지한다.
- nullable String storeName과 nullable LocalDateTime paidAt의 필드 구조는 유지한다.

### Output Ports 및 Application Policy

기존 LoadAccountTransactionPort, SaveSettlementExpensePort, LoadSettlementExpensePort와 영속 Adapter를 재사용한다.
신규 Port·Policy는 필요하지 않다. Application의 SettlementPaymentInfo가 상세·링크 표시 규칙을 공유한다.

### Web API

- POST /api/v2/settlements/{settlementId}/expenses/payment-history: 저장과 응답 구조 유지.
- GET /api/v2/settlements/{settlementId}: 결제내역의 paymentInfo.storeName=null.
- GET /api/v2/settlement-links/{token}: 결제내역의 storeName=null.
- 인증·오류 및 paidAt 계약 유지. 두 Web DTO의 Swagger 상호명 설명을 수정한다.

### 저장소 및 외부 연동

- Database Entity, constraint, query 변경: 없음.
- Flyway migration required: No. 기존 description/source/paid_at을 읽는 표시 규칙만 수정한다.
- Planned migration / Migration PR: N/A.
- Redis, 외부 API, 환경 변수 및 프로필 변경: 없음.

## 5. 설계 결정

SettlementPaymentInfo.resolve에서 기존 대상 개수 판정을 유지한 뒤 RECEIPT_OCR에만 description을 상호명으로 사용한다.
PAYMENT_HISTORY는 description을 수정하지 않고 상호명만 null로 반환한다.
영수증만 먼저 필터링하면 영수증·결제내역 혼합 시 대표 정보를 잘못 선택하므로 대상 필터는 그대로 둔다.
Controller별 분기나 Domain 저장값 변경 대신 기존 공통 표시 helper에서 규칙을 수정한다.

## 6. 영향 범위

| 영역 | 영향 | 확인 대상 |
|---|---|---|
| Application | 공통 상호명 표시 수정 | SettlementPaymentInfo, SettlementDetailService, SettlementLinkEntryService |
| API | nullable 유지, 출처별 설명 갱신 | SettlementDetailWebResponse.PaymentInfo, SettlementLinkEntryWebResponse |
| Domain / Persistence | 변경 없음 | SettlementPaymentExpenseService, SettlementExpensePersistenceAdapter, SettlementExpenseMapper |
| Redis / Security / Configuration | 변경 없음 | 기존 인증 및 링크 계약 |
| 검증 | 기존 기대값과 HTTP 회귀 검증 갱신 | SettlementPaymentInfoTest, SettlementDetailServiceTest, SettlementLinkEntryServiceTest, SettlementPaymentInfoHttpIntegrationTest |
| 문서 | 설계·완료 기록 및 이전 표시 규칙 변경 안내 | 본 계획, [기존 결제 표시 계획](../completed/settlement-payment-info.md) |

## 7. 구현 계획

- [x] Migration-only PR: N/A (스키마 변경 없음).
- [x] UseCase / Command / Result: N/A (기존 계약 유지).
- [x] Output Ports: N/A (기존 경계 재사용).
- [x] Domain: N/A (상태·저장 규칙 유지).
- [x] Application Policies: N/A (기존 표시 helper 수정).
- [x] 공통 표시 helper 및 기존 단위 테스트 기대값 수정.
- [x] Output Adapters: N/A (영속 매핑 유지).
- [x] Web DTO Swagger 및 실제 HTTP 회귀 검증 갱신.
- [x] Infra: N/A (설정 변경 없음).
- [x] 문서와 검증 결과 기록 후 completed로 이동.

### 예상 커밋 경계

1. `fix(settlement): 결제내역 정산 상호명에 거래 설명 노출 차단` — 공통 표시 수정과 기존 단위 테스트 갱신.
2. `test(settlement): 결제내역 상호명과 거래 정보 보존 HTTP 검증` — 가져오기부터 상세·링크까지 기존 HTTP 검증 갱신.
3. `docs(settlement): 결제내역 상호명 표시 계약과 검증 기록` — Swagger, Feature Plan, 이전 문서 안내.

## 8. 검증

Java 21과 일회용 PostgreSQL 18·Redis 8을 준비하고 scripts/test.sh의 합성 인증·외부 연동 설정을 사용한다.
공통 표시, 상세·링크 Service, 실제 가져오기 HTTP, 영수증 및 OpenAPI의 기존 테스트를 실행한다.
HTTP 검증에서 저장된 description과 결제일, 두 응답의 명시적 null 및 결제일, 미인증 상세 401을 확인한다.

```bash
bash scripts/test.sh compileJava test --tests '*SettlementPaymentInfoTest' --tests '*SettlementDetailServiceTest' --tests '*SettlementLinkEntryServiceTest' --console=plain
bash scripts/test.sh compileJava test --console=plain
git diff --check
git status --short --untracked-files=all
```

| 항목 | 결과 | 근거 |
|---|---|---|
| 수정 전 재현 | 확인 | 기존 단위 테스트 50건 중 결제내역 관련 5건 실패 |
| compileJava / compileTestJava | 통과 | scripts/test.sh가 두 컴파일 task 실행 |
| 수정 후 단위 회귀 | 통과 | 공통 표시·상세·링크 Service 50건 |
| 전체 회귀 | 통과 | Java 190개 클래스 1,443건, 실패·오류·건너뜀 0건; JavaScript 35건 통과 |
| 실제 HTTP | 통과 | 월말·연말 2건: 저장 설명·결제일 보존, 상세·링크 storeName 명시적 null, 미인증 상세 401 |
| 영수증·OpenAPI | 통과 | 영수증 상호명 유지, nullable 계약 및 API 스키마 검증 |
| 문서 링크·로컬 경로 | 통과 | 수정 문서의 상대 링크와 영향 범위 Java 타입 위치 확인 |
| diff 확인 | 통과 | git diff --check |

### 완료 기준

- [x] 결제내역 상호명을 숨길 수 있는 응답을 확인했다.
- [x] 정상·경계 및 기존 권한 오류 흐름을 확인했다.
- [x] 문서와 Swagger를 실제 표시 규칙에 맞췄다.
- [x] 실제 앱·운영 검증 미수행을 기록했다.

## 9. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견 위험 |
|---|---|---|---|
| 2026-10-03 | 이슈·이전 PR 관례 확인, 최신 develop 및 호출·영속 흐름 분석 | 표시 규칙과 회귀 테스트 수정 | 이전 테스트가 거래 설명을 상호명으로 기대함 |
| 2026-10-04 | 공통 helper·Swagger·기존 회귀 검증 수정, 전체 Java·JavaScript 통과 | PR 리뷰 | 실제 앱 화면은 미검증 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 | 대안·영향 |
|---|---|---|---|
| 2026-10-03 | 공통 helper에서 PAYMENT_HISTORY 상호명만 null로 반환 | 상세·링크의 일관성과 저장값 보존 | 복수 대상·영수증·결제일 판정 유지 |

## 11. 완료 기록

- 완료일: 2026-10-04.
- 최종 동작: 단일 결제내역의 상세·링크 상호명은 null이며 설명 저장과 결제일은 유지한다. 단일 영수증 상호명은 기존대로 반환한다.
- 미검증 항목: 실제 앱 화면과 운영 API 호출·배포는 수행하지 않았다. 일회용 서버에서 실제 HTTP 계약은 검증했다.
- 남은 위험·후속 작업: 앱의 null 상호명 행 숨김은 앱에서 확인한다. 서버 추가 구현은 필요하지 않다.
