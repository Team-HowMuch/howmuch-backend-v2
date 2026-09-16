# Feature Plan: 영수증 OCR 프록시 API

- Status: Draft
- Last updated: 2026-09-16
- Related issue: [#396](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/396), [howmuch-frontend-v2#249](https://github.com/Team-HowMuch/howmuch-frontend-v2/issues/249)
- Related ADR: N/A

## 1. Purpose

### Problem

**앱이 개인 장비의 Tailscale Funnel 주소로 영수증 이미지를 직접 올린다.**

세 가지가 동시에 문제다.

- **인증이 없다.** 주소만 알면 누구나 호출한다. OCR은 한 건당 약 3초가 걸리는 GPU 작업이라 그대로 비용이자 가용성 위험이다.
- **주소가 앱에 박혀 있다.** 장비나 도메인이 바뀌면 앱을 다시 심사해야 한다.
- **영수증 이미지는 개인정보다.** 구매처·품목·금액이 담긴다. 전송 경로와 보존 정책을 백엔드가 쥐고 있어야 하는데 지금은 앱과 개인 장비 사이에서 끝난다.

### Why now

프론트(#249)가 운영 API 경유로 바꾸려고 이 엔드포인트를 기다린다. 장비가 꺼지면
영수증 촬영 기능 전체가 멈추는 상태를 더 끌 이유가 없다.

### Success criteria

- [ ] 인증된 사용자만 영수증 이미지를 올려 OCR 결과를 받는다.
- [ ] 앱이 내부 OCR 주소를 모른다.
- [ ] 이미지를 우리 저장소에 보관하지 않는다.
- [ ] OCR 서비스가 죽어 있어도 백엔드가 500이 아니라 의미 있는 상태 코드로 답한다.
- [ ] 업로드 크기와 타임아웃이 명시적으로 제한된다.

### Out of scope

- **사용자별 호출 제한.** 후속 이슈로 뺀다. 실제 사용량을 보고 한도를 정하는 편이 맞다.
  다만 홈서버 한 대가 병목이라는 점은 그대로 남으므로 후속에서 반드시 다룬다.
- 기존 `ExtractReceiptItemsPort`(참조 문자열 → 정산 품목) 경로. 목적이 달라 건드리지 않는다.
- OCR 결과를 정산에 바로 반영하는 흐름. 앱이 결과를 받아 기존 등록 API로 보낸다.
- 이미지 저장·재처리·비동기 큐.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement`(영수증) 주변이지만, 이 기능 자체는 정산 상태를 바꾸지 않는다
- Reason a new domain is required, if applicable: 없음. 새 도메인 모델을 만들지 않는다.
  이 기능은 외부 서비스 호출과 응답 변환이 전부이고, 지켜야 할 불변식이 없다
- Relationships to other domains: `User`(인증된 호출자)

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 영수증 분석 | 이미지 한 장을 구조화된 영수증 데이터로 바꾸는 일 | `AnalyzeReceiptUseCase` |
| 영수증 분석 결과 | 상호·일시·품목·합계 | `ReceiptAnalysis` |
| 합계 검증 여부 | OCR 숫자와 교차 검증되었는지 | `totalVerified` |
| 내부 OCR 서비스 | 비전 모델과 텍스트 OCR을 돌리는 별도 서비스 | `ReceiptOcrClient` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 영수증을 찍어 품목과 합계를 받는다 | `AnalyzeReceiptUseCase` (신규) |

### State and invariants

- State transitions: 없음. 이 기능은 아무 상태도 바꾸지 않는다
- Rules that must always hold:
  - 인증되지 않은 호출은 OCR 서비스에 닿지 않는다.
  - 이미지는 응답 후 우리 쪽에 남지 않는다(컨테이너 임시 파일은 요청 끝에 삭제된다).
- Invalid states: N/A

## 3. Scenarios

### Normal flow

1. 사용자가 영수증을 찍어 `POST /api/v2/receipts/ocr`에 multipart로 올린다.
2. 백엔드가 인증을 확인하고 크기·형식을 검사한다.
3. 내부 OCR 서비스에 그대로 전달한다.
4. 응답을 백엔드 DTO로 변환해 돌려준다.

### Failure flow

- Condition: 이미지가 아니거나 빈 파일
- Returned error or state transition: 400. OCR 서비스까지 가지 않는다
- Condition: 파일이 업로드 한도를 넘음
- Returned error or state transition: 413
- Condition: OCR 서비스가 응답하지 않거나 연결되지 않음
- Returned error or state transition: 503. 장비가 꺼진 상태를 사용자에게 그대로 알린다
- Condition: OCR 서비스가 분석에 실패(`ok=false`)
- Returned error or state transition: 422. 요청은 닿았고 이미지를 읽지 못한 것이다
- Condition: 인증 없음
- Returned error or state transition: 401 (기존 필터 체인)

### Boundary conditions

- OCR은 한 건당 약 3초다. 타임아웃을 그보다 넉넉히 두되 무한정 기다리지 않는다.
- `result`가 `null`일 수 있다. 그때는 실패로 다룬다.
- `total_verified=false`는 실패가 아니다. 값을 그대로 전달해 앱이 사용자에게 확인받게 한다.
- 품목의 `name`·`quantity`·`price`가 각각 `null`일 수 있다. 앱이 감당해야 하므로 그대로 내보낸다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: AnalyzeReceiptUseCase (신규)
Command: AnalyzeReceiptCommand(fileName, contentType, bytes)
Result: ReceiptAnalysisResult(storeName, purchasedAt, items, totalAmount, paymentMethod, totalVerified)
Web Request DTO -> Command: 컨트롤러가 MultipartFile 을 Command 로 옮긴다
Result -> Web Response DTO: ReceiptOcrWebResponse.from()
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `AnalyzeReceiptImagePort` | 이미지 한 장을 영수증 데이터로 분석한다 | `AnalyzeReceiptService` | `ReceiptOcrHttpAdapter` |

### Application Policy

- Decision owned by the Policy: 없음. 판정 규칙이 없다
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: N/A
- Future inputs deliberately not implemented yet: 사용자별 호출 제한

### Web API

```text
POST /api/v2/receipts/ocr
Content-Type: multipart/form-data
Request: file = 영수증 이미지 (JPEG/PNG)
Response: {
  "storeName": "스타필드",
  "purchasedAt": "2026-09-16 16:47",
  "items": [{"name": "참치김밥", "quantity": 1, "price": 3500}],
  "totalAmount": 60000,
  "paymentMethod": "카드",
  "totalVerified": true
}
Errors:
  400 INVALID_RECEIPT_IMAGE          빈 파일이거나 이미지가 아님
  401                                 인증 없음 (기존 필터 체인)
  413 PAYLOAD_TOO_LARGE               업로드 한도 초과 (기존 multipart 설정)
  422 RECEIPT_OCR_FAILED              OCR 서비스가 이미지를 읽지 못함
  503 RECEIPT_OCR_UNAVAILABLE         OCR 서비스에 닿지 못하거나 시간 초과
Authentication: 필요
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: **없음**
- Flyway migration required (`Yes` / `No`): **No**
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 내부 OCR 서비스 `POST /ocr/receipt` (multipart `file`) 신규 연동
- Environment variable or profile changes: OCR base URL·타임아웃·허용 이미지 형식

## 5. Design decisions

### Business logic

- Rules and their locations: 형식·크기 검사는 웹 경계, 실패 해석은 Service
- Behavior owned by Domain: 없음
- Decisions owned by Application Policy: 없음
- Flow orchestrated by Application: 인증된 사용자 확인 → 포트 호출 → 결과 변환

### Technology and patterns

- Constraint to solve: 내부 서비스의 스키마 변화가 앱 계약을 깨지 않아야 한다
- Selected approach: OCR 응답을 백엔드 DTO로 변환한다
- Reason for selection: 아래 별도 항목
- Alternatives considered: 응답을 그대로 통과시키기
- Trade-offs and limits: 필드가 늘면 백엔드도 함께 고쳐야 한다

#### 응답을 그대로 통과시키지 않는다

OCR 응답에는 `raw`(비전 모델 원본 문자열), `ocr_lines`, `corrections` 같은 디버깅용
필드가 들어 있다. 그대로 내보내면 **내부 모델 동작이 공개 API가 된다.** 모델을 바꾸거나
보정 로직을 손볼 때마다 앱 계약이 흔들리고, `raw`에는 이미지에서 읽은 텍스트가 통째로
담겨 로그·캐시에 남을 여지도 커진다.

백엔드 DTO로 변환하면 필드가 늘 때 백엔드도 고쳐야 하지만, 그 비용은 계약을 한 곳에서
쥐는 값으로 치를 만하다.

#### 이미지를 보관하지 않는다

보존 기간을 정하는 것보다 두지 않는 편이 낫다. 지켜야 할 삭제 일정도, 유출 표면도
생기지 않는다. 이미지는 DB에도 오브젝트 스토리지에도 들어가지 않고, 로그에도 남기지 않는다.

**다만 "디스크에 전혀 닿지 않는다"는 사실이 아니다.** 멀티파트 해석은 서블릿 컨테이너가
하고, `spring.servlet.multipart.file-size-threshold` 기본값이 `0B`라 업로드된 파트는
`java.io.tmpdir` 아래 임시 파일로 먼저 떨어졌다가 요청이 끝날 때
`DispatcherServlet.cleanupMultipart` → `Part.delete()`로 지워진다. 이건 이 저장소의 모든
업로드 엔드포인트(그룹 이미지·프로필 이미지)가 이미 그렇게 동작하는 방식이고, 이번
기능이 새로 만든 노출면이 아니다. 문서와 Swagger 문구를 "보관하지 않는다"로 맞췄다.

`file-size-threshold`를 한도 위로 올리면 임시 파일 자체가 사라지지만, 그 설정은 전역이라
그룹·프로필 업로드까지 최대 5MB를 힙에 들고 있게 된다. 동시 업로드 수에 따라 메모리가
직접 걸리는 변경이므로 이번 PR에서 혼자 정하지 않는다. **후속 이슈 후보.**

나중에 재처리나 품질 분석이 필요해지면 그때 보존 정책과 함께 따로 설계한다.

#### OCR 서비스 실패를 두 가지로 나눈다

"닿지 못함"과 "읽지 못함"은 사용자가 할 일이 다르다. 전자는 기다렸다 다시 하는 것이고
후자는 다시 찍는 것이다. 503과 422로 나눈다.

가르는 기준은 **"사진을 다시 찍으면 달라지는가"** 하나다.

| 상태 | 우리 응답 | 이유 |
|---|---|---|
| 401·403 | 503 | 내부 서비스 인증 설정이 틀렸다. 사용자가 할 수 있는 게 없다 |
| 404·405 | 503 | `RECEIPT_OCR_PATH` 오타 같은 배포 실수. 재촬영은 영원히 실패한다 |
| 408·429 | 503 | 홈서버 한 대가 밀렸다. 잠시 후 재시도가 맞다 |
| 502·503·504 | 503 | 앞단 프록시가 뒤쪽이 죽었다고 알리는 신호 |
| 400·413·415·422 | 422 | 서비스가 이미지를 보고 거절했다 |
| 500 | 422 | 이 서비스는 분석 실패를 `500 + {"ok": false, "error"}`로 알린다 |
| 연결 실패·타임아웃·본문 해석 실패 | 503 | 셋 다 사진으로 풀리지 않는다 |

처음 구현은 502·503·504만 갈랐다. 그러면 경로 오타 하나로 **모든 사용자가 모든 영수증에서**
"더 밝은 곳에서 다시 찍어주세요"를 듣고, 다시 찍어도 똑같이 실패한다. 리뷰에서 잡혔다.

**남은 위험:** `application.yaml`의 `spring.jackson.deserialization.accept-float-as-int: false`
때문에, OCR 서비스가 `"total_amount": 10500.0` 처럼 정수 자리에 소수를 보내면 역직렬화가
터지고 사용자는 503을 본다. 서비스 OpenAPI는 integer로 선언되어 있어 지금은 나지 않지만,
모델을 갈아끼울 때 조용히 깨질 수 있는 지점이다. 터지면 500이 아니라 503이라는 점만
확보해 두었다(`treatsUnparseableBodyAsUnavailable`).

#### 기존 ExtractReceiptItemsPort 를 건드리지 않는다

이름이 비슷해 헷갈리지만 목적이 다르다. 기존 포트는 `(requesterId, receiptReference)`로
**이미 분석된 결과를 정산에 가져오는** 경로이고, 이번 것은 **이미지를 분석하는** 경로다.
시그니처도 결과 모양도 맞지 않는다. 새 포트를 만든다.

참고로 이슈 본문은 "기존 `ReceiptOcrAdapter`(#194) 경로"라고 적었지만, 저장소에는 그런
어댑터가 없다. prod 프로파일에는 예외를 던지는 `UnavailableReceiptExtractionAdapter`만
있다. 즉 #194의 운영 어댑터는 아직 없는 상태다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 신규 엔드포인트 1개 | `ReceiptOcrController` |
| Application | UseCase 1개, Output Port 1개 | `AnalyzeReceiptService` |
| Domain | 없음 | - |
| Persistence | 없음 | - |
| Redis | 없음 | - |
| Security | `/api/**`가 이미 인증을 요구한다 | `SecurityConfig` |
| Configuration | OCR base URL·타임아웃 Properties | `infra/receipt` |
| Documentation | 본 문서, Swagger | `docs/features/` |

Existing behavior that must remain unchanged:

- 기존 영수증 가져오기 경로(`ExtractReceiptItemsPort`)와 그 상태 코드
- multipart 업로드 한도 값 자체(5MB/6MB). 이 PR은 한도를 바꾸지 않는다

**바뀌는 기존 동작 (의도한 변경, 리뷰 필요):**

`GlobalExceptionHandler`에 `MaxUploadSizeExceededException` → 413,
`MultipartException` → 400, `MissingServletRequestPartException` → 400을 추가했다.
이건 공용 핸들러라 영수증 OCR만이 아니라 **기존 업로드 엔드포인트에도 함께 적용된다.**

| 엔드포인트 | 이전 | 이후 |
|---|---|---|
| `POST /api/v2/groups` | 한도 초과 시 500 INTERNAL_SERVER_ERROR | 413 PAYLOAD_TOO_LARGE |
| `PATCH /api/v2/groups/{groupId}` | 〃 | 〃 |
| `PATCH /api/v2/user/me/profile` | 〃 | 〃 |

한도 초과를 500으로 돌려주던 것이 버그였으므로 개선이지만, 범위 밖의 계약 변경이다.
기존 테스트는 전부 `standaloneSetup`이라 한도를 강제하지 않아 빨개지는 것은 없다
(확인: `GroupControllerTest`, `UserControllerTest`). 대신 `GlobalExceptionHandlerTest`에
매핑을 못 박아 두었다.

**남긴 것 (다른 사람 코드라 건드리지 않음, 오빠 판단 필요):**

- `GroupDocs`의 `@ApiResponses`에는 413이 없다. Swagger가 실제와 어긋난다
- `GroupImagePolicy.MAX_IMAGE_SIZE`가 컨테이너 한도와 똑같은 5MB라, 그 클래스의
  `INVALID_GROUP_IMAGE`(400) 분기는 운영에서 도달 불가다. 컨테이너가 먼저 413을 낸다
- `RequestTraceFilter.area()`에 `/api/v2/receipts` 분기가 없어 새 엔드포인트가 `other`로
  묶인다. 앱에서 가장 느린 경로인데 대시보드에서 따로 볼 수 없다

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`Required` / `N/A`) — **N/A** (스키마 변경 없음)
- [ ] UseCase and Application Command/Result
- [ ] Required Output Ports
- [ ] Domain models and rules — **N/A**
- [ ] Application Policies — **N/A**
- [ ] Application Service
- [ ] Output Adapters
- [ ] Web Request/Response DTOs, Controllers, and Swagger
- [ ] Infra configuration and Bean wiring
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(receipt): 영수증 OCR 프록시 설계 수립
feat(receipt): 영수증 이미지 분석 UseCase와 Port를 추가한다
feat(receipt): 내부 OCR 서비스 HTTP 어댑터를 추가한다
feat(receipt): 영수증 OCR 엔드포인트를 노출한다
test(receipt): 인증·형식·실패 분기를 검증한다
docs(receipt): 영수증 OCR 프록시 검증 결과 기록
```

## 8. Verification

### Commands

```bash
docker compose -f compose.test.yml up -d
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test \
DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh clean test
docker compose -f compose.test.yml down -v
```

OCR 서비스는 개인 장비에 있어 CI에서 부를 수 없다. 어댑터는 `MockRestServiceServer`로
실제 HTTP 계약(멀티파트 필드 이름, 응답 파싱, 타임아웃, 연결 실패)을 검증한다.

### Completion criteria

- [ ] Purpose의 사용자 결과를 검증했다.
- [ ] 정상·실패·경계 흐름을 검증했다.
- [ ] 문서와 실제 코드가 일치한다. 같은 주장을 하는 다른 줄이 남아 있지 않은지 확인했다.
- [ ] 수행하지 못한 검증과 사유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 설계 문서 | Pending | 본 문서 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-16 | OCR 서비스 스펙 확인, 저장소 현황 확인, 설계 문서 작성 | 구현 | 이슈가 말한 `ReceiptOcrAdapter`가 저장소에 없다. prod에는 예외를 던지는 어댑터만 있어 #194의 운영 연동은 아직 없는 상태다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-16 | OCR 응답을 백엔드 DTO로 변환한다 | `raw`·`ocr_lines` 같은 내부 디버깅 필드가 공개 API가 되면 모델을 바꿀 때마다 앱 계약이 흔들린다 | 필드가 늘면 백엔드도 함께 고쳐야 한다 |
| 2026-09-16 | 이미지를 보관하지 않는다 | 보존 기간을 지키는 것보다 아예 두지 않는 편이 안전하다 | 재처리·품질 분석이 필요해지면 따로 설계한다 |
| 2026-09-16 | `file-size-threshold`는 이번에 바꾸지 않는다 | 전역 설정이라 모든 업로드가 최대 5MB를 힙에 든다. 혼자 정할 변경이 아니다 | 요청 처리 중 임시 파일이 남는다(요청 끝에 삭제). 후속 이슈 후보 |
| 2026-09-16 | 도달 불가 상태를 열거해 503으로 보낸다 | 경로 오타 하나로 모든 사용자가 무한 재촬영을 하게 된다 | 새 상태가 생기면 목록을 갱신해야 한다 |
| 2026-09-16 | 기존 `ExtractReceiptItemsPort`를 건드리지 않는다 | 이름은 비슷하지만 '참조로 가져오기'와 '이미지 분석'으로 목적이 다르다 | 영수증 관련 포트가 둘이 된다 |
| 2026-09-16 | 호출 제한을 이번 범위에서 뺀다 | 실제 사용량을 보고 한도를 정하는 편이 맞다 | 홈서버 한 대가 병목으로 남는다. 후속 이슈 필요 |
| 2026-09-16 | 닿지 못함(503)과 읽지 못함(422)을 나눈다 | 사용자가 할 일이 다르다. 기다리기와 다시 찍기 | 상태 코드가 하나 늘어난다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
