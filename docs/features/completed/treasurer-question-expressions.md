# Feature Plan: AI 총무 자연어 표현 다양성 지원 및 정산 제목 오추출 수정

- Status: Completed
- Last updated: 2026-09-10
- Related issue: [#318](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/318), [#317](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/317) (선행)
- Related ADR: N/A

## 1. Purpose

### Problem

**표현 인식이 좁다.** `InquiryParser`의 의도 키워드는 고정 문자열 목록이다. `SEND_KEYWORDS`에 `보낼`·`내야`·`보내야`는 있으나 `줘야`·`안 보낸`·`부담`이 없고, `RECEIVE_KEYWORDS`에 `들어올`·`남았`이 없다. 같은 뜻의 구어체가 분류되지 않는다.

**정산 제목을 잘못 뽑는다.** `titleHint()`는 `stripQueryGrammar()`로 문법 어휘를 문자열에서 제거한 잔여물에 `(…모임|…정산)` 정규식을 적용한다. 제거가 **단어 경계를 고려하지 않아** 단어 중간을 잘라낸다.

```text
"보낼 정산이랑 받을 정산은 뭐가 달라?"
  → 공백 제거   "보낼정산이랑받을정산은뭐가달라?"
  → "낼정산" 제거 (SEND_KEYWORDS)  "보이랑받을정산은뭐가달라?"
  → "받을" 제거                    "보이랑정산은뭐가달라?"
  → 정규식 매치                     titleHint = "보이랑정산"
```

이 힌트로 `matchesTitle()` 필터가 걸려 0건이 조회되고, 사용자는 `"보낼 정산은 없어요"`라는 **단정형 오답**을 받는다. 질문이 차단되는 것보다 영향이 크다.

### Why now

#317이 관련성 판정을 분리해 "무관한 질문"과 "인식하지 못한 질문"을 구분했다. 이제 인식 범위 자체를 넓힐 차례다. #317 완료 기록에 남긴 한계 두 가지도 이 작업 범위다.

### Success criteria

- [x] 아래 구어체 표현이 의미에 맞는 의도로 분류된다.
- [x] 문법 잔여물에서 정산 제목이 추출되지 않는다.
- [x] 제목 힌트를 신뢰할 수 없으면 필터를 적용하지 않고 전체 범위로 조회한다.
- [x] 제목 힌트는 데이터 조회 의도일 때만 추출한다.
- [x] 기존 `InquiryParserTest`가 단언하는 분류 결과와 제목이 모두 유지된다.
- [x] 서비스와 무관한 질문이 표현 확장 때문에 통과하지 않는다.

### Out of scope

- 서비스 이용 안내 질문에 실제로 답하는 것 — #319
- 형태소 분석기 도입. 이번에도 문자열 규칙으로 해결한다
- LLM 보조 판정
- `InquiryIntent` 확장과 응답 계약 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `treasurer` 분석 계층 전부. 정산 조회·집계는 건드리지 않는다
- Reason a new domain is required: 없음
- Relationships: 없음. 질문 문자열 해석만 바뀐다

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 의도 표현 | 같은 의도를 가리키는 여러 자연어 표현의 묶음 | `InquiryParser`의 의미 단위 상수 |
| 제목 힌트 | 질문에서 뽑은 정산·모임 이름 후보 | `ParsedInquiry.titleHint` |
| 문법 구간 | 질문에서 의도·기간·조사에 해당해 제목이 될 수 없는 문자 범위 | `grammarMask` |

### State and invariants

- 상태 전이 없음. 읽기 전용 질의다
- 제목 힌트는 원문에 연속으로 존재하는 문자열이어야 한다. 문법 어휘를 제거한 잔여물을 이어 붙인 결과여서는 안 된다

## 3. Scenarios

### Normal flow

1. 사용자가 구어체로 묻는다. 예: `"누구한테 돈 줘야 해?"`
2. `InquiryParser`가 의미 단위 표현으로 `SEND_SETTLEMENTS`를 판정한다
3. 데이터 조회 의도이므로 제목 힌트를 추출한다. 신뢰할 수 없으면 `null`로 두고 전체 범위를 조회한다

### Failure flow

- 표현을 인식하지 못하면 `UNKNOWN`이 되고 #317의 관련성 판정으로 넘어간다. 기존 동작이다

### Boundary conditions

- 제목 후보의 앞부분이 문법 구간에 걸치면 제목으로 쓰지 않는다
- 제목 후보의 앞부분이 한 글자이거나 의문사면 제목으로 쓰지 않는다

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase / Command / Result / Web DTO: 전부 변경 없음
```

### Output Ports

변경 없음. 새 Port를 만들지 않는다.

### Application Policy

- `InquiryParser`가 소유하는 판단: 의도 표현 해석과 제목 힌트 추출
- Service가 넘기는 사실: 질문 문자열
- 아직 구현하지 않는 입력: 사용자의 실제 모임·정산 이름 목록. 이름을 대조하면 제목 판정이 정확해지지만 조회가 한 번 늘어난다

### Web API

변경 없음.

### Persistence and external integrations

- Database, Flyway, Redis, 외부 API, 환경 변수: 전부 변경 없음
- Flyway migration required (`Yes` / `No`): **No**

## 5. Design decisions

### 표현을 의미 단위로 묶는다

의도별 평면 목록을 뜻으로 나눈 상수로 재구성한다. 목록을 늘리기만 하면 같은 문제가 반복된다.

```text
SEND     보낼 돈 지칭 · 의무 표현 · 미납 표현
RECEIVE  받을 돈 지칭 · 입금 예정 표현
STATUS   진행 상태 표현 · 미완료 표현
EXPENSE  지출 표현 · 사용 금액 표현
```

### 일상어 표현은 정산 맥락이 있을 때만 인정한다

`남았`·`남은`·`처리해야`·`들어와` 같은 표현은 정산 전용 단어가 아니다. 단독으로 매칭하면 `"밥 남았어?"`, `"언제 들어와?"` 가 정산 조회를 받는다. 무관한 질문이 단정형 답을 받는 쪽이 차단되는 쪽보다 나쁘다.

키워드를 두 단계로 나눈다.

```text
강함  단독으로 뜻이 분명하다            보낼 · 받을 · 지출 · 진행상태
약함  맥락 단어가 함께 있어야 인정한다   줘야 · 들어와 · 썼 · 남았 · 처리해야
맥락  정산 · 모임 · 돈 · 금액 · 회비 · 입금 · 송금 · 이체 · 계좌
```

지출 질문만 `얼마`를 맥락에 추가한다. `"이번 달에 얼마 썼어?"` 가 대표 질문인데 다른 맥락 단어가 없다.

맥락 단어는 **어절 단위로** 본다. 부분 문자열로 보면 `"돈까스 남았어?"` 의 `돈` 이 맥락이 되어 정산 조회가 나간다. 어절 앞에서 시작하고 조사나 어미로만 이어져야 맥락으로 인정한다. 어절 끝의 문장부호는 세지 않는다.

안내 키워드(`ocr`, `영수증`)는 강한 의도에는 지지만 약한 표현보다는 앞에 둔다. `"영수증 정산은 어떻게 처리해야 해?"` 는 정산 상태가 아니라 안내 질문이다. 기존 규칙(안내는 지출·상태 의도가 없을 때만)은 강한 의도에 한해 그대로다.

### 제목 추출을 잔여물 방식에서 문법 구간 방식으로 바꾼다

문자열을 잘라내는 대신 **문법 어휘가 차지한 문자 범위를 표시**하고, `모임`·`정산` 앞에서 문법 구간에 닿을 때까지만 거슬러 올라간다.

```text
"진행중인여행모임정산상태는"
 └문법┘                        진행중인 = 문법 구간
        여행모임               ← 문법 구간 직전까지만 후보
```

이렇게 하면 후보가 항상 원문의 연속 부분 문자열이라 `보이랑정산` 같은 조합이 나오지 않는다.

문법 구간만으로는 부족하다. 키워드 목록에 없는 조사가 사이에 끼면 그대로 통과한다.

```text
"이번주에는정산있어"
 └문법┘                    이번주 = 문법 구간
       에는                 ← 목록에 없는 조사. 여기서 멈춘다
       에는정산             ← 원문에 없는 제목
```

그래서 후보에 세 가지를 더 요구한다.

- 후보가 **어절 첫 글자**에서 시작해야 한다. `에는`은 `주에는`의 중간이라 버려진다
- 후보의 **마지막 어절**이 조사·접속 조각이면 버린다 (`중에정산`, `건데정산`). 어절 단위로 정확히 비교한다. 부분 문자열로 보면 `이전모임` 같은 제목까지 걸린다
- 앞부분이 의문사·부사면 버린다 (`왜아직정산`)
- 마지막 어절이 지시·소유 대명사면 버린다 (`내정산`, `우리모임`). `내 정산`의 `내`는 제목이 아니라 소유격이다

따옴표나 괄호 같은 문장부호 다음도 어절 시작으로 본다. `"여행 모임"에서 받을 돈 있어?` 의 제목을 살린다.

앞부분 최소 길이는 한 글자다. `봄 정산` 같은 제목이 실제로 있다. 두 글자로 올려 조사를 막으려던 시도는 이 제목들을 함께 잃었다.

소문자 변환이 문자열 길이를 바꾸면 위치를 신뢰할 수 없으므로 제목을 뽑지 않는다.

### 제목 힌트는 데이터 조회 의도일 때만 쓴다

설명형 질문에 제목 필터가 걸리면 0건 오답이 된다.

### Alternatives considered

- 형태소 분석기 도입: 정확하지만 의존성과 성능 비용이 크고, 이번 문제는 규칙으로 해결된다
- 사용자의 실제 모임·정산 이름과 대조: 가장 정확하지만 조회가 늘고 이번 범위를 넘는다

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음 | `TreasurerAnswerWebResponse` |
| Application | 표현 상수를 강함/약함으로 재구성, 제목 추출 교체 | `InquiryParser` |
| Domain | 없음 | `InquiryIntent`, `PersonalSettlement` |
| Persistence | 없음 | - |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | 본 문서, `ai-treasurer.md`의 제목 힌트 서술 | `docs/features/` |
| Test | `AskTreasurerServiceTest`의 되묻기 목록 축소, 방향 단언 추가 | `AskTreasurerServiceTest` |

Existing behavior that must remain unchanged:

- 기존 `InquiryParserTest` 11건이 단언하는 의도·기간·제목
- 보낼/받을 키워드가 함께 있을 때 먼저 나온 쪽을 쓰는 규칙
- #317의 무관 질문 범위 안내 5건과 서비스 이용 안내 되묻기 5건
  - 단, #317이 되묻기로 두었던 구어체 정산 질문 6건은 이번 작업으로 조회 응답이 된다. #317 완료 기록이 이 표현들을 #318 범위로 남겼으므로 의도된 변경이다.
- 정산 조회·금액 계산·응답 계약

## 7. Implementation plan

- [ ] Migration-only pull request — **N/A**
- [ ] UseCase and Application Command/Result — **N/A**
- [ ] Required Output Ports — **N/A**
- [ ] Domain models and rules — **N/A**
- [x] Application Policies — `InquiryParser` 표현 재구성과 제목 추출 교체
- [ ] Application Service — **N/A**
- [ ] Output Adapters — **N/A**
- [ ] Web DTOs, Controllers, Swagger — **N/A**
- [ ] Infra configuration — **N/A**
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): 질문 표현 확장과 제목 오추출 수정 설계 수립
fix(treasurer): 문법 잔여물이 정산 제목으로 추출되지 않게 한다
feat(treasurer): 정산 질문의 구어체 표현을 의미 단위로 인식한다
test(treasurer): 표현 다양성과 제목 추출 회귀를 검증한다
test(treasurer): 구어체 정산 질문을 되묻기 대신 조회로 검증한다
fix(treasurer): 정산 제목의 원문 대소문자를 유지한다
fix(treasurer): 일상어와 조사 조각이 정산 답변을 만들지 않게 한다
test(treasurer): 리뷰에서 드러난 오분류를 회귀로 고정한다
docs(treasurer): 표현 확장과 제목 추출 검증 결과 기록
fix(treasurer): 맥락 단어를 어절 단위로 보고 안내 의도를 지킨다
docs(treasurer): 표현 확장과 제목 추출 검증 결과 기록
```

## 8. Verification

```bash
docker compose -f compose.test.yml up -d
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test \
DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh test --tests '*Treasurer*' --tests '*Inquiry*'
docker compose -f compose.test.yml down -v
```

### Completion criteria

- [x] Purpose의 사용자 결과를 검증했다.
- [x] 정상·실패·경계 흐름을 검증했다.
- [x] 문서와 실제 코드가 일치한다. 같은 주장을 하는 다른 줄이 남아 있지 않은지 확인했다.
- [x] 수행하지 못한 검증과 사유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| treasurer·inquiry 테스트 | 16개 클래스 121건 통과, 실패 0, 건너뜀 0 | `compose.test.yml`(PostgreSQL 18 · Redis 8) + `scripts/test.sh`. `InquiryParser`는 `AskTreasurerService`만 쓰므로 이 필터가 변경 영향 범위를 모두 덮는다 |
| 구어체 표현 분류 | 통과 | `InquiryParserTest`의 SEND·RECEIVE·EXPENSE·STATUS 표현 테스트 4건 |
| 되묻기 → 조회 전환 | 통과 | `AskTreasurerServiceTest.answersColloquialSettlementQuestionInsteadOfClarifying` 6건 |
| 제목 오추출 수정 | 통과 | `"보낼 정산이랑 받을 정산은..."` → `titleHint=null`. 이전 구현은 `"보이랑정산"` |
| 제목은 원문의 연속 부분 문자열 | 통과 | `extractsTitleHintAsContiguousTextOfTheQuestion` |
| 제목의 원문 대소문자 유지 | 통과 | `keepsOriginalLetterCaseInTitleHint` (`"MT 정산"` → `"MT정산"`) |
| 제목 힌트는 조회 의도에서만 | 통과 | `ignoresTitleHintWhenIntentDoesNotFilterByTitle` (GUIDE는 `null`) |
| 기존 분류 결과 유지 | 통과 | `InquiryParserTest` 기존 11건. `AskTreasurerServiceTest`의 질문 13개 모두 의도·기간·제목 동일 |
| 무관 질문 오탐 없음 | 통과 | `guidesScopeForQuestionUnrelatedToService` 5건 유지. `requiresSettlementContextForEverydayWords` 5건(`"밥 남았어?"`, `"언제 들어와?"` 등) |
| 조사 조각이 제목이 되지 않음 | 통과 | `doesNotTreatParticleLeftoverAsTitle` 4건 |
| 돈 방향 판정 | 통과 | `readsUnpaidByOthersAsMoneyComingToMe` 3건. 서비스 단에서 SEND·RECEIVE 정산을 하나씩 두고 어느 쪽이 나오는지 단언 |
| 제목 길이 상·하한 | 통과 | `extractsSingleCharacterTitle`, `limitsTitlePrefixLength` (20자 채택, 21자 거절) |
| 안내 의도 유지 | 통과 | `keepsGuideIntentWhenOnlyEverydayVerbsCompete` 4건. 강한 의도(`"이번 달 지출 영수증"`)는 여전히 안내를 이긴다 |
| 맥락 단어 어절 판정 | 통과 | `readsContextWordsAsWholeWords` (`"돈까스 남았어?"` → UNKNOWN) |
| 문장부호 제목 경계 | 통과 | `acceptsPunctuationAsTitleBoundary` (`"여행 모임"에서` → `여행모임`) |
| 대명사가 제목이 되지 않음 | 통과 | `doesNotTreatPronounAsTitle` (`"내 정산"`, `"우리 모임"`, `"이 정산"`) |
| 응답 계약 무변경 | 통과 | `TreasurerControllerTest` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-10 | 원인 분석, 설계 문서 작성 | 제목 추출 교체 | 표현을 넓히면 무관 질문 오탐이 늘 수 있다 |
| 2026-09-10 | 제목 추출 교체, 표현 재구성, 회귀 테스트, 전체 검증 | PR 생성 | 첫 검증에서 #317 되묻기 6건이 깨졌다. 계획서가 두 작업의 겹침을 확인하지 않은 탓이다 |
| 2026-09-10 | 재리뷰로 제목 대소문자 결함 1건 수정 | PR 생성 | 제목이 필터뿐 아니라 안내 문구에도 쓰이는 것을 처음에 놓쳤다 |
| 2026-09-10 | 작업을 보지 않은 리뷰어에게 검사시켜 오분류 3건 수정 | PR 생성 | 자체 리뷰가 자기 코드의 쉬운 경로만 밟았다. 통과한 110건이 결함을 하나도 잡지 못했다 |
| 2026-09-10 | PR 생성, Codex 지적 4건과 추가 발견 1건 수정 | 병합 | 통과한 118건도 소유격 `"내 정산"` 오추출을 잡지 못했다. 제목을 단언하는 테스트가 그 문장을 밟지 않았다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-10 | 제목 추출을 문법 구간 방식으로 바꾼다 | 잔여물 방식이 단어 중간을 잘라 원문에 없는 제목을 만든다 | 후보가 항상 원문의 연속 부분 문자열이 된다 |
| 2026-09-10 | `부담`·`지불`은 어미까지 포함한 형태로만 넣는다 | 단독 명사는 `"부담 없이 써도 돼?"`를 SEND로 잘못 분류했다 | 어미가 다른 표현은 놓친다 |
| 2026-09-10 | 맥락 단어는 어절 단위로 본다 | 부분 문자열로 보면 `"돈까스"` 의 `돈` 이 맥락이 된다 | 맥락 단어 뒤 조사·어미 목록을 손으로 관리해야 한다 |
| 2026-09-10 | 안내 키워드를 강한 의도 뒤, 약한 표현 앞에 둔다 | 약한 일상어가 안내 질문을 가로챘다 | 기존 규칙(안내는 지출·상태 의도가 없을 때만)은 강한 의도에 한해 유지된다 |
| 2026-09-10 | 문장부호 다음도 어절 시작으로 본다 | 따옴표·괄호로 감싼 제목이 버려졌다 | 부호가 제목 앞에 붙어도 살린다 |
| 2026-09-10 | 지시·소유 대명사는 제목이 될 수 없다 | `"내 정산"` 의 `내` 가 제목으로 잡혀 조회가 0건이 됐다 | 대명사로 시작하는 실제 제목은 못 잡는다 |
| 2026-09-10 | 약한 표현은 정산 맥락 단어가 함께 있을 때만 인정한다 | 일상어가 단독 매칭되어 무관한 질문이 정산 조회를 받았다 | 맥락 단어가 없는 관련 질문은 놓친다. `"이번 달에 처리해야 하는 게 뭐야?"` 가 그 예다 |
| 2026-09-10 | 제목 후보는 어절 첫 글자에서 시작해야 한다 | 문법 구간 규칙만으로는 목록에 없는 조사를 막지 못한다 | 어절 경계가 없는 붙여쓴 질문은 제목을 못 뽑는다 |
| 2026-09-10 | 마지막 어절이 조사·접속 조각이면 버린다 | `중에`, `건데` 처럼 온전한 어절인 조사는 어절 규칙으로 못 막는다 | 손으로 적은 목록이라 여기 없는 조사는 샌다 |
| 2026-09-10 | 제목 최소 길이를 한 글자로 되돌린다 | 두 글자는 조사를 막으려던 미봉책이었고 `봄 정산` 같은 제목을 함께 잃었다 | 어절·조사 규칙이 조사를 대신 막는다 |
| 2026-09-10 | 제목 위치는 소문자 문자열에서 찾고 잘라내기는 원문에서 한다 | 제목이 안내 문구에 그대로 나가므로 표기를 보존해야 한다 | 소문자 변환이 길이를 바꾸는 문자에서는 소문자 쪽으로 물러선다 |
| 2026-09-10 | `지불해야`는 EXPENSE가 아니라 SEND에 둔다 | 낼 돈을 묻는 질문이라 지출 요약과 뜻이 다르다 | EXPENSE는 `지불했` 같은 과거형만 본다 |
| 2026-09-10 | 형태소 분석기를 도입하지 않는다 | 의존성·성능 비용이 크고 이번 문제는 규칙으로 해결된다 | 부분 문자열 오탐이 완전히 사라지지는 않는다 |
| 2026-09-10 | #317이 되묻기로 두었던 구어체 질문 6건을 조회 응답으로 바꾼다 | #317 완료 기록이 이 표현들을 #318 범위로 남겼다 | 되묻기 목록에는 #319가 다룰 안내 질문만 남는다 |
| 2026-09-10 | 사용자의 실제 이름 목록과 대조하지 않는다 | 조회가 한 번 늘고 이번 범위를 넘는다 | 존재하지 않는 이름도 제목으로 볼 수 있다 |

## 11. Completion record

- Completed on: 2026-09-10
- Final behavior: 의도 키워드를 의미 그룹으로 나누고 각 그룹에 구어체 표현을 채웠다. 제목 추출은 문법 어휘를 문자열에서 제거하던 방식을 버리고, `모임`/`정산` 접미사 앞을 문법 구간에 닿을 때까지 거슬러 올라가는 방식으로 바꿨다. 후보가 항상 원문의 연속 부분 문자열이므로 없는 제목이 만들어지지 않는다. 제목 힌트는 데이터 조회 의도일 때만 쓴다. `InquiryIntent` 값과 응답 필드, 정산 조회·금액 계산 경로는 그대로다.
- Behavior change worth noting: #317이 되묻기로 두었던 구어체 정산 질문 6건이 이제 실제 조회 결과를 받는다. #317 완료 기록이 이 표현들을 #318 범위로 남긴 대로다. 다만 #317이 한계로 적은 `"이번 달에 처리해야 하는 게 뭐야?"` 는 여전히 분류되지 않는다. 맥락 단어가 하나도 없어 약한 표현만으로는 인정하지 않기 때문이다. 이 한계는 닫히지 않았다.
- Unverified items: 실제 앱에서의 응답 문구 확인. 서버 계약까지만 검증했다. 전체 테스트가 아니라 `*Treasurer*`·`*Inquiry*` 필터로 돌렸고, `InquiryParser`의 사용처가 `AskTreasurerService` 하나뿐인 것을 근거로 삼았다.
- Remaining risks: `TITLE_STOP_TOKENS`와 맥락 단어·조사 목록은 손으로 적은 것이라 여기 없는 표현은 샌다. 근본 해결은 제목 후보를 사용자의 실제 정산 제목과 대조해 맞는 게 없으면 필터를 걸지 않는 것인데, 이번 범위 밖이다. 그 외에는 부분 문자열 매칭이라 `"썼"`처럼 짧은 표현은 여전히 오탐 여지가 있다. `"영수증에 뭐라고 썼어?"`는 GUIDE보다 EXPENSE로 먼저 걸린다. `"왜 아직 정산이 안 끝났어?"`는 *왜*를 묻는데 *무엇*을 답한다. 되묻기보다는 낫지만 설명형 질문의 정답은 아니다. 제목 후보를 사용자의 실제 정산 제목과 대조하지 않으므로 존재하지 않는 이름도 제목으로 볼 수 있다. 이 경우 조회 0건이 되어 단정형 오답이 나갈 수 있다.
- Follow-up work: 설명형·안내 질문에 실제로 답하는 것은 #319. 회귀 검증 보강은 #320. `InquiryScopePolicy`의 부분 문자열 오탐(`"돈까스"`)은 이번 범위 밖으로 남았다.
