# Feature Plan: 계좌 표시 순서 일괄 저장

- Status: In progress
- Last updated: 2026-09-01
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/223
- Related ADR: -

## 1. Purpose

### Problem

앱의 계좌 관리 바텀시트는 드래그로 **여러 계좌를 옮긴 뒤 저장을 한 번** 누른다.
확정 시점에는 항상 목록 전체의 순서가 함께 바뀐다.

지금 있는 쓰기 API는 `PATCH /api/v2/accounts/{accountId}/display-order` 하나뿐인데,
이것은 **"한 계좌를 특정 위치로 옮긴다(move-to-index)"** 는 의미다.
`AccountDisplayOrderPolicy.reorder`는 대상 계좌를 목록에서 빼고 지정 인덱스에 다시 넣은 뒤
나머지를 `0..n-1`로 재배치한다.

호출 하나하나가 전체 순서를 다시 계산하므로, 클라이언트가 여러 번 호출하면
**앞선 호출이 바꿔놓은 목록 위에서 다음 호출의 인덱스가 해석된다.** 사용자가 화면에서 만든
배열과 서버에 남는 순서가 달라진다.

추가로 두 가지 문제가 있다.

- `AccountPreferenceService.updateDisplayOrder`는 매 호출마다
  `loadAccountPort.findActiveByUserIdForUpdate(userId)`로 **같은 행들에 비관적 쓰기 잠금**을 건다.
  N개의 요청이 서로를 기다린다.
- 중간에 하나가 실패하면 앞의 호출은 이미 커밋되어 **사용자가 지정한 적 없는 순서**가 남는다.
  클라이언트가 되돌릴 방법이 없다.

### Why now

계좌 관리 화면의 순서 변경이 앱에 붙는 시점이다. 순차 호출로 두면 저장 결과가 화면과 다르게
남는 일이 상시 발생하고, 사용자는 원인을 알 수 없다.

### Success criteria

- [ ] 한 번의 요청으로 활성 계좌 전체의 순서가 저장되고, 하나라도 어긋나면 아무것도 저장되지 않는다.
- [ ] 저장된 순서가 요청 배열과 정확히 일치한다.
- [ ] 계좌가 빠졌거나 중복이거나 소유하지 않은 계좌면 저장을 거부한다.
- [ ] 기존 단건 `PATCH`의 동작은 그대로 유지된다.

### Out of scope

- 단건 `PATCH /{accountId}/display-order` 제거 — 다른 클라이언트가 쓰고 있을 수 있어 별도 판단한다.
- 기본 계좌 설정(`PATCH /{accountId}/default`) 동작 변경
- 비활성 계좌의 표시 순서

## 2. Domain analysis

### Domain boundary

- 재사용할 기존 도메인: `account` (`Account`)
- 새 도메인이 필요한 이유: 없음. 저장 단위만 계좌 1건에서 전체로 넓힌다.
- 다른 도메인과의 관계: 없음

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 표시 순서 | 계좌 목록에서의 0 기반 위치 | `Account.displayOrder` |
| 일괄 저장 | 활성 계좌 전체의 순서를 한 트랜잭션에서 교체 | `ReplaceAccountDisplayOrdersUseCase` |
| 기본 계좌 | 정산 수령 등에서 먼저 제안되는 계좌 | `Account.isDefault` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 계좌 소유자 | 바텀시트에서 만든 순서를 한 번에 확정한다 | `ReplaceAccountDisplayOrdersUseCase` |

### State and invariants

- 상태 전이: 없음
- 항상 지켜야 하는 규칙:
  - 요청은 소유한 활성 계좌 전체를 정확히 한 번씩 포함한다.
  - 저장 후 `displayOrder`는 `0..n-1`이며 중복이 없다.
- 잘못된 상태: 계좌 누락·중복·미소유 계좌 포함

## 3. Scenarios

### Normal flow

1. 사용자에게 활성 계좌 3건이 `[10, 11, 12]` 순서로 있다.
2. 바텀시트에서 드래그해 `[11, 12, 10]`으로 만들고 저장을 누른다.
3. 앱이 `{"accountIds": [11, 12, 10]}` 을 한 번 보낸다.
4. 한 트랜잭션에서 `11→0, 12→1, 10→2`가 반영된다.

### Failure flow

- 계좌 누락·중복·미소유 계좌 → `INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST` (400)
- 빈 배열 또는 `null` 요소 → `INVALID_ARGUMENT` (400, Bean Validation)
- 인증 없음 → 401

### Boundary conditions

- 동시성: 기존 `findActiveByUserIdForUpdate`의 비관적 잠금을 그대로 쓴다. 같은 사용자의 동시 저장은
  직렬화된다. 잠금 획득은 `PostgresLockTimeoutConfigurer`의 500ms 제한을 따르고, 초과 시
  `GlobalExceptionHandler`가 503으로 변환한다.
- 활성 계좌 1건: 전체 포함 규칙을 만족하면 정상 처리된다.
- 활성 계좌 0건: 요청이 비어 있으면 Bean Validation에서 거부된다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ReplaceAccountDisplayOrdersUseCase.replaceDisplayOrders(ReplaceAccountDisplayOrdersCommand command)
Command: ReplaceAccountDisplayOrdersCommand(List<Long> accountIds)
Result:  없음 (void)
Web Request DTO -> Command: ReplaceAccountDisplayOrdersWebRequest.toCommand()
Result -> Web Response DTO: ApiResponse.success() (기존 단건 PATCH와 동일)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadAccountPort` | 잠금 포함 활성 계좌 조회 | `AccountPreferenceService` | `AccountPersistenceAdapter` |
| `AccountPreferenceStorePort` | 순서 배열대로 `0..n-1` 재배치 | `AccountPreferenceService` | `AccountPersistenceAdapter` |

새 Port도, 새 Port 메서드도 만들지 않는다. `updateDisplayOrders(userId, orderedAccountIds)` 가
이미 순서 배열을 받아 인덱스를 그대로 `displayOrder`로 쓴다. 단건 경로가 `reorder`로 배열을
만들어 넘기던 자리에, 일괄 경로는 검증된 요청 배열을 그대로 넘긴다.

### Application Policy

- Policy가 소유하는 판단: 요청 배열이 활성 계좌 전체를 정확히 한 번씩 덮는지
- Service가 Port로 읽어 Policy에 넘기는 사실: `List<Account>`(활성 계좌), 요청 ID 배열
- Entity 불변식이 아닌 이유: `Account` 1건은 자기 `displayOrder`만 알고, 전체 포함 여부는
  사용자의 활성 계좌 목록과 요청 전체를 함께 봐야 결정된다.
- 지금 구현하지 않는 입력: 비활성 계좌

### Web API

```text
PATCH /api/v2/accounts/display-order

Request:
{ "accountIds": [11, 12, 10] }   // 배열 순서가 곧 displayOrder 0..n-1

Response:
{ "success": true, "data": null }

Errors:
  400 INVALID_ARGUMENT                        빈 배열 또는 null 요소
  400 INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST   누락·중복·미소유 계좌
  401 UNAUTHORIZED
  503 TEMPORARILY_UNAVAILABLE                 잠금 대기 초과

Authentication: bearerAuth
```

경로 충돌은 없다. 컨트롤러의 기존 `PATCH` 매핑은 `/{accountId}/default` 와
`/{accountId}/display-order` 뿐이고, `PATCH /api/v2/accounts/{accountId}` 매핑은 존재하지 않는다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): **No**
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- 규칙 위치: 전체 포함·중복·소유 검증은 `AccountDisplayOrderPolicy`(Application Policy),
  현재 사용자 조회와 트랜잭션 경계는 `AccountPreferenceService`
- Domain이 소유하는 행위: 없음. `displayOrder`는 Port가 인덱스로 부여한다.
- Application Policy가 소유하는 판단: `AccountDisplayOrderPolicy.validateReplacement(...)`
- Application이 조립하는 흐름: 현재 사용자 → 잠금 조회 → Policy 검증 → `updateDisplayOrders`

### Technology and patterns

- 해결할 제약: 계좌 수만큼의 순차 호출은 원자성이 없고, 단건 API의 move-to-index 의미 때문에
  결과가 호출 순서에 의존한다.
- 선택한 접근: 단일 `@Transactional` UseCase에서 전체 배열을 그대로 반영
- 선택 이유: 잠금을 한 번만 잡고, 트랜잭션 경계가 곧 원자성 경계이며, 기존 Port를 그대로 쓴다.
- 검토한 대안:
  - 단건 `PATCH`의 의미를 "절대 위치 지정"으로 바꾸기 — 기존 계약이 깨지고 순차 호출의 부분 실패는 남는다.
  - 클라이언트 보상 트랜잭션 — 보상 호출 자체가 실패할 수 있다.
- 트레이드오프와 한계: `updateDisplayOrders`가 계좌 수만큼 UPDATE를 보낸다. 계좌는 소수라
  문제되지 않는다고 판단했다.

### 이슈 #223이 남긴 결정 사항에 대한 답

1. **전원 포함 강제** — 강제한다. 일관성 때문만이 아니라 **정확성 때문에 필수**다.
   `updateDisplayOrders`는 배열에 있는 계좌만 갱신하므로, 빠진 계좌는 옛 `displayOrder`를 그대로
   유지해 순서 값이 중복된다.
2. **기본 계좌 취급** — 배열 순서를 그대로 존중한다. `setDefault`는 `displayOrder`를 전혀 건드리지
   않아 두 값은 독립된 축이고, 기본 계좌를 0번으로 강제하면 사용자가 드래그해 만든 화면과
   저장 결과가 달라진다.
3. **중복·미소유 ID** — 400으로 거부한다.
4. **기존 단건 `PATCH`** — 유지한다. 제거는 클라이언트 전환을 확인한 뒤 별도로 판단한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `PATCH /api/v2/accounts/display-order` 추가 | `AccountController`, `AccountDocs` |
| Application | UseCase·Command 추가, Service에 구현 추가, Policy에 검증 추가 | `AccountPreferenceService`, `AccountDisplayOrderPolicy` |
| Domain | 상태 코드 1개 추가 | `AccountStatusCode` |
| Persistence | 변경 없음 | `AccountPreferenceStorePort` |
| Redis | 변경 없음 | - |
| Security | 변경 없음 | `SecurityConfig` |
| Configuration | 변경 없음 | - |
| Documentation | 이 Feature Plan, Swagger | `docs/features/active/account-display-order-bulk-save.md` |

변경되면 안 되는 기존 동작:

- `PATCH /{accountId}/display-order`의 move-to-index 의미와 오류 계약
- `PATCH /{accountId}/default`가 `displayOrder`를 건드리지 않는 것
- 계좌 목록 조회의 정렬 기준

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports (기존 재사용, 추가 없음)
- [x] Domain models and rules (상태 코드 추가)
- [x] Application Policies
- [x] Application Service
- [x] Output Adapters (변경 없음)
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A` — 신규 Bean 없음)
- [ ] Documentation and verification results

### Expected commit boundaries

```text
docs(account): 계좌 표시 순서 일괄 저장 설계 문서 추가
feat(account): 계좌 표시 순서 일괄 저장 API 추가
test(account): 계좌 표시 순서 일괄 저장 검증 테스트 추가
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*AccountPreference*'
git diff --check
git status --short --untracked-files=all
```

수동 확인 절차(PostgreSQL 필요):

1. 활성 계좌 3건을 만들고 `GET /api/v2/accounts`로 현재 순서를 확인한다.
2. 역순 배열을 `PATCH /api/v2/accounts/display-order`로 보내고 다시 조회해 순서가 뒤집혔는지 본다.
3. 계좌 하나를 빼고 보내 400 `INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST`와,
   실패 후 순서가 직전 그대로인지 확인한다.
4. 다른 사용자의 계좌 ID를 섞어 보내 400이 나오는지 확인한다.

### Completion criteria

- [ ] Purpose의 사용자 결과를 확인했다.
- [ ] 정상·실패·경계 흐름을 확인했다.
- [ ] 문서와 Swagger가 실제 계약과 일치한다.
- [ ] 수행하지 못한 검증과 이유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | 오류 없음 |
| `AccountPreferenceServiceTest` | Pass | 배열 그대로 저장, 기본 계좌 위치 유지, 누락·중복·미소유 거부 5건 추가 |
| `AccountControllerTest` | Pass | 경로 비충돌·Command 변환 1건, 오류 계약 1건 추가 |
| 수동 API 확인 | Not run | 로컬 PostgreSQL 미기동 |

`compileTestJava`가 `origin/develop`에서 깨져 있어(PR #220에서 수정) 테스트 실행에는 해당 커밋을
임시로 얹었고, 이 브랜치에는 포함하지 않았다. **#220 머지 후에 CI가 정상 확인할 수 있다.**

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-01 | 설계 문서 작성, 계약 확정 | 구현 및 검증 | 단건 `PATCH`와 일괄 `PATCH`가 공존하는 동안, 클라이언트가 둘을 섞어 쓰면 여전히 순서가 어긋날 수 있다. 프론트 전환 완료를 확인해야 한다. |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-01 | 활성 계좌 전체 포함을 강제 | 빠진 계좌가 옛 `displayOrder`를 유지해 순서 값이 중복되므로 정확성 문제 | 프론트는 항상 전체를 보내므로 영향 없음 |
| 2026-09-01 | 기본 계좌를 0번으로 강제하지 않음 | `setDefault`가 `displayOrder`를 건드리지 않아 두 값은 독립 축 | 기본 계좌가 목록 중간에 올 수 있다 |
| 2026-09-01 | 전용 상태 코드 신설 | `INVALID_ACCOUNT_DISPLAY_ORDER`는 단건 인덱스 범위 오류로 이미 쓰여 원인 구분 불가 | 클라이언트가 분기할 코드가 하나 늘어난다 |
| 2026-09-01 | 새 Port 메서드를 만들지 않음 | `updateDisplayOrders`가 이미 순서 배열을 받는 형태 | 단건·일괄이 같은 Port를 공유한다 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
