# Feature Plan: AI 비서 답변 신고

- Status: Completed
- Last updated: 2026-09-15
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

- [x] 모든 답변 응답에 `answerId`가 포함된다.
- [x] 그 `answerId`로 답변을 신고할 수 있다.
- [x] 본인 답변만 신고할 수 있고, 남의 `answerId`는 404다.
- [x] 같은 답변을 두 번 신고하면 409다.
- [x] 신고되지 않은 답변은 보관 기간이 지나면 지워진다.
- [x] 신고된 답변은 처리 완료 전까지 지워지지 않는다.
- [x] 답변 저장이 실패해도 질문 응답 자체는 나간다.

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
- Planned migration: `V36__create_treasurer_answer_report_schema.sql`
- Migration pull request (`Pending` / URL): `build/treasurer-answer-report-schema` 브랜치로 develop에 올림 (V35는 #380에서 선점되어 V36으로 조정)
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

#### 질문 흐름은 트랜잭션을 열지 않는다

이 흐름은 정산을 읽고, 최대 20초가 걸릴 수 있는 LLM을 호출하고, 답변을 저장한다.
바깥에 읽기 전용 트랜잭션을 두면 커넥션 하나를 LLM 호출 내내 쥔 채로 저장이 두 번째
커넥션을 요구한다. **동시 질문이 풀 크기만큼 들어오면 전부 첫 커넥션을 쥐고 두 번째를
기다려 아무도 진행하지 못한다.** 그러면 저장이 실패해 `answerId`가 비고, 이 기능이
지키려던 신고 경로가 조용히 꺼진다.

정산 조회는 어댑터가 자체 트랜잭션에서 하고 바로 커넥션을 돌려준다. 저장도 마찬가지다.
한 요청이 커넥션 두 개를 동시에 쥐는 구간이 없다. 주석만으로는 다음 사람이 다시 넣을 수
있어, LLM 호출 시점에 트랜잭션이 살아 있지 않은지 통합 테스트로 고정했다.

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
| Persistence | 테이블 2개 신설 | `V36` |
| Redis | 없음 | - |
| Security | 소유 검사를 스키마와 응용 양쪽에 둔다 | `CurrentActorPort` |
| Configuration | 보관 기간·정리 주기 Properties | `infra/treasurer` |
| Documentation | 본 문서, `ai-treasurer.md` 응답 계약 | `docs/features/` |

Existing behavior that must remain unchanged:

- 기존 답변 내용·의도 분류·응답 필드
- 답변 지연. 저장이 응답을 늦추지 않아야 한다
- 로그인하지 않은 경로는 없다

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`Required` / `N/A`) — **Required**
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules
- [x] Application Policies — **N/A**
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring — 보관 정리 스케줄러
- [x] Documentation and verification results

마이그레이션 PR을 먼저 만들고 멈춘 뒤, 그 위에서 나머지를 구현했다. 기능 브랜치는
마이그레이션 브랜치 위에 올려 두었으므로 마이그레이션이 머지되면 develop으로 재배치한다.

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
| 설계 문서 | 완료 | 본 문서 |
| V1~V36 마이그레이션 순차 적용 | 36개 성공 | 실제 PostgreSQL에 순서대로 적용 |
| 스키마 제약 21건 | 전부 의도대로 | 남의 답변 신고는 복합 FK가, 재신고는 유니크가, 신고된 답변 삭제는 RESTRICT가 거부 |
| 인덱스 사용 | `EXPLAIN ANALYZE`로 확인 | 정리 10만 행 중 300건에 heap 블록 5개, 소유 확인·중복 확인은 Index (Only) Scan |
| `FOR UPDATE SKIP LOCKED` | 동작 확인 | 한쪽이 2건을 잠근 동안 다른 쪽이 대기 없이 다른 2건 삭제 |
| 도메인 불변식 14건 | 전부 통과 | 컴파일 후 직접 실행 |
| Gradle 전체 스위트 | 170개 클래스 **1224건 전부 통과**, 실패·에러·건너뜀 0 | `compose.test.yml`(PostgreSQL 18 · Redis 8) + `scripts/test.sh clean test`, `origin/develop` 3583f00 위에서 실행. `build/test-results/test/*.xml` 파싱 |
| 신고 통합 테스트 | 13건 통과 (LLM 호출 중 트랜잭션 미보유 포함) | 같은 실행 |
| 웹 계약 테스트 | 14건 통과 | 같은 실행 |
| 질의 서비스 테스트 | 48건 통과 | 같은 실행 |
| 제목 확인 정책 | 15건 통과 | 같은 실행. develop의 `TitleHintVerification`(#382)과 합친 뒤 |

### develop 리베이스와 충돌 해결

PR을 연 뒤 #382(`fix/treasurer-unverified-title-hint`)가 develop에 머지되면서
`AskTreasurerService`에서 충돌이 났다. 팀이 머지 커밋을 쌓지 않으므로 리베이스로
해결했다. 두 변경이 같은 메서드들을 서로 다르게 바꾼 의미 충돌이라 손으로 합쳤다.

- develop(#382): `TitleHintVerification` 필드, `verify`/`matchesTitle`, `titleDropped`,
  3인자 `scopeLabel`, `answerList`/`answerStatus`/`phrasedAnswer`의 `deterministicOnly`
- 이 브랜치(#381): `@Transactional` 제거, `answerFor`/`saveAnswer` 분리,
  `Answered`/`Phrased` 레코드, `CurrentActorPort`·`SaveTreasurerAnswerPort`

한 곳은 판단이 필요했다. develop의 `if (deterministicOnly) return draft.fallbackAnswer();`
를 `return new Phrased(draft.fallbackAnswer(), TreasurerAnswerGeneratedBy.SERVER);` 로
합쳤다. 이름으로 좁히기에 실패한 답변은 LLM 문장을 쓰지 않으므로 생성 주체가 SERVER다.

필드가 10개가 되었고 선언 순서가 곧 Lombok 생성자 인자 순서다. 테스트의
`new AskTreasurerService(...)` 10개 인자와 순서가 일치하는지 따로 확인했다.

이전 실행에서 무관하게 실패하던 `ck_account_records_deleted_at` 2건
(`SettlementPaymentInfoHttpIntegrationTest`, `AccountDeletionPersistenceIntegrationTest`)은
이번 실행에서 나오지 않았다. 원인은 여전히 규명되지 않았다. 애플리케이션이 쓴
`created_at`과 트리거의 `CURRENT_TIMESTAMP`를 부등호로 묶은 제약이라, 서버와 DB
호스트의 시계가 다르면 운영에서도 같은 역전이 날 수 있다. 재현이 들쭉날쭉하다는 점
자체가 위험이므로 별도 이슈로 다룬다.

### 작성 중 사용한 대체 검증

Gradle을 돌릴 수 없는 환경에서 작업했다. 클라우드 세션은 Maven Central과
services.gradle.org가 막혀 있고 디바이스 VM에는 Java 11만 있다. 그래서
작성하는 동안에는 다음으로 대신 확인하고, 마지막에 맥에서 전체 스위트를
돌려 위 결과를 얻었다.

- 마이그레이션과 제약은 실제 PostgreSQL 인스턴스에 직접 적용해 확인
- 도메인 불변식은 javac로 컴파일한 뒤 실제 실행해 확인
- 생성자 인자 수 불일치는 선언과 호출부를 대조하는 검사기로 확인
  (이번에 `TreasurerController` 4→5, `AskTreasurerService` 7→9 두 건을 잡았다)
- 저장소 내부 import가 실재하는 파일을 가리키는지 확인

이 대체 검증은 컴파일러가 아니다. 실제로 `@DisplayName` import 누락과
Lombok 생성자 필드 순서 오류를 전체 스위트가 잡아냈다. 두 경우 모두 검사기를
보강했고, 보강한 뒤에는 그 오류를 일부러 되돌려 실제로 잡히는지 확인했다.
그래도 제네릭 불일치나 메서드 이름 오타는 여전히 컴파일러만 잡는다.

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 현재 코드 확인, 설계 문서 작성 | 마이그레이션 PR | 질문 원문을 저장하므로 보관 기간을 반드시 지켜야 한다 |
| 2026-09-20 | #382 머지로 생긴 충돌을 리베이스로 해결, 최신 develop(3583f00)에서 1224건 통과 | force-push | develop이 #402로 한때 깨져 있었다(#404로 수정됨). CI가 과금으로 멈춰 있어 로컬 실행이 유일한 검증 경로다 |

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

- Completed on: 2026-09-15
- Final behavior:
  - `POST /api/v2/treasurer/questions` 응답에 `answerId`가 실린다. 저장에 실패하면 `null`이고 앱은 신고 버튼을 숨긴다.
  - `POST /api/v2/treasurer/answers/{answerId}/reports`로 본인이 받은 답변을 신고한다. 남의 답변·없는 답변·정리된 답변은 모두 같은 404, 재신고는 409.
  - 보관 기간(기본 30일)이 지난 미신고 답변은 스케줄러가 배치로 정리한다. 신고된 답변은 남는다.
- Unverified items:
  - 실제 앱에서의 응답 확인. 서버 계약까지만 검증했다.
  - LLM을 실제로 붙인 경로(`provider != none`)에서의 `generated_by` 기록. 통합 테스트는 `provider=none`으로 돈다.
- Remaining risks:
  - 저장 실패 로그가 예외 타입만 남겨 원인 추적이 어렵다. 저장은 부수 효과라 삼키는 것이 맞지만, 실패가 잦아지면 신고 버튼이 조용히 사라지므로 메트릭이 필요하다.
  - `treasurer_answers.user_id`는 `ON DELETE CASCADE`인데 신고 FK가 `RESTRICT`라, 사용자 행을 물리 삭제하면 막힌다. 현재 탈퇴(#376)는 익명화라 실제 경로는 아니다.
  - 신고된 답변의 질문 원문은 보관 기간과 무관하게 남는다. 처리한 신고를 지우는 시점이 실제 삭제 시점이다.
- Follow-up work:
  - 탈퇴(#376) 시 그 사용자의 `treasurer_answers` 정리. 지금은 익명화만 하고 질문 원문은 남는다. 정산·금융 이력과 달리 상대방 이해관계가 없는 본인 데이터다.
  - 신고 확인·처리 관리자 API. 지금은 DB 직접 조회이고 `TreasurerAnswerReport.resolve`는 호출자가 없다. 그때 `(status, created_at)` 인덱스도 함께 넣는다.
  - `TreasurerAnswerFactCheck`의 기존 결함 2건. 허용 숫자를 폴백 문자열 전체에서 정규식으로 긁어 정산 제목의 숫자까지 허용되고, 월·일·년 앞 숫자는 초안에 날짜 사실이 없어도 무조건 통과한다.
  - `Clock` 빈이 `TreasurerConfig`에 있는데 정산·알림·계좌가 모두 쓴다. 공통 설정으로 옮기는 편이 맞다.
