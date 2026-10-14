# Feature Plan: 정산 목록 받을 금액(receivableAmount) 추가

- Status: Completed
- Last updated: 2026-10-14
- Related issue: (생성 후 기입)
- Related ADR: [Capability-oriented Output Ports](../../adr/0003-capability-oriented-output-ports.md)

## 1. Purpose

### Problem

앱은 `GET /api/v2/settlements?tab=RECEIVE` 목록의 `totalAmount`를 받을 금액으로 표시한다. `totalAmount`는 생성자 본인 몫을 포함한 정산 총액(`settlements.total_amount`)이다. AI 비서(`/treasurer/summary`, `/treasurer/questions`)와 정산 상세의 받을 금액은 생성자 몫을 뺀 금액이다.

그래서 같은 정산이 목록과 AI 비서·상세에서 다른 금액으로 보인다. 예를 들어 생성자가 30,000원 영수증에서 10,000원어치 품목을 골랐다면, 목록은 30,000원, AI 비서와 상세는 20,000원을 보여 준다.

### Why now

#364에서 FE가 금액 불일치를 보고했다. [#514](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/514)가 AI 비서 금액을 상세와 맞췄지만 목록 응답 계약은 범위에서 뺐다. 목록만 다른 기준으로 남아 있다.

### Success criteria

- [ ] RECEIVE 목록 항목에 `receivableAmount`가 내려온다. 같은 정산의 정산 상세 받을 금액(`summary.expectedAmount`)과 같다.
- [ ] 품목 정산(`ITEM_ALLOCATION`)이 `REQUESTED`/`SUSPENDED`이면 "영수증 총액 − 생성자 몫"이다. 그 밖에는 생성자를 뺀 참여자 share 합계다.
- [ ] `totalAmount`의 값과 의미는 바뀌지 않는다.
- [ ] PAY 탭의 `receivableAmount`는 `null`이다.
- [ ] 한 정산의 보정 계산이 실패해도 목록 전체는 실패하지 않고 share 합계를 쓴다.
- [ ] 목록 건수만큼 쿼리가 늘지 않는다(N+1 없음).
- [ ] AI 비서의 같은 정산 받을 금액과 일치한다(아래 결정 D1 반영 시 `SUSPENDED` 포함).

### Out of scope

- `totalAmount` 의미 변경
- PAY 탭의 내 몫 표시(`amountToPay`) 규칙
- 입금 확인액을 뺀 잔액 표시. `receivableAmount`는 받을 총액이고, 잔액은 기존 미납 요약(`remainingReceivableAmount`)이 담당한다
- `/treasurer/summary`와 `/treasurer/questions`의 기간 기준 차이
- 앱 화면 변경(FE 작업)

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`(목록 조회, 품목 배분 정책)
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: `treasurer`가 같은 계산을 쓴다. 결정 D2에 따라 계산 진입점을 공유한다

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 정산 총액 | 생성자 몫을 포함한 영수증·지출 합계 | `totalAmount`, `settlements.total_amount` |
| 받을 금액 | 생성자가 다른 참여자에게서 받을 총액. 입금 여부와 무관 | `receivableAmount` |
| 선택 대기 품목 정산 | 참여자 품목 선택이 끝나지 않아 share가 비었거나 일부만 있는 품목 정산 | `ITEM_ALLOCATION` + `REQUESTED`/`SUSPENDED` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 받을 정산 목록에서 받을 금액을 본다 | `GetSettlementsUseCase` (기존) |

### State and invariants

- State transitions: 없음(조회 전용)
- Rules that must always hold: 같은 정산의 받을 금액은 목록·상세·AI 비서에서 같다
- Invalid states: 받을 금액이 음수이거나 정산 총액보다 크다

## 3. Scenarios

### Normal flow

1. 생성자가 ITEM_ALLOCATION 정산(총액 30,000원)을 요청했고, 본인은 10,000원어치 품목을 골랐다.
2. 앱이 `GET /api/v2/settlements?tab=RECEIVE`를 호출한다.
3. 목록 조회 후, 선택 대기 품목 정산들의 품목·선택·할인·배달비·생성자 참여자를 한 번에 불러와 정책으로 계산한다.
4. 응답 항목은 `totalAmount=30000`, `receivableAmount=20000`이다. 상세의 `expectedAmount`와 AI 비서 금액도 20,000원이다.

### Failure flow

- Condition: 선택이 없는 품목을 가리키는 등 저장 데이터가 맞지 않아 정책이 `ApplicationException`/`ArithmeticException`을 던진다.
- Returned error or state transition: 그 정산만 share 합계로 대체하고 경고 로그를 남긴다. #514의 `TreasurerRequestedReceivableResolver`와 같은 방식이다.

### Boundary conditions

- 생성자가 참여자에 없음: 뺄 몫이 없으므로 `totalAmount`
- 생성자가 아직 품목을 고르지 않음: `totalAmount`
- 할인·배달비가 있는 영수증: 정책이 생성자 몫에 할인·배달비 배분을 반영한다
- DUTCH_PAY, AWAITING_DEPOSIT 이후, COMPLETED: 생성자를 뺀 share 합계
- 목록이 비었음: 추가 조회 없음

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetSettlementsUseCase (변경 없음)
Command: GetSettlementsCommand (변경 없음)
Result: SettlementListItemResult + Long receivableAmount
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: SettlementListItemWebResponse.from(result)에 receivableAmount 추가
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementListPort` (기존) | 목록 Read Model에 share 기준 받을 금액(`receivableShareAmount`) 추가 | `SettlementListService` | `SettlementPersistenceAdapter` |
| `LoadRequestedItemReceivableFactsPort` (신규) | 여러 정산의 총액·생성자 참여자 ID·품목(옵션 포함)·선택·지출을 한 번에 불러온다 | `RequestedItemReceivableCalculator` | `RequestedItemReceivableFactsPersistenceAdapter` |

신규 Port는 "선택 대기 품목 정산의 받을 금액 계산 재료"라는 하나의 능력을 표현한다(결정 D3). Adapter는 정산·참여자·품목·옵션·선택·지출을 정산 ID 목록으로 한 번씩, 모두 여섯 번 조회한다. 정산 안의 품목 순서는 단건 조회(`findAllOrderedBySettlementId`)와 같다.

### Application Policy

- Decision owned by the Policy: 생성자 몫 계산. 기존 `SettlementItemAllocationPolicy.calculateRequestedReceivableAmount`를 그대로 쓴다
- Facts loaded by the Service and passed to the Policy: 정산 총액, 품목, 선택, 생성자 참여자 ID, 영수증 할인 합, 배달비 합
- Why the rule is not an Entity-local invariant: 여러 참여자의 선택과 지출 조정을 함께 봐야 한다
- Future inputs deliberately not implemented yet: 없음

어떤 정산에 보정을 적용할지(`ITEM_ALLOCATION` + `REQUESTED`/`SUSPENDED`)는 `SettlementItemAllocationPolicy.usesRequestedReceivable` 한 곳에 둔다. 정산 상세, 목록, AI 비서가 이 메서드를 같이 쓴다(결정 D2).

### Web API

```text
GET /api/v2/settlements?tab=RECEIVE|PAY&status=...&groupId=...&yearMonth=...
Request: 변경 없음
Response: data[] 항목에 receivableAmount 추가
  receivableAmount: Long, nullable
    RECEIVE: 생성자를 뺀 참여자가 낼 총액. 선택 대기 품목 정산은 영수증 총액 − 생성자 몫
    PAY: null
  totalAmount: 변경 없음 (생성자 몫을 포함한 정산 총액)
Errors: 변경 없음
Authentication: 변경 없음 (로그인 사용자)
```

`SettlementListItemWebResponse`의 `@Schema(requiredProperties)`에 `receivableAmount`를 넣고 nullable로 둔다. 다른 탭별 필드와 같은 방식이다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: `SettlementJpaRepository.findAllForUser`에 생성자를 뺀 share 합계 컬럼 추가. 결정 D3에 따라 품목·선택·지출·참여자 일괄 조회 쿼리 추가
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

`findAllForUser`는 `settlement_participants`와 `settlement_shares`를 이미 조인하므로 share 합계를 같은 GROUP BY에서 구할 수 있다. `settlement_links`는 `uk_settlement_links_settlement`(V16)로 정산당 하나라 합계가 중복되지 않는다. `payment_summary`는 서브쿼리에서 이미 집계된다.

## 5. Design decisions

### Business logic

- Rules and their locations: 보정 계산은 `SettlementItemAllocationPolicy`. 보정 적용 조건과 일괄 계산은 Application 컴포넌트(결정 D2)
- Behavior owned by Domain: 없음
- Decisions owned by Application Policy: 생성자 몫 계산
- Flow orchestrated by Application: `SettlementListService`가 RECEIVE 탭이면 목록을 받은 뒤 보정 대상 ID를 모아 한 번에 계산하고 결과에 넣는다

### Technology and patterns

- Constraint to solve: 목록은 여러 건이고, 보정 계산은 정산마다 품목·선택·지출을 읽는다
- Selected approach: 보정 대상 정산 ID 목록으로 재료를 IN 쿼리 몇 번에 불러온 뒤 메모리에서 계산한다
- Reason for selection: 쿼리 수가 목록 건수와 무관하다. 계산은 기존 정책을 그대로 써서 상세·AI 비서와 값이 갈라지지 않는다
- Alternatives considered: 정산마다 `TreasurerRequestedReceivableResolver.resolve` 호출(N+1). SQL로 생성자 몫 계산(정책 중복, 할인·배달비 배분 규칙이 두 곳에 생김)
- Trade-offs and limits: 신규 일괄 조회 쿼리와 매핑 코드가 늘어난다

### 결정

리뷰를 기다리지 않고 아래와 같이 정해 구현한다. 근거는 Decision log에 있다.

- **D1. `SUSPENDED` 정렬 → 포함.** 정산 상세는 `683b116d`(2026-09-29)부터 `REQUESTED`/`SUSPENDED` 모두 보정 금액을 쓴다. #514의 AI 비서는 `REQUESTED`만 봤고, #514 Plan의 "정산 상세도 이 상태는 share 합계를 쓴다"는 문장은 당시에도 코드와 달랐다. 목록과 AI 비서 모두 `SUSPENDED`를 포함한다.
- **D2. 계산 진입점 공유 → `RequestedItemReceivableCalculator`.** 목록과 AI 비서가 같은 일괄 계산 컴포넌트를 쓴다. AI 비서의 정산별 조회(`TreasurerRequestedReceivableResolver`)는 지우고 일괄 계산으로 바꾼다. 정산 상세는 한 건이라 Policy 메서드를 그대로 부르되, 적용 조건은 같은 `usesRequestedReceivable`을 쓴다.
- **D3. Port 모양 → 신규 능력 Port 하나.** ADR 0003 기준으로 "받을 금액 계산 재료"라는 한 능력을 표현하는 Port가 기존 Port 네 개에 일괄 메서드를 흩어 넣는 것보다 응집된다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 목록 응답에 nullable 필드 추가(하위 호환) | `SettlementListItemWebResponse` |
| Application | 목록 서비스에 받을 금액 계산 추가, 계산 컴포넌트 신설, AI 비서 정산별 조회 제거, 적용 조건 Policy 메서드 | `SettlementListService`, `RequestedItemReceivableCalculator`, `TreasurerSettlementQuery`, `SettlementItemAllocationPolicy`, `SettlementDetailService` |
| Domain | 없음 | - |
| Persistence | 목록 쿼리 컬럼 추가, 일괄 조회 쿼리 추가 | `SettlementJpaRepository.findAllForUser`, `SettlementPersistenceAdapter` |
| Redis | 없음 | - |
| Security | 없음 | - |
| Configuration | 없음 | - |
| Documentation | Swagger 설명, 클라이언트 계약 문서, #514 Plan의 SUSPENDED 문장 정정과 완료 이동, AI 비서 문서 | `SettlementListItemWebResponse`, `docs/features/active/settlement-client-contract.md`, `docs/features/completed/treasurer-item-allocation-receivable.md`, `docs/features/active/ai-treasurer.md` |

Existing behavior that must remain unchanged:

- `totalAmount` 값과 의미
- PAY 탭의 `amountToPay`와 품목 정산 총액 대체 규칙
- 정산 상세의 `expectedAmount` 계산
- 목록 필터·정렬·그룹 정보

## 7. Implementation plan

- [ ] Migration-only pull request to `develop` — N/A (스키마 변경 없음)
- [ ] UseCase and Application Command/Result — `SettlementListItemResult.receivableAmount`
- [ ] Required Output Ports — `LoadSettlementListPort` Read Model 필드, 결정 D3의 일괄 조회 Port
- [ ] Domain models and rules — N/A
- [ ] Application Policies — 기존 `calculateRequestedReceivableAmount` 재사용, 변경 없음
- [ ] Application Service — 계산 컴포넌트(D2), `SettlementListService`, (D1 채택 시) `TreasurerSettlementQuery`
- [ ] Output Adapters — 목록 쿼리 share 합계, 일괄 조회 쿼리
- [ ] Web Request/Response DTOs, Controllers, and Swagger — `SettlementListItemWebResponse`, Swagger 설명
- [ ] Infra configuration and Bean wiring — N/A
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 정산 목록 받을 금액 설계 수립
docs(settlement): 정산 목록 받을 금액 설계 결정 반영
feat(settlement): 받을 정산 목록에 생성자 몫을 뺀 받을 금액을 내려준다
fix(treasurer): 중단된 품목 정산의 받을 금액을 정산 상세와 맞춘다
test(settlement): 정산 목록 받을 금액 검증 추가
docs(treasurer): 받을 금액 기준 문서 정정
docs(settlement): 정산 목록 받을 금액 검증 결과 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
docker compose -p howmuch-test -f compose.test.yml up -d --wait
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
  REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  ./gradlew test --tests '*SettlementList*' --tests '*Treasurer*' --tests '*SettlementDetail*' --tests '*SettlementOpenApi*'
docker compose -p howmuch-test -f compose.test.yml down -v
git diff --check
git status --short --untracked-files=all
```

- 서비스 단위 테스트: 보정 대상/비대상, 생성자 비참여, 생성자 미선택, 할인·배달비, 계산 실패 대체, PAY 탭 null
- 영속 통합 테스트: share 합계 컬럼, 일괄 조회 쿼리 결과, 삭제 정산 제외, 깨진 행 격리
- OpenAPI 계약 테스트: `receivableAmount` nullable 노출
- 계획했지만 하지 않은 것: 목록·상세·AI 비서 금액을 한 fixture로 직접 대조하는 통합 테스트와 쿼리 수 측정 테스트. 상세 조회가 수령 계좌 snapshot 복호화와 은행 목록 조회에 의존해 fixture 비용이 크다. 대신 세 곳이 같은 Policy 메서드와 같은 적용 조건(`usesRequestedReceivable`)을 쓰는지를 코드와 단위 테스트로 확인했다. 쿼리 수는 Adapter 코드상 여섯 번으로 고정이다

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

로컬 Mac에서 `compose.test.yml`(Postgres, Redis)을 띄워 전체 테스트를 실행했다. 기준 커밋은 `df4bee8b`(develop `23c18cf9` 위)다.

| Item | Result | Evidence |
|---|---|---|
| 전체 테스트 | 245개 suite, 1,906건 중 1,901건 통과. 실패 5건은 Testcontainers가 colima Docker 소켓을 찾지 못한 초기화 실패(`DockerClientProviderStrategy`)였다 | `build/test-results/test/*.xml` |
| Testcontainers 재실행 | `DOCKER_HOST`를 colima 소켓으로 지정해 `*Certificate*`, `*Kafka*`를 다시 실행했고 실패한 5개 suite(65건)를 포함해 모두 통과했다. 이 브랜치는 인증서·Kafka 코드를 바꾸지 않는다 | 같은 위치 |
| 일괄 계산 | `RequestedItemReceivableCalculatorTest` 13건 통과(적용 조건 8, 생성자 몫, 할인·배달비, 생성자 비참여, 계산 실패 격리, 빈 입력) | 같은 위치 |
| 목록 서비스 | `SettlementListServiceTest` 22건 통과(재계산, Share 합계, 재료 없음 대체, PAY null 포함) | 같은 위치 |
| 목록 영속 | `SettlementListPersistenceIntegrationTest` 29건 통과(Share 합계 컬럼, 선택 대기 품목 정산 10,000원, 계산 재료 일괄 조회, 삭제 정산 제외, 깨진 행 격리 포함) | 같은 위치 |
| AI 비서 | `TreasurerSettlementQueryTest` 7건(SUSPENDED 보정 포함), `AskTreasurerServiceTest` 61건, `TreasurerAnswerReportIntegrationTest` 13건 통과. #535 후속 커밋을 포함한 treasurer 테스트 결과다 | 같은 위치 |
| 정산 상세 회귀 | `SettlementDetailServiceTest` 28건, `SettlementItemAllocationPolicyTest` 29건 통과 | 같은 위치 |
| Web 계약 | `SettlementControllerTest` 38건, `SettlementOpenApiIntegrationTest` 2건 통과(`receivableAmount` required·nullable) | 같은 위치 |
| 독립 리뷰 | P1 없음. P2 2건 중 깨진 행 격리는 수정하고 테스트를 추가했다. 직접 대조·쿼리 수 테스트는 위 사유로 넣지 않았다 | 리뷰 기록 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-14 | 초안 작성. develop `1d0c9826` 기준 코드 대조 | D1~D3 결정, 이슈 생성 | AI 비서와 상세의 `SUSPENDED` 금액 불일치 |
| 2026-10-14 | D1~D3 결정, develop `23c18cf9` 기준 구현 | Gradle 테스트, 이슈 생성 | 없음 |
| 2026-10-14 | 전체 테스트 통과, 독립 리뷰 반영, 완료 계획서로 이동 | 이슈·PR, FE에 `receivableAmount` 전환 요청 | `AWAITING_DEPOSIT`에서 직접 중단된 품목 정산은 Share가 다 있어도 보정 공식을 써서 Share 합계보다 몇 원 클 수 있다(상세와 같은 값) |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-14 | `totalAmount` 유지, `receivableAmount` 추가 | 기존 계약(PAY 탭 대체 규칙 설명 포함)과 배포된 앱 버전을 조용히 깨지 않는다 | `totalAmount` 의미 변경은 하위 호환 불가 |
| 2026-10-14 | D1: AI 비서도 `SUSPENDED` 보정 | 같은 정산의 받을 금액이 상세·목록·AI 비서에서 같아야 한다 | 중단된 품목 정산의 AI 비서 금액이 커진다(0원 → 실제 받을 금액) |
| 2026-10-14 | D2: 일괄 계산 컴포넌트 공유, AI 비서 정산별 조회 제거 | 조건과 계산이 한 곳에 있고 AI 비서 N+1도 사라진다 | `TreasurerRequestedReceivableResolver` 삭제 |
| 2026-10-14 | D3: 신규 능력 Port | ADR 0003. 계산 재료를 한 대화로 표현한다 | 기존 Port 네 개는 바꾸지 않는다 |

## 11. Completion record

- Completed on: 2026-10-14
- Final behavior: RECEIVE 목록 항목에 `receivableAmount`가 내려온다. 품목 정산 `REQUESTED`/`SUSPENDED`는 영수증 총액 − 생성자 몫(할인·배달비 반영), 그 밖에는 생성자를 뺀 Share 합계이며 정산 상세 `expectedAmount`와 같은 기준이다. PAY는 null이다. AI 비서도 `SUSPENDED`를 보정하고 목록과 같은 일괄 계산을 쓴다. 계산 재료는 정산 수와 무관하게 여섯 번 조회한다
- Unverified items: 목록·상세·AI 비서 금액의 단일 fixture 직접 대조, 쿼리 수 측정, 실제 앱 화면 표시
- Remaining risks: 직접 중단된(입금 대기 → 중단) 품목 정산은 보정 공식이 Share 합계보다 몇 원 클 수 있다. 목록 조회와 계산 재료 조회가 서로 다른 트랜잭션이라 그 사이 상태가 바뀌면 한 응답 안에서 값이 어긋날 수 있다. `application.treasurer`가 `application.settlement` 컴포넌트를 쓰는 첫 사례다(D2에서 의도)
- Follow-up work: FE의 받을 금액 표시를 `receivableAmount`로 전환, PAY 탭 내 몫 표시 규칙
