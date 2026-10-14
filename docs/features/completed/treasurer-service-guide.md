# Feature Plan: AI 비서 서비스 안내

- Status: Completed
- Last updated: 2026-10-14
- Related issue: (생성 예정)
- Related ADR: 없음

## 1. Purpose

### Problem

- AI 비서는 내 정산 숫자를 묻는 질문만 답한다. `InquiryParser`가 의도를 찾지 못하면 `unmatchedAnswer`가 LLM 없이 고정 문구("이렇게 물어볼 수 있어요")를 돌려준다. 팀 피드백도 "내 정산 상태 답변 빼고는 모두 같은 문구가 뜬다"였다.
- 사용법 질문이 데이터 질문으로 잘못 빠지기도 한다. 키워드가 먼저 걸리기 때문이다.

| 질문 | 지금 걸리는 의도 | 원래 원하는 것 |
|---|---|---|
| 송금은 어떻게 해? | SEND_SETTLEMENTS ("송금") | 송금 방법 안내 |
| 결제수단 등록 어떻게 해? | EXPENSE_SUMMARY ("결제") | 계좌 등록 방법 |
| 받을 계좌 바꾸려면? | RECEIVE_SETTLEMENTS ("받을") | 기본 계좌 변경 방법 |
| 로그인은 어떻게 해? | UNKNOWN + 서비스 무관 → 범위 밖 안내 | 로그인 방법 |
| 독촉 알림 또 보내고 싶어 | UNKNOWN → 고정 문구 | 30분 재발송 제한 안내 |

- GUIDE 의도는 `ocr`, `영수증`에만 걸리고, 답(`OCR_ANSWER`)은 "아직 준비 중"이라고 말한다. 영수증 인식은 이미 제공 중이라 틀린 안내다.

### Why now

- 베타 테스트 중 사용자가 AI 비서에게 앱 사용법을 묻는다. 고정 문구와 틀린 OCR 안내가 계속 노출된다.

### Success criteria

- [ ] 얼마 사용법 질문에 서비스 안내 문서를 근거로 답한다(카카오뱅크 AI처럼 방법 안내 + 이어서 물어볼 질문).
- [ ] 문서에 없는 기능이나 숫자를 말한 답, 근거 ID가 없는 답은 사용자에게 나가지 않는다.
- [ ] 얼마와 관계없는 질문은 지금처럼 LLM 없이 거절한다.
- [ ] 내 정산 숫자를 묻는 기존 질문의 답과 속도는 바뀌지 않는다. LLM을 끈 환경의 동작도 지금과 같다.
- [ ] 앱 응답 계약을 바꾸지 않는다.

### Out of scope

- 벡터 검색(RAG), pgvector, DB 구조 변경
- AI 비서가 송금·정산 생성·독촉 같은 실제 처리를 하는 것
- 답변에 화면 바로가기 액션 추가(앱 계약 변경)
- 안내 답변에 개인 정산 데이터를 섞는 것

## 2. Domain analysis

### Domain boundary

- 재사용: `treasurer`(질문 흐름, 답변 저장, 신고), 기존 LLM 설정(`TreasurerLlmProperties`, `treasurerLlmRestClient`)
- 새 도메인: 없음. 서비스 안내 문서는 서버 리소스로 둔다.

### Ubiquitous language

| 용어 | 뜻 | 코드 |
|---|---|---|
| 서비스 안내 문서 | AI 비서가 사용법 답변의 근거로 쓰는 문서 | `src/main/resources/treasurer/service-knowledge.md`, `TreasurerKnowledge` |
| 근거 ID | 문서의 `## [ID]`, `- [ID]` 표식. 답변이 무엇을 근거로 했는지 가리킨다 | `TreasurerKnowledge.sourceIds` |
| 안내 경로 | GUIDE(사용법), DATA(내 숫자 질문), UNRELATED(무관) | `TreasurerGuideReply.Route` |
| 사용법 말투 | "어떻게 해", "방법", "하려면"처럼 하는 방법을 묻는 표현 | `InquiryHowToPolicy` |

### Actors and UseCases

- 로그인한 회원이 `POST /api/v2/treasurer/questions`로 질문한다. UseCase는 기존 `AskTreasurerUseCase` 그대로다.

### State and invariants

- 안내 답변은 근거 ID가 1개 이상이고 모두 문서에 있어야 한다.
- 안내 답변에 나오는 단위 붙은 숫자(원, 분, 시간, 자리 등)는 문서에 그대로 있어야 한다.
- 안내 경로에는 개인 정산 데이터를 넣지 않는다. LLM에는 질문 원문과 문서만 보낸다.

## 3. Scenarios

### Normal flow

1. `InquiryParser`가 의도를 정한다.
2. UNKNOWN이면 `InquiryScopePolicy`로 관련성을 본다. 무관이면 지금처럼 예시를 돌려준다(LLM 없음).
3. 관련 UNKNOWN, GUIDE, 또는 데이터 의도인데 사용법 말투면 안내 LLM을 부른다(문서 전체 + 질문).
4. LLM이 GUIDE로 답하고 `TreasurerGuideCheck`를 통과하면 `intent=GUIDE`, `view=TEXT`, 후속 질문을 `prompts`에 담아 돌려준다. 저장되는 답변은 `generatedBy=LLM`이다.
5. 그 밖에는 기존 흐름(되묻기, 영수증 고정 문구, 정산 조회)으로 간다.

### Failure flow

- LLM 미설정(`TREASURER_LLM_PROVIDER=none`), 키 없음, 호출 실패, JSON 아님 → 기존 흐름. 사용자에게 오류를 내지 않는다.
- 검사 탈락 → 기존 흐름. `reason`만 경고 로그로 남기고 질문·답변 원문은 남기지 않는다.
- 문서 리소스가 없거나 근거 ID가 중복 → 서버가 뜨지 않는다(배포 단계에서 막는다).

### Boundary conditions

- 안내 LLM이 UNRELATED라고 하면 예시 칩과 함께 범위 밖 안내(`UNKNOWN_ANSWER`).
- 안내 LLM이 DATA라고 하면 데이터 의도가 있을 때는 정산을 조회하고, 없으면 되묻는다.
- "보낼 돈 어떻게 돼?", "보낼 돈 뭐야?", "왜 이렇게 많아?"는 사용법 말투로 보지 않는다. 안내를 거치지 않아 지금 속도를 유지한다.
- 문서에 있는 예시 금액(리캡의 40,000원 등)은 답에 써도 된다. "0원"처럼 문서 숫자의 일부만 떼어 쓴 것은 막는다.

## 4. Contracts

### Input Port and boundary mapping

- 변경 없음. `AskTreasurerCommand(question)`.

### Output Ports

| Port | 책임 |
|---|---|
| `LoadTreasurerKnowledgePort` | 서비스 안내 문서 제공. 구현 `ClasspathTreasurerKnowledgeAdapter`가 시작할 때 한 번 읽고 검증 |
| `GenerateTreasurerGuidePort` | 문서와 질문으로 LLM 안내 응답(`TreasurerGuideReply`)을 받는다. 실패는 `Optional.empty()` |

### Application Policy

| Policy | 판단 |
|---|---|
| `TreasurerGuideCheck` | 경로가 GUIDE인지, 길이 600자 이하, 근거 ID 존재, 문서에 없는 숫자 없음, 후속 질문 정리(40자, 3개, URL·줄바꿈·미기재 숫자 제외) |
| `InquiryHowToPolicy` | 데이터 의도 질문이 사용법 말투인지 |
| `InquiryScopePolicy`(수정) | 앱 기능 이름(로그인, 탈퇴, 알림, 리캡 등)을 관련 질문으로 본다. 비밀번호·링크·프로필처럼 일상어로도 쓰는 말은 앱 문맥 단어(얼마, 정산, 그룹, 계좌 등)가 있을 때만 |

### Web API

- 변경 없음. 기존 `TreasurerAnswerResult.prompts`(후속 질문 칩)를 안내 답변에도 채운다. 칩 `type`은 `GUIDE`.

### Persistence and external integrations

- DB 변경 없음. 안내 답변도 기존 `treasurer_answers`에 저장된다(신고 가능).
- OpenAI는 `response_format=json_object`, Gemini는 `responseMimeType=application/json`으로 요청한다. 문서가 매번 같아 프롬프트 앞부분이 캐시 대상이다.

## 5. Design decisions

### Business logic

- 벡터 RAG 대신 문서 전체를 시스템 프롬프트에 넣는다. 문서가 약 15KB라 컨텍스트에 여유가 크고, 검색 단계가 없으니 엉뚱한 조각을 근거로 그럴듯하게 틀리는 실패가 없다. 문서가 커지면 근거 ID 단위로 골라 넣는 방식으로 바꾼다.
- 문서 내용은 노션 「기능 명세서 v4」를 바탕으로 develop 코드와 대조해 썼다. 명세와 코드가 다르면 코드를 따랐다(예: 품목 몫 비율은 1, 1/2, 1/3, 1/4만, 배달비 반영). 코드도 없는 기능(정기 정산, 압축 정산, 카테고리, 게임, 위젯, 정산 수정, PIN 변경)은 `[NOT-YET]`에 모았다.
- 회원 탈퇴는 끝나지 않은 정산이 있으면 막히고, 방장은 권한을 넘긴 뒤 탈퇴할 수 있다(`USER_DELETION_BLOCKED_BY_SETTLEMENT`, `USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED`).
- AI 대화 기록은 휴대폰과 서버에 각각 남는다. 문서는 "서버에 일정 기간 보관"으로 적어 보관 기간 설정(`TREASURER_ANSWER_RETENTION`)이 바뀌어도 틀리지 않게 했다.
- 영수증 고정 문구(`OCR_ANSWER`)를 실제 기능에 맞게 고쳤다. LLM을 끈 환경에서도 이 문구가 나간다.

### Technology and patterns

#### 정산 숫자 다듬기와 안내 LLM을 따로 둔다

- `TreasurerGuideLlmAdapter`를 `TreasurerLlmAdapter`와 별도 빈으로 둔다. 한 클래스가 두 포트를 구현하면 `@MockitoBean GenerateTreasurerAnswerPort`가 빈을 바꿔치울 때 안내 포트 빈까지 사라진다. HTTP 호출 코드 약 40줄이 겹치는 것은 감수한다.

#### 사용법 말투는 좁게 잡는다

- 처음에는 "어떻게", "뭐야", "왜"까지 넣었지만, "보낼 돈 어떻게 돼?" 같은 흔한 데이터 질문마다 LLM을 한 번 더 부르게 돼(최악 20초 + 20초) 독립 리뷰에서 P1로 지적됐다. 하는 방법을 묻는 표현만 남겼다.

#### LLM 답을 서버가 다시 검사한다

- 근거 ID 대조와 숫자 대조는 서버가 확인할 수 있는 최소한의 사실 검사다. "있는 기능처럼 말하는" 문장 자체는 막지 못하므로 규칙 프롬프트(문서에 없으면 없다고 가정)와 `[NOT-YET]` 목록으로 보완한다.

## 6. Impact surface

- 변경: `AskTreasurerService`(생성자에 의존성 4개 추가, 안내 분기), `InquiryScopePolicy`(관련 단어), `TreasurerCardCopy.OCR_ANSWER`
- 추가: `TreasurerKnowledge`, `TreasurerGuideReply`, `LoadTreasurerKnowledgePort`, `GenerateTreasurerGuidePort`, `ClasspathTreasurerKnowledgeAdapter`, `TreasurerGuideLlmAdapter`, `TreasurerGuideCheck`, `InquiryHowToPolicy`, `service-knowledge.md`
- 문서: `ai-treasurer.md`의 미분류·GUIDE 서술
- 앱: 변경 없음. 단, 앱이 UNKNOWN 외 답변에서도 `prompts` 칩을 그리는지 확인 필요
- 비용: 안내 경로 질문마다 LLM 1회(시스템 프롬프트 약 15KB)

## 7. Implementation plan

1. 서비스 안내 문서 작성(명세서 v4 × develop 대조)
2. 문서 모델·로더, 안내 포트·어댑터
3. 검사 Policy, 사용법 말투 Policy, 관련성 단어 보강
4. `AskTreasurerService` 분기
5. 단위 테스트, 독립 리뷰 반영

### Expected commit boundaries

1. `docs(treasurer): AI 비서 서비스 안내 설계 수립`
2. `feat(treasurer): AI 비서가 서비스 안내 문서로 사용법 질문에 답한다` (기존 테스트의 생성자 인자와 영수증 문구 단언 수정 포함)
3. `test(treasurer): AI 비서 서비스 안내 검증 추가`
4. `docs(treasurer): AI 비서 서비스 안내 검증 결과 기록`

## 8. Verification

### Commands

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

로컬 Mac에서 `compose.test.yml`(Postgres, Redis)을 띄워 위 명령을 실행했고 `BUILD SUCCESSFUL`이었다. 22개 suite, 230건이 실패·오류·건너뜀 없이 통과했다. 기준 커밋은 `c06a048d`(develop `a662f1e2` 위)다.

| Item | Result | Evidence |
|---|---|---|
| 질문 흐름 | `AskTreasurerServiceTest` 58건 통과. 신규 10건: 관련 미분류 → 안내, 안내 답변 LLM 저장, 무관 질문 LLM 미호출, 사용법 말투 → 안내, 안내가 DATA → 정산 조회, 흔한 데이터 말투 LLM 미호출, 안내 미사용 시 정산 조회, 미기재 근거 → 되묻기, UNRELATED → 예시, 영수증 질문 안내 | `build/test-results/test/*.xml` |
| 답변 검사 | `TreasurerGuideCheckTest` 14건 통과(근거 ID, 미기재 숫자·부분 숫자·다른 표기 금액, 길이, 마크다운·코드 울타리, 후속 질문, 거절 이유) | 같은 위치 |
| 말투·관련성 | `InquiryHowToPolicyTest` 21건, `InquiryScopePolicyTest` 34건, 기존 `InquiryParserTest` 29건 통과 | 같은 위치 |
| 문서·어댑터 | `TreasurerKnowledgeTest` 3건, 배포 문서 2건, `TreasurerGuideLlmAdapterTest` 7건 통과 | 같은 위치 |
| 스프링 구성 | `AI 비서 답변 신고 영속 통합` 13건, `AI 비서 Web API 계약` 14건 통과. 새 빈과 배포 문서로 컨텍스트가 뜬다 | 같은 위치 |
| 실제 LLM 응답 | 실행하지 않음. 이 환경에 LLM 키가 없다 | 배포 후 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-14 | 명세서 v4 열람·코드 대조, 문서 작성, 구현, 독립 리뷰 반영 | Gradle 테스트, 실제 LLM 평가, 이슈 생성 | 앱이 GUIDE 답변의 칩을 그리지 않을 수 있음 |
| 2026-10-14 | Gradle 테스트 230건 통과, 완료 계획서로 이동 | 이슈·PR, 배포 후 실제 LLM 확인 | 없음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-14 | 문서 전체 주입(하네스), RAG 미도입 | 문서가 작고 검색 오답 위험이 없다 | 문서가 커지면 근거 ID 단위 선택 |
| 2026-10-14 | 명세서 진행 상태가 아니라 코드 존재 여부로 안내 범위를 정한다 | 명세서 상태 표시가 실제와 어긋나 있다(서버에 이미 있는 기능이 "시작 전") | 코드가 없는 기능만 `[NOT-YET]` |
| 2026-10-14 | 사용법 말투를 좁게 잡는다 | 흔한 데이터 질문의 지연·오답 방지(독립 리뷰 P1) | 일부 사용법 질문은 정산 조회로 빠질 수 있다 |
| 2026-10-14 | 문서에 없는 단위 숫자를 막는다 | 30분·8자리 같은 규칙을 틀리게 말하는 것도 오안내다(독립 리뷰 P2) | 문서 숫자를 다른 표현으로 쓰면 답이 버려질 수 있다 |
| 2026-10-14 | 일상어 기능 이름은 앱 문맥이 있을 때만 관련 질문 | 무관 질문의 LLM 호출 줄이기(독립 리뷰 P2) | "알림이 안 와" 같은 질문은 문맥 없이도 관련으로 본다 |

## 11. Completion record

- Completed on: 2026-10-14
- Final behavior: 관련 미분류 질문, 영수증 안내, 사용법 말투 질문은 서비스 안내 문서 전체를 근거로 LLM이 답하고, 근거 ID와 숫자를 통과한 답만 `intent=GUIDE`로 후속 질문 칩과 함께 나간다. 실패하면 기존 고정 안내나 정산 조회로 돌아간다. 무관 질문과 흔한 데이터 질문은 안내 LLM을 부르지 않는다
- Unverified items: 실제 LLM 응답 품질과 지연, 앱의 GUIDE 답변 칩 표시
- Remaining risks: LLM이 근거 ID를 맞게 달고도 문서와 다른 내용을 말하는 경우는 숫자 외에는 막지 못한다. 안내 경로 질문마다 LLM 비용이 든다
- Follow-up work: 실제 LLM 정답 세트 평가, 답변 바로가기 액션(앱 계약 합의 후), 기능 출시 때 안내 문서 갱신
