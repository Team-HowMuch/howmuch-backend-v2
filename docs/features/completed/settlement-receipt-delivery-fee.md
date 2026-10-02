# Feature Plan: 영수증 정산 배달비 등록·분배

- 상태: 구현 및 검증 완료
- 갱신일: 2026-10-02
- 관련 이슈: [#463](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/463)
- 선행 마이그레이션: [PR #464](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/464), 병합 완료
- 관련 규칙: [AGENTS.md](../../../AGENTS.md)

## 1. 목적과 범위

OCR 응답과 앱 등록 요청에는 배달비가 있지만 영수증 정산 등록 계약에는 없어 지출·참여자 몫·송금액에서 누락된다. 기존 settlement 도메인에 배달비를 추가하고 품목 금액 비율로 분배한다.

- [x] 지출 금액이 `Σ(품목 net) - discount + deliveryFee`와 일치한다.
- [x] 확정 참여자 배달비 몫의 합이 전체 배달비와 정확히 일치한다.
- [x] 지출·품목·내 몫·링크·상세 응답과 송금 안내에 배달비를 반영한다.
- [x] 독촉 대상 판단은 배달비가 포함된 확정 Share를 사용한다.
- [x] 기존 지출과 배달비 미입력 요청은 배달비 0으로 기존 동작을 유지한다.

OCR 서버와 앱 화면 변경, 기존 영수증 금액 재계산은 제외한다. 선행 V42 병합 후 사용자 요청으로 기능 구현을 재개했다. 추가 Flyway 마이그레이션은 필요 없다.

## 2. 도메인과 시나리오

- 행위자: 정산 생성자, 품목 분배 참여자.
- `deliveryFee`: 영수증 전체의 순 배달비, 0 이상인 원 단위 금액.
- `deliveryFeeShare`: 참여자의 품목 원금 비율로 배분한 배달비.
- 기존 `SettlementExpense`를 확장한다. `RECEIPT_OCR` 외 source는 할인과 마찬가지로 배달비 0만 허용한다.
- 품목별 할인 후 합계가 `itemTotal`이다. `discount >= itemTotal` 거부는 배달비와 무관하게 유지한다.
- 등록 검증 → 지출·품목 저장 및 배분 초안 무효화 → 선택·제출·확정 → 상세·송금 안내 순서로 금액을 전달한다.
- 실패·경계: 음수 배달비, 금액 오버플로, 할인 상한, 미입력/null/0, 원 단위 잔액, 0원 품목, 부분 선택과 미배분 품목을 검증한다.

## 3. 계약과 설계

### 입력과 출력

- 기존 `CreateSettlementReceiptExpenseUseCase`를 사용한다. 신규 UseCase/Port는 만들지 않는다.
- `CreateSettlementReceiptExpenseWebRequest -> CreateSettlementReceiptExpenseCommand`에 `deliveryFee`를 전달한다. Web의 미입력/null은 0으로 정규화하고 음수는 400으로 거부한다.
- `ExtractReceiptItemsPort.ReceiptExtraction`과 Writer 검증에도 값을 전달한다. 기존 추출 Adapter는 기본값 0을 유지한다.
- `SettlementExpenseResult`, `SettlementItemListResult`, `SettlementItemAllocationResult`, `SettlementDetailResult`와 대응 Web Response에 배달비를 반영한다.
- `POST /api/v2/settlements/{settlementId}/expenses/receipt`의 인증·소유권·수정 가능 상태·중복 방지 계약을 유지한다.
- 지출/품목 목록에 `deliveryFee`, 내 몫/링크 응답에 `deliveryFee`·`deliveryFeeShare`, 상세 `paymentInfo.deliveryFee`와 참여자 `deliveryFeeShare`를 추가한다.

### 재사용한 외부 경계

| Port | 호출 위치와 목적 | Adapter |
|---|---|---|
| `CurrentActorPort` | 등록·조회 Service의 현재 사용자 확인 | 기존 Security Adapter |
| `LoadSettlementPort`, `SaveSettlementPort` | 소유권·잠금·배분 초안 상태 | 기존 Settlement Persistence Adapter |
| `LoadSettlementExpensePort`, `SaveSettlementExpensePort` | 지출 조회·중복 확인·금액 합계·저장 | `SettlementExpensePersistenceAdapter` |
| `LoadSettlementItemPort`, `SaveSettlementItemPort` | 품목 조회·저장 | 기존 Settlement Item Persistence Adapter |
| `SettlementItemAllocationStorePort` | 선택·제출 조회 및 저장 | 기존 Item Allocation Persistence Adapter |
| `ExtractReceiptItemsPort` | 기존 receipt-ocr 경로의 추출값 | 기존 추출 Adapter |

### 계산 책임

- Domain: 배달비 음수 및 source 불변식.
- Service/Writer: 요청·추출값 검증, 오버플로 검사, 총액 조립과 트랜잭션.
- `SettlementRequestPolicy`: 품목 합에서 할인을 빼고 배달비를 더해 정산 총액과 대조한다.
- `SettlementItemAllocationPolicy`: 개인 몫은 `base - discountShare + deliveryFeeShare`다. 배달비와 할인은 같은 품목 원금을 기준으로 배분한다.
- 확정 전 잠정 배달비는 `deliveryFee × base ÷ itemTotal`을 내림한 값이다.
- 전원 제출 및 유효한 품목 배분 확인 후 최대 잔여법으로 배달비 잔액을 배분한다. 잔여 비율이 같으면 UUID 순서로 정한다. 곱셈은 `BigInteger`로 처리한다.
- 배달비는 품목 총액보다 클 수 있으므로 할인의 상한 검증과 분리한다.
- 배달비만 있는 경우도 할인과 동일하게 전원 제출 전에는 Share를 생성하지 않는다. 상세에서는 미확정 참여자를 `NOT_READY`, `expectedAmount=null`로 반환한다.
- 기존 품목 원금 내림과 생성자 잔액 부담은 유지한다. 원금 내림에 따른 허용 오차와 별개로 배달비 분배 합계는 정확히 배달비와 일치한다.
- 독촉과 송금 안내는 기존 확정 Share 조회를 재사용한다. 독촉 메시지는 금액 문구가 없는 기존 형식이며, 배달비가 반영된 Share로 미납 대상 여부를 판단한다.

### 저장 구조와 마이그레이션

- 선행 Flyway migration required: **Yes**, [V42](../../../src/main/resources/db/migration/V42__add_settlement_expense_delivery_fee.sql)로 완료.
- 이번 기능 구현의 추가 migration required: **No**.
- `settlement_expenses.delivery_fee BIGINT NOT NULL DEFAULT 0` 및 비음수 CHECK 제약을 사용한다.
- 기존 행과 기존 애플리케이션 INSERT는 0을 사용한다. 기존 `amount`·`discount`는 변경하지 않는다.
- JPA Entity와 Mapper에서 값을 왕복 저장한다. source 규칙은 Domain에서 적용한다.
- Redis 키·외부 API·보안·환경 변수·Infra 변경: N/A.

## 4. 영향 범위

| 영역 | 변경 또는 확인한 계약 | 결과 |
|---|---|---|
| 스키마 | `settlement_expenses.delivery_fee` | 선행 PR에서 완료, 이번 변경 없음 |
| 입력 | `CreateSettlementReceiptExpenseWebRequest`, `CreateSettlementReceiptExpenseCommand` | 배달비 전달·기본값·검증 |
| 등록 | `SettlementReceiptExpenseService`, `SettlementReceiptExpenseWriter`, `ExtractReceiptItemsPort` | 등록 및 추출 경로의 총액 계산 |
| 도메인·저장 | `SettlementExpense`, `SettlementExpenseJpaEntity`, `SettlementExpenseMapper` | 불변식 및 왕복 매핑 |
| 분배 | `SettlementItemAllocationPolicy`, Reader/Writer/StateLoader | 잠정치·확정치 및 전원 제출 조건 |
| 조회 | `SettlementDetailService`, Result/Web Response | 배달비·개인 몫·예상 금액 |
| 독촉·송금 | 기존 Reminder 및 Transfer Instruction 경로 | 저장된 Share 재사용 확인 |
| 문서 | Settlement 관련 `*Docs`, 응답 `@Schema` | 필드 및 확정 시점 명시 |

## 5. 실행 계획과 커밋 경계

- [x] 최신 develop·이슈·기존 PR·라벨·중첩 AGENTS·영수증 등록 흐름 확인.
- [x] 설계와 마이그레이션 필요 여부 확정.
- [x] V42 업그레이드·제약 검증 후 SQL 단독 PR #464 생성 및 해당 작업 종료.
- [x] 병합된 V42를 기준으로 Domain/저장 구현.
- [x] 등록 계약·검증 및 총액 계산 구현.
- [x] 분배 정책과 확정 시점 구현.
- [x] 응답·Swagger·문서 갱신 및 전체 검증.
- N/A: 신규 Output Port, 신규 Infra 구성, 외부 연동 추가. 기존 경계를 재사용한다.

구현 전 정한 커밋 경계에 따라 각 기능 변경에 동작 검증을 포함한다. 모든 기능 커밋에서 컴파일을 확인했다.

```text
feat(settlement): 영수증 배달비 도메인과 저장 매핑 추가
feat(settlement): 영수증 등록 배달비 검증과 총액 계산 반영
feat(settlement): 품목 비율 배달비 분배와 금액 검증 추가
feat(settlement): 정산 응답과 문서에 배달비 및 개인 몫 반영
docs(settlement): 배달비 정산 구현과 검증 기록
```

## 6. 검증

전용 임시 디렉터리·포트의 PostgreSQL 18과 Redis를 사용했다. 애플리케이션 `.env`를 읽지 않고 합성 설정과 테스트 계정으로 검증했다. 저장소 테스트 스크립트가 필요한 환경 기본값을 제공한다.

```bash
bash scripts/test.sh compileJava test --console=plain
git diff --check
git status --short --untracked-files=all
```

| 항목 | 결과 |
|---|---|
| 선행 V41 → V42 검증 | 세 source의 기존 행 보존 및 기본값 0, 양수/BIGINT 상한 허용, 음수·NULL 거부 확인. 기존 저장 통합 테스트 V41/V42 각각 5건 통과 |
| Domain/저장 집중 검증 | 16건 통과: source·음수 불변식, 기본값, V42 컬럼 왕복 저장 |
| 등록 집중 검증 | 28건 통과: 미입력/null/0/양수, 할인+배달비, 음수, 합계 오버플로, 할인 상한 |
| 분배 집중 검증 | 53건 통과: 잠정치, 전원 제출, 잔여법, UUID 동률 순서, 품목 합보다 큰 배달비, 0원 참여자, 미배분 품목 |
| 응답 및 Swagger | Controller/Service/OpenAPI 테스트 통과, 배달비 필드와 상세 필수 필드 확인 |
| 실제 HTTP 통합 | 음수 등록 400, 정상 등록→요청→선택→제출→상세·공개 링크·송금 안내 검증 통과 |
| 전체 회귀 | Java 190개 테스트 클래스, 1,440건 통과. 실패·오류·건너뜀 0건. JavaScript 35건 통과 |
| 컴파일 | `compileJava` 성공, 전체 테스트 소스 컴파일 성공 |
| 문서 및 변경 검사 | 상대 링크·참조 로컬 경로 확인, `git diff --check` 통과 |

HTTP 통합 검증에서는 품목 10,000원, 전체 할인 1,000원, 배달비 3,003원을 등록했다. 동등한 품목 선택의 배달비는 1,502원·1,501원으로 확정되고 개인 금액 6,002원·6,001원의 합이 정산 총액 12,003원과 일치한다. 참여자 상세·공개 링크·송금 안내의 금액도 6,001원으로 일치한다.

## 7. 진행·결정 기록

| 날짜 | 결정/진행 | 근거 및 영향 |
|---|---|---|
| 2026-10-02 | 마이그레이션을 #464로 먼저 분리하고 종료 | AGENTS.md의 마이그레이션 게이트 준수 |
| 2026-10-02 | 병합 후 사용자 요청으로 기능 구현 재개 | 최신 develop `560664db` 기준, 추가 스키마 변경 없음 |
| 2026-10-02 | 할인과 배달비의 비례 계산을 공유하되 상한 검증 분리 | 배달비가 품목 합보다 큰 영수증도 지원 |
| 2026-10-02 | 배달비만 있어도 전원 제출 후 확정 | 잠정치와 최종 잔액 차이로 송금액이 바뀌는 문제 방지 |
| 2026-10-02 | 기존 품목 원금 내림 정책 유지 | 배달비 기능 밖의 기존 정산 반올림 정책 변경 방지 |
| 2026-10-02 | 실제 HTTP 통합 및 전체 회귀 완료 | 저장·분배·응답·송금 금액의 일관성 확인 |

## 8. 완료 기록

- 완료일: 2026-10-02.
- 정상·실패·경계 동작, 배달비 0의 기존 동작, API 문서 계약을 검증했다.
- 운영 서버 호출과 실제 외부 송금은 수행하지 않았다. 이번 검증 범위는 로컬 애플리케이션의 실제 HTTP 및 DB·Redis 통합이다.
- 앱 화면의 새 필드 표시와 OCR 서버 변경은 이 백엔드 작업 범위 밖이다.
- 기존 품목 원금 내림 정책에 따른 잔액은 유지된다. 배달비 자체는 원 단위 잔액까지 정확하게 분배한다.
