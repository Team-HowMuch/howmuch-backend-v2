# Feature Plan: 영수증 OCR 응답의 배달비 전달

- Status: Implementation complete, verification recorded
- Last updated: 2026-09-29
- Related issue: [#442](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/442)
- Related pull request: [#443](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/443)
- Related ADR: N/A
- 선행 작업: [영수증 OCR 프록시 API](receipt-ocr-proxy.md), [영수증 품목 옵션·할인과 비율 할인 분배](settlement-receipt-options-discounts.md)

## 1. Purpose

### Problem

OCR 서비스가 배달비를 `items` 에서 빼고 `result.delivery_fee` 로 따로 내려주도록 바뀌었다
(howmuch-ai-server [`c891e52`](https://github.com/Team-HowMuch/howmuch-ai-server/commit/c891e52), 홈서버 배포 완료).
`OcrReceiptApiResponse` 는 모르는 필드를 무시하므로 이 값을 버린다. 그래서 배달 영수증의
배달비가 앱 응답 어디에도 없고, 앱이 품목·옵션·할인으로 합계를 다시 계산하면 배달비만큼 모자란다.

예전에는 OCR 모델이 배달비를 품목으로 올리면 우연히 전달되기라도 했지만, OCR 서비스 배포 후에는
그 경로도 없다.

### Why now

OCR 서비스는 이미 배포돼 있다. 백엔드가 따라가지 않는 동안 배달 영수증은 배달비가 빠진 채로
정산 화면에 들어간다. 금액 정합성 문제라 `priority: P1` 로 처리한다.

### Success criteria

- [x] 앱이 영수증 OCR 응답에서 `deliveryFee` 를 받는다.
- [x] OCR 서비스가 `delivery_fee` 를 주지 않으면(구버전) `deliveryFee` 는 생략되지 않고 `null` 로 나간다.
- [x] `0` 과 `null` 이 섞이지 않는다.
- [x] 앱이 물어온 "`price` 가 옵션 포함 금액이냐"에 Swagger 만 보고 답할 수 있다.

### Out of scope

- 배달비를 정산에 어떻게 넣을지(품목으로 등록할지, 참여자에게 어떻게 나눌지). 앱이 정한다.
- 정산 저장 구조 변경. 정산 쪽 요청 계약과 테이블은 그대로다.
- OCR 인식률 개선. OCR 서비스 저장소에서 따로 진행한다.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: 영수증 OCR 프록시(`receipt`). 도메인 모델은 없고 DTO 경계만 있다.
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 정산(`settlement`)은 앱을 거쳐 이 응답을 받는다. 서버 안에서 직접 잇지 않는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 배달비 | 배달팁 할인·무료배달을 상계한 배달비 순액 | `deliveryFee` / OCR `delivery_fee` |
| 줄 금액 | 옵션 제외·할인 전·수량 반영 품목 한 줄의 금액 | `items[].price` |
| 줄 최종 금액 | `price + Σ subItems.price − items[].discount` | 계산값, 필드 없음 |
| 영수증 전체 할인 | 특정 품목에 귀속되지 않는 할인. 배달팁 할인은 포함하지 않는다 | 최상위 `discount` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 앱 사용자 | 영수증을 찍어 배달비까지 포함된 분석 결과를 받는다 | `AnalyzeReceiptUseCase` (변경 없음) |

### State and invariants

- State transitions: N/A. 상태를 저장하지 않는다.
- Rules that must always hold: `deliveryFee` 는 OCR 값을 그대로 옮긴다. 백엔드가 계산하거나 0 으로 바꾸지 않는다.
- Invalid states: 없음. 음수 검증은 OCR 서비스 계약(0 이상)에 맡긴다.

## 3. Scenarios

### Normal flow

1. 앱이 배달 영수증 사진을 `POST /api/v2/receipts/ocr` 로 올린다.
2. OCR 서비스가 `"delivery_fee": 3000` 을 포함해 응답한다.
3. `OcrReceiptApiResponse.Result` → `ReceiptAnalysisResult` → `ReceiptOcrWebResponse` 로 옮긴다.
4. 앱이 `"deliveryFee": 3000` 을 받는다.

### Failure flow

- Condition: OCR 서비스가 `delivery_fee` 를 주지 않는다(구버전).
- Returned error or state transition: 오류 없음. `"deliveryFee": null`. 이때 배달비가 품목에 섞여 있을 수 있다.

### Boundary conditions

- `delivery_fee: 0`: 배달비가 없거나 OCR 이 배달비 줄을 찾지 못한 경우다. OCR 서비스는 못 읽어도 0 을 준다. 백엔드는 둘을 구분할 수 없다.
- `delivery_fee` 가 소수(`3000.0`): `spring.jackson.deserialization.accept-float-as-int: false` 로 역직렬화가 실패하고 503 이 된다. 기존 필드와 같은 동작이며 OCR 서비스는 정수를 준다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: AnalyzeReceiptUseCase (변경 없음)
Command: AnalyzeReceiptCommand (변경 없음)
Result: ReceiptAnalysisResult (+ Long deliveryFee)
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: ReceiptOcrWebResponse.from() (+ deliveryFee)
```

기존 6인자 호환 생성자(`ReceiptAnalysisResult`, `OcrReceiptApiResponse.Result`)는 `discount`, `deliveryFee` 를 `null` 로 채운다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `AnalyzeReceiptImagePort` | 변경 없음. 반환 Result 에 필드만 늘었다 | `AnalyzeReceiptService` | `ReceiptOcrHttpAdapter` |

### Application Policy

- Decision owned by the Policy: N/A. 판단 없이 값을 옮긴다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: N/A
- Future inputs deliberately not implemented yet: N/A

### Web API

```text
POST /api/v2/receipts/ocr
Request: 변경 없음 (multipart file)
Response: data.deliveryFee (Long, nullable) 추가. 기존 필드는 이름·타입 그대로
Errors: 변경 없음
Authentication: 변경 없음 (bearerAuth)
```

Swagger 설명을 함께 고친다.

- `items[].price`: 줄 금액(옵션 제외·할인 전·수량 반영). 줄 최종 금액은 `price + Σ subItems.price − discount`.
- `items[].subItems[].price`: 영수증에 찍힌 옵션 줄 금액 그대로. 수량을 곱하지 않고 더한다.
- 최상위 `discount`: 품목 할인과 배달팁 할인을 포함하지 않는다.
- `deliveryFee`: 0 과 `null` 의 뜻.
- 합계 공식 `Σ(줄 최종 금액) − 최상위 discount + deliveryFee` 는 계약일 뿐 인식 오류로 어긋날 수 있고,
  `totalVerified` 는 품목 합까지 보장하지 않는다.

**앱이 정산으로 옮길 때 주의할 변환.** OCR `price` 는 옵션을 **뺀** 금액이고, 정산 등록 요청의
`items[].amount` 는 옵션을 **포함한** 금액이다([선행 계획](settlement-receipt-options-discounts.md) 3절).
앱은 `amount = price + Σ subItems.price` 로 바꿔 보내야 한다. 이 계약 차이는 이 작업에서 바꾸지 않는다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 내부 OCR 서비스 응답의 `result.delivery_fee` 를 읽는다. 요청은 그대로다.
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 없음. 배달비 계산(무료배달 상계, 합계·내역 줄 처리)은 OCR 서비스 몫이다.
- Behavior owned by Domain: N/A
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 기존과 같다.

### Technology and patterns

- Constraint to solve: 기존 호출부(다른 사람 테스트 포함)를 깨지 않고 레코드에 필드를 더한다.
- Selected approach: 레코드에 컴포넌트를 추가하고, 기존 6인자 호환 생성자는 새 값을 `null` 로 채운다.
- Reason for selection: 선행 작업(`c57b857`)이 `discount` 를 더할 때 쓴 방식과 같다. 호출부를 고치지 않아도 된다.
- Alternatives considered:
  - 백엔드에서 `null` 을 0 으로 바꾼다 → 구버전 OCR 에서 "배달비가 품목에 섞여 있음" 신호가 사라져 기각.
  - 백엔드가 줄 최종 금액을 계산해 내려준다 → OCR 쪽 `price` 의미가 흔들리는 영수증에서 틀린 값을 확정적으로 보이게 되어 기각. 계약만 문서화한다.
- Trade-offs and limits: 0 이 "배달비 없음"과 "못 찾음"을 함께 뜻한다. OCR 서비스가 둘을 구분해 주기 전에는 백엔드도 구분할 수 없다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답에 `deliveryFee` 추가, 금액 필드 Swagger 설명 수정 | `ReceiptOcrWebResponse`, `ReceiptOcrDocs` |
| Application | Result 에 `deliveryFee` 추가 | `ReceiptAnalysisResult` |
| Domain | 없음 | - |
| Persistence | 없음 | - |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | 이 계획서 | `docs/features/completed/receipt-ocr-delivery-fee.md` |

외부 경계: `OcrReceiptApiResponse` 가 `delivery_fee` 를 읽는다.

Existing behavior that must remain unchanged:

- 기존 응답 필드의 이름·타입·null 동작.
- 웹 응답은 camelCase 다([#428](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/428)). `delivery_fee` 가 새어 나가지 않는다.
- 상태 코드 분류(400·413·422·503)와 이미지 판정.
- 정산 등록 요청 계약과 저장 구조.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: 저장 구조 변경 없음)
- [x] UseCase and Application Command/Result: `ReceiptAnalysisResult` 에 필드 추가
- [x] Required Output Ports: N/A (기존 Port 그대로)
- [x] Domain models and rules: N/A
- [x] Application Policies: N/A
- [x] Application Service: N/A (값을 옮기기만 한다)
- [x] Output Adapters: `OcrReceiptApiResponse` 매핑
- [x] Web Request/Response DTOs, Controllers, and Swagger: `ReceiptOcrWebResponse`, `ReceiptOcrDocs`
- [x] Infra configuration and Bean wiring: N/A
- [x] Documentation and verification results: 이 문서

### Expected commit boundaries

이 계획서는 구현 뒤에 썼다(결정 로그 참고). 실제 커밋 경계는 다음과 같다.

```text
feat(receipt): OCR 응답의 배달비를 앱까지 전달한다
docs(receipt): 품목 금액의 의미와 합계 계산식을 API 문서에 적는다
test(receipt): OCR 배달비 전달을 검증한다
docs(receipt): 배달비 전달 계획과 검증 결과를 기록한다
```

## 8. Verification

### Commands

```bash
docker compose -f compose.test.yml up -d
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
  REDIS_HOST=127.0.0.1 REDIS_PORT=16379 bash scripts/test.sh test
git diff --check
git status --short --untracked-files=all
```

일회용 스택(`compose.test.yml`, 15432/16379)을 쓴다. 개발 DB 는 건드리지 않는다.

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| 전체 스위트 | 177개 클래스 / 1336건, 실패 2건(무관) | `build/test-results/test/*.xml` 파싱 |
| OCR 응답 매핑 | 통과 | `OcrReceiptApiResponseTest` 7 → 8. 엄격 매퍼로 3000·0·필드 없음(null) 구분 |
| 어댑터 경유 | 통과 | `ReceiptOcrHttpAdapterTest` 28 → 29. `RestClient` 실제 변환기 |
| 웹 계약 | 통과 | `ReceiptOcrControllerTest` 5 → 6. camelCase, `delivery_fee` 미노출, `null` 출력 |
| 나머지 영수증 클래스 | 통과 | `ReceiptOcrHttpIntegrationTest` 6, 정산 영수증 3개 클래스 17 |
| 커밋별 컴파일 | 확인 | 각 커밋의 생성자 호출부 인자 수 대조, 레코드 3개 javac 컴파일·실행 |
| 실패 2건 | 이 작업과 무관 | 아래 참고 |
| 수동 종단 호출 | 미수행 | 아래 참고 |

**실패 2건.** `AuthSessionRedisIntegrationTest.authSession_rotatingRefresh_setsExpiryAndPreservesPolicy` 파라미터 2개(TTL 기대 [298026, 300026]ms, 실제 297022ms).
이 작업은 인증·Redis 파일을 건드리지 않는다. 같은 클래스를 `origin/develop`(`b66d096d`)에서 단독 실행해도 실패하고(13건 중 1건), 실행마다 1건·2건으로 흔들린다.
`AuthSessionRedisAdapter.rotate()` 가 JVM 시계로 계산한 절대 시각을 `SET ... PXAT` 로 보내고 Redis 는 자기 시계로 TTL 을 정하므로, 로컬 Redis 컨테이너(colima VM)의 시계가 호스트와 어긋나면 ±1초 허용 범위를 벗어나는 것으로 보인다.

**수동 종단 호출 미수행.** OCR 서비스 쪽 `delivery_fee` 출력은 실측 영수증 63장으로 확인했다(배달비 3장 분리, 품목 합과 일치). 백엔드를 거친 종단 호출은 개발 서버 배포 후 확인해야 한다.

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-28 | 구현·Swagger·테스트, 독립 리뷰 반영, PR #443 | Codex 리뷰 대응 | 인증 테스트가 로컬 시계 차이로 흔들림 |
| 2026-09-29 | Codex 지적에 따라 이 계획서 추가 | 개발 서버 배포 후 종단 확인 | - |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-28 | 웹 응답은 `deliveryFee`(camelCase) | #428 에서 응답 필드명을 camelCase 로 통일했다 | `delivery_fee` 를 쓰면 앱이 정규화 함수를 다시 둬야 한다 |
| 2026-09-28 | `null` 을 0 으로 바꾸지 않는다 | 구버전 OCR 신호를 보존한다. 최상위 `discount` 와 같은 규칙이다 | 앱이 null 을 처리해야 한다 |
| 2026-09-28 | 6인자 호환 생성자 유지 | 다른 사람 테스트를 고치지 않는다. 선행 작업과 같은 방식 | 호환 생성자가 늘어난다 |
| 2026-09-28 | 독립 리뷰의 Swagger 문구 지적 6건 반영 | 0 의 뜻, 최상위 `discount` 범위, 공식 속 `discount` 구분, 옵션에 수량을 곱하는지, 옵션 `nullable`, `null` 일 때의 단서 | - |
| 2026-09-29 | 계획서를 구현 뒤에 추가 | 여러 패키지에 걸친 변경이라 `AGENTS.md` 가 계획서를 요구한다(Codex 지적). 구현 전에 쓰지 않은 것은 누락이었다 | 이미 올린 커밋은 고쳐 쓰지 않고 이 문서 커밋을 위에 쌓는다 |

## 11. Completion record

- Completed on: 2026-09-29 (PR #443 머지 대기)
- Final behavior: 영수증 OCR 응답에 `deliveryFee` 가 추가되고, 금액 필드의 의미가 Swagger 에 적혀 있다.
- Unverified items: 백엔드를 거친 실제 OCR 종단 호출.
- Remaining risks:
  - `deliveryFee: 0` 이 "배달비 없음"과 "못 찾음"을 함께 뜻한다.
  - 합계 공식은 계약일 뿐, 실측 63장 중 35장에서만 품목 합이 합계와 맞았다.
  - 앱이 정산 등록 시 `amount = price + Σ subItems.price` 변환을 빠뜨리면 옵션 금액이 빠진다.
- Follow-up work:
  - OCR 인식률 개선(요약·결제줄이 품목으로 새는 문제 등). OCR 서비스 저장소.
  - `AuthSessionRedisAdapter.rotate()` 의 `PXAT` 절대 시각이 앱 서버와 Redis 의 시계 차이에 민감한 문제. 별도 이슈 후보.
