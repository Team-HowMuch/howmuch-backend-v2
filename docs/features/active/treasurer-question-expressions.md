# Feature Plan: AI 총무 자연어 표현 다양성 지원 및 정산 제목 오추출 수정

- Status: Draft
- Last updated: 2026-09-10
- Related issue: [#318](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/318)
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

- [ ] 아래 구어체 표현이 의미에 맞는 의도로 분류된다.
- [ ] 문법 잔여물에서 정산 제목이 추출되지 않는다.
- [ ] 제목 힌트를 신뢰할 수 없으면 필터를 적용하지 않고 전체 범위로 조회한다.
- [ ] 제목 힌트는 데이터 조회 의도일 때만 추출한다.
- [ ] 기존 `InquiryParserTest`가 단언하는 분류 결과와 제목이 모두 유지된다.
- [ ] 서비스와 무관한 질문이 표현 확장 때문에 통과하지 않는다.

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

### 제목 추출을 잔여물 방식에서 문법 구간 방식으로 바꾼다

문자열을 잘라내는 대신 **문법 어휘가 차지한 문자 범위를 표시**하고, `모임`·`정산` 앞에서 문법 구간에 닿을 때까지만 거슬러 올라간다.

```text
"진행중인여행모임정산상태는"
 └문법┘                        진행중인 = 문법 구간
        여행모임               ← 문법 구간 직전까지만 후보
```

이렇게 하면 후보가 항상 원문의 연속 부분 문자열이라 `보이랑정산` 같은 조합이 나오지 않는다.

추가 판정 두 가지를 둔다.

- 앞부분이 두 글자 미만이면 버린다 (`에정산`, `내정산`)
- 앞부분이 의문사·부사면 버린다 (`왜아직정산`)

### 제목 힌트는 데이터 조회 의도일 때만 쓴다

설명형 질문에 제목 필터가 걸리면 0건 오답이 된다.

### Alternatives considered

- 형태소 분석기 도입: 정확하지만 의존성과 성능 비용이 크고, 이번 문제는 규칙으로 해결된다
- 사용자의 실제 모임·정산 이름과 대조: 가장 정확하지만 조회가 늘고 이번 범위를 넘는다

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 없음 | `TreasurerAnswerWebResponse` |
| Application | 표현 상수 재구성, 제목 추출 교체 | `InquiryParser` |
| Domain | 없음 | `InquiryIntent`, `PersonalSettlement` |
| Persistence | 없음 | - |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | 본 문서, `ai-treasurer.md`의 제목 힌트 서술 | `docs/features/` |

Existing behavior that must remain unchanged:

- 기존 `InquiryParserTest` 11건이 단언하는 의도·기간·제목
- 보낼/받을 키워드가 함께 있을 때 먼저 나온 쪽을 쓰는 규칙
- #317의 관련성 판정 결과
- 정산 조회·금액 계산·응답 계약

## 7. Implementation plan

- [ ] Migration-only pull request — **N/A**
- [ ] UseCase and Application Command/Result — **N/A**
- [ ] Required Output Ports — **N/A**
- [ ] Domain models and rules — **N/A**
- [ ] Application Policies — `InquiryParser` 표현 재구성과 제목 추출 교체
- [ ] Application Service — **N/A**
- [ ] Output Adapters — **N/A**
- [ ] Web DTOs, Controllers, Swagger — **N/A**
- [ ] Infra configuration — **N/A**
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): 질문 표현 확장과 제목 오추출 수정 설계 수립
fix(treasurer): 문법 잔여물이 정산 제목으로 추출되지 않게 한다
feat(treasurer): 정산 질문의 구어체 표현을 의미 단위로 인식한다
test(treasurer): 표현 다양성과 제목 추출 회귀를 검증한다
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
| 2026-09-10 | 원인 분석, 설계 문서 작성 | 제목 추출 교체 | 표현을 넓히면 무관 질문 오탐이 늘 수 있다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-10 | 제목 추출을 문법 구간 방식으로 바꾼다 | 잔여물 방식이 단어 중간을 잘라 원문에 없는 제목을 만든다 | 후보가 항상 원문의 연속 부분 문자열이 된다 |
| 2026-09-10 | 형태소 분석기를 도입하지 않는다 | 의존성·성능 비용이 크고 이번 문제는 규칙으로 해결된다 | 부분 문자열 오탐이 완전히 사라지지는 않는다 |
| 2026-09-10 | 사용자의 실제 이름 목록과 대조하지 않는다 | 조회가 한 번 늘고 이번 범위를 넘는다 | 존재하지 않는 이름도 제목으로 볼 수 있다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
