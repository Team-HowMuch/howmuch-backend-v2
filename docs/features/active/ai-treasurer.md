# Feature Plan: AI 총무 (개인 정산 비서)

- Status: Implemented (initial scope)
- Last updated: 2026-08-27
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
- [x] 의도 파악 실패 시 예시 질문을 안내한다.
- [x] 미완료 정산을 보낼/받을·장기 미처리 우선순위로 안내하고 화면 이동 액션만 제공한다.
- [x] 정산 데이터를 변경하지 않는다.
- [x] 질의 답변 문장은 OpenAI 또는 Gemini로 생성하고, 키/호출 실패 시 서버 템플릿으로 내려간다.

### Out of scope

- OCR AI 서버
- 송금·입금 확인·알림 발송 실행
- 기한 필드(정산 도메인에 dueDate 없음) — 기한 초과는 미구현, 장기 미처리는 `createdAt` 7일 기준
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
- 질문 의도 없음: UNKNOWN + 예시
- 기간/제목 필터 후 0건: 건수 0 안내

## 4. Contracts

### Input Port

```text
GetTreasurerSummaryUseCase → TreasurerSummaryResult
AskTreasurerUseCase(AskTreasurerCommand) → TreasurerAnswerResult
GetTreasurerRecommendationsUseCase → TreasurerRecommendationsResult
```

### Output Ports

| Port | Purpose |
|---|---|
| `LoadTreasurerSettlementsPort` | 사용자 RECEIVE/PAY 전체 정산(제목 포함) |
| `GenerateTreasurerAnswerPort` | 집계된 팩트로 안내 문장 생성 |

### Web API

- `GET /api/v2/treasurer/summary`
- `POST /api/v2/treasurer/questions` body `{ "question": "..." }`
- `GET /api/v2/treasurer/recommendations`

## 5. Design decisions

- 의도 분류는 키워드 (`InquiryParser`). 금액·건수는 서버가 계산한다.
- `answer` 문장만 OpenAI(`gpt-4o-mini`) 또는 Gemini(`gemini-flash-latest`)가 생성한다. `TREASURER_LLM_PROVIDER=none` 이거나 키/호출 실패면 템플릿 문장을 쓴다.
- 기존 `LoadSettlementListPort`는 title이 없어 treasurer 전용 조회를 둔다. 목록 HTTP 계약은 변경하지 않는다.
- 내부 시각은 UTC 저장값을 쓰되, `이번 달`은 Asia/Seoul 달력이다.
- 받을 금액은 생성자 본인 share를 제외한 금액이다.
- 보낼/받을 금액과 추천은 `REQUESTED`/`AWAITING_DEPOSIT`/`SUSPENDED`만 포함한다. `DRAFT`는 진행 중 건수에만 넣고 잔액·추천에서는 뺀다.
- 지출 요약은 SEND 방향만 합산하고 `DRAFT`는 제외한다.
- 기간과 제목이 함께 있으면 기간·질의 문법을 제거한 뒤 `○○모임`/`○○정산`만 제목으로 쓴다.
- 보낼/받을 키워드가 한 질문에 같이 있으면 먼저 나온 쪽을 쓴다.
- 추천 액션은 실제 실행 가능한 상태만 노출한다. `TRANSFER`와 `CHECK_DEPOSIT`은 `AWAITING_DEPOSIT`에서만 제공한다.
- LLM이 서버 팩트·템플릿에 없는 숫자를 쓰면 템플릿 문장으로 되돌린다. `8월`처럼 날짜 접미 숫자는 허용한다.

## 6. Impact surface

- 신규 treasurer 패키지, SettlementJpaRepository 쿼리 1개
- Security: `/api/**` authenticated 기존 규칙

## 7. Verification

- Domain/Policy/Service 단위 테스트
- Controller MockMvc 계약 테스트
- LLM 응답 파서·어댑터 단위 테스트
- treasurer native query 영속성 통합 테스트
- `./gradlew compileJava test` (관련 테스트)

## 8. Commit boundaries

1. feat(treasurer): 개인 정산 비서 조회 API

## 9. Open questions

- 사용자별 PAID와 정산 COMPLETED 분리: 입금 대조 bulk 조회가 생기면 교체
- 기한 초과: dueDate 추가 시 우선순위 1·3 활성화
