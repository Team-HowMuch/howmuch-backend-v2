# Feature Plan: AI 총무 확인되지 않은 정산 제목 처리

- Status: Completed
- Last updated: 2026-09-14
- Related issue: (미생성), [#318](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/318) (선행)
- Related ADR: N/A

## 1. Purpose

### Problem

**있지도 않은 제목으로 걸러놓고 "없어요"라고 단정한다.**

`InquiryParser`가 뽑은 제목 힌트를 `AskTreasurerService`가 그대로 필터로 쓴다. 그 이름의 정산이 실제로 있는지는 확인하지 않는다. 힌트가 사용자의 어느 정산과도 맞지 않으면 조회 결과가 0건이 되고, 응답은 이렇게 나간다.

```text
"이번 달 기준으로 조회했어요. '여행모임'이(가) 포함된 정산을 찾았어요. 보낼 정산은 없어요."
```

한 문장 안에서 **찾았다고 했다가 없다고 한다.** 사용자가 받는 결론은 "이번 달에 보낼 정산이 없다"인데, 실제로는 보낼 정산이 있고 그 이름의 정산만 없을 뿐이다. 돈에 대한 단정형 오답이다.

두 경로로 들어온다.

1. 사용자가 실제로 없는 이름을 말한 경우 (`"작년 여행 모임 정산 얼마야?"` — 이름이 다름)
2. 파서가 제목을 잘못 뽑은 경우. #318이 크게 줄였지만 `TITLE_STOP_TOKENS`와 조사 목록이 수기라 완전히 막지 못한다

### Why now

#318 완료 기준에 `제목 힌트를 신뢰할 수 없으면 필터를 적용하지 않고 전체 범위로 조회한다`가 체크되어 있으나 **구현되지 않았다.** 파서가 힌트를 안 뽑는 경우만 처리됐고, 뽑았는데 맞는 정산이 없는 경우는 그대로 남았다.

#360으로 계좌 거래에서 정산으로 넘어오는 경로가 늘었다. AI 총무가 "없어요"라고 한 정산이 계좌 화면에는 보이는 상황이 나올 수 있다.

### Success criteria

- [x] 제목 힌트와 맞는 정산이 하나도 없으면 힌트를 적용하지 않고 전체 범위로 조회한다.
- [x] 그 경우 응답이 "찾았어요"라고 말하지 않는다. 이름으로 찾지 못해 전체에서 조회했음을 알린다.
- [x] 힌트와 맞는 정산이 있으면 기존과 동일하게 그 이름으로 거른다.
- [x] 기간 때문에 0건인 경우와 이름 때문에 0건인 경우를 구분한다.
- [x] 이름으로 좁히지 못한 답변은 LLM을 거치지 않는다.
- [x] 정산 이름이 `모임`·`정산`을 빼고 지어졌어도 힌트가 맞는 것으로 본다.
- [x] 사용자에게 정산이 하나도 없어도 답변이 사실과 어긋나지 않는다.

### Out of scope

- 제목 추출 규칙 자체의 개선. #318에서 다뤘다
- 비슷한 이름 추천("혹시 이 정산인가요?")
- `InquiryIntent` 값과 응답 필드 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `PersonalSettlement`, `InquiryIntent`, `InquiryPeriod`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 없음. 조회 결과 해석만 바뀐다

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 제목 힌트 | 질문에서 뽑은 정산 이름 후보 | `ParsedInquiry.titleHint` |
| 확인된 힌트 | 사용자의 정산 중 하나 이상이 이름에 포함하는 힌트 | 본 작업에서 판정 |
| 확인되지 않은 힌트 | 맞는 정산이 하나도 없는 힌트 | 필터를 적용하지 않는다 |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 없는 이름을 말했을 때 잘못된 단정을 받지 않는다 | 기존 `AskTreasurerUseCase` |

### State and invariants

- State transitions: 없음. 읽기 전용이다.
- Rules that must always hold:
  - 힌트로 거른 결과가 0건이면 그 힌트로 단정하지 않는다.
  - 힌트 확인은 기간과 무관하게 사용자의 전체 정산을 기준으로 한다.
- Invalid states: 없는 이름을 근거로 "정산이 없다"고 답하는 것.

## 3. Scenarios

### Normal flow

1. 사용자에게 `회식 정산`이 있고 `여행 모임`은 없다.
2. `"여행 모임 정산 얼마야?"` 라고 묻는다.
3. 힌트 `여행모임`이 어느 정산에도 맞지 않아 확인되지 않은 힌트로 본다.
4. 힌트 없이 전체를 조회하고, 이름으로 찾지 못했음을 함께 알린다.

### Failure flow

- Condition: 사용자에게 정산이 하나도 없다.
- Returned error or state transition: 오류가 아니다. 힌트를 버리고 전체에서 조회한 뒤 "없어요"가 나간다. 이 경우는 단정이 사실이다.

### Boundary conditions

- 힌트와 맞는 정산이 있으나 기간 밖이면 힌트는 확인된 것이다. 기존대로 기간으로 걸러 0건을 반환한다.
- 힌트가 없으면(파서가 뽑지 않음) 기존 흐름 그대로다.
- 제목이 `null`인 정산은 힌트 확인 대상에서 빠진다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 AskTreasurerUseCase — 변경 없음
Command: 기존 AskTreasurerCommand
Result: 기존 TreasurerAnswerResult — scope 문구만 달라진다
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 변경 없음
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `TreasurerSettlementQuery#loadMine` | 사용자의 정산 전체 | `AskTreasurerService` | 기존 |

새 Port는 만들지 않는다. 이미 전체를 불러오고 있어 추가 조회가 없다.

### Application Policy

- Decision owned by the Policy: 안내 문구는 기존대로 `TreasurerCardCopy` 계열에 둔다
- Facts loaded by the Service and passed to the Policy: 힌트 확인 여부
- Why the rule is not an Entity-local invariant: 정산 한 건의 불변식이 아니라 조회 결과 집합에 대한 해석이다
- Future inputs deliberately not implemented yet: 비슷한 이름 추천

### Web API

```text
POST /api/v2/treasurer/questions
Request: 변경 없음
Response: 변경 없음. scope 문구가 달라지는 경우가 생긴다
Errors: 변경 없음
Authentication: 기존 그대로
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 힌트 확인은 `AskTreasurerService`가 이미 불러온 목록으로 판정한다
- Behavior owned by Domain: 없음
- Decisions owned by Application Policy: 안내 문구
- Flow orchestrated by Application: 전체 조회 → 힌트 확인 → 적용 여부 결정 → 기존 분기

### Technology and patterns

- Constraint to solve: 조회를 늘리지 않고 힌트의 신뢰도를 판정한다
- Selected approach: `loadMine()` 결과를 두 번 본다. 힌트와 맞는 정산이 하나도 없으면 힌트를 버린다. 전체가 비어 있는 경우도 같다
- Reason for selection: 이미 전체를 메모리에 들고 있어 추가 조회가 없다
- Alternatives considered: 정산 제목만 따로 조회해 대조하는 방법. 조회가 한 번 늘고 얻는 것이 없다
- Trade-offs and limits: 이름이 정말 없는 경우와 파서가 잘못 뽑은 경우를 구분하지 못한다. 둘 다 전체 조회로 떨어진다

#### 필터를 버리되 말하지 않고 넘어가지는 않는다

힌트를 조용히 버리면 사용자가 이름으로 물었는데 전체 결과를 받고도 이유를 모른다. 기간 문구 뒤에 이름으로 찾지 못했음을 덧붙인다. 파서가 잘못 뽑은 힌트였다면 사용자가 모르는 단어가 문구에 나오지만, 틀린 단정보다는 낫다.

#### 힌트를 버렸으면 LLM을 거치지 않는다

힌트를 버리면 답변 재료가 **질문이 지목한 이름과 무관한 정산들**이 된다. LLM에 넘기는 사실에는 정산 이름이 없고 금액만 있으며(`factsOf`가 `headline`을 넘긴다), `TreasurerAnswerFactCheck`는 숫자가 허용 목록에 있는지만 본다. 그래서 `"여행 모임 정산은 52,000원이에요"` 처럼 **다른 정산 금액을 그 모임 것으로 쓴 문장이 검사를 통과한다.**

이 경로는 이번 변경으로 처음 생겼다. 전에는 결과가 0건이라 붙일 숫자가 없었다. 틀린 "없어요"를 고치려다 틀린 금액을 만들 수는 없으므로, 이 경우에는 서버가 만든 문구를 그대로 내보낸다.

#### 힌트에서 접미사를 뗀 형태도 확인한다

힌트는 `모임`·`정산` 접미사를 포함한다(`titleAt`이 접미사까지 잘라 돌려준다). 정산 이름이 그 말을 빼고 `여행`이면 `"여행".contains("여행모임")`이 거짓이라 맞는 정산을 놓치고 전체 조회로 떨어진다. 접미사를 뗀 형태로 한 번 더 대조해 실제로 맞는 정산을 찾는다. 위 LLM 우회 경로로 빠지는 빈도도 줄어든다.

#### 힌트 확인은 기간 이전에 한다

`"지난달 회식 정산"` 처럼 이름은 맞고 기간만 다른 경우, 힌트는 확인된 것으로 보고 기존대로 기간으로 걸러야 한다. 기간까지 적용한 뒤 판정하면 이름이 맞는데도 전체 조회로 떨어진다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음. scope 문구만 달라진다 | `TreasurerAnswerWebResponse` |
| Application | 힌트 적용 여부 판정과 scope 문구 | `AskTreasurerService` |
| Domain | 없음 | `PersonalSettlement` |
| Persistence | 없음 | - |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | 본 문서, `ai-treasurer.md`의 제목 필터 서술, `treasurer-question-expressions.md`의 미구현 성공 기준 | `docs/features/` |

Existing behavior that must remain unchanged:

- 힌트와 맞는 정산이 있을 때의 필터 결과와 문구
- 힌트가 없을 때의 기간 문구
- 정산이 하나도 없을 때의 "없어요" 문구
- 의도 분류와 응답 계약

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`Required` / `N/A`) — **N/A**
- [ ] UseCase and Application Command/Result — **N/A**
- [ ] Required Output Ports — **N/A**
- [ ] Domain models and rules — **N/A**
- [x] Application Policies — 안내 문구
- [x] Application Service — 힌트 확인과 적용 여부
- [ ] Output Adapters — **N/A**
- [ ] Web Request/Response DTOs, Controllers, and Swagger — **N/A**
- [ ] Infra configuration and Bean wiring — **N/A**
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): 확인되지 않은 정산 제목 처리 설계 수립
fix(treasurer): 맞는 정산이 없는 제목으로 단정하지 않는다
test(treasurer): 제목 확인 여부에 따른 응답을 검증한다
docs(treasurer): 확인되지 않은 제목 처리 검증 결과 기록
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
| treasurer·inquiry 테스트 | 16개 클래스 128건 통과, 실패 0, 건너뜀 0 | `compose.test.yml`(PostgreSQL 18 · Redis 8) + `scripts/test.sh` |
| Codex 리뷰 반영 후 전체 스위트 | 1177건 통과, 실패 0, 건너뜀 0 | 같은 방법 |
| 일반어 제목 판정 | `AskTreasurerServiceTest` 44건 · `TitleHintVerificationTest` 15건 통과 | 같은 방법 |
| 맞는 정산이 없는 제목 | 통과 | `answersFromAllSettlementsWhenTitleMatchesNothing` — 전체에서 답하고 "없어요" 단정이 없다 |
| 이름은 맞고 기간만 다름 | 통과 | `keepsTitleFilterWhenTitleExistsOutsideRequestedPeriod` — 필터 유지, 0건 |
| 접미사 없는 이름 | 통과 | `narrowsByTitleWithoutGenericSuffix` (`"여행"` 정산을 `"여행모임"` 질문으로 찾음) |
| 일반어로 좁히지 않음 | 통과 | `doesNotNarrowByGenericWordLeftAfterStrippingSuffix` (`"모임 정산"` → 2건 111,000원) |
| 힌트를 버리면 LLM 우회 | 통과 | SEND·STATUS·DUE 세 갈래에서 `verifyNoInteractions(generateTreasurerAnswerPort)` |
| 정산이 하나도 없을 때 | 통과 | `keepsAnswerTruthfulWhenUserHasNoSettlementAtAll` |
| 기존 동작 유지 | 통과 | `keepsTitleFilterWhenPeriodAndGroupNameAppearTogether` 등 기존 단언 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 원인 확인, 설계 문서 작성 | 서비스 수정 | #318 완료 기준에 체크됐으나 구현되지 않은 항목이다 |
| 2026-09-14 | 수정·테스트, 독립 리뷰로 결함 4건 수정 | 전체 검증 | 틀린 "없어요"를 고치다 LLM이 다른 정산 금액을 갖다 쓰는 경로를 새로 만들었다 |
| 2026-09-15 | Codex 리뷰 반영. 제목 확인을 Policy로 옮기고 일반어 조합 힌트를 막았다 | PR 머지 | 흔한 말 판정을 접미사를 뗀 경로에만 걸어 둬, 이름이 실제 정산에 맞는 경로로 같은 결함이 남아 있었다. 한 경로만 막으면 다른 경로가 열려 있는지 반드시 확인해야 한다 |
| 2026-09-14 | 2차 독립 리뷰로 P1 1건 추가 수정, 전체 검증 128건 | PR 생성 | 접미사 떼기가 일반어에도 걸려 금액을 축소 단정했다. 수정이 또 다른 결함을 만든 두 번째 사례다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | 맞는 정산이 없으면 힌트를 버린다 | 없는 이름으로 "정산이 없다"고 단정하는 것이 최악이다 | 이름으로 좁히려던 의도는 살리지 못한다 |
| 2026-09-14 | 힌트를 버렸음을 문구로 알린다 | 조용히 버리면 전체 결과를 받고도 이유를 모른다 | 잘못 뽑은 힌트가 문구에 노출될 수 있다 |
| 2026-09-14 | 힌트 확인은 기간 적용 이전에 한다 | 이름은 맞고 기간만 다른 경우를 살린다 | 판정 기준이 사용자의 전체 정산이다 |
| 2026-09-14 | 힌트를 버린 답변은 LLM을 거치지 않는다 | 질문이 지목한 이름과 무관한 금액을 LLM이 그 이름 것으로 쓸 수 있고 FactCheck는 숫자만 봐서 못 막는다 | 그 경우 문장이 서버 문구로 고정된다 |
| 2026-09-14 | 힌트에서 `모임`·`정산` 접미사를 뗀 형태도 대조한다 | 힌트가 접미사를 포함해 `여행` 같은 이름을 놓쳤다 | 뗀 형태가 이름 구실을 할 때만 쓴다 |
| 2026-09-14 | 뗀 형태가 한 글자이거나 흔한 말이면 쓰지 않는다 | `"모임 정산 얼마 보내야 해?"` 가 `"모임"` 으로 좁혀져 다른 정산이 빠진 채 금액을 단정했다 | 이름이 한 글자인 정산은 좁히지 못한다 |
| 2026-09-14 | 정산이 하나도 없는 경우를 따로 분기하지 않는다 | 그때도 "찾지 못해 전체에서 조회했어요" 가 사실이다 | 분기를 늘리지 않는다. 문구가 조금 길어진다 |
| 2026-09-14 | 제목 대조용 조회를 새로 만들지 않는다 | `loadMine()` 이 이미 전체를 들고 있다 | 조회 비용이 늘지 않는다 |

## 11. Completion record

- Completed on: 2026-09-15 (Codex 리뷰 반영 포함)
- Final behavior: 질문에서 뽑은 정산 이름을 사용자의 정산과 대조해 하나라도 맞을 때만 필터로 쓴다. 맞는 것이 없으면 전체에서 답하고, 이름으로 찾지 못했음을 문구로 알리며, 그 답변은 LLM을 거치지 않고 서버 문구로 나간다. 힌트가 `모임`·`정산` 접미사를 포함하므로 뗀 형태로도 대조하되, 뗀 형태가 한 글자이거나 흔한 말이면 이름의 근거로 보지 않는다. `InquiryIntent` 값과 응답 필드는 변경하지 않았다.
- Unverified items: 실제 앱에서의 응답 문구 확인. 서버 계약까지만 검증했다. LLM을 실제로 붙인 경로(`provider != none`)는 통합 테스트가 없어 `verifyNoInteractions` 로만 확인했다.
- Remaining risks: `GENERIC_TITLE_WORDS` 는 손으로 적은 목록이라 여기 없는 일반어(`"저녁 정산"` 의 `"저녁"` 등)는 여전히 이름으로 오인될 수 있다. 이름이 한 글자인 정산은 접미사를 뗀 경로로 좁히지 못한다. 제목 대조는 부분 문자열이라 짧은 이름이 여러 정산에 걸릴 수 있고, 그 경우 좁힌 범위가 사용자의 의도보다 넓다.
- Follow-up work: 테스트 환경의 `ck_account_records_deleted_at` 위반. colima VM 시계가 맥 호스트보다 뒤처지면 애플리케이션이 쓴 `created_at` 보다 트리거의 `CURRENT_TIMESTAMP` 가 이르러, 방금 만든 계좌를 지우는 테스트가 깨진다. VM을 다시 띄우면 사라지므로 이번 변경과 무관하지만, 애플리케이션 시각과 DB 시각을 부등호로 묶은 제약이라 서버와 DB 호스트가 다른 운영에서도 같은 역전이 날 수 있다. 별도 이슈로 다룬다. `TreasurerAnswerFactCheck` 의 결함 두 가지를 별도 이슈로 다룬다. (1) 허용 숫자를 fallback 문장 전체에서 정규식으로 긁어서 정산 이름의 숫자(`"2차 모임"`)가 허용 목록에 들어간다. (2) `isCalendarNumber` 가 `월`·`일`·`년` 뒤 숫자를 무조건 통과시키는데 draft 에는 날짜 사실이 없어, LLM 이 없는 마감일을 지어내도 막지 못한다. 둘 다 이번 변경이 만든 것이 아니라 원래 있던 경로다.
