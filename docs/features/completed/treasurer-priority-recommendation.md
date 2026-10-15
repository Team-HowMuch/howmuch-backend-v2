# Feature Plan: AI 총무 정산 우선순위 안내

- Status: Completed (구현·검증. 앱의 추천 `priority` 값 사용 여부는 배포 전 확인)
- Last updated: 2026-10-15
- Related issue: 없음(사용자 요청, 2026-10-15)
- Related ADR: N/A
- 선행 문서: [AI 총무](../active/ai-treasurer.md)

## 1. Purpose

### Problem

AI 총무에 "뭐부터 처리해?", "먼저 처리할 정산 추천해줘"라고 물으면 우선순위 안내로 가지 않는다. 우선순위를 매기는 추천 API(`GET /api/v2/treasurer/recommendations`)는 있지만 채팅과 연결돼 있지 않다. 추천 순서도 요구와 다르다.

- 지금 순서: 보낼 + 7일 지남 → 보낼 → 받을 + 7일 지남 → 받을. `SEND_OVERDUE`, `RECEIVE_OVERDUE`는 정의만 있고 쓰이지 않는다(마감일 개념이 없어서).
- 같은 단계 안의 순서 기준이 없어 조회된 순서 그대로다.
- 보낼 정산·받을 정산 목록 답변은 우선순위와 관계없이 조회 순서다.

### 요구사항(2026-10-15 사용자 전달)

- 정산을 사용자 입장에서 보낼 정산과 받을 정산으로 나눈다.
- 기한이 지났거나 오래 처리되지 않은 정산은 급한 항목으로 따로 본다. 기한 개념이 정해지지 않았으므로 요청 후 하루 이상 지나면 기한 초과로 본다.
- 순서: 보낼 + 기한 초과 → 보낼 + 장기 미처리 → 받을 + 기한 초과 → 받을 + 일반 미완료. 단순히 받을 금액이 남은 정산은 낮게 둔다.
- 보낼 정산과 받을 정산의 정리를 원하면 이 순서로 안내한다.
- 맨 위 항목이 보낼 정산이고 하루 이상 지났으면 "N시간 이상 지난 정산이에요"와 함께 빨리 정산하라고 권한다.

### Success criteria

- [x] "뭐부터 처리해?", "먼저 처리할 정산 추천해줘", "보낼 정산과 받을 정산 정리해줘"가 우선순위 안내로 답한다
- [x] 추천 API와 채팅이 같은 우선순위 규칙을 쓴다
- [x] 보낼·받을 정산 목록 답변도 같은 순서로 카드를 보여준다
- [x] 맨 위가 하루 이상 지난 보낼 정산이면 경과 시간과 함께 빨리 정산하라고 안내한다
- [x] 기존 질문 분류 결과는 바뀌지 않는다(우선순위 표현이 없는 질문)

### Out of scope

- 정산 마감일(dueDate) 도입. 기한 초과는 요청 시각 기준으로만 판단한다
- 추천 칩·빠른 질문 추가(앱 화면 배치와 함께 정한다)
- "누가 얼마 안 냈다" 같은 참여자별 이유 문장
- 마감 질문(`DUE_SETTLEMENTS`)의 7일 기준 변경
- 앱 화면

## 2. Domain analysis

### Domain boundary

- 재사용 도메인: AI 총무(`treasurer`). 정산 데이터는 기존 `TreasurerSettlementQuery` → `LoadTreasurerSettlementsPort`로 읽는다.
- 우선순위 규칙은 도메인 `TreasurerRecommendation.prioritize`에 두고 추천 API와 채팅이 함께 쓴다. 새 Port·저장 구조는 없다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 기한 초과 | 요청됨·입금 대기 정산이 요청 후 1일 이상 지남 | `PersonalSettlement.isOverdue`, `OVERDUE_AFTER = 1일` |
| 장기 미처리 | 요청 후 7일 초과 미완료 | `PersonalSettlement.isStale`(기존) |
| 일반 미완료 | 기한 초과가 아닌 미완료(요청 후 1일 미만, 중단) | `SEND_OPEN`, `RECEIVE_OPEN` |

### State and invariants

- 추천 대상은 기존과 같이 `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED`다. `DRAFT`, `COMPLETED`는 빠진다.
- 중단(`SUSPENDED`) 정산은 생성자가 멈춘 상태라 사용자가 할 일이 없다. 경과 시간과 관계없이 기한 초과로 보지 않고 일반 미완료에 두며, 이유 문장으로 중단됐다고 알린다.
- 장기 미처리(7일 초과)는 항상 기한 초과(1일 이상)에 포함된다. 별도 단계로 두지 않고, 같은 단계 안에서 오래된 순으로 정렬해 자연히 위로 오게 하고 이유 문장으로 표시한다(2026-10-15 결정).

## 3. Scenarios

### Normal flow

1. 사용자가 "뭐부터 처리해?"라고 묻는다.
2. 질문 분류가 `PRIORITY_SETTLEMENTS`로 판단한다.
3. 끝나지 않은 내 정산 전체(기간을 말하면 그 기간)를 우선순위대로 정렬한다.
4. 답변: "끝나지 않은 정산 전체를 기준으로 조회했어요. 먼저 처리할 정산은 3건이에요. 가장 먼저 확인할 정산은 '제주 여행'이에요. 30시간 이상 지난 정산이에요. 빨리 정산해 주세요." 카드는 우선순위 순서다. 제목 끝 글자에 받침이 없으면 "'동아리 회비'예요"처럼 쓴다.

### Boundary conditions

- 요청 후 정확히 24시간이면 기한 초과다. 23시간 59분이면 아니다.
- 경과 시간은 내림한 시간 단위로 표시한다. 10일이면 "240시간 이상"(2026-10-15 결정: 항상 시간 단위).
- 맨 위가 받을 정산이거나 기한 초과가 아닌 보낼 정산이면 경과 시간 안내를 붙이지 않는다.
- 요청 시각이 없으면 기한 초과가 아니고 같은 단계의 맨 뒤에 둔다.
- 보낼 정산만 묻는 질문("보낼 정산 뭐부터 보내?")은 보낼 정산 목록을 우선순위 순으로 보여준다. 방향이 없거나 둘 다 있는 질문만 추천 의도로 간다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: AskTreasurerUseCase (기존) — 새 의도 PRIORITY_SETTLEMENTS
UseCase: GetTreasurerRecommendationsUseCase (기존) — 순서·이유 문장 변경, 응답 형태는 그대로
```

### Output Ports

새 Port 없음. 기존 `LoadTreasurerSettlementsPort`.

### Application Policy

- `InquiryParser`: 우선순위 표현을 인식한다.
  - 단독으로 분명한 표현: "뭐부터", "무엇부터", "뭘 먼저", "어떤 것부터", "우선순위" 등 "무엇을 먼저"를 묻는 말
  - 정산 맥락이 있어야 인정하는 표현: "먼저 처리", "먼저 해야", "먼저 보내", "정산부터", "돈부터", "추천", "정리", "급한", "밀린". "영화 추천해줘", "몇 시부터 보내?", "회원가입 먼저 해야 해?"는 우선순위 질문이 아니다.
  - 우선순위 표현이 있고 보낼·받을 중 한쪽만 있으면 그 방향 의도, 없거나 둘 다 있으면 `PRIORITY_SETTLEMENTS`
  - 마감·지출·진행 상태·영수증 키워드가 함께 있으면 그 의도가 이긴다. "지출 정리해줘"는 `EXPENSE_SUMMARY`, "급한 정산 있어?"는 `SETTLEMENT_STATUS`다.
  - 우선순위 표현은 우선순위 질문에서만 제목 추출의 문법 어휘로 본다. "정리모임에서 보낼 돈"의 제목 `정리모임`은 그대로 남는다.

### Ordering rule

| 순서 | 단계 | 조건 |
|---|---|---|
| 1 | `SEND_OVERDUE` | 보낼, 요청됨·입금 대기, 요청 후 1일 이상 |
| 2 | `SEND_OPEN` | 보낼, 나머지 미완료 |
| 3 | `RECEIVE_OVERDUE` | 받을, 요청됨·입금 대기, 요청 후 1일 이상 |
| 4 | `RECEIVE_OPEN` | 받을, 나머지 미완료 |

같은 단계 안: 요청 시각이 이른 순(없으면 뒤) → 금액이 큰 순 → 정산 ID 순.

### Web API

```text
POST /api/v2/treasurer/questions
  intent: 새 값 PRIORITY_SETTLEMENTS, view LIST, 카드는 우선순위 순서
  answer: 서버 문구(LLM 미사용). 순서·경과 시간을 LLM이 바꾸지 못하게 한다
GET /api/v2/treasurer/recommendations
  priority 값: SEND_OVERDUE, SEND_OPEN, RECEIVE_OVERDUE, RECEIVE_OPEN (SEND_STALE, RECEIVE_STALE 제거)
```

### Persistence and external integrations

- Flyway migration required: No. 답변 저장 `treasurer_answers.intent`는 CHECK 제약이 없다(V36 주석: 의도가 늘어도 마이그레이션 불필요)
- Redis, External API: 변경 없음

## 5. Design decisions

- 4단계(1일 기준) + 단계 안 오래된 순(2026-10-15 결정). 1일 기준을 쓰면 7일 기준 장기 미처리는 모두 기한 초과에 들어가 따로 단계를 둘 수 없다.
- 경과 시간은 항상 시간 단위(2026-10-15 결정).
- 추천 의도 답변은 서버 문구만 쓴다. 기존 추천 API도 LLM을 쓰지 않는다.
- 보낼 정산 목록 답변에도 맨 위가 기한 초과면 같은 안내 문장을 덧붙인다. LLM 문장을 쓰는 경우에도 서버가 계산한 문장을 뒤에 붙인다. 이 경우 저장되는 `generatedBy`는 `LLM`으로 남지만 덧붙인 문장은 서버 문구다.
- 보낼 정산 카드의 주의 표시(`ATTENTION`)를 장기 미처리 대신 기한 초과 기준으로 바꾼다. 급한 항목 기준을 하나로 맞추기 위해서다.

## 6. Impact surface

- `GET /api/v2/treasurer/recommendations`: `priority` 값이 바뀐다(`SEND_STALE` → `SEND_OVERDUE` 등). 앱이 이 값을 쓰면 함께 바꿔야 한다
- `POST /api/v2/treasurer/questions`: 새 의도 값, 보낼·받을 목록 카드 순서, 보낼 카드 주의 표시 기준
- 기존 질문 분류는 우선순위 표현이 없으면 그대로다

## 7. Implementation plan

### Expected commit boundaries

- `docs(treasurer)`: Feature Plan
- `feat(treasurer)`: 우선순위 규칙·정렬, 질문 분류, 채팅 답변, API 문서
- `test(treasurer)`: 도메인·분류·서비스 테스트
- `docs(treasurer)`: 검증 결과

## 8. Verification

### Commands

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 bash scripts/test.sh test --tests '*Treasurer*' --tests '*Inquiry*' --tests '*PersonalSettlement*'
```

### Completion criteria

- [x] 단계 경계(23시간 59분 / 24시간), 중단 정산, 요청 시각 없음, 같은 단계 정렬
- [x] 추천 API 순서·이유 문장
- [x] 우선순위 질문 분류와 기존 분류 유지
- [x] 채팅 답변 문구(경과 시간 안내 유무), 카드 순서, 기간 지정 여부
- [x] 전체 테스트

### Results

- 2026-10-15 관련 테스트(`*Treasurer*`, `*Inquiry*`, `*PersonalSettlement*`): 통과. 건너뜀 1건은 로컬 전용 LLM 평가(`TREASURER_EVAL_PROVIDER` 없음)
- 2026-10-15 전체 테스트 `bash scripts/test.sh test`: 2132건, 실패 0, 오류 0, 건너뜀 1(같은 로컬 평가)
- 독립 리뷰 반영
  - 우선순위 표현이 사용법·지출·진행 상태·마감·영수증 질문을 가로채던 문제를 고쳤다. 맥락 없이 인정하는 표현을 "무엇을 먼저"류로 줄이고, 명시 의도 키워드가 있으면 우선순위로 보내지 않는다. 회귀 테스트를 추가했다.
  - 우선순위 표현이 다른 의도의 제목 추출에 영향을 주지 않게 했다.
  - 제목 뒤 서술격 조사(이에요/예요)를 받침에 맞췄다.
  - 받을 정산 기한 초과 문구를 "하루가 넘었는데"로 바꿨다.
- 남은 판단
  - LLM 문장에 서버 안내가 붙은 답변은 `generatedBy=LLM`으로 저장된다.
  - 오래 멈춘 중단 정산은 "중단된 정산이에요."만 보여준다(계획대로).
- 앱 영향: 추천 API `priority` 값이 `SEND_STALE`/`RECEIVE_STALE`에서 `SEND_OVERDUE`/`SEND_OPEN`/`RECEIVE_OVERDUE`/`RECEIVE_OPEN`으로 바뀐다. 앱이 이 값을 쓰는지 확인해야 한다.

### PR #565 리뷰 후속(2026-10-15)

- P2(#565에서 반영): "아직 송금 안 한 사람"·"안 낸 사람" 표현이 보낼 방향과 함께 있는 우선순위 질문이 받을 목록으로만 답하던 문제를 고쳤다. 이 표현은 받을 방향으로 세고, 그 안의 "송금"은 보낼 방향으로 세지 않는다.
- P1(후속 PR): 총무 조회가 정산 생성 시각(`settlements.created_at`)을 요청 시각으로 써서, 초안으로 오래 둔 뒤 요청한 정산이 요청 직후부터 기한 초과로 보였다.
  - 정산 요청 때만 만들어지고 정산마다 하나인 정산 링크(`settlement_links`, `UNIQUE(settlement_id)`, 재발급해도 `created_at` 유지)의 생성 시각을 요청 시각으로 쓴다. 링크가 없으면(초안·이전 데이터) 생성 시각을 쓴다.
  - 정산 목록 API(`findAllForUser`)가 이미 쓰는 `COALESCE(link.created_at, settlement.created_at)`와 같은 기준이다. 새 컬럼·마이그레이션은 필요 없다.
  - 검증: `TreasurerSettlementPersistenceIntegrationTest`에 정산 생성 2일 뒤 링크를 만들면 요청 시각이 링크 시각이고, 링크가 없는 정산은 그대로 생성 시각인지 확인하는 테스트를 넣었다. 쿼리를 되돌리면 이 테스트가 실패하는 것을 확인했다.
  - 전체 `bash scripts/test.sh test`: 2133건, 실패 0, 오류 0, 건너뜀 1(로컬 전용 LLM 평가)

### 요청부터 안내까지 HTTP 검증(2026-10-15)

- `TreasurerRequestFlowHttpIntegrationTest`: 실제 HTTP로 품목 정산을 만들고 `POST /api/v2/settlements/{id}/request`로 요청한 뒤 정산 생성 시각을 3일 앞당긴다.
  - 요청 직후: 참여자의 "뭐부터 처리해?" 답변은 `PRIORITY_SETTLEMENTS`, `period` null, 보낼 카드 `marker` `NONE`, 경과 시간 안내 없음. 추천 API는 참여자 `SEND_OPEN`, 생성자 `RECEIVE_OPEN`
  - 정산 링크 생성 시각을 25시간 앞당긴 뒤: 카드 `ATTENTION`, 답변 끝 "25시간 이상 지난 정산이에요. 빨리 정산해 주세요.", 추천 API는 `SEND_OVERDUE`, `RECEIVE_OVERDUE`
  - 총무 조회를 생성 시각 기준으로 되돌리면 요청 직후 카드가 `ATTENTION`이 되어 실패하는 것을 확인했다.
- 계획 문서의 질의 API 경로를 실제 경로 `POST /api/v2/treasurer/questions`로 고쳤다.
