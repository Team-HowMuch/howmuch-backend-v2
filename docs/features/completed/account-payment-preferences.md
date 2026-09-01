# Feature Plan: 기본 결제수단 및 계좌 표시 순서 변경

- Status: Completed
- Last updated: 2026-08-31
- Related issue: N/A
- Related ADR: N/A

## 1. Purpose

### Problem

계좌 조회 응답에는 `isDefault`, `displayOrder`가 있지만 사용자가 이를 변경할 API가 없다. 앱에서 기본 결제수단을 선택하거나 계좌 카드 순서를 바꿔도 서버 상태에 반영할 수 없다.

### Success criteria

- [x] 소유한 활성 계좌를 기본 결제수단으로 지정할 수 있다.
- [x] 기본 결제수단은 사용자별 활성 계좌 중 하나만 유지된다.
- [x] 소유한 활성 계좌를 요청한 `displayOrder` 위치로 이동할 수 있다.
- [x] 순서 변경 뒤 활성 계좌의 `displayOrder`는 `0..N-1`로 정규화된다.
- [x] 타인·삭제 계좌는 기존 `ACCOUNT_NOT_FOUND`로 숨긴다.

### Out of scope

- 계좌명 변경
- CODEF 원격 계좌 설정 변경
- 기본 계좌 해제만 수행하는 API
- 여러 계좌 ID를 한 번에 전달하는 별도 reorder API

## 2. Domain and scenarios

- Existing domain: `Account`
- Actor: 로그인 사용자
- UseCases: `SetDefaultAccountUseCase`, `UpdateAccountDisplayOrderUseCase`
- Invariants:
  - 기본 계좌는 활성 계좌 중 정확히 하나만 선택한다.
  - 표시 순서는 활성 계좌 목록 안의 0 기반 위치다.
  - 변경 시 사용자 소유의 활성 계좌 전체를 잠가 동시 변경을 직렬화한다.

### Normal flow

1. 현재 사용자 ID로 활성 계좌 전체를 ID 순서로 잠금 조회한다.
2. 대상 계좌 소유권과 활성 상태를 확인한다.
3. 기본 계좌 변경은 기존 기본 계좌를 해제한 뒤 대상을 설정한다.
4. 순서 변경은 현재 `(displayOrder, id)` 순서에서 대상을 이동하고 전체 순서를 저장한다.

### Failure and boundaries

- 대상 계좌 없음: `404 ACCOUNT_NOT_FOUND`
- `displayOrder < 0` 또는 활성 계좌 수 이상: `400 INVALID_ACCOUNT_DISPLAY_ORDER`
- 이미 기본 계좌거나 같은 위치인 요청: 성공하는 멱등 요청으로 처리한다.

## 3. Contracts

```text
PATCH /api/v2/accounts/{accountId}/default
Response: ApiResponse<Void>

PATCH /api/v2/accounts/{accountId}/display-order
Body: { "displayOrder": 1 }
Response: ApiResponse<Void>
```

- `UpdateAccountDisplayOrderWebRequest -> UpdateAccountDisplayOrderCommand`
- `AccountPreferenceService -> AccountDisplayOrderPolicy + LoadAccountPort + AccountPreferenceStorePort`
- Authentication: `bearerAuth`
- Flyway migration required: No. 기존 `accounts.is_default`, `accounts.display_order`를 사용한다.

## 4. Design decisions

- 기본 계좌는 같은 트랜잭션에서 기존 값을 해제한 뒤 대상을 설정해 partial unique index 충돌을 피한다.
- 다중 계좌의 순서 검증·계산은 `AccountDisplayOrderPolicy`가 수행하고 Adapter는 계산된 계좌 ID 순서를 저장한다.
- 별도 응답 DTO는 만들지 않는다. 변경 후 목록은 기존 `GET /api/v2/accounts`로 조회한다.

## 5. Impact surface

| Area | Impact |
|---|---|
| API | Account Controller·Swagger에 PATCH 2개 추가 |
| Application | UseCase 2개, Command 1개, Service 1개, Policy 1개 추가 |
| Domain | 잘못된 표시 순서 StatusCode 추가 |
| Persistence | 활성 계좌 잠금 조회, 기본 여부·순서 update 추가 |
| Database | 기존 컬럼·제약 재사용, migration 없음 |
| Security | `CurrentActorPort` 기준 소유 계좌만 변경 |

## 6. Implementation and verification

- [x] UseCase·Command·Output Port 정의
- [x] Application 순서·소유권 규칙 구현
- [x] JPA 잠금·update 구현
- [x] Web DTO·Controller·Swagger 구현
- [x] 단위·Web·PostgreSQL 통합 검증
- [x] 문서 완료 상태 반영

### Expected commit boundary

```text
feat(account): 기본 결제수단과 표시 순서 변경 API 추가
```

### Verification

```bash
./gradlew compileJava
./gradlew test --tests '*AccountPreferenceServiceTest' --tests '*AccountControllerTest' --tests '*AccountPreferencePersistenceIntegrationTest'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

### Verification result

- `./gradlew compileJava`: 성공
- Application·Web 집중 테스트: 성공
- PostgreSQL 16 영속 통합 테스트: 성공
- PostgreSQL 16·Redis 7 전체 테스트 522개: 성공
- `git diff --check`: 성공
