# Feature Plan: AI 비서 질문 의도를 LLM으로 분류

- Status: In progress
- Last updated: 2026-10-19
- Related issue: 생성 전 (#317 후속)
- Related ADR: 없음

## 1. Purpose

### Problem

앱에서 "내가 지금 해야하는 정산", "해야하는 정산 목록 줄래?"를 물으면 둘 다 정산 목록 대신 되묻기 문구(`TreasurerCardCopy.CLARIFY_ANSWER`)만 나온다.

질문 의도를 키워드 목록으로만 나누는 구조라서 생기는 문제다. 코드 대조 기준은 develop `c6b293b4`다.

1. `InquiryParser.intentOf()`(L152-L200)는 목록에 없는 표현을 `UNKNOWN`으로 돌려준다. `해야`는 조사·어미 목록(`CONTEXT_SUFFIXES`, L41)에 있어 의도로 치지 않는다.
2. `AskTreasurerService.answerFor()`(L112-L122)는 `UNKNOWN`이면 `InquiryScopePolicy`(이것도 키워드)로 관련성을 본 뒤 안내 LLM(`guidedAnswer`, L249)을 부른다.
3. 안내 LLM(`TreasurerGuideLlmAdapter.RULES` 5번, L41)은 이런 질문을 `route: DATA`로 맞게 판정한다. 그런데 응답에 어떤 정산을 볼지가 없어 `unmatchedAnswer()`(L228-L233)가 되묻기 문구로 끝낸다.

### Why now

질문 하나가 막힐 때마다 키워드를 추가하는 방식은 끝이 없다. 이미 LLM이 질문을 맞게 이해하고 있는데 서버가 그 결과를 버리고 있다.

### Success criteria

- [ ] "내가 지금 해야하는 정산", "해야하는 정산 목록 줄래?"가 정산 목록으로 답한다.
- [ ] LLM이 실패하거나 키가 없으면 기존 키워드 분류 결과와 같다.
- [ ] enum 밖 값, 질문 원문에 없는 `titleHint`는 버린다.
- [ ] 기존 의도별 응답 계약(`intent`·`view`·`items`·`actions`·`prompts`)이 바뀌지 않는다.

### Out of scope

- 키워드 목록(`InquiryParser`, `InquiryScopePolicy`) 수정. 대비 경로로 그대로 둔다.
- 금액·건수·목록 계산 방식. 지금처럼 서버가 조회·계산한다.
- 프론트엔드 변경. 응답 계약이 같다.
- LLM 키 교체(OpenAI → Gemini). 운영 결정이라 사용자가 정한다.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: treasurer
- Reason a new domain is required, if applicable: 해당 없음
- Relationships to other domains: 없음. 정산 조회는 기존 `TreasurerSettlementQuery`를 그대로 쓴다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 라우터 | 질문을 안내·데이터·무관으로 나누고, 데이터면 어떤 정산을 볼지까지 정하는 안내 LLM 호출 | `GenerateTreasurerGuidePort`, `TreasurerGuideReply` |
| 대비 경로 | LLM 응답이 없을 때 쓰는 지금의 키워드 분류 흐름 | `InquiryParser` + `InquiryScopePolicy` |
| 되묻기 | 데이터 질문인데 어떤 정산인지 정할 수 없을 때의 답 | `TreasurerCardCopy.CLARIFY_ANSWER` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 자연어로 내 정산을 묻는다 | `AskTreasurerUseCase` (변경 없음) |

### State and invariants

- State transitions: 해당 없음. 조회 전용이다.
- Rules that must always hold:
  - 금액·건수·목록은 LLM이 만들지 않는다.
  - LLM이 준 `titleHint`는 질문 원문(공백 제거)의 연속 부분 문자열일 때만 쓰고, 다시 `TitleHintVerification.verify()`로 사용자 정산과 대조한다.
  - LLM 응답이 없으면 지금과 결과가 같다.
- Invalid states: LLM이 정한 의도가 `GUIDE`나 `UNKNOWN` 같은 데이터 의도 밖의 값이면 의도가 없는 것으로 본다.

## 3. Scenarios

### Normal flow

1. 사용자가 `POST /api/v2/treasurer/questions`로 "내가 지금 해야하는 정산"을 묻는다.
2. 서비스가 안내 LLM을 한 번 부른다. LLM은 `{"route":"DATA","intent":"SEND_SETTLEMENTS"}`처럼 답한다.
3. 서비스가 값을 검증하고 그 의도로 지금의 데이터 답변 경로를 탄다.
4. `intent=SEND_SETTLEMENTS`, `view=LIST`, 보낼 정산 카드가 나간다.

### Failure flow

- LLM 실패·키 없음·JSON 아님: 지금 흐름 그대로(키워드 → 관련성 → 되묻기/범위 안내).
- `route=DATA` + `intent=UNCLEAR`: 되묻기.
- `route=DATA` + 의도 없음·무효: 키워드 결과가 데이터 의도면 그걸 쓰고, 아니면 되묻기.
- `route=GUIDE` + 검사 탈락: 지금 흐름 그대로(영수증 고정 문구, 정산 조회, 되묻기).
- `route=UNRELATED`: 범위 안내.

### Boundary conditions

- `PRIORITY_SETTLEMENTS`에서 기간이 없으면 "끝나지 않은 정산 전체", 다른 의도에서 기간이 없으면 이번 달. 지금 의미를 유지한다.
- LLM이 기간이나 제목을 비우면 키워드가 뽑은 값을 쓴다.
- LLM `titleHint`에 공백이 있으면 공백을 뺀 형태로 쓴다. 정산 제목 비교(`TitleHintVerification.matchesTitle`)가 공백을 뺀 제목에 대고 하기 때문이다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: AskTreasurerUseCase (변경 없음)
Command: AskTreasurerCommand (변경 없음)
Result: TreasurerAnswerResult (변경 없음)
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 변경 없음
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `GenerateTreasurerGuidePort` (기존) | 안내 답변과 함께 데이터 질문의 의도·기간·제목을 받는다 | `AskTreasurerService` | `TreasurerGuideLlmAdapter` |

새 Port는 없다. 호출 한 번에 분류와 안내 답변을 합친다.

### Application Policy

- Decision owned by the Policy: 해당 없음. 새 Policy를 두지 않는다. LLM 값 검증(enum 범위)은 어댑터가 응답을 읽을 때, 원문 부분 문자열 확인과 대비 경로 선택은 서비스가 한다. 정산 대조는 기존 `TitleHintVerification`이다.

### Web API

변경 없음.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required: No
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 안내 LLM 출력 JSON에 `intent`, `period`, `titleHint`를 더한다.

```json
{"route":"GUIDE|DATA|UNRELATED",
 "intent":"SEND_SETTLEMENTS|RECEIVE_SETTLEMENTS|SETTLEMENT_STATUS|EXPENSE_SUMMARY|DUE_SETTLEMENTS|PRIORITY_SETTLEMENTS|UNCLEAR",
 "period":"THIS_WEEK|THIS_MONTH|LAST_MONTH|null",
 "titleHint":"질문 원문에 있는 정산·모임 이름 또는 null",
 "answer":"...", "sources":[...], "followUps":[...]}
```

- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- 모든 질문에서 안내 LLM을 먼저 한 번 부른다. 대비 경로는 이 결과를 재사용하고 LLM을 다시 부르지 않는다.
- `answerFor()` 분기
  - 응답 없음: 지금 흐름
  - `UNRELATED`: 범위 안내
  - `GUIDE`: 검사 통과 시 안내 답변, 탈락 시 지금 흐름
  - `DATA` + 데이터 의도: 그 의도로 데이터 답변
  - `DATA` + `UNCLEAR`: 되묻기
  - `DATA` + 의도 없음·무효: 키워드 결과가 `UNKNOWN`이 아니면 지금 흐름, `UNKNOWN`이면 되묻기
- `intent`·`period`는 정해진 값과 정확히 같을 때만 받는다. 대소문자가 다르거나 목록 밖이면 버린다.
- 프롬프트에는 키워드 목록이 아니라 의도별 뜻을 적는다.

### Technology and patterns

- Constraint to solve: 키워드로 잡지 못한 데이터 질문을 LLM이 이미 이해하는데 서버가 버린다.
- Selected approach: 기존 안내 LLM 호출 하나를 라우터로 넓힌다.
- Reason for selection: 호출이 늘지 않고, 이미 받은 판정(`DATA`)을 그대로 쓴다.
- Alternatives considered: 키워드 추가(끝이 없어 기각), 분류 전용 LLM 호출 추가(호출이 3번이 돼 기각).
- Trade-offs and limits:
  - 키워드로 바로 잡히던 데이터 질문도 LLM을 먼저 부른다. 호출이 1번(문장 다듬기)에서 2번으로 늘어 응답이 1~2초 늘고 비용이 조금 는다.
  - 서비스와 무관한 질문도 LLM을 부른다.
  - 키워드로 잡히던 질문이라도 LLM이 `UNRELATED`나 `GUIDE`로 판정하면 그 판정을 따른다.
  - OpenAI 키는 2026-10-24까지다. 그 뒤 키가 없으면 대비 경로로만 동작해 같은 증상이 다시 나온다. Gemini 경로(`TREASURER_LLM_PROVIDER=gemini`)는 이미 있다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음 | `TreasurerAnswerResponse` |
| Application | 분기 재구성, 응답 모델 필드 추가 | `AskTreasurerService`, `TreasurerGuideReply` |
| Domain | 없음 | `InquiryIntent`, `InquiryPeriod` 값 그대로 |
| Persistence | 없음 |  |
| Redis | 없음 |  |
| Security | 없음 |  |
| Configuration | 없음 |  |
| Documentation | 이 문서, `ai-treasurer.md` 3장·5장 | |

Existing behavior that must remain unchanged:

- LLM 응답이 없을 때 모든 질문의 의도·기간·제목·문구
- 제목을 버린 답변은 LLM 문장을 쓰지 않는 동작
- 우선순위 질문의 기간 없음 = 전체 의미

의도적으로 바뀌는 기존 테스트(`AskTreasurerServiceTest`)

- `unrelatedQuestion_skipsGuide`, `wordPrefixedMoneyTerm_skipsGuide`, `dataPhrasing_skipsGuide`: 이제 모든 질문이 안내 LLM을 먼저 부른다. "LLM 응답이 없으면 지금처럼 답한다"로 바꿔 대비 경로를 지키는 테스트로 남긴다.

## 7. Implementation plan

- [ ] Migration-only pull request to `develop`: N/A (스키마 변경 없음)
- [ ] UseCase and Application Command/Result: N/A (변경 없음)
- [ ] Required Output Ports: N/A (기존 Port 그대로)
- [ ] Domain models and rules: N/A
- [ ] Application Policies: N/A
- [ ] Application Service: `TreasurerGuideReply` 필드 추가, `answerFor()` 분기 재구성
- [ ] Output Adapters: `TreasurerGuideLlmAdapter` 프롬프트와 파싱
- [ ] Web Request/Response DTOs, Controllers, and Swagger: N/A
- [ ] Infra configuration and Bean wiring: N/A
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): LLM 우선 의도 분류 계획 정리
feat(treasurer): 안내 LLM이 정한 의도로 정산 질문에 답한다
test(treasurer): LLM 의도 분류와 키워드 대비 경로 검증
docs(treasurer): LLM 의도 분류 검증 결과 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
docker compose -f compose.test.yml up -d
bash scripts/test.sh test --tests '*Treasurer*' --tests '*Inquiry*'
bash scripts/test.sh test
git diff --check
git status --short --untracked-files=all
```

테스트 결과는 `build/test-results/test/TEST-*.xml`의 클래스별 tests/failures로 확인한다.

### Completion criteria

- [ ] Verified the user outcome defined under Purpose.
- [ ] Verified normal, failure, and boundary flows.
- [ ] Confirmed documentation and Swagger match the actual contract.
- [ ] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
|  | Pending |  |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-19 | 계획 작성, develop `c6b293b4` 기준 줄 번호 재확인 | 구현 | 키 기한 2026-10-24 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-19 | LLM 우선, 키워드는 대비용 | 키워드 예외 처리를 끝없이 늘리지 않는다(사용자 결정) | 호출·지연 증가 |
| 2026-10-19 | LLM 판정이 키워드 결과와 달라도 LLM을 따른다 | 사용자 결정 | 키워드로 잡히던 질문이 LLM 오판으로 범위 안내를 받을 수 있다 |
| 2026-10-19 | LLM `titleHint`는 공백을 빼고 원문과 비교·사용한다 | 제목 비교가 공백 없는 제목에 대고 한다 | 띄어 쓴 이름이 정산과 안 맞는 문제 방지 |
| 2026-10-19 | LLM이 기간·제목을 비우면 키워드 값을 쓴다 | 키워드는 대비용 | LLM이 빠뜨린 "지난달" 등을 놓치지 않는다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
