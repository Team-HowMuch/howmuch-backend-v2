# Feature Plan: AI 비서 답변 항목에 정산 유형·상태 추가

- Status: Completed
- Last updated: 2026-09-09
- Related issue: [#341](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/341)
- Related ADR: N/A

## 1. Purpose

### Problem

`POST /api/v2/treasurer/questions` 응답의 `items`에는 `direction`과 `marker`만 있다. 앱은 카드를 눌렀을 때 정산 목록과 같은 분기(내가 낼 정산이고 미완료면 참여·송금 화면, 그 밖에는 상세)로 이동해야 하는데, 그 분기에 필요한 값이 없다.

- 정산이 끝났는지: `marker`는 카드 강조 표시를 정하는 값이라 정산 상태로 쓸 수 없다.
- 더치페이인지 품목 배분인지: 응답에 아예 없다.

카드 하단에 "누르면 해당되는 정산 상세로 이동해요!"를 안내하면서 실제로는 눌러도 아무 일이 없다.

### Why now

앱 이슈 howmuch-frontend-v2#98이 이 필드를 기다리고 있다. 필드가 생기면 앱은 정산 목록과 같은 분기를 그대로 붙인다.

### Success criteria

- [x] `items[].allocationType`이 `DUTCH_PAY` / `ITEM_ALLOCATION` 또는 `null`로 내려간다.
- [x] `items[].status`가 정산 목록 응답과 같은 값으로 내려간다.
- [x] 기존 필드와 응답 구조가 변경되지 않아 기존 앱 버전이 그대로 동작한다.
- [x] 정산 조회 횟수가 늘지 않는다.

### Out of scope

- `GET /api/v2/treasurer/recommendations`의 `Item` — 이슈에서 적용 범위 판단을 요청했다. 아래 Open questions 참고.
- 앱의 화면 전환 구현
- 정산 도메인 로직 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `treasurer` 분석 계층과 기존 정산 조회 쿼리
- Reason a new domain is required: 없음
- Relationships: `settlements.allocation_type`과 `settlements.status`를 읽기만 한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 배분 유형 | 더치페이인지 품목 배분인지 | `SettlementAllocationType` |
| 정산 상태 | 정산 진행 상태 | `SettlementStatus` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 로그인 사용자 | 답변 카드에서 해당 정산 화면으로 이동 | `AskTreasurerUseCase` (기존) |

### State and invariants

- 상태 전이 없음. 읽기 전용 질의다.
- `allocationType`은 배분 방식을 정하기 전 정산에서 `null`이다. 정산 목록 응답과 같은 규칙이다.

## 3. Scenarios

### Normal flow

1. 사용자가 AI 비서에 정산을 묻는다.
2. 서버가 기존 조회 경로로 본인 정산을 읽는다.
3. 답변 카드에 배분 유형과 정산 상태를 함께 담는다.
4. 앱이 두 값으로 이동 화면을 정한다.

### Failure flow

- 기존과 동일하다. 새 실패 경로가 없다.

### Boundary conditions

- 배분 방식을 정하지 않은 정산: `allocationType`이 `null`이다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: AskTreasurerUseCase (변경 없음)
Command: AskTreasurerCommand (변경 없음)
Result:  TreasurerAnswerResult.Item 에 allocationType, status 추가
Result -> Web Response DTO: TreasurerAnswerWebResponse.Item 에 문자열 두 필드 추가
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadTreasurerSettlementsPort` | 기존 조회에 배분 유형을 함께 싣는다 | `TreasurerSettlementQuery` | `TreasurerSettlementPersistenceAdapter` |

### Application Policy

- `TreasurerCardCopy`가 카드를 만들 때 두 값을 함께 담는다. 문구 규칙은 변경하지 않는다.

### Web API

```text
POST /api/v2/treasurer/questions
Response items[]:
  기존: settlementId, requestedDate, headline, subtitle, direction, amount, marker
  추가: allocationType (DUTCH_PAY | ITEM_ALLOCATION | null)
        status (정산 목록과 동일한 값)
Errors: 변경 없음
Authentication: 변경 없음
```

필드 추가라 기존 클라이언트는 영향을 받지 않는다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: treasurer 전용 native query의 SELECT와 GROUP BY에 `settlement.allocation_type`을 추가한다. 조인이나 조회 횟수는 늘지 않는다.
- Flyway migration required (`Yes` / `No`): **No** (기존 컬럼을 읽기만 한다)
- Redis, 외부 API, 환경 변수: 변경 없음

## 5. Design decisions

### Business logic

- 새로 계산하는 값이 없다. 정산 도메인이 이미 가진 값을 그대로 옮긴다.
- 앱이 정산 목록과 같은 분기를 쓰도록 `status`는 목록 응답과 같은 값으로 내린다.

### Technology and patterns

- Constraint to solve: 앱이 카드 탭 시 이동 화면을 정할 근거가 없다.
- Selected approach: 답변 항목에 두 필드를 추가한다.
- Alternatives considered: 앱이 카드를 누를 때 정산 상세를 한 번 더 조회해 분기한다.
- Reason for selection: 이슈에서 대안을 기각했다. 탭과 화면 전환 사이에 왕복이 끼어 반응이 느려지고, 목록·알림함과 달리 AI 비서만 다르게 동작하게 된다.
- Trade-offs and limits: `allocationType`이 `null`인 정산에서 앱이 어떤 화면을 쓸지는 앱이 정한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 항목 필드 2개 추가 | `TreasurerAnswerWebResponse` |
| Application | Result·Policy·조회 매핑에 필드 전달 | `TreasurerAnswerResult`, `TreasurerCardCopy`, `TreasurerSettlementQuery`, `AskTreasurerService` |
| Domain | `PersonalSettlement`에 배분 유형 추가 | `PersonalSettlement` |
| Persistence | native query SELECT·GROUP BY, projection | `SettlementJpaRepository`, `TreasurerSettlementPersistenceAdapter` |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | 본 문서, `ai-treasurer.md` 갱신 | `docs/features/active/` |

Existing behavior that must remain unchanged:

- 기존 응답 필드와 카드 문구
- 금액·건수 집계와 정산 상태 판정
- `GET /chat`, `/summary`, `/recommendations` 응답

## 7. Implementation plan

- [x] Migration-only pull request to `develop` — **N/A** (스키마 변경 없음)
- [x] UseCase and Application Command/Result — `TreasurerAnswerResult.Item`
- [x] Required Output Ports — `LoadTreasurerSettlementsPort.TreasurerSettlementRecord`
- [x] Domain models and rules — `PersonalSettlement`
- [x] Application Policies — `TreasurerCardCopy.Card`
- [x] Application Service — `AskTreasurerService.toItem`
- [x] Output Adapters — native query, projection, 매핑
- [x] Web Request/Response DTOs — `TreasurerAnswerWebResponse.Item`
- [x] Infra configuration and Bean wiring — **N/A**
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): AI 비서 답변 항목 정산 유형 상태 추가 설계 수립
feat(treasurer): AI 비서 답변 항목에 정산 유형과 상태를 담는다
test(treasurer): 답변 항목의 정산 유형 상태 회귀 검증
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
docker compose -f compose.test.yml up -d
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test \
DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh test --tests '*Treasurer*'
git diff --check
```

### Completion criteria

- [x] 응답에 두 필드가 나가는 것을 확인했다.
- [x] native query가 배분 유형을 함께 싣는 것을 통합 테스트로 확인했다.
- [x] 기존 필드와 문구가 변하지 않았다.

### Results

| Item | Result | Evidence |
|---|---|---|
| treasurer·inquiry 테스트 | 67개 통과, 실패 0, 건너뜀 0 | `compose.test.yml`(PostgreSQL 18 · Redis 8) + `scripts/test.sh` |
| native query 배분 유형 조회 | 통과 | `TreasurerSettlementPersistenceIntegrationTest` 2건 |
| 배분 유형 매핑(값 있음·null) | 통과 | `TreasurerSettlementPersistenceAdapterTest` 2건 |
| 카드 세 분기 필드 전달 | 통과 | `TreasurerCardCopyTest` 5건 |
| 응답 JSON 필드 노출 | 통과 | `TreasurerControllerTest` 5건 |
| 수동 앱 연동 확인 | 미수행 | 앱 화면 전환은 howmuch-frontend-v2#99에서 검증 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-09 | 이슈 분석, 설계 문서 작성, 구현 | 검증 | 적용 범위(추천 API) 미결 |
| 2026-09-10 | 카드 분기 테스트 보강, 전체 검증 완료 | PR 생성 | 없음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-09 | 앱 추가 조회 대신 서버 필드 추가 | 화면 전환 지연과 AI 비서만 다른 동작을 피한다 | 응답 필드가 늘어난다 |
| 2026-09-09 | 웹 응답에서 두 값을 문자열로 내린다 | 이 DTO의 기존 열거형 필드(`direction`, `marker`)가 모두 문자열이다 | JSON 표현은 열거형 직렬화와 동일하다 |
| 2026-09-09 | `PersonalSettlement`에 기존 생성자 시그니처를 보존한다 | 정산 조회 외 호출부와 테스트의 변경을 막는다 | 생성자 오버로드가 2개 늘어난다 |
| 2026-09-10 | `/recommendations`에는 추가하지 않는다 | 저장소 선례를 따른다. 배분 방식을 응답에 담은 기존 작업(`4224665`)이 필요한 응답 하나만 변경했고, `settlementId`를 담은 웹 응답 18개 중 `allocationType`을 가진 것은 6개다 | 추천 카드가 탭 대상이 될 때 같은 방식으로 추가한다 |

## 11. Open questions

- 없음. `GET /api/v2/treasurer/recommendations` 적용 여부는 아래 결정 기록으로 종결했다.

## 12. Completion record

- Completed on: 2026-09-10
- Final behavior: `POST /api/v2/treasurer/questions` 응답의 `items`가 `allocationType`(`DUTCH_PAY` / `ITEM_ALLOCATION` / `null`)과 `status`를 함께 반환한다. 기존 필드와 카드 문구는 변경하지 않았고, 정산 조회 횟수도 늘지 않았다.
- Unverified items: 앱에서의 실제 화면 전환. 서버 계약까지만 검증했고 앱 동작은 howmuch-frontend-v2#99 범위다.
- Remaining risks: `status`는 정산 전체 상태이므로 본인이 납부를 마쳐도 다른 참여자가 미납이면 `COMPLETED`가 아니다. `SUSPENDED` 정산도 `COMPLETED`가 아니라, 이슈에 적힌 앱 분기(`status !== "COMPLETED"`)로는 두 경우 모두 참여·송금 화면으로 이동한다. 정산 목록 API와 동일한 동작이라 일관성은 유지되지만 분기 기준은 프론트와 합의가 필요하다.
- Follow-up work: 사용자별 납부 여부와 정산 `COMPLETED`의 분리(`ai-treasurer.md` 미해결 과제). 추천 API 적용은 해당 카드가 탭 대상이 될 때.
