# Feature Plan: CommonCode 조회 캐시

- Status: Completed
- Last updated: 2026-08-23
- Related issue: [#67](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/67)
- Related ADR: 없음

## 1. Purpose

### Problem

은행 카탈로그와 계좌 연동 검증을 포함한 CommonCode 활성 조회가 요청마다 PostgreSQL을 조회한다. CommonCode는 관리자 변경 빈도가 낮고 데이터 크기도 작으므로 반복 조회를 애플리케이션 캐시로 줄인다.

### Why now

CommonCode 사용처가 `CommonCodeService`와 `BankCatalog`로 늘어났고, 각 호출자에 별도 캐시를 두면 캐시 정책이 분산된다.

### Success criteria

- [x] 활성 CommonCode 단건·목록·존재 여부 조회가 Spring Cache를 사용한다.
- [x] 캐시 항목은 저장 후 1분이 지나면 만료된다.
- [x] CommonCode 관리자 저장 성공 후 기존 CommonCode 캐시가 무효화된다.
- [x] 기존 은행 카탈로그와 CommonCode 관리자 기능의 결과 계약이 유지된다.

### Out of scope

- Redis 분산 캐시 전환
- CommonCode snapshot API
- 관리자 그룹 검색·상세 조회 자체의 캐싱
- 도메인별 `BankCatalog` 캐시 구현

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `commoncode`
- Reason a new domain is required, if applicable: 없음
- Relationships to other domains: `account`의 `BankCatalog`가 `LoadCommonCodePort`를 통해 은행 코드를 조회한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 활성 CommonCode 조회 | 활성 그룹과 활성 상세 코드만 조회하는 읽기 | `LoadCommonCodePort` |
| CommonCode 캐시 | 활성 조회 결과를 임시 보관하는 Spring Cache 항목 | `commonCodeByGroup`, `commonCodeByGroupAndCode`, `commonCodeExistsByGroupAndCode` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 인증 사용자 | 은행 목록 조회 | `GetBankCatalogUseCase` |
| 계좌 연동 UseCase | 은행 코드 지원 여부 검증 | `BankCatalog.validate` |
| 관리자 | CommonCode 저장 후 최신 값을 사용 | `CreateCommonCodeGroupUseCase`, `UpdateCommonCodeGroupUseCase` |

### State and invariants

- State transitions: 캐시는 조회 시 생성되고 TTL 만료 또는 CommonCode 저장 트랜잭션의 커밋 이후 제거된다.
- Rules that must always hold:
  - 캐시된 값은 활성 CommonCode 조회 결과와 동일한 형태여야 한다.
  - CommonCode 저장이 성공한 뒤 다음 조회는 저장 결과를 다시 읽을 수 있어야 한다.
- Invalid states: 실패한 저장 때문에 캐시가 제거되거나, 비활성 CommonCode가 활성 조회 캐시에 들어가는 상태

## 3. Scenarios

### Normal flow

1. Application이 `LoadCommonCodePort`의 활성 조회를 호출한다.
2. `CommonCodePersistenceAdapter`의 `@Cacheable`이 캐시 적중 여부를 판단한다.
3. 적중 시 JPA Repository를 호출하지 않고 캐시 값을 반환한다.
4. 미적중 시 Repository 결과를 반환하고 1분 TTL로 저장한다.

### Failure flow

- 조건: CommonCode 저장이 실패한다.
- 결과: 저장 Adapter의 캐시 무효화가 실행되지 않고 기존 캐시 정책을 유지한다.

### Boundary conditions

- 1분 경과: Caffeine 항목이 만료되고 다음 조회에서 DB를 다시 읽는다.
- 동시 미적중: `sync = true`로 같은 키에 대한 중복 로딩을 줄인다.
- 관리자 저장: 트랜잭션 커밋 이후 세 조회 캐시를 전체 제거해 커밋 전 stale 재캐싱과 롤백 시 불필요한 제거를 피한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 CommonCode 조회 UseCase 유지
Command: 변경 없음
Result: 기존 CommonCode 및 BankCatalog Result 유지
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 변경 없음
```

### Output Ports

기존 `LoadCommonCodePort`를 재사용한다. 캐시는 외부 저장소와 Application 계약을 변경하지 않는 Adapter 기술 세부사항이다.

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadCommonCodePort` | 활성 CommonCode 단건·목록·존재 여부 조회 | `CommonCodeService`, `BankCatalog` | `CommonCodePersistenceAdapter` |

### Application Policy

- Decision owned by the Policy: 없음
- Facts loaded by the Service and passed to the Policy: 변경 없음
- Why the rule is not an Entity-local invariant: 캐시는 업무 규칙이 아니라 조회 성능을 위한 기술 동작이다.
- Future inputs deliberately not implemented yet: 분산 캐시 일관성, 캐시 warm-up, 수동 purge API

### Web API

공개 API 계약은 변경하지 않는다.

```text
GET /api/v2/accounts/banks
Request: 없음
Response: 기존 BankCatalog 응답
Errors: 기존 계약 유지
Authentication: 기존 bearerAuth 유지
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): `No`
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음
- Cache technology: Spring Cache abstraction + Caffeine
- Cache TTL: `expireAfterWrite=60s`

## 5. Design decisions

### Business logic

- Rules and their locations: 활성 조회 조건은 기존 JPA Query와 `LoadCommonCodePort`에 유지한다.
- Behavior owned by Domain: 변경 없음
- Decisions owned by Application Policy: 변경 없음
- Flow orchestrated by Application: 기존 조회 흐름 유지

### Technology and patterns

- Constraint to solve: 반복적인 CommonCode DB 조회를 줄이되 호출자별 캐시 중복 구현을 피한다.
- Selected approach: `CommonCodePersistenceAdapter`의 활성 조회 메서드에 `@Cacheable`을 적용하고, 저장 성공 시 `TransactionSynchronization`의 `afterCommit`에서 Spring Cache 항목을 제거한다. Cache Manager는 Spring Boot의 Caffeine 자동 설정을 사용한다.
- Reason for selection: 외부 Port를 변경하지 않고 `CommonCodeService`와 `BankCatalog`의 모든 호출을 한 Adapter 경계에서 커버할 수 있다.
- Alternatives considered:
  - 호출자별 `CommonCodeService`·`BankCatalog` 캐시: 같은 정책이 분산되고 직접 Port 호출을 놓칠 수 있다.
  - Redis Cache: 다중 인스턴스 공유에는 유리하지만 이번 1분 로컬 캐시 요구에는 직렬화·운영 설정이 추가된다.
  - 수동 Map/TTL 구현: Spring Cache가 제공하는 동시성·만료 추상화를 중복 구현한다.
- Trade-offs and limits: 인스턴스별 캐시이므로 다중 인스턴스 간 즉시 공유하지 않는다. 현재는 TTL 1분과 저장 성공 후 무효화로 허용 가능한 stale 범위를 제한한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답·경로 변경 없음 | `BankCatalog` 호출 흐름 |
| Application | Port와 UseCase 변경 없음 | `LoadCommonCodePort`, `CommonCodeService`, `BankCatalog` |
| Domain | 변경 없음 | `domain.commoncode.CommonCode` |
| Persistence | 조회 Adapter 캐시 annotation, 저장 후 무효화 | `CommonCodePersistenceAdapter` |
| Redis | 영향 없음 | 해당 없음 |
| Security | 영향 없음 | 해당 없음 |
| Configuration | `CommonCodeCacheConfig`에서 Spring Cache 활성화, Caffeine 의존성과 1분 spec 추가 | `CommonCodeCacheConfig`, `build.gradle`, `application.yaml` |
| Documentation | Feature Plan과 Issue #67 갱신 | 본 문서 |

Existing behavior that must remain unchanged:

- 활성 CommonCode의 그룹·코드·정렬 조건
- 은행 카탈로그 응답과 계좌 연동 전 은행 코드 검증
- CommonCode 관리자 저장 및 낙관적 락 동작

## 7. Implementation plan

- [x] Migration-only pull request to `develop`: `N/A` — DB 구조 변경 없음
- [x] UseCase and Application Command/Result: `N/A` — 기존 계약 유지
- [x] Required Output Ports: `N/A` — 기존 `LoadCommonCodePort` 재사용
- [x] Domain models and rules: `N/A` — 변경 없음
- [x] Application Policies: `N/A` — 변경 없음
- [x] Application Service: `N/A` — 호출 흐름 유지
- [x] Output Adapters: CommonCode 조회 캐시와 저장 후 무효화
- [x] Web Request/Response DTOs, Controllers, and Swagger: `N/A` — API 계약 변경 없음
- [x] Infra configuration and Bean wiring: `CommonCodeCacheConfig`에서 `@EnableCaching` 활성화 및 Caffeine 자동 설정
- [x] Documentation and verification results

### Expected commit boundaries

```text
build(commoncode): Spring Cache와 Caffeine 의존성 추가
feat(commoncode): CommonCode 조회 캐시와 저장 후 무효화 구현
docs(commoncode): 캐시 Feature Plan과 검증 결과 갱신
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*CommonCode*'
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [x] 세 활성 조회 경로의 캐시 적중 확인
- [x] 저장 성공 후 캐시 무효화 확인
- [x] 1분 TTL 설정 확인
- [x] 컴파일·관련 테스트·diff check 통과

### Results

| Item | Result | Evidence |
|---|---|---|
| 활성 조회 캐시 적중 | Passed | `CommonCodePersistenceAdapterCacheTest.cachesAllActiveReadPaths` |
| 커밋 후 캐시 무효화 | Passed | `CommonCodePersistenceAdapterCacheTest.evictsCachesAfterCommit` |
| 롤백 시 캐시 유지 | Passed | `CommonCodePersistenceAdapterCacheTest.keepsCachesAfterRollback` |
| 1분 TTL 및 만료 후 재조회 | Passed | `CommonCodePersistenceAdapterCacheTest.reloadsAfterTtl` |
| 전체 Gradle 테스트 | Partial | `.env` 환경에서 `./gradlew test`: 187개 중 185개 통과, 2개 실패. `AccountTransactionPersistenceIntegrationTest`의 `account_transactions`, `account_monthly_summaries` PostgreSQL 권한 오류 |
| 애플리케이션 캐시 프록시·자동 설정 | Passed | `CommonCodeCacheApplicationContextTest` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-23 | Issue #67 범위와 Spring Cache + Caffeine 방향 확정 | 코드 구현 | 다중 인스턴스 간 캐시 공유는 범위 밖 |
| 2026-08-23 | 세 활성 조회 경로 캐시, 저장 후 무효화, 관련 테스트 구현 | 문서 완료 처리 | 다중 인스턴스 간 캐시 공유는 범위 밖 |
| 2026-08-24 | 저장 캐시 무효화를 트랜잭션 `afterCommit`으로 변경하고 Boot context·TTL 테스트 추가 | PR 리뷰 반영 | 전체 테스트는 기존 PostgreSQL 권한 오류로 Partial |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-23 | CommonCodePersistenceAdapter에 캐시 적용 | `CommonCodeService`와 `BankCatalog`의 공통 경계에서 한 번에 커버 | 호출자별 캐시 구현을 만들지 않음 |
| 2026-08-23 | Caffeine `expireAfterWrite=60s` 사용 | Spring Cache 추상화와 로컬 TTL을 가장 작게 구성 | Redis 분산 캐시는 후속 과제 |
| 2026-08-24 | 저장 후 캐시 제거는 트랜잭션 `afterCommit`에서 실행 | 커밋 전 stale 재캐싱과 롤백 시 조기 제거 방지 | 트랜잭션이 없으면 Adapter 반환 후 즉시 제거 |

## 11. Completion record

- Completed on: 2026-08-23
- Final behavior: 활성 CommonCode 단건·목록·존재 여부 조회를 Caffeine에 1분간 캐시하고 CommonCode 저장 성공 후 외부 트랜잭션 커밋 시 전체 캐시를 비운다.
- Unverified items: 실제 운영 PostgreSQL 권한 환경에서 전체 테스트 2건을 확인하지 못함. CommonCode 테스트와 캐시 설정·TTL은 통과했다.
- Remaining risks: 다중 인스턴스별 캐시가 서로 다른 값을 최대 TTL 동안 보유할 수 있음
- Follow-up work: 운영에서 다중 인스턴스 즉시 일관성이 요구되면 Redis Cache 검토
