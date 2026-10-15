# Feature Plan: 정산 카테고리

- Status: Completed (#555 저장 구조·#556 자동 분류·#557 표시 머지, #558 내 카테고리 수정 구현·검증. 앱 화면 확인은 개발 서버 배포 후)
- Last updated: 2026-10-15
- Related issue: [#555](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/555) 저장 구조, [#556](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/556) 자동 분류, [#557](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/557) 표시, [#558](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/558) 내 카테고리 수정
- Related ADR: N/A
- 기능 명세: 노션 기능 명세서 v4 STL-008, STL-008-001~004

## 1. Purpose

### Problem

정산마다 지출 카테고리를 자동으로 붙이고, 생성자와 참여자가 각자 자기 카테고리를 바꿀 수 있어야 한다. 리캡(RCP)의 카테고리 분석이 이 값을 쓴다. 지금은 카테고리 목록, 분류 결과, 회원별 수정값, 분류 규칙을 저장할 곳이 없고 관련 코드도 없다.

### Why now

리캡의 카테고리·기록·인사이트 카드가 정산 카테고리를 선행 조건으로 둔다. 카테고리가 없으면 리캡의 절반이 비어 있다.

### Success criteria

- [ ] 앱이 정산용 카테고리 18개(표시명, 세부 예시, 아이콘 키)를 서버에서 받아 표시한다
- [x] 정산을 요청하면 요청과 별도로 카테고리가 정해진다(규칙 → AI → 미분류)
- [x] 정산 목록·상세에 조회하는 회원 기준 "내 카테고리"가 보인다
- [x] 회원이 내 카테고리를 바꿔도 다른 참여자의 카테고리는 그대로다
- [x] LLM 미설정·장애에서도 정산 흐름은 실패하지 않는다

### Out of scope

- 계좌 거래 내역 분류(확장용 카테고리 5개는 목록에만 넣어 두고 쓰지 않는다)
- 정기 정산의 설정 카테고리(STL-011, 담당 범위 밖). 저장 구조에는 출처 `RECURRING`만 열어 둔다
- 리캡 집계(RCP, 담당 범위 밖)
- 수정 기록을 분류 규칙으로 자동 추가(팀이 검토 후 규칙 테이블에 반영)
- 배포 전에 이미 요청된 정산의 소급 분류
- 앱 화면

## 2. Domain analysis

### Domain boundary

- 재사용 도메인: 정산(`settlement`), 공통 코드(`commoncode`)
- 카테고리는 정산 하위 개념으로 둔다. 정산 상태 전이에는 관여하지 않고, 요청 시점에 분류 작업을 하나 만든다.
- 카테고리 목록은 은행 목록(`BankCatalog`, `BANK` 그룹)과 같은 방식으로 공통 코드에서 읽는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 정산 카테고리 | 정산 지출의 분류 | 공통 코드 그룹 `SETTLEMENT_CATEGORY` |
| 정산용 카테고리 | 자동 분류와 수정 선택지에 나오는 18개 | `attr2 = 'Y'` |
| 기본 카테고리 | 자동 분류 결과. 모든 참여자의 기본값 | `settlement_categories.category_code` |
| 내 카테고리 | 조회하는 회원 기준 카테고리. 수정값이 있으면 수정값, 없으면 기본 카테고리 | `settlement_user_categories` ?? `settlement_categories` |
| 분류 출처 | 기본 카테고리가 정해진 근거 | `RULE`, `AI`, `UNCATEGORIZED`, `RECURRING` |
| 분류 규칙 | 키워드 → 카테고리 | `settlement_category_rules` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 회원(앱) | 카테고리 목록을 받아 표시 | 카테고리 목록 조회 (#557) |
| 정산 생성자 | 정산 요청 시 분류 작업 생성 | 정산 요청 (기존, #556에서 확장) |
| 서버 스케줄러 | 대기 중인 분류 작업 처리 | 카테고리 분류 작업 처리 (#556) |
| 회원(생성자·참여자) | 정산 목록·상세에서 내 카테고리 확인 | 정산 목록·상세 조회 (기존, #557에서 확장) |
| 회원(생성자·참여자) | 내 카테고리 수정 | 내 카테고리 수정 (#558) |

### State and invariants

- 분류 작업 상태: `PENDING` → `PROCESSING` → `CLASSIFIED`, 실패 시 `PROCESSING` → `RETRY` → `PROCESSING`. 재시도를 다 쓰면 `UNCATEGORIZED`로 `CLASSIFIED`.
- 선점은 `attempt_count < max_attempts`인 `PENDING`·`RETRY` 작업만 한다. 선점할 때 `attempt_count`를 1 올린다.
- 선점이 만료된 `PROCESSING` 작업은 바로 다시 가져가지 않는다. 시도가 남았으면 `RETRY`(`PROCESSING_TIMEOUT`)로 돌리고, 다 썼으면 `PROCESSING_TIMEOUT`, `UNCATEGORIZED`로 끝낸다(V52 입금 대조 작업과 같은 방식). 서버가 계속 죽어도 시도 횟수가 쌓여 끝이 난다.
- 실패 사유를 남긴 채 끝나는 작업은 시도를 모두 쓴 작업뿐이다(`attempt_count = max_attempts`). 분류에 성공하면 `last_failure_code`를 비운다.
- 정산 하나에 분류 결과는 하나다(영수증 품목이 여러 종류여도 가게 이름 우선).
- `CLASSIFIED`만 카테고리와 출처를 가진다. 대기·처리 중에는 둘 다 비어 있다.
- 출처가 `UNCATEGORIZED`이면 카테고리도 `UNCATEGORIZED`이고, 그 밖의 출처는 `UNCATEGORIZED`일 수 없다.
- 내 카테고리 수정값은 (정산, 회원)마다 하나다. 비회원은 카테고리가 없다.
- 품목·금액이 바뀌어도 다시 분류하지 않는다.

## 3. Scenarios

### Normal flow

1. 생성자가 영수증 "스타벅스 강남점" 정산을 요청한다.
2. 요청 트랜잭션에서 `settlement_categories`에 `PENDING` 작업이 생긴다. 요청 응답은 분류를 기다리지 않는다.
3. 스케줄러가 작업을 선점하고, 규칙 "스타벅스"가 맞아 `CAFE_SNACK`(출처 `RULE`)으로 저장한다.
4. 참여자가 정산 목록·상세를 열면 `myCategory = CAFE_SNACK`이 보인다.
5. 참여자가 `FOOD`로 바꾸면 그 참여자에게만 `FOOD`가 보이고, 생성자는 `CAFE_SNACK` 그대로다.

### Failure flow

- 규칙이 없고 LLM이 미설정: 바로 `UNCATEGORIZED`로 저장한다.
- LLM 호출 실패: `RETRY`로 두고 다시 시도한다. 3회를 다 쓰면 `UNCATEGORIZED`로 끝낸다.
- LLM이 목록 밖 코드나 확신 없음으로 답함: `UNCATEGORIZED`.
- 처리 중 서버가 죽음: 선점 만료(`lease_until`)가 지나면 `RETRY`로 돌리고 다음 폴링이 다시 가져간다. 시도를 다 썼으면 미분류로 끝낸다.
- 정산과 관계없는 회원의 수정 요청: 403. 정산용이 아닌 코드: 400. 삭제된 정산: 404.

### Boundary conditions

- 직접 입력만 있는 정산은 분류 근거가 없으므로 요청 시 바로 `UNCATEGORIZED`로 저장한다(출처 `UNCATEGORIZED`, 시도 0회).
- 정기 정산(STL-011, 담당 외)이 카테고리를 정하지 못하면 출처 `RECURRING`이 아니라 `UNCATEGORIZED`로 저장해야 한다. DB가 `RECURRING` + `UNCATEGORIZED` 조합을 거부한다.
- 대기 중 정산이 소프트 삭제되면 분류하지 않는다. 작업 처리 시 삭제된 정산은 건너뛰고 작업을 정리한다. 처리 중(LLM 응답 대기 등)에 삭제돼도 분류 완료·포기로 저장하기 직전에 정산 행을 공유 잠금으로 다시 읽어, 삭제됐으면 결과 대신 작업을 지운다(#556).
- 분류 전(`PENDING`·`PROCESSING`·`RETRY`)이면 `myCategory`는 `null`이다. 배포 전에 요청된 정산도 `null`이다.
- 분류가 끝나기 전에 회원이 수정하면 수정값이 우선한다.
- 짧은 영문 키워드(예: `CU`)는 단어 단위로만 맞춘다(`CUBE`에는 맞지 않음).
- 내 카테고리는 정산 상태(초안·중단 포함)와 관계없이 바꿀 수 있다. 같은 값을 다시 보내도 결과는 같다(#558).
- 내 카테고리로 `UNCATEGORIZED`(미분류)도 고를 수 있다. 정산용 18개에 들어 있기 때문이다(#558).
- 여러 서버가 동시에 폴링해도 선점으로 한 작업을 한 번만 처리한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetSettlementCategoriesUseCase (#557)
Result: List<SettlementCategoryResult(code, name, description, icon)>

UseCase: ProcessSettlementCategoryJobsUseCase (#556)
Command: limit (1~100, 한 번 폴링에서 처리할 작업 수)
Result: 처리한 작업 수

UseCase: ChangeMySettlementCategoryUseCase (#558)
Input: settlementId(경로) + ChangeMySettlementCategoryCommand(categoryCode)
Result: SettlementCategoryResult (저장된 myCategory)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadCommonCodePort` (기존) | 카테고리 목록 | 카테고리 카탈로그 | `CommonCodePersistenceAdapter` |
| `SettlementCategoryJobStorePort` | 작업 생성(대기·즉시 미분류), 선점, 만료 회수, 결과·재시도·포기·삭제 저장 | 정산 요청, 분류 처리 | `SettlementCategoryJdbcAdapter` (#556) |
| `LoadSettlementCategoryRulePort` | 사용 중인 규칙 | 분류 처리 | `SettlementCategoryRuleJdbcAdapter` (#556) |
| `ClassifySettlementCategoryPort` | 가게 이름·거래명·품목명 → 후보 중 하나 또는 모름 | 분류 처리 | `SettlementCategoryLlmAdapter` (#556) |
| `LoadMySettlementCategoryPort` | 정산 여러 건의 내 카테고리 코드(회원 수정값 → 분류 완료 기본값)를 한 번에 | 목록·상세 | `SettlementUserCategoryJdbcAdapter` (#557) |
| `SaveMySettlementCategoryPort` | (정산, 회원) 수정값 저장. 있으면 덮어쓴다 | 내 카테고리 수정 | `SettlementUserCategoryJdbcAdapter` (#558) |

Port 이름은 능력 기준(ADR 0003)으로 정했다. 작업 Port는 입금 대조 작업 Port(`SettlementDepositReconciliationJobStorePort`)처럼 생성과 선점을 한 Port에 둔다. AI 분류 Port 뒤에 모델을 두므로, 다른 모델로 바꿀 때 Application 코드는 그대로다.

### Application Policy

- 분류 Policy가 정한다: 규칙 매칭(정규화, 단어 경계, 우선순위), 분류 재료 선택(가게 이름·거래명 → 품목명), AI 결과 채택 여부.
- 규칙 정규화: 소문자로 바꾸고 공백을 모두 지운다. DB 유니크 인덱스와 같은 기준이라 `유튜브 프리미엄`과 `유튜브프리미엄`은 같은 규칙이다.
- 여러 규칙이 맞으면 `priority`가 높은 것, 같으면 정규화한 키워드가 긴 것, 그래도 같으면 `id`가 작은 것을 고른다.
- 키워드 매칭: 대소문자를 무시하고 키워드 글자 사이의 공백도 무시한다. 영문·숫자로 시작하거나 끝나는 키워드는 그 앞뒤에 다른 영문·숫자가 붙으면 맞지 않는다(`CU`는 `CU 강남점`, `CU편의점`에 맞고 `CUBE`, `SCUBA`에는 맞지 않음. `카카오T`는 `카카오T 블루`에 맞고 `카카오TALK`에는 맞지 않음).
- 분류 재료: 직접 입력이 아닌 지출(영수증·결제 내역)의 `description`(가게 이름·거래명)을 표시 순서대로 최대 5개, 그 지출들의 품목명을 최대 30개. 각 100자까지 자르고 중복을 뺀다. 직접 입력 지출과 그 품목은 쓰지 않는다.
- 규칙은 가게 이름·거래명에서 먼저 찾고, 하나도 안 맞으면 품목명에서 찾는다. 정산용이 아닌 카테고리를 가리키는 규칙은 무시한다.
- AI 결과 채택: 후보(정산용, 미분류 제외) 안의 코드만 `AI`로 저장한다. 후보 밖 코드나 모름은 실패가 아니라 미분류(`UNCATEGORIZED`)다.
- 재시도: 호출 실패(`PROVIDER_ERROR`)나 읽을 수 없는 답(`INVALID_RESPONSE`)이면 1분 뒤, 그다음 5분 뒤 다시 한다. 3번째도 실패하면 실패 사유와 함께 미분류로 끝낸다. 선점은 2분이다(LLM 읽기 제한 20초).
- Service가 불러서 넘기는 사실: 정산 지출(`description`, `source`), 품목명, 사용 중인 규칙, 공통 코드의 카테고리 목록.

### Web API

```text
GET /api/v2/settlement-categories (#557)
Response: [{ code, name, description, icon }] 정산용 18개(미분류 포함), sort_order 순. description은 null일 수 있다
Authentication: 없음(은행 목록 GET /api/v3/banks와 같이 공개, 2026-10-15 결정)

GET /api/v2/settlements, GET /api/v2/settlements/{id} (#557)
Response: 기존 응답 + myCategory: { code, name, icon } | null
  - 회원이 고른 값 → 분류가 끝난 기본 값 → null(요청 전·분류 전·배포 전 정산)
  - 미분류는 code UNCATEGORIZED. 코드가 정산용 카테고리 목록에 없으면(공통 코드에서 내린 경우) null
  - 목록은 정산 수와 관계없이 쿼리 1번(정산 ID IN 조회), 상세는 1건 조회
  - 비회원 정산 링크 응답에는 넣지 않는다

PUT /api/v2/settlements/{settlementId}/my-category (#558)
Request: { categoryCode } (필수, 50자 이하)
Response: { code, name, icon }
Errors: 400 INVALID_ARGUMENT(빈 값·50자 초과), 400 INVALID_SETTLEMENT_CATEGORY(정산용이 아닌 코드),
        403 SETTLEMENT_ACCESS_DENIED(정산과 관계없는 회원), 404 SETTLEMENT_NOT_FOUND(없는·삭제된 정산)
Authentication: 회원
  - 수정할 수 있는 사람은 정산 상세와 같다: 생성자 또는 회원으로 연결된 참여자
  - 확인 순서: 정산 존재(404) → 접근(403) → 코드(400). 관계없는 회원은 코드와 관계없이 403이다
```

### Persistence and external integrations

- Flyway migration required: Yes
- Planned migration: `V56__create_settlement_category_schema.sql`
  - 공통 코드 그룹 `SETTLEMENT_CATEGORY` 23개(정산용 18 + 확장용 5). `name` 표시명, `description` 세부 예시, `attr1` 아이콘 키, `attr2` 정산 사용 여부, `sort_order` 순서
  - `settlement_categories`: 정산당 1행. 분류 결과 + 작업 상태(V52 `settlement_deposit_reconciliation_jobs`의 상태·선점 제약 방식)
  - `settlement_user_categories`: (정산, 회원) 수정값. 정산·회원 삭제 시 함께 삭제
  - `settlement_category_rules`: 키워드, 카테고리, 우선순위, 사용 여부. 초기값은 명세 STL-008-004 예시 13개. 키워드는 앞뒤 공백 없이 저장하고, 정규화 키워드(소문자·공백 제거)로 중복을 막는다. `UNCATEGORIZED` 규칙은 거부한다
  - 기존 테이블 변경 없음. 카테고리 코드는 은행 코드처럼 외래 키 없이 형식만 검사하고, 존재 여부는 애플리케이션에서 공통 코드로 확인한다
- Migration pull request: [#559](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/559) (V56만 포함, 머지됨). 스키마 테스트는 #556 PR에 들어간다
- Redis: 변경 없음(공통 코드 캐시는 기존 설정 사용)
- External API: AI 분류는 AI 비서와 같은 LLM 설정(`TREASURER_LLM_PROVIDER`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_REASONING_EFFORT`)과 RestClient를 쓴다. 보내는 값은 가게 이름·거래명·품목명과 카테고리 목록뿐이고 회원 이름·금액·계좌는 넣지 않는다. 결제 내역 지출의 거래명은 은행 적요 그대로라 이체 상대 이름이 들어 있을 수 있다(출금 거래도 지출로 가져올 수 있음). 이름만 있으면 LLM이 모름으로 답해 미분류가 된다. LLM이 설정되지 않으면 규칙만 쓰고 나머지는 미분류다
- 설정: `howmuch.settlement.category-classification.enabled`(기본 `true`), `poll-interval`(기본 5초), `batch-size`(기본 10). 환경 변수는 `SETTLEMENT_CATEGORY_CLASSIFICATION_*`. 테스트 스크립트는 다른 폴러처럼 끈다

## 5. Design decisions

### Business logic

- 카테고리 목록은 공통 코드로 관리한다. 명세가 "서버 공통 코드로 관리, 앱에 고정하지 않음"을 요구하고, 은행 목록이 같은 선례다.
- 아이콘은 아이콘 키(`food`, `cafe-snack` 등)로 내려주고 앱이 아이콘 이미지에 연결한다(2026-10-15 결정).
- 기본 카테고리와 내 카테고리를 분리한다. 한 사람의 수정이 다른 참여자의 리캡을 바꾸지 않게 하기 위해서다.
- 내 카테고리로 `UNCATEGORIZED`를 고를 수 있다(2026-10-15 결정). 카테고리 목록 API가 주는 18개를 그대로 선택지로 쓰면 되고, 잘못 고른 값을 미분류로 되돌릴 수 있다.
- 접근 규칙은 정산 상세와 같지만 공유 Policy로 빼지 않는다. 생성자 확인과 연결된 참여자 확인 두 줄이라, 상세 서비스를 고치는 리팩터링은 이 이슈 범위에 넣지 않는다.

### Technology and patterns

- Constraint to solve: 분류(특히 LLM)가 정산 요청을 느리게 하거나 실패시키면 안 된다.
- Selected approach: DB 작업 행 + 폴링 스케줄러 + 선점(lease). 입금 대조 작업(V52)과 같은 방식이다.
- Alternatives considered: 요청 트랜잭션 안 동기 분류(지연·장애 전파), 별도 메시지 큐(팀 선례 없음).
- Trade-offs and limits: 분류 결과가 몇 초 늦게 보인다. 그동안 `myCategory`는 `null`이다.

## 6. Impact surface

- 정산 요청 흐름에 분류 작업 생성 추가(#556). 같은 요청 트랜잭션에서 저장하므로 요청이 실패하면 작업도 남지 않는다. 요청 응답에는 LLM 호출이 없다
- 분류 대기 중이나 처리 중에 정산이 삭제(소프트 삭제)되면 작업 행을 지운다. 끝 상태 저장 직전 정산 행을 `FOR SHARE`로 읽으므로 그동안 정산 삭제는 잠깐 기다린다(#556)
- 새 스케줄러 스레드 1개(`settlement-category-classification-`)
- 정산 목록·상세 응답에 nullable 필드 추가(#557). 기존 필드 변경 없음
- 새 엔드포인트 2개(#557, #558). 카테고리 목록 API는 `SecurityConfig`에 GET 공개 한 줄을 추가한다(#557)
- `SwaggerConfig`에 OpenAPI 후처리(`nullableReferenceCustomizer`)를 추가한다. `@Schema(nullable = true)` 객체 속성이 OAS 3.1에서 `$ref` 옆 `type: null`(null 불허)로 나오는 것을 `anyOf: [참조, null]`로 바꾼다. 현재 해당 속성은 `myCategory` 두 곳뿐이다(#557)
- `SettlementListItemResult`, `SettlementDetailResult`에 `myCategory`를 추가하고 기존 인자 수의 생성자를 남긴다(기존 호출·테스트 유지)
- 정산 삭제 시 카테고리 행은 FK로 함께 지워진다
- 내 카테고리 수정은 `settlement_user_categories`에 (정산, 회원) 기준 upsert만 한다. 정산·분류 작업 행은 건드리지 않고 마이그레이션도 없다(#558)
- 수정은 정산 행을 잠그지 않는다. 접근 확인 직후 정산이 삭제되거나 참여자가 빠지면 그 요청은 저장될 수 있지만, 삭제된·접근할 수 없는 정산의 값은 어떤 조회에도 나오지 않아 그대로 둔다(#558)

## 7. Implementation plan

1. #555 마이그레이션 PR #559: V56만 포함(머지됨)
2. #556 자동 분류: 작업 생성, 분류 Policy, 규칙·AI 어댑터, 스케줄러
3. #557 카테고리 목록 API, 목록·상세 `myCategory`
4. #558 내 카테고리 수정 API

### Expected commit boundaries (#558)

- `docs(settlement)`: Feature Plan 갱신
- `feat(settlement)`: 내 카테고리 수정 UseCase·서비스, 저장 Port와 upsert, PUT API·문서, 카테고리 상태 코드
- `test(settlement)`: 서비스·저장소·컨트롤러·HTTP·OpenAPI 테스트
- `docs(settlement)`: 검증 결과, Feature Plan을 `completed`로 이동

### Expected commit boundaries (#557)

- `docs(settlement)`: Feature Plan 갱신
- `feat(settlement)`: 카테고리 목록 API, 내 카테고리 조회 Port·어댑터, 목록·상세 `myCategory`, 공개 경로
- `test(settlement)`: 카탈로그·조회·서비스·컨트롤러·OpenAPI 테스트
- `docs(settlement)`: 검증 결과

### Expected commit boundaries (#556)

- `docs(settlement)`: Feature Plan
- `feat(settlement)`: 분류 작업 생성, 분류 Policy, 워커, 저장소·규칙·LLM 어댑터, 스케줄러와 설정
- `test(settlement)`: #559에서 옮긴 스키마 테스트, 분류 테스트
- `docs(settlement)`: 검증 결과

## 8. Verification

### Commands

```bash
docker compose -f compose.test.yml up -d
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 bash scripts/test.sh test --tests '*SettlementCategory*' --tests '*SettlementRequestServiceTest' --tests '*SettlementListServiceTest' --tests '*SettlementDetailServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementOpenApiIntegrationTest' --tests '*SettlementUserCategory*' --tests '*MySettlementCategory*' --tests '*SettlementMyCategory*'
```

### Completion criteria

- [x] V56이 빈 DB와 V55까지 적용된 DB에서 적용된다
- [x] 공통 코드 23개, 정산용 18개가 순서대로 조회된다
- [x] 분류 작업 상태별 필수 값이 어긋난 행을 DB가 거부한다
- [x] 정상 생명주기(대기 → 처리 → 재시도 → 처리 → 선점 만료 회수 → 분류 완료)를 DB가 허용한다
- [x] 정규화 기준 중복 키워드, 앞뒤 공백 키워드, `UNCATEGORIZED` 규칙을 DB가 거부한다
- [x] 정산 삭제 시 카테고리 행이 함께 지워진다
- [x] 정산 요청 시 분류 작업이 함께 저장되고, 직접 입력만 있으면 바로 미분류로 저장된다
- [x] 규칙 키워드가 있는 가게 이름·거래명·품목명은 규칙으로, 없으면 AI로, 실패하면 미분류로 저장된다
- [x] 짧은 영문 키워드가 다른 단어 안에서 오탐되지 않는다
- [x] 여러 워커가 동시에 선점해도 한 작업은 한 번만 가져간다
- [x] LLM 미설정·거절·잘못된 응답에서 정산 흐름이 실패하지 않는다
- [x] 늦게 끝난 처리는 다른 워커가 회수한 작업의 결과를 덮어쓰지 않는다
- [x] 카테고리 목록 API가 정산용 18개를 순서대로 주고, 확장용 5개는 주지 않는다. 로그인 없이 호출된다
- [x] 목록·상세의 `myCategory`가 수정값 → 자동 분류 값 → null 순서로 정해진다
- [x] 같은 정산을 다른 회원이 조회하면 각자의 카테고리가 보인다
- [x] OpenAPI 문서에 `myCategory`가 nullable로 표시된다
- [x] 생성자와 참여자가 각자 바꾼 값이 서로의 `myCategory`에 영향을 주지 않는다
- [x] 정산과 관계없는 회원은 수정할 수 없다(403), 없는·삭제된 정산은 404
- [x] 확장용·없는 코드는 400, `UNCATEGORIZED`는 저장된다
- [x] 같은 값을 다시 보내도 결과가 같다
- [x] 수정 후 정산 목록·상세에 바꾼 값이 보인다

### Results

#### #555 저장 구조 (PR #559)

2026-10-15, 로컬(macOS, compose.test.yml PostgreSQL 18 + Testcontainers PostgreSQL 16.14)

- `SettlementCategorySchemaIntegrationTest` 통과: 빈 테스트 DB에 V1~V56 적용 후 시드, 상태별 제약, 정상 생명주기(선점 만료 회수 포함), 시도를 다 쓰기 전 실패 종료 거부, 규칙 키워드 정규화, 정산 삭제 연쇄 삭제
- PR #559 Codex 리뷰 반영: 실패 사유를 남긴 종료는 `attempt_count = max_attempts`만 허용(`ck_settlement_categories_failure_exhausted`), 선점 만료 작업은 V52처럼 `RETRY`로 돌려 다음 선점에서 시도 횟수를 올린다
- `SettlementCategoryMigrationTest` 1/1 통과: V55까지 적용하고 정산을 넣은 DB에 V56 적용. 기존 정산 유지, 분류 행 없음, 은행 공통 코드 수 변화 없음, 카테고리 23개·규칙 13개
- 리뷰 반영 후 V56 내용을 바꿨기 때문에, 예전 V56이 적용된 로컬 테스트 DB는 체크섬 불일치로 시작하지 않았다. `docker compose -f compose.test.yml down -v`로 테스트 DB를 새로 만든 뒤 위 결과를 얻었다. 머지 후에는 V56을 고치지 않고 새 버전으로 바꾼다
- 애플리케이션 동작(작업 생성, 선점, 분류, API)은 #556~#558에서 검증한다

#### #556 자동 분류

2026-10-15, 로컬(macOS, compose.test.yml PostgreSQL 18 · Redis 8 + Testcontainers PostgreSQL 16.14). `build/test-results`의 XML에서 클래스별로 확인한 숫자다.

| 테스트 | 건수 | 확인한 것 |
|---|---|---|
| `SettlementCategoryClassificationPolicyTest` | 16 | 영문 키워드 단어 경계(`CU`/`CUBE`, `GS25`/`GS250`, `카카오T`/`카카오TALK`), 대소문자·유니코드 공백 무시, 특수 문자·이모지 키워드, 가게 이름 우선·품목명 대체, 동점 처리, 분류 재료(직접 입력 제외, 순서, 중복, 개수·길이 제한, 제어 문자), 후보 필터, AI 결과 채택, 재시도 간격 |
| `SettlementCategoryClassificationWorkerTest` | 11 | 규칙 → AI → 미분류 순서, 후보 밖·모름은 미분류, LLM 미설정, 실패 재시도·마지막 시도 포기, 예외, 삭제된 정산, 재료 없음, 품목명 규칙, 처리 개수 |
| `SettlementCategoryLlmAdapterTest` | 7 | 미설정 시 호출 없음, 실제 OpenAI 요청 형식(`json_object`, temperature 없음)과 응답 읽기, 429 거절, 읽을 수 없는 응답, 답 형식, 프롬프트 내용 |
| `SettlementCategoryJdbcAdapterIntegrationTest` | 12 | 작업 생성 멱등, 대기 시각 전 선점 안 함, 10개 스레드 동시 선점에서 작업 5개를 한 번씩만 가져감, 결과 저장 시 실패 사유 비움, 선점 만료·다른 토큰의 결과 거부, 만료 회수(재시도·마지막 시도 미분류), 포기·삭제, 처리 중 정산 삭제 시 분류 완료·포기 대신 작업 삭제, 살아 있는 정산은 그대로 저장, 규칙 조회 |
| `SettlementCategoryClassificationIntegrationTest` | 4 | 폴러를 켠 설정(입금 대조 폴러와 함께)으로 컨텍스트가 뜨고, 실제 규칙·공통 코드로 `스타벅스` → `CAFE_SNACK`(RULE), `CUBE` → AI, AI 실패 → 재시도, 삭제된 정산 → 작업 삭제 |
| `SettlementRequestServiceTest` | 6 | 영수증 지출이 있으면 대기 작업 저장, 직접 입력만 있으면 미분류 저장(기존 4건 포함) |
| `SettlementCategorySchemaIntegrationTest`, `SettlementCategoryMigrationTest` | 21, 1 | #559에서 옮긴 스키마 테스트 |

- 위 8개 클래스 78건 실행, 실패 0(PR #560 Codex 리뷰 반영 후 다시 실행).
- 전체 `scripts/test.sh test`(2077건)를 먼저 돌렸을 때 7건이 실패했다. 모두 결과 저장 SQL에서 텍스트 블록 끝 공백이 잘려 `WHERE`와 조건이 붙은 문제였다(`WHEREsettlement_id`). 조건 문자열을 고친 뒤 관련 클래스를 다시 돌려 위 결과를 얻었다. 나머지 2070건은 첫 실행에서 통과했다.
- 독립 리뷰(작업을 보지 않은 별도 에이전트): P1 없음. 반영: 켜진 설정 컨텍스트 테스트, 유니코드 공백·제어 문자 처리, 글자 단위 자르기, 응답 봉투 파싱 실패를 잘못된 응답으로 분류, 후보 코드 형식 필터, 선점 상실 로그, 과거 시각 테스트 행 정리. 반영하지 않음: 401·400 같은 재시도해도 소용없는 4xx도 3번 시도한다(키 설정 오류는 서버 로그로 드러남). 결제 내역 거래명에 이체 상대 이름이 들어갈 수 있다는 점은 위 External API에 적었다.
- 실제 Luna 호출과 앱 화면 확인은 개발 서버 배포 뒤에 한다.
- PR #560 Codex 리뷰 반영: LLM 응답을 기다리는 사이 정산이 소프트 삭제되면 분류 완료로 남아 지워지지 않던 문제. 분류 완료·포기 저장 직전에 정산 행을 공유 잠금으로 읽고, 삭제됐으면 작업을 지운다. 재시도로 돌리는 경우는 다음 폴링에서 삭제를 보고 지우므로 그대로 둔다.

#### #557 카테고리 목록 API와 내 카테고리

2026-10-15, 로컬(macOS, compose.test.yml). XML에서 클래스별로 확인한 숫자다.

- 전체 `scripts/test.sh test`(2090건): 처음 실행에서 새 테스트 2건만 실패, 기존 테스트는 모두 통과.
  - `SettlementOpenApiIntegrationTest`: `myCategory`가 `{"type":"null","$ref":...}`로 생성돼 null을 허용하지 않는 스키마였다. `SwaggerConfig` 후처리로 `anyOf: [참조, null]`로 고쳤다.
  - `SettlementMyCategoryHttpIntegrationTest`: 생성자 상세는 받는 계좌 스냅샷을 복호화하는데 테스트용 가짜 계좌라 404였다(카테고리와 무관). 상세 확인은 참여자 두 명(바꾼 회원 `FOOD`, 안 바꾼 회원 `CAFE_SNACK`)으로 바꿨다.
- 고친 뒤 관련 18개 클래스 180건 실행, 실패 0: `SettlementOpenApiIntegrationTest` 3, `GroupOpenApiIntegrationTest` 1, `SettlementMyCategoryHttpIntegrationTest` 1, `SettlementCategoryApiIntegrationTest` 1, `SettlementUserCategoryJdbcAdapterIntegrationTest` 1, `SettlementCategoryCatalogTest` 1, `MySettlementCategoryReaderTest` 3, `SettlementControllerTest` 39, `SettlementListServiceTest` 23, `SettlementDetailServiceTest` 29, `SettlementRequestServiceTest` 6, #556 분류·스키마 테스트 72.
- 독립 리뷰: P1 없음. 반영: OpenAPI nullable 참조 검증을 엄격하게(위 수정으로 이어짐), 정산 ID를 배열 바인드 하나로(`= ANY(?)`), 실제 HTTP로 회원별 카테고리 확인. 남김: 공통 코드의 아이콘 키(`attr1`)를 비우면 필수 필드 `icon`이 비어 나간다(시드는 모두 채워져 있음).

#### #558 내 카테고리 수정

2026-10-15, 로컬(macOS, compose.test.yml PostgreSQL · Redis + Testcontainers). XML에서 클래스별로 확인한 숫자다.

| 테스트 | 건수 | 확인한 것 |
|---|---|---|
| `MySettlementCategoryServiceTest` | 7 | 생성자는 참여자 목록 없이 저장, 연결된 참여자의 `UNCATEGORIZED` 저장, 관계없는 회원은 코드가 틀려도 403(카테고리 목록·저장 호출 없음), 없는 정산 404, 확장용(`TRANSFER`)·없는 코드·소문자(`food`) 400, 저장 시각 UTC |
| `SettlementUserCategoryJdbcAdapterIntegrationTest` | 3 | 처음 저장 → 덮어쓰기(생성 시각 유지, 수정 시각 갱신), 같은 값 재전송, 다른 회원 행 그대로, 조회 Port가 새 값을 읽음, 시계가 뒤로 가도 `created_at <= updated_at` 유지(기존 1건 포함) |
| `SettlementMyCategoryControllerTest` | 7 | 응답 `{code, name, icon}`(설명 없음), 코드 누락·빈 값·공백·51자, 형식이 틀린 정산 ID, 깨진 JSON은 서비스 호출 없이 `INVALID_ARGUMENT`, `INVALID_SETTLEMENT_CATEGORY` 400 |
| `SettlementMyCategoryHttpIntegrationTest` | 3 | 실제 HTTP: 참여자 수정값이 그 참여자의 목록·상세에만 보이고 다른 참여자·생성자는 자동 분류 값, 생성자도 따로 수정, 미분류로 되돌리기, 같은 값 재전송. 관계없는 회원 403, `TRANSFER`·`food` 400, 삭제된·없는 정산 404, 토큰 없음 401, 거절된 요청은 행을 남기지 않음, 초안 정산도 수정 가능(기존 1건 포함) |
| `SettlementOpenApiIntegrationTest` | 3 | PUT 경로, `bearerAuth`, 요청·응답 스키마, 400·401·403·404 응답, `categoryCode` 필수(기존 2건 포함) |

- 전체 `scripts/test.sh test`: 2107건, 실패 0, 건너뜀 1(로컬 평가 전용 `TreasurerGuideLiveEvaluation`). JavaScript 테스트 포함 종료 코드 0.
- 처음 관련 테스트를 돌릴 때 `SettlementCategoryMigrationTest`가 Testcontainers Docker 소켓을 찾지 못해 실패했다(환경 문제). 위 Commands의 `DOCKER_HOST` 등을 넣고 다시 돌려 1/1 통과했고, 전체 실행도 같은 설정으로 했다.
- 독립 리뷰(작업을 보지 않은 별도 에이전트): P1 없음. 반영: 어댑터 클래스 주석, OpenAPI 400 설명에 형식이 틀린 정산 ID·JSON 포함, 해당 컨트롤러 테스트 추가(전체 실행 뒤 반영했으므로 바뀐 4개 클래스 16건을 다시 돌려 통과). 남김: 접근 확인 직후 정산 삭제·참여자 제외와 겹치는 요청은 저장될 수 있으나 보이지 않으므로 그대로 둔다(위 Impact surface), 접근 규칙이 정산 상세와 중복된다(위 Design decisions).
- 실제 앱의 카테고리 선택 시트 호출은 개발 서버 배포 뒤에 확인한다.
- PR #563 Codex 리뷰 반영: 공통 코드 관리 화면은 코드 형식을 제한하지 않아, 관리자가 `Food`·`CAFE-SNACK`처럼 저장 형식(`^[A-Z][A-Z_]{0,49}$`)이 아닌 정산용 코드를 넣으면 수정 API가 DB 제약 위반으로 500을 냈다. 카테고리 카탈로그가 저장할 수 없는 형식의 코드를 빼도록 했다. 목록 API에 나오지 않고, 수정 API는 `INVALID_SETTLEMENT_CATEGORY` 400이다(분류 Policy는 원래 같은 형식으로 후보를 거른다). `SettlementCategoryCatalogTest` 2건(형식 경계: 소문자, 숫자, 하이픈, 공백, 밑줄 시작, 50자·51자) 추가 후 카테고리 관련 17개 클래스 152건 실패 0.
