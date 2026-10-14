# Feature Plan: AI 총무 품목별 정산의 받을 금액 보정

- Status: Verified (Gradle 테스트 통과, PR 대기)
- Last updated: 2026-10-13
- Related issue: (미생성)
- Related ADR: N/A

## 1. Purpose

### Problem

**영수증 정산(`ITEM_ALLOCATION`)을 만들어 두었는데 AI 총무가 받을 돈을 0원이라고 답한다.**

AI 총무는 받을 금액을 `settlement_shares.amount` 합계로 구한다(`SettlementJpaRepository#findAllForTreasurer`의 `receivableAmount`). 품목별 정산은 참여자가 품목을 고른 뒤 제출해야 그 사람의 share가 생긴다. 영수증 할인이 있으면 전원이 제출한 뒤에야 한꺼번에 생긴다(`SettlementItemAllocationWriter`). 그래서 `REQUESTED` 동안에는 합계가 일부만 잡히거나 0원이다.

```text
POST /api/v2/treasurer/questions  -> items[].amount = 0, summary.receive.value = "0원"
GET  /api/v2/treasurer/summary    -> receiveAmount 가 정산 상세의 예상 금액보다 작다
```

정산 상세 화면은 같은 구간(`ITEM_ALLOCATION`이고 `REQUESTED`)에서 영수증 총액에서 생성자 본인이 고른 몫을 뺀 값을 쓴다(`SettlementDetailService#requestedItemExpectedAmount`). 같은 정산이 화면마다 다른 금액으로 보인다.

### Why now

사용자가 영수증을 올리고 참여자가 품목을 고르는 동안이 품목별 정산에서 AI 총무를 가장 많이 찾을 시점인데, 바로 그 구간에서 0원이 나온다.

### Success criteria

- [x] `REQUESTED` 상태의 품목별 받을 정산 금액이 정산 상세와 같은 값(영수증 총액 − 생성자가 고른 몫, 영수증 할인 반영)이다.
- [x] `POST /api/v2/treasurer/questions`의 리스트 카드 `amount`와 상태 카드 `summary.receive.value`, `GET /api/v2/treasurer/summary`의 `receiveAmount`가 같은 보정된 금액을 쓴다.
- [x] 품목별이 아니거나 `REQUESTED`가 아니거나 보낼 정산이면 기존 금액을 그대로 쓴다.
- [x] 보정 금액을 계산할 수 없는 정산 한 건 때문에 AI 총무 전체가 실패하지 않는다.
- [x] 정산 상세의 기존 계산 결과는 바뀌지 않는다.

### Out of scope

- 품목별 보낼 정산(내가 낼 돈)의 금액. 내가 품목을 고르기 전에는 내 몫이 정해지지 않아 금액 표시 방식(`금액 미정` 등)이 화면 계약 결정이 필요하다. 별도 이슈로 다룬다
- `SUSPENDED` 품목별 정산. 정산 상세도 이 상태는 share 합계를 쓴다
- `/summary`(전체 기간)와 `/questions`(기간 생략 시 이번 달)의 기간 기준 차이
- `GET /api/v2/settlements`의 응답 계약

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`(품목·선택·할인 계산 정책), `treasurer`(조회)
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: treasurer가 settlement의 기존 계산을 호출만 한다

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 선택 대기 | 품목별 정산이 요청됐지만 아직 확정되지 않은 상태 | `SettlementStatus.REQUESTED` |
| 생성자 몫 | 생성자 본인이 고른 품목 금액에서 할인 몫을 뺀 값 | `SettlementItemAllocationPolicy#calculatePersonalAmount` |
| 받을 금액(선택 대기) | 영수증 총액 − 생성자 몫 | `calculateRequestedReceivableAmount` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | AI 총무와 요약 카드에서 받을 돈을 정확히 본다 | 기존 `AskTreasurerUseCase`, `GetTreasurerSummaryUseCase`, `GetTreasurerRecommendationsUseCase` |

### State and invariants

- State transitions: 없음. 읽기 전용이다.
- Rules that must always hold: 같은 정산의 받을 금액은 정산 상세와 AI 총무에서 같다.
- Invalid states: 선택 대기 중인 품목별 정산의 받을 금액이 share 합계(0원 포함)로 나가는 것.

## 3. Scenarios

### Normal flow

1. 생성자가 12,000원 영수증(피자 10,000 + 콜라 2,000)으로 품목별 정산을 요청한다. 생성자는 콜라를 골랐다.
2. 아직 참여자 누구도 제출하지 않아 share가 없다.
3. 생성자가 "받을 정산 알려줘"라고 묻는다.
4. 받을 금액이 10,000원(12,000 − 2,000)으로 나온다. 정산 상세의 예상 금액과 같다.

### Failure flow

- Condition: 저장된 선택이 품목과 맞지 않거나 금액 계산이 넘친다.
- Returned error or state transition: 오류가 아니다. 그 정산은 조회한 금액을 그대로 쓰고 경고 로그를 남긴다. 정산 상세는 같은 데이터에서 오류를 낸다.

### Boundary conditions

- 생성자가 참여자에 없으면(백필 전 정산) 총액을 받을 금액으로 본다. 정산 상세와 같고, 품목은 조회하지 않는다.
- 생성자가 아무것도 고르지 않았으면 총액이다.
- 영수증 할인은 정산 상세와 같은 정책 메서드로 반영한다.
- 정산이 조회 사이에 삭제돼 없으면 조회한 금액을 쓴다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 AskTreasurerUseCase / GetTreasurerSummaryUseCase / GetTreasurerRecommendationsUseCase — 변경 없음
Command: 변경 없음
Result: 변경 없음. 금액 값만 달라진다
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 변경 없음
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 `LoadSettlementPort`, `LoadSettlementParticipantPort`, `LoadSettlementItemPort`, `LoadSettlementExpensePort`, `SettlementItemAllocationStorePort` | 선택 대기 정산의 총액·생성자 참여자·품목·선택·영수증 할인 | `TreasurerRequestedReceivableResolver` | 기존 |

새 Port는 만들지 않는다. 정산 상세가 쓰는 Port를 그대로 쓴다.

### Application Policy

- Decision owned by the Policy: 선택 대기 중 받을 금액 = 영수증 총액 − 생성자 몫
- Facts loaded by the Service and passed to the Policy: 총액, 품목, 선택, 생성자 참여자 ID, 영수증 할인
- Why the rule is not an Entity-local invariant: 품목·선택·할인 여러 Entity를 모아야 나오는 값이다
- Future inputs deliberately not implemented yet: 보낼 정산의 내 몫

### Web API

```text
POST /api/v2/treasurer/questions — 요청·응답 형태 변경 없음
GET /api/v2/treasurer/summary — 요청·응답 형태 변경 없음
GET /api/v2/treasurer/recommendations — 요청·응답 형태 변경 없음
Errors: 변경 없음
Authentication: 기존 그대로
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 계산은 `SettlementItemAllocationPolicy#calculateRequestedReceivableAmount` 한 곳이다. 정산 상세와 AI 총무가 같은 메서드를 호출한다
- Behavior owned by Domain: 없음
- Decisions owned by Application Policy: 총액 − 생성자 몫
- Flow orchestrated by Application: `TreasurerSettlementQuery#loadMine`이 대상 정산만 `TreasurerRequestedReceivableResolver`에 넘겨 금액을 바꾼다

### Technology and patterns

- Constraint to solve: share가 아직 없거나 일부만 있는 구간의 받을 금액을 정산 상세와 같게 만든다
- Selected approach: 정산 상세의 계산을 Policy 메서드로 올려 두 곳이 호출한다. SQL에서 구하지 않고 Application에서 보정한다
- Reason for selection: 품목 몫과 할인 배분은 SQL로 옮기면 정산 상세와 갈라진다. 기존 로직을 재구현하지 않는다
- Alternatives considered: (1) 영수증 총액을 그대로 사용 — 쿼리 한 줄로 끝나지만 생성자 몫이 포함돼 "받을 돈"이 부풀고, 정산 상세와 숫자가 다르다. (2) 정산 상세의 private 메서드를 복사 — 두 계산이 갈라진다
- Trade-offs and limits: 대상 정산마다 조회가 최대 6번(정산·참여자·영수증 지출·품목과 옵션·선택) 늘어난다. 대상은 선택 대기 중인 품목별 받을 정산뿐이지만 개수 상한은 없다. 기간·제목 필터는 `loadMine()` 이후에 걸려 필터 밖 정산도 계산한다

#### 계산할 수 없는 한 건이 전체를 막지 않는다

정산 상세는 품목과 선택이 어긋난 정산에서 오류를 낸다. AI 총무는 사용자의 모든 정산을 한꺼번에 읽으므로 한 건 때문에 전체가 실패하면 안 된다. 계산 실패 시 조회한 금액으로 되돌리고 경고 로그를 남긴다.

#### 계산기는 별도 Component로 둔다

`TreasurerSettlementQuery`는 이미 `LoadTreasurerSettlementsPort`만 알고 있다. 정산 쪽 Port 다섯 개를 더하면 조회 클래스가 커지고, 질문 흐름이 LLM 호출 동안 커넥션을 쥐지 않으려면 계산만 짧은 읽기 트랜잭션으로 묶어야 한다. 그래서 `TreasurerRequestedReceivableResolver`를 두고 `resolve`에만 `@Transactional(readOnly = true)`를 건다. 요약·추천 Service가 이미 연 트랜잭션에는 합류한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 형태 변경 없음. 선택 대기 품목별 받을 정산의 금액 값이 달라진다 | `TreasurerAnswerWebResponse`, `TreasurerSummaryWebResponse` |
| Application | Policy 메서드 추가, 정산 상세가 호출, AI 총무 조회 보정 | `SettlementItemAllocationPolicy`, `SettlementDetailService`, `TreasurerSettlementQuery`, `TreasurerRequestedReceivableResolver` |
| Domain | 없음 | - |
| Persistence | 없음 | `SettlementJpaRepository#findAllForTreasurer` 그대로 |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | 본 문서, `ai-treasurer.md`의 받을 금액 서술 | `docs/features/` |

Existing behavior that must remain unchanged:

- 정산 상세 `paymentSummary.expectedAmount` 계산 결과
- 품목별이 아니거나 `REQUESTED`가 아닌 정산의 받을 금액
- 보낼 정산의 금액
- `TreasurerSettlementQuery` 이후 필터·집계 흐름

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` (`Required` / `N/A`) — **N/A**
- [ ] UseCase and Application Command/Result — **N/A**
- [ ] Required Output Ports — **N/A**
- [ ] Domain models and rules — **N/A**
- [x] Application Policies — `calculateRequestedReceivableAmount`
- [x] Application Service — 정산 상세 위임, AI 총무 금액 보정
- [ ] Output Adapters — **N/A**
- [ ] Web Request/Response DTOs, Controllers, and Swagger — **N/A**
- [ ] Infra configuration and Bean wiring — **N/A**
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(treasurer): 품목별 정산 받을 금액 보정 설계 수립
fix(treasurer): 선택 대기 품목별 정산의 받을 금액을 정산 상세와 맞춘다
test(treasurer): 선택 대기 품목별 정산의 받을 금액을 검증한다
docs(treasurer): 품목별 정산 받을 금액 검증 결과 기록
```

## 8. Verification

```bash
docker compose -f compose.test.yml up -d
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test \
DB_PASSWORD=howmuch_test REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  bash scripts/test.sh test --tests '*Treasurer*' --tests '*SettlementDetail*' --tests '*SettlementItemAllocation*'
docker compose -f compose.test.yml down -v
```

### Completion criteria

- [x] Purpose의 사용자 결과를 검증했다.
- [x] 정상·실패·경계 흐름을 검증했다.
- [x] 문서와 실제 코드가 일치한다. 같은 주장을 하는 다른 줄이 남아 있지 않은지 확인했다.
- [x] 수행하지 못한 검증과 사유를 기록했다.

### Results

develop(`a662f1e2`) 위로 rebase한 뒤 로컬 Mac에서 `compose.test.yml`(Postgres 18, Redis 8)을 띄워 위 명령을 실행했고 `BUILD SUCCESSFUL`이었다. 실패·오류·건너뜀은 없었다.

| Item | Result | Evidence |
|---|---|---|
| treasurer·settlement 상세·품목 배분 테스트 | 모든 suite 통과 (failures 0, errors 0, skipped 0) | `build/test-results/test/*.xml` |
| 신규 Resolver 테스트 | 8건 통과 (생성자 몫 제외, 미선택, 영수증 할인, 배달비, 정산 없음, 생성자 비참여, 선택 불일치 폴백, 적용 대상 판별) | `AI 총무 선택 대기 품목 정산 받을 금액` |
| 정책 테스트 | 29건 통과 (신규: 생성자 몫 제외·미선택·할인·배달비) | `품목별 배분 정책` |
| treasurer 질의 테스트 | 4건 통과 (보정 적용, 폴백, 요약 receiveAmount 10,000) | `TreasurerSettlementQueryTest` |
| 정산 상세 회귀 | 28건 통과 | `정산 상세 납부 현황 조회` |
| 총무 정산 영속 조회(SQL) | 3건 통과 | `총무 정산 영속 조회` |
| 첫 실행 | Docker(colima)가 꺼져 통합 테스트 16건이 DB 연결 오류로 실패했다. 코드 문제가 아니라 환경 문제였고, DB를 띄운 뒤 재실행해 통과했다 | 실행 로그 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-13 | 원인 확인(share 합계), 설계 수립, 구현, 독립 리뷰 반영 | Gradle 테스트 실행과 결과 기록, 이슈 생성 | 선택 대기 구간의 share는 0원이 아니라 일부만 있을 수도 있다. 할인이 있으면 전원 제출 전까지 없다 |

| 2026-10-13 | develop에 들어온 배달비를 반영해 rebase하고 Gradle 테스트 통과 | 이슈 생성, PR | 정산 상세가 배달비 중 생성자 몫도 뺀다. 같은 계산을 쓰도록 정책 메서드에 배달비를 받았다 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-13 | 받을 금액을 영수증 총액 − 생성자 몫으로 한다 | 정산 상세 화면과 같은 숫자여야 한다. 총액만 쓰면 생성자 몫이 포함돼 부풀려진다 | 총액 그대로 쓰는 안은 쿼리 한 줄이지만 화면 간 값이 갈라져 쓰지 않았다 |
| 2026-10-13 | 계산을 Policy 메서드로 올려 정산 상세가 같이 호출한다 | 두 곳의 계산이 갈라지지 않게 한다 | 정산 상세 코드를 건드리지만 호출 위임만 바뀐다 |
| 2026-10-13 | 계산 실패 시 조회한 금액으로 되돌린다 | 한 건 때문에 AI 총무 전체가 실패하지 않게 한다 | 그 정산은 0원으로 보일 수 있다. 경고 로그로 남긴다 |
| 2026-10-13 | 선택 대기 중 배달비는 정산 상세와 같이 생성자 몫만큼 뺀다 | develop에서 정산 상세의 예상 금액이 배달비를 반영하게 바뀌었다 | 같은 정책 메서드를 쓰므로 화면과 값이 갈라지지 않는다 |
| 2026-10-13 | 보낼 정산의 내 몫은 이번 범위에서 뺀다 | 품목을 고르기 전 금액 표시는 화면 계약을 정해야 한다 | 후속 이슈로 다룬다 |

## 11. Completion record

- Completed on: 2026-10-13
- Final behavior: 선택 대기 중인 품목별 받을 정산의 금액을 영수증 총액 − 생성자 몫(할인·배달비 반영)으로 내려준다. 계산할 수 없는 정산은 기존 값으로 되돌리고 경고 로그를 남긴다
- Unverified items: 실제 DB·앱에서 `/api/v2/treasurer/questions`와 `/summary` 응답 확인, SUSPENDED 상태 보정
- Remaining risks: 대상 정산마다 조회가 늘고 개수 상한이 없다. 기간·제목 필터 전에 계산한다
- Follow-up work: 품목별 보낼 정산의 금액 표시, SUSPENDED 상태, 요약과 질문의 기간 기준 차이
