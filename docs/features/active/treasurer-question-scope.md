# Feature Plan: AI 총무 질문 범위 개선 (관련성 판단과 의도 분류 분리)

- Status: Draft
- Last updated: 2026-09-09
- Related issue: [#317](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/317) (선행), [#318](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/318), [#319](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/319), [#320](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/320)
- Related ADR: N/A

## 1. Purpose

### Problem

AI 총무는 `InquiryParser`의 키워드 매칭이 `InquiryIntent.UNKNOWN`을 반환하면 `AskTreasurerService.ask()` 진입 직후 고정 안내 문구로 종료한다. 질문이 HowMuch와 관련 있는지 판단하는 단계가 없어 "세부 의도 분류 실패"와 "서비스와 무관한 질문"이 같은 경로로 처리된다.

그 결과 `정산은 어떻게 하는 거야?`와 `푸딩 만드는 방법 알려줘.`가 완전히 동일한 응답을 받는다. 사용자는 서비스 관련 질문을 했는데 거절당한다.

### Why now

`InquiryParser`의 분류 로직을 분리 실행해 질문별 결과를 대조한 결과, 서비스 관련 일반 질문 9개 중 6개, 구어체 정산 질문 11개 중 8개가 `UNKNOWN`으로 차단된다. 필터를 넓히는 후속 작업(#318, #319)이 모두 이 분리 구조 위에서만 성립한다.

### Success criteria

- [ ] 서비스 관련 질문이 세부 의도 분류 실패만으로 범위 안내를 받지 않는다.
- [ ] 서비스와 무관한 질문은 기존과 동일하게 범위 안내를 받는다.
- [ ] LLM 미사용(`TREASURER_LLM_PROVIDER=none`)에서도 규칙만으로 위 두 항목이 성립한다. 단, 규칙 판정 범위는 서비스 어휘를 포함한 질문이다.
- [ ] `InquiryIntent` 값과 `POST /api/v2/treasurer/questions` 응답 필드가 변경되지 않는다.
- [ ] 금액·건수는 계속 서버가 계산하고 LLM이 생성하지 않는다.

### Out of scope

- 서비스 이용 안내 질문에 대한 실제 설명 답변 — #319
- 자연어 표현 확장과 정산 제목 오추출 수정 — #318
- 대화 Context(이전 질문 맥락) 활용 — 요청 DTO 확장과 이력 저장소 신규 도입이 필요하다. 별도 과제.
- `InquiryIntent` enum 확장 — 프론트엔드 응답 계약을 고정하기 위해 의도적으로 제외한다.

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `treasurer` 분석 계층, `PersonalSettlement` 판정 규칙, 기존 정산 조회 경로 전부
- Reason a new domain is required: 없음. 기존 `treasurer` Application 계층 안에서 해결한다.
- Relationships to other domains: 정산 목록을 읽기만 한다. 변경 없음.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 서비스 관련성 | 질문이 HowMuch의 정산·지출·모임·서비스 이용과 관련되는지 | `InquiryScope` |
| 세부 의도 | 관련 질문 안에서 무엇을 요구하는지 | `InquiryIntent` (기존) |
| 되묻기 | 관련 질문이지만 세부 의도를 특정하지 못해 추가 정보를 요청하는 응답 | `TreasurerCardCopy.CLARIFY_ANSWER` |
| 범위 안내 | 서비스와 무관한 질문에 지원 범위를 알리는 응답 | `TreasurerCardCopy.UNKNOWN_ANSWER` (기존) |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 자연어 질의 | `AskTreasurerUseCase` (기존, 변경 없음) |

### State and invariants

- 상태 전이 없음. 읽기 전용 질의다.
- 항상 성립해야 하는 규칙: 서비스 관련성 판단은 세부 의도 분류보다 먼저 수행하며, 세부 의도 분류 실패가 곧 거절이 되지 않는다.
- 잘못된 상태: 관련성 판단 없이 `UNKNOWN`만으로 응답을 확정하는 것.

## 3. Scenarios

### Normal flow

1. 인증 사용자가 `POST /api/v2/treasurer/questions`로 질문을 보낸다.
2. `InquiryParser`가 세부 의도를 분류한다.
3. 의도가 `UNKNOWN`이 아니면 기존 경로를 그대로 탄다.
4. 의도가 `UNKNOWN`이면 `InquiryScopePolicy`가 서비스 관련성을 판정한다.
5. 관련이면 되묻기 응답, 무관이면 범위 안내를 반환한다.

### Failure flow

- 미인증: 401 UNAUTHORIZED (기존)
- 빈 질문: 400 (Jakarta validation, 기존)
- LLM 호출 실패 또는 미사용: 규칙 판정 결과만 사용한다. 예외를 전파하지 않는다.

### Boundary conditions

- 규칙이 관련으로 판정하지 못하고 LLM도 없으면 무관으로 본다. 기존 동작을 유지하는 보수적 선택이다.
- 규칙과 LLM 판정이 다르면 규칙의 관련 판정을 우선한다. LLM은 규칙이 판정하지 못한 경우에만 개입한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: AskTreasurerUseCase (변경 없음)
Command: AskTreasurerCommand (변경 없음)
Result:  TreasurerAnswerResult (변경 없음)
Web Request DTO -> Command: AskTreasurerWebRequest.toCommand() (변경 없음)
Result -> Web Response DTO: TreasurerAnswerWebResponse.from() (변경 없음)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `ClassifyTreasurerQuestionPort` | 규칙이 판정하지 못한 질문의 서비스 관련성을 LLM에 묻는다. 미사용·실패는 `Optional.empty()` | `AskTreasurerService` | `TreasurerLlmAdapter` |
| `GenerateTreasurerAnswerPort` | 기존. 답변 문장 생성 | `AskTreasurerService` | `TreasurerLlmAdapter` |
| `LoadTreasurerSettlementsPort` | 기존. 변경 없음 | `TreasurerSettlementQuery` | `TreasurerSettlementPersistenceAdapter` |

### Application Policy

- Policy가 소유하는 판단: 질문 문장이 HowMuch 서비스와 관련되는지 (`InquiryScopePolicy`)
- Service가 Policy에 넘기는 사실: 질문 문자열
- Entity 지역 불변식이 아닌 이유: 정산 엔티티 상태가 아니라 질문 해석 규칙이다. 여러 어휘 집합을 조합해 결정한다.
- 아직 구현하지 않는 입력: 이전 대화 맥락, 사용자별 모임 이름 목록

### Web API

```text
POST /api/v2/treasurer/questions
Request:  { "question": "..." }            (변경 없음)
Response: TreasurerAnswerWebResponse       (필드 변경 없음)
Errors:   400 (빈 질문), 401 (미인증)      (변경 없음)
Authentication: 기존 /api/** authenticated
```

응답 매핑 (계약 고정):

| 질문 | `intent` | `view` | `answer` |
|---|---|---|---|
| 정산 데이터 조회 | 기존 5종 | `LIST` / `SUMMARY` | 기존 |
| 관련이나 의도 불명 | `UNKNOWN` | `TEXT` | 되묻기 |
| 서비스 무관 | `UNKNOWN` | `TEXT` | 범위 안내 |

`intent` 하나로 되묻기와 범위 안내를 겸하므로 검증은 `answer` 문구로 구분한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): **No**
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: LLM 분류 호출 1건 추가. 기존 `treasurerLlmRestClient`와 `TreasurerLlmProperties`를 재사용한다.
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- 규칙 위치: 서비스 관련성은 `InquiryScopePolicy`, 세부 의도는 기존 `InquiryParser`
- Domain 소유: `PersonalSettlement`의 정산 판정. 변경하지 않는다.
- Application Policy 소유: 관련성 판정
- Application 오케스트레이션: 의도 분류 → 관련성 판정 → 응답 선택

### Technology and patterns

- Constraint to solve: `TreasurerLlmProperties`의 기본 provider가 `none`이고 키가 없으면 `generate()`가 즉시 `Optional.empty()`를 반환한다. 분류를 LLM 단독에 두면 기본 환경에서 전부 실패한다.
- Selected approach: 규칙 1차, 규칙이 판정하지 못한 경우에만 LLM 2차
- Reason for selection: LLM 없이도 동작하고, 규칙이 놓친 표현은 LLM이 흡수한다
- Alternatives considered: (1) LLM 단독 분류 — 기본 환경에서 동작하지 않음 (2) 키워드 목록만 확장 — 표현이 늘 때마다 같은 문제가 반복됨
- Trade-offs and limits: LLM 미사용 환경에서는 서비스 어휘를 포함하지 않은 질문(`이번 달에 처리해야 하는 게 뭐야?`)이 여전히 범위 안내를 받는다. #318의 표현 확장으로 좁혀 나간다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음 | `TreasurerController`, `TreasurerAnswerWebResponse` |
| Application | 분기 교체, Policy 신설, Port 신설 | `AskTreasurerService`, `InquiryScopePolicy`, `ClassifyTreasurerQuestionPort` |
| Domain | 없음 | `InquiryIntent`, `PersonalSettlement` |
| Persistence | 없음 | `TreasurerSettlementPersistenceAdapter` |
| Redis | 없음 | - |
| Security | 없음 | 기존 `/api/**` authenticated |
| Configuration | 없음 | `TreasurerLlmProperties` 재사용 |
| Documentation | 본 문서, `ai-treasurer.md` 갱신 | `docs/features/active/` |

Existing behavior that must remain unchanged:

- 보낼·받을·상태·지출·마감 질의의 분류 결과와 조회 결과
- OCR 안내(`GUIDE`) 고정 응답
- `GET /chat`, `/summary`, `/recommendations` 전부
- 금액·건수 계산과 `TreasurerAnswerFactCheck` 검증
- 사용자 본인 기준 정산 상태 판정과 추천 우선순위

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`Required` / `N/A`) — **N/A** (스키마 변경 없음)
- [ ] UseCase and Application Command/Result — **N/A** (기존 계약 유지)
- [x] Required Output Ports — `ClassifyTreasurerQuestionPort`
- [ ] Domain models and rules — **N/A** (`InquiryIntent` 변경하지 않음)
- [ ] Application Policies — `InquiryScopePolicy`, `InquiryScope`
- [ ] Application Service — `AskTreasurerService` 2단계 분기
- [ ] Output Adapters — `TreasurerLlmAdapter`에 분류 호출 추가
- [ ] Web Request/Response DTOs, Controllers, and Swagger — **N/A** (계약 변경 없음)
- [ ] Infra configuration and Bean wiring — **N/A** (기존 Bean 재사용)
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): AI 총무 질문 범위 개선 설계 수립
feat(treasurer): 서비스 관련성 판단과 세부 의도 분류를 분리한다
feat(treasurer): 규칙이 판정하지 못한 질문의 관련성을 LLM으로 보완한다
test(treasurer): 서비스 관련 질문 차단 회귀를 검증한다
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*Treasurer*' --tests '*Inquiry*'
git diff --check
git status --short --untracked-files=all
```

`./gradlew test` 전체 실행은 PostgreSQL과 Redis가 필요하다. 환경이 없으면 treasurer 단위 테스트로 범위를 제한하고 미검증 범위를 기록한다.

### Completion criteria

- [ ] Purpose의 사용자 결과를 검증했다.
- [ ] 정상·실패·경계 흐름을 검증했다.
- [ ] 문서와 실제 계약이 일치한다.
- [ ] 수행하지 못한 검증과 사유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 설계 문서 | Pending | 본 문서 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-09 | 코드 분석, 이슈 #317~#320 등록, 설계 문서 작성 | Policy와 Port 구현 | LLM 미사용 환경의 규칙 판정 범위 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-09 | `InquiryIntent`를 확장하지 않는다 | 프론트엔드 응답 계약 고정 | 되묻기와 범위 안내가 같은 `UNKNOWN` 값을 쓴다. 검증은 `answer` 문구로 구분한다 |
| 2026-09-09 | 관련성 판정은 규칙 1차, LLM 2차 | 기본 provider가 `none`이라 LLM 단독 의존이 불가능하다 | 규칙이 놓친 표현은 LLM 없이는 범위 안내를 받는다 |
| 2026-09-09 | 기존 정산 조회·계산 경로를 그대로 재사용한다 | 정산 Business Logic이 이미 개인 기준으로 동작한다 | AI 총무 변경이 정산 도메인에 영향을 주지 않는다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
