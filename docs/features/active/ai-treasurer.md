# Feature Plan: AI 총무 (개인 정산 비서)

- Status: Implemented (initial scope)
- Last updated: 2026-10-15 (OpenAI 모델 GPT-6 Luna 전환, #553)
- Related issue: [#187](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/187), F-GRO-004
- Related ADR: N/A

## 1. Purpose

### Problem

정산 목록 API는 탭·상태 필터만 제공한다. 사용자는 홈/AI 총무에서 **본인 기준** 보낼·받을·진행 중·완료 요약, 자연어 질의, 미완료 우선 안내가 필요하다.

### Why now

기획서가 확정되었고 `develop`에 `GET /api/v2/settlements`가 있다. 금액·건수는 서버가 집계하고, 안내 문장만 OpenAI 또는 Gemini로 다듬는다.

### Success criteria

- [x] 로그인 사용자가 본인 정산 현황 요약(받을/보낼/진행 중/완료 건수·금액, 안내 문구 1개)을 조회한다.
- [x] 자연어 질문으로 보낼 정산, 받을 정산, 진행 상태, 지출 요약을 조회한다. 기간(`이번 달`/`지난달`)·정산 제목(모임명 대용) 필터와 조회 기준 안내를 포함한다.
- [x] 의도 파악 실패 시, 서비스와 관련된 질문이면 되묻고 무관한 질문이면 예시 질문을 안내한다.
- [x] 미완료 정산을 보낼/받을·기한 초과 우선순위로 안내하고 화면 이동 액션만 제공한다. 순서와 기한 초과 기준은 [정산 우선순위 안내](../completed/treasurer-priority-recommendation.md)를 따른다.
- [x] 정산 데이터를 변경하지 않는다.
- [x] 질의 답변 문장은 OpenAI 또는 Gemini로 생성하고, 키/호출 실패 시 서버 템플릿으로 내려간다.
- [x] 채팅 시작 시 인사·바로가기(보낼/받을/정산 상태)와 메뉴 제안 칩을 제공한다.
- [x] 보낼/받을 질의는 날짜·헤드라인·부제·마커가 있는 리스트 카드를 반환한다.
- [x] 정산 상태 질의는 낼 돈/받을 돈/이번 달 완료 요약 카드를 반환한다.

### Out of scope

- OCR AI 서버
- 송금·입금 확인·알림 발송 실행
- 기한 필드(정산 도메인에 dueDate 없음) — 기한 초과는 요청 후 24시간 기준으로 대신한다([정산 우선순위 안내](../completed/treasurer-priority-recommendation.md)). 장기 미처리는 요청 후 7일 기준
- 사용자별 입금 PAID를 정산 COMPLETED와 분리하는 집계(목록에 paymentStatus 없음). 완료는 정산 상태 `COMPLETED`로 근사
- 그룹 ID 필터(정산 엔티티에 groupId 없음). 모임명·정산명 힌트는 `○○모임`/`○○정산` 패턴만 사용하고 질문 문장 잔여는 쓰지 않는다

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Settlement` 조회 (`LoadTreasurerSettlementsPort` → 목록 SQL 확장)
- Reason a new domain is required: 개인 정산 분류·안내 규칙이 정산 생성/상태 전이와 분리된다. `treasurer` 분석 계층.
- Relationships: 정산 목록을 읽기만 한다.

### Ubiquitous language

| Term | Meaning | Code |
|---|---|---|
| 보낼 정산 | 생성자가 아니고 참여자인, 요청된 이후 미완료 정산 | `SettlementDirection.SEND` + outstanding |
| 받을 정산 | 생성자인, 요청된 이후 미완료 정산 | `RECEIVE` + outstanding |
| 사용자 완료 | 정산 상태가 COMPLETED | `userActionCompleted` |
| 장기 미처리 | 요청된 이후 미완료이고 요청일 7일 초과 | `isStale` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 현황 요약 | `GetTreasurerSummaryUseCase` |
| 로그인 사용자 | 채팅 시작(인사·바로가기·제안 칩) | `GetTreasurerChatUseCase` |
| 로그인 사용자 | 자연어 질의 | `AskTreasurerUseCase` |
| 로그인 사용자 | 확인 필요 항목 | `GetTreasurerRecommendationsUseCase` |

## 3. Scenarios

### Normal flow

1. 인증 사용자가 `/api/v2/treasurer/summary` 요청
2. RECEIVE/PAY 전체 목록을 읽어 분류·집계
3. 우선 안내 문구 1개와 카드 집계 반환

### Failure flow

- 미인증: 401 UNAUTHORIZED
- 빈 질문: 400 (Jakarta validation)

### Boundary conditions

- 정산 없음: 금액 0, “현재 처리할 정산이 없어요.”
- 모든 질문은 안내 LLM이 먼저 분류한다. 무관이면 UNKNOWN + 예시, 데이터 질문인데 어떤 정산인지 정할 수 없으면 UNKNOWN + 되묻기(`TreasurerCardCopy.CLARIFY_ANSWER`) + 예시 칩. 상세는 `treasurer-llm-intent-routing.md` 참고
- 안내 LLM이 없거나 실패하면 키워드 대비 경로로 간다. 질문 의도 없음 + 서비스 무관이면 UNKNOWN + 예시, 서비스 관련이면 UNKNOWN + 되묻기 + 예시 칩
- 기간 필터 후 0건: 건수 0 안내. 제목 필터는 맞는 정산이 있을 때만 적용하므로 제목 때문에 0건이 되지 않는다

## 4. Contracts

### Input Port

```text
GetTreasurerChatUseCase → TreasurerChatResult
GetTreasurerSummaryUseCase → TreasurerSummaryResult
AskTreasurerUseCase(AskTreasurerCommand) → TreasurerAnswerResult
GetTreasurerRecommendationsUseCase → TreasurerRecommendationsResult
```

### Output Ports

| Port | Purpose |
|---|---|
| `LoadTreasurerSettlementsPort` | 사용자 SEND/RECEIVE 전체 정산(제목 포함). 방향은 `SettlementDirection` |
| `GenerateTreasurerAnswerPort` | LLM 문장 후보만 반환(`Optional`). 실패·미사용은 empty |

### Web API

- `GET /api/v2/treasurer/chat` 인사·바로가기·메뉴 제안 칩
- `GET /api/v2/treasurer/summary`
- `POST /api/v2/treasurer/questions` body `{ "question": "..." }` — `view`가 `LIST`/`SUMMARY`/`TEXT`
- `GET /api/v2/treasurer/recommendations`

## 5. Design decisions

- 의도 분류는 안내 LLM이 먼저 한다(`TreasurerGuideLlmAdapter`의 `route`·`intent`·`period`·`titleHint`). LLM이 없거나 실패하면 키워드(`InquiryParser`)로 분류한다. 금액·건수는 서버가 계산한다. 상세는 `treasurer-llm-intent-routing.md` 참고.
- 대비용 의도 키워드는 강함과 약함으로 나뉜다. `남았`·`들어와`처럼 일상어로도 쓰이는 약한 표현은 정산·모임·돈 같은 맥락 단어가 질문에 함께 있을 때만 의도로 본다.
- 키워드 대비 경로에서 세부 의도 분류에 실패하면 `InquiryScopePolicy`가 서비스 관련성을 다시 판정한다. 무관이면 범위를 안내한다. 관련이면 서비스 안내 문서로 답하고, 안내가 실패하면 되묻는다. 상세는 `treasurer-question-scope.md`(#317), `treasurer-service-guide.md` 참고.
- `answer` 문장만 OpenAI(`gpt-6-luna`) 또는 Gemini(`gemini-flash-latest`)가 생성한다. `TREASURER_LLM_PROVIDER=none` 이거나 키/호출 실패면 템플릿 문장을 쓴다.
- OpenAI 기본 모델은 추론 모델 `gpt-6-luna`다(#553). 추론 모델은 `temperature`를 받지 않으므로 두 OpenAI 요청(조회 답변 문장, 서비스 안내) 모두 `temperature`를 보내지 않고 `reasoning_effort`를 보낸다. 값은 `OPENAI_REASONING_EFFORT`이며 설정하지 않으면 `low`, 빈 값으로 두면 요청에서 뺀다. 추론 모델이 아닌 모델(`OPENAI_MODEL`)로 바꿀 때는 `OPENAI_REASONING_EFFORT=`로 비워야 한다. 비우지 않으면 OpenAI가 400으로 거절하고 템플릿 문장으로 되돌아간다. 추론 강도를 올리면 응답이 길어지므로 `TREASURER_LLM_READ_TIMEOUT`(기본 20초)도 함께 늘린다.
- OpenAI·Gemini가 요청을 거절하면 상태 코드와 오류 본문 앞 200자를 경고 로그(`Treasurer LLM call failed` / `Treasurer guide LLM call failed`)에 남긴다. 질문 원문은 남기지 않는다.
- 기존 `LoadSettlementListPort`는 title이 없어 treasurer 전용 조회를 둔다. 목록 HTTP 계약은 변경하지 않는다.
- 내부 시각은 UTC 저장값을 쓰되, `이번 달`은 Asia/Seoul 달력이다.
- 받을 금액은 생성자 본인 share를 제외한 금액이다.
- 품목 정산(`ITEM_ALLOCATION`)이 `REQUESTED`/`SUSPENDED`이면 share가 비었거나 일부만 있어, 받을 금액을 정산 상세·정산 목록과 같은 기준(영수증 총액 − 생성자 몫)으로 다시 계산한다(`RequestedItemReceivableCalculator`, #514, `settlement-list-receivable-amount.md`). 계산하지 못한 정산은 share 합계를 쓴다.
- `summary`는 기간 없이 진행 중 전체를, 질문은 기간(기본 이번 달) 안의 정산만 집계하므로 두 금액은 다를 수 있다.
- 보낼/받을 금액과 추천은 `REQUESTED`/`AWAITING_DEPOSIT`/`SUSPENDED`만 포함한다. `DRAFT`는 진행 중 건수에만 넣고 잔액·추천에서는 뺀다.
- 지출 요약은 SEND 방향만 합산하고 `DRAFT`는 제외한다.
- 제목은 `모임`/`정산` 접미사 앞을 문법 어휘 구간에 닿을 때까지 거슬러 올라가 뽑는다. 질문에서 문법을 잘라내지 않으므로 제목은 항상 원문의 연속 부분 문자열이다.
- 제목 힌트는 보낼/받을/상태/지출/마감 의도에서만 쓴다. 안내·미분류 의도는 제목으로 거르지 않는다.
- 제목 힌트는 사용자의 정산과 대조해 하나라도 맞을 때만 필터로 쓴다. 맞는 것이 없으면 버리고 전체에서 답하며, 이름으로 찾지 못했음을 안내에 적는다. 없는 이름으로 0건을 만들어 "없어요"라고 단정하지 않기 위해서다.
- 제목을 버린 답변은 LLM을 거치지 않는다. 답변 재료가 질문이 지목한 이름과 무관한 정산이라 LLM이 금액을 그 이름 것으로 쓸 수 있고, 팩트 체크는 숫자만 본다.
- 보낼/받을 키워드가 한 질문에 같이 있으면 먼저 나온 쪽을 쓴다.
- 추천 액션은 실제 실행 가능한 상태만 노출한다. `TRANSFER`와 `CHECK_DEPOSIT`은 `AWAITING_DEPOSIT`에서만 제공한다.
- LLM이 서버 팩트·템플릿에 없는 숫자를 쓰면 `TreasurerAnswerFactCheck`가 템플릿 문장으로 되돌린다. Adapter는 생성 문장만 반환하고 금액 검증은 하지 않는다. `8월`처럼 날짜 접미 숫자는 허용한다.
- 카드 문구 Policy(`TreasurerCardCopy`)는 자체 Chip/Card 레코드를 반환하고, Service가 Result로 매핑한다.
- 평소 입력은 자유 대화(`POST /questions`)이고, 메뉴 칩·바로가기도 같은 질의 API로 처리한다.
- 바로가기(보낼/받을/정산 상태)와 기간을 생략한 질의는 `이번 달 기준으로 조회했어요.`를 쓴다.
- 리스트 카드 부제의 완료 인원은 입금(`settlement_deposits`)이 있는 참여자 수로 근사한다. 부분 입금은 완료로 센다.
- 리스트 카드는 앱의 화면 이동 분기를 위해 `allocationType`과 `status`를 함께 내린다. 배분 방식을 정하기 전이면 `allocationType`은 null이다. 상세는 `treasurer-answer-item-navigation.md`(#341) 참고.
- `이번 주`는 Asia/Seoul ISO 주이고, 기한 필드가 없어 `마감`은 요청일 + 7일이 해당 기간에 들어오는 미완료 정산으로 근사한다.
- OCR 안내 키워드(`ocr`, `영수증`)는 보낼/받을/마감/지출/상태 의도가 없을 때만 GUIDE로 본다.
- GUIDE는 서비스 안내 문서로 답하고, 안내 LLM이 없거나 검사에서 버려지면 영수증 인식 고정 문구를 반환한다. OCR 서버는 호출하지 않는다.
- 안내 LLM이 사용법 질문(GUIDE)으로 답하고 검사를 통과하면 키워드 의도와 관계없이 안내로 답한다. 안내 답변이 검사에서 버려지면 LLM을 다시 부르지 않고 키워드 분류로 답한다. 사용법 말투만 따로 보던 `InquiryHowToPolicy`는 LLM이 모든 질문을 먼저 나누면서 없앴다.

## 6. Impact surface

- 신규 treasurer 패키지, SettlementJpaRepository 쿼리 1개
- Security: `/api/**` authenticated 기존 규칙

## 7. Verification

- Domain/Policy/Service 단위 테스트
- Controller MockMvc 계약 테스트
- LLM 응답 파서·어댑터 단위 테스트
- treasurer native query 영속성 통합 테스트
- `./gradlew compileJava test` (관련 테스트)
- #553 GPT-6 Luna 전환(2026-10-15, develop `470c73a` 기준): `compose.test.yml`(PostgreSQL 18 · Redis 8) + `scripts/test.sh test --tests '*Treasurer*' --tests '*OpenAiChatRequestTest'`로 22개 클래스 169건 실행, 실패 0, 건너뜀 1(실제 키가 필요한 로컬 평가 `TreasurerGuideLiveEvaluation`, 저장소에 포함하지 않음). 신규 `OpenAiChatRequestTest` 4건, `TreasurerLlmPropertiesTest` 4건(빈 `reasoning-effort`가 비활성으로 바인딩되는지 포함).
- 실제 `gpt-6-luna` 호출(2026-10-15, 로컬 평가 30문항, 추론 강도 `low`): 안내 LLM 호출 22회 모두 응답했고 거절(400)·검사 탈락은 0건이다. `response_format: json_object`와 `system` 역할을 그대로 받는다. 응답 시간 평균 2.5초, 최대 4.5초로 읽기 타임아웃(20초) 안이다. 기대 경로 일치 28/30이며, 사용법 질문 21/22가 문서 근거 안내로, "비트코인 송금은 어떻게 해?"는 범위 밖으로 답했다.
- 남은 2건은 LLM이 아니라 규칙 분류 문제로 이번 범위 밖이다. "진행 중인 정산이 있으면 회원 탈퇴 돼?"는 `SETTLEMENT_STATUS`로 분류돼 안내 LLM을 부르지 않고, "아직 안 낸 거 있어?"는 서비스 어휘가 없어 무관 질문으로 판정된다. 별도 이슈로 다룬다.

## 8. Commit boundaries

1. feat(treasurer): 개인 정산 비서 조회 API

## 9. Open questions

- 사용자별 PAID와 정산 COMPLETED 분리: 입금 대조 bulk 조회가 생기면 교체
- 기한 초과: 요청 후 24시간 기준으로 우선순위 1·3을 활성화했다. dueDate가 생기면 기준을 바꿀지 다시 정한다
