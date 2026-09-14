# Feature Plan: AI 비서 답변 신고

- Status: Draft
- Last updated: 2026-09-14
- Related issue: [#381](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/381), [howmuch-frontend-v2#205](https://github.com/Team-HowMuch/howmuch-frontend-v2/issues/205)
- Related ADR: N/A

## 1. Purpose

### Problem

**생성형 답변을 앱 안에서 신고할 수 없다.**

Google Play 생성형 AI 콘텐츠 정책은 생성 결과를 앱을 나가지 않고 신고할 수 있게 요구한다. `TreasurerLlmAdapter`가 OpenAI/Gemini를 실제로 호출하므로 AI 비서가 그 대상이다.

지금은 세 가지가 모두 없다.

- 신고 엔드포인트가 없다
- `POST /api/v2/treasurer/questions` 응답에 답변 식별자가 없다
- 질문·답변을 저장하지 않는다

그래서 프론트가 **어떤 답변을 신고하는지 가리킬 방법이 없다.**

### Why now

정책 요건이라 미루면 스토어 심사에서 막힌다. 프론트(#205)가 서버의 `answerId`를 기다린다.

### Success criteria

- [ ] 모든 답변 응답에 `answerId`가 포함된다.
- [ ] 그 `answerId`로 답변을 신고할 수 있다.
- [ ] 본인 답변만 신고할 수 있고, 남의 `answerId`는 404다.
- [ ] 같은 답변을 두 번 신고하면 409다.
- [ ] 신고되지 않은 답변은 보관 기간이 지나면 지워진다.
- [ ] 신고된 답변은 처리 완료 전까지 지워지지 않는다.
- [ ] 답변 저장이 실패해도 질문 응답 자체는 나간다.

### Out of scope

- 관리자 조회·처리 API. 저장까지만 하고 DB로 직접 본다
- 신고 알림(슬랙·푸시)
- 신고 사유별 자동 조치
- 답변 내용 자체의 품질 개선

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `Treasurer`(답변), `User`
- Reason a new domain is required, if applicable: 신고는 답변과 수명이 다르고 상태를 가진다. `TreasurerAnswerReport`를 새 도메인 모델로 둔다
- Relationships to other domains: `User`가 신고자이자 답변 소유자다. 정산 도메인과는 무관하다

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 저장된 답변 | 사용자에게 나간 AI 비서 답변 한 건 | `treasurer_answers` |
| 답변 식별자 | 프론트가 신고 대상을 가리키는 값 | `answerId` (UUID) |
| 생성 주체 | 문장을 LLM이 만들었는지 서버 문구인지 | `generated_by` (`LLM` / `SERVER`) |
| 신고 | 답변에 대한 사용자의 이의 제기 | `treasurer_answer_reports` |
| 처리 상태 | 신고를 확인했는지 | `status` (`OPEN` / `RESOLVED`) |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 이상한 답변을 앱 안에서 신고한다 | `ReportTreasurerAnswerUseCase` (신규) |
| 로그인 사용자 | 답변을 받는다 | 기존 `AskTreasurerUseCase` — `answerId` 추가 |
| 운영자 | 신고를 확인한다 | DB 직접 조회. API 없음 |

### State and invariants

- State transitions: 신고는 `OPEN` → `RESOLVED` 한 방향이다. 답변 자체는 상태가 없다.
- Rules that must always hold:
  - 신고자는 그 답변을 받은 사용자여야 한다.
  - 한 답변에 신고는 하나다.
  - 신고가 달린 답변은 지워지지 않는다.
- Invalid states: 남의 답변에 달린 신고, 답변 없는 신고.

## 3. Scenarios

### Normal flow

1. 사용자가 질문하고 답변과 `answerId`를 받는다.
2. 답변이 이상해서 신고 버튼을 누른다.
3. `POST /api/v2/treasurer/answers/{answerId}/reports`에 사유를 보낸다.
4. `reportId`와 `createdAt`을 받는다.

### Failure flow

- Condition: 남의 `answerId` 또는 없는 `answerId`
- Returned error or state transition: 404. 남의 것인지 없는 것인지 구분해 알리지 않는다. 존재 여부가 새면 안 된다
- Condition: 이미 신고한 답변
- Returned error or state transition: 409
- Condition: 답변 저장 실패
- Returned error or state transition: 질문 응답은 그대로 나간다. `answerId`가 없을 뿐이다. 저장 실패가 답변을 막지 않는다

### Boundary conditions

- 보관 기간이 지난 답변을 신고하면 404다. 앱이 오래된 대화를 들고 있을 수 있다.
- `detail`은 선택이고 500자 이하다.
- 같은 사용자가 동시에 두 번 신고하면 유니크 제약이 잡는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ReportTreasurerAnswerUseCase (신규)
Command: ReportTreasurerAnswerCommand(answerId, reason, detail)
Result: TreasurerAnswerReportResult(reportId, createdAt)
Web Request DTO -> Command: ReportTreasurerAnswerWebRequest.toCommand()
Result -> Web Response DTO: TreasurerAnswerReportWebResponse.from()

기존 AskTreasurerUseCase: TreasurerAnswerResult 에 answerId 추가
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SaveTreasurerAnswerPort` | 사용자에게 나간 답변을 남긴다 | `AskTreasurerService` | `TreasurerAnswerPersistenceAdapter` |
| `LoadTreasurerAnswerPort` | 신고 대상 답변을 본인 것으로 확인해 불러온다 | `ReportTreasurerAnswerService` | 위와 같음 |
| `SaveTreasurerAnswerReportPort` | 신고를 남긴다 | `ReportTreasurerAnswerService` | `TreasurerAnswerReportPersistenceAdapter` |
| `DeleteExpiredTreasurerAnswerPort` | 보관 기간이 지난 답변을 지운다 | 보관 정리 Worker | 위와 같음 |

### Application Policy

- Decision owned by the Policy: 없음. 규칙이 전부 불변식이라 도메인과 스키마가 지킨다
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: N/A
- Future inputs deliberately not implemented yet: 사유별 자동 조치

### Web API

```text
POST /api/v2/treasurer/answers/{answerId}/reports
Request: { "reason": "INACCURATE" | "OFFENSIVE" | "PRIVACY" | "OTHER", "detail": "선택, 500자 이하" }
Response: { "reportId": UUID, "createdAt": "2026-09-14T10:00:00" }
Errors:
  404 TREASURER_ANSWER_NOT_FOUND    본인 답변이 아니거나 없거나 보관 기간이 지남
  409 TREASURER_ANSWER_ALREADY_REPORTED
  400 검증 실패
Authentication: 기존 그대로 (로그인 필요)

POST /api/v2/treasurer/questions
Response: 기존 필드 + answerId (UUID, 저장 실패 시 null)
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 테이블 2개 신설
- Flyway migration required (`Yes` / `No`): **Yes**
- Planned migration: `V35__create_treasurer_answer_report_schema.sql`
- Migration pull request (`Pending` / URL): Pending
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 보관 기간과 정리 주기를 Properties로 뺀다

## 5. Design decisions

### Business logic

- Rules and their locations: 소유·중복 규칙은 스키마 제약으로 못 박고 서비스가 그 위반을 상태 코드로 옮긴다
- Behavior owned by Domain: 신고 상태 전이
- Decisions owned by Application Policy: 없음
- Flow orchestrated by Application: 답변 저장은 질문 흐름에, 신고는 별도 UseCase

### Technology and patterns

- Constraint to solve: 남의 답변을 신고하지 못하게 하되, 응용 계층 검사에만 기대지 않는다
- Selected approach: 복합 FK로 스키마가 소유를 강제한다
- Reason for selection: 저장소의 기존 기법이다(`uk_account_transactions_id_account` + 복합 FK)
- Alternatives considered: 서비스에서 `userId`를 비교만 하는 방법. 쓰기 경로에 버그가 생기면 남의 답변에 신고가 달린다
- Trade-offs and limits: `treasurer_answers`에 `UNIQUE (id, user_id)`가 한 줄 늘어난다

#### 소유는 스키마가 강제한다

`treasurer_answers (id, user_id)`에 유니크를 두고, 신고가 `(answer_id, reporter_user_id)`로 그 유니크를 참조한다. 그러면 **남의 답변에 달린 신고 행은 데이터베이스가 거부한다.** 응용 계층 검사는 사용자에게 404를 돌려주기 위한 것이지 보안 경계가 아니다.

#### 신고된 답변은 FK가 지킨다

보관 정리는 `treasurer_answers`를 지운다. 신고의 FK를 `ON DELETE RESTRICT`로 두면 **신고가 달린 답변은 지워지지 않는다.** 정리 쿼리에서 조건을 빠뜨려도 데이터가 사라지지 않는다.

#### 모든 답변을 저장한다

정책이 겨냥하는 것은 생성형 결과지만, 사용자는 그 답변을 LLM이 썼는지 서버 문구인지 알 수 없다. 응답에 `answerId`가 있다 없다 하면 앱이 분기해야 하고, 서버 문구가 틀렸을 때는 신고할 수 없다. 모두 저장하고 `generated_by`로 구분한다.

#### 답변 저장 실패가 답변을 막지 않는다

저장은 답변의 부수 효과다. 저장에서 예외가 나도 사용자는 답변을 받아야 한다. 이 경우 `answerId`가 `null`이고 앱은 신고 버튼을 숨긴다.

#### 질문 원문을 저장한다

신고를 확인하려면 무엇을 묻고 무엇을 답했는지 함께 봐야 한다. 질문에는 모임 이름이나 금액 같은 개인적인 내용이 들어갈 수 있으므로 **보관 기간을 두고 지운다.** 기본 30일로 시작한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 신규 엔드포인트 1개, 기존 응답에 `answerId` 추가 | `TreasurerController`, `TreasurerAnswerWebResponse` |
| Application | UseCase 1개, Port 4개, 답변 저장 부수 효과 | `AskTreasurerService` 외 |
| Domain | `TreasurerAnswerReport`, 사유·상태 enum | `domain/treasurer` |
| Persistence | 테이블 2개 신설 | `V35` |
| Redis | 없음 | - |
| Security | 소유 검사를 스키마와 응용 양쪽에 둔다 | `CurrentActorPort` |
| Configuration | 보관 기간·정리 주기 Properties | `infra/treasurer` |
| Documentation | 본 문서, `ai-treasurer.md` 응답 계약 | `docs/features/` |

Existing behavior that must remain unchanged:

- 기존 답변 내용·의도 분류·응답 필드
- 답변 지연. 저장이 응답을 늦추지 않아야 한다
- 로그인하지 않은 경로는 없다

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`Required` / `N/A`) — **Required**
- [ ] UseCase and Application Command/Result
- [ ] Required Output Ports
- [ ] Domain models and rules
- [ ] Application Policies — **N/A**
- [ ] Application Service
- [ ] Output Adapters
- [ ] Web Request/Response DTOs, Controllers, and Swagger
- [ ] Infra configuration and Bean wiring — 보관 정리 스케줄러
- [ ] Documentation and verification results

마이그레이션 PR을 먼저 만들고 그 시점에 작업을 멈춘다. 나머지는 후속 작업이다.

### Expected commit boundaries

```text
docs(treasurer): AI 비서 답변 신고 설계 수립
build(database): AI 비서 답변과 신고 스키마 추가      <- 여기서 멈춤
feat(treasurer): 답변을 저장하고 식별자를 반환한다
feat(treasurer): 답변 신고 API를 추가한다
feat(treasurer): 보관 기간이 지난 답변을 정리한다
test(treasurer): 소유·중복·보관 규칙을 검증한다
docs(treasurer): 답변 신고 검증 결과 기록
```

## 8. Verification

```bash
docker compose -f compose.test.yml up -d
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test \
DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh test --tests '*Treasurer*'
docker compose -f compose.test.yml down -v
```

소유·중복·삭제 제약은 실제 PostgreSQL에 붙는 통합 테스트로 확인한다. 모킹으로는 검증되지 않는다.

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
| 2026-09-14 | 현재 코드 확인, 설계 문서 작성 | 마이그레이션 PR | 질문 원문을 저장하므로 보관 기간을 반드시 지켜야 한다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | 관리자 API를 만들지 않는다 | 정책 요건은 앱 안에서의 신고까지다. DB로 확인한다 | 처리 상태 컬럼은 두되 조회는 후속 |
| 2026-09-14 | 모든 답변을 저장한다 | 사용자는 LLM 문장인지 서버 문구인지 모른다 | 행이 더 쌓인다. `generated_by`로 구분 |
| 2026-09-14 | 소유를 복합 FK로 강제한다 | 응용 계층 검사만으로는 쓰기 버그가 바로 남의 답변 신고가 된다 | `treasurer_answers`에 유니크 한 줄 추가 |
| 2026-09-14 | 신고 FK를 `ON DELETE RESTRICT`로 둔다 | 정리 쿼리가 조건을 빠뜨려도 신고된 답변이 사라지지 않는다 | 정리 시 신고된 건을 명시적으로 제외해야 한다 |
| 2026-09-14 | 저장 실패가 답변을 막지 않는다 | 저장은 부수 효과다 | `answerId`가 `null`인 응답이 나갈 수 있다 |
| 2026-09-14 | 보관 기간 기본 30일 | 질문 원문에 개인적인 내용이 들어갈 수 있다 | Properties로 조정 가능 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
