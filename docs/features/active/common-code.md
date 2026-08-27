# Feature Plan: Common Code 조회 도메인 및 JPA 구조

- Status: In progress
- Last updated: 2026-08-22
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

계좌·은행 등 여러 기능에서 공통 코드를 사용해야 하지만, 현재 `common_code_group`과 `common_code_detail` 테이블만 정의되어 있고 애플리케이션에서 이를 조회하는 Domain, Output Port, JPA Adapter 계약이 없다.

각 기능이 JPA Repository를 직접 호출하거나 코드값을 중복 보유하면 저장소 기술이 Application으로 누출되고 코드 정의가 분산된다.

### Why now

은행 코드를 포함한 공통 코드 사용처를 추가하기 전에 조회 경계를 고정해야 한다. 먼저 공통 조회 계약을 만들고, 이후 실제 호출자가 생길 때 도메인별 정책이나 Provider를 추가한다.

### Success criteria

- [x] Application Service가 JPA Entity와 Spring Data Repository를 직접 참조하지 않고 공통 코드 조회 계약을 사용한다.
- [ ] 활성 공통 코드만 조회되며, 존재하지 않거나 비활성인 코드는 `Optional.empty()` 또는 빈 목록으로 구분된다.
- [x] `common_code_detail`의 복합키 `(group_code, code)`가 Domain과 JPA 사이에서 손실 없이 매핑된다.
- [x] Domain 코드가 JPA, Spring, HTTP 타입에 의존하지 않는다.

### Out of scope

- 공통 코드 등록·수정·삭제 API와 관리자 기능
- `CommonCodeSnapshot` 캐시 및 Redis 저장
- `getMap` 결과 캐시와 스냅샷 최적화
- 공통 코드 그룹별 `BankCodeProvider` 등 별도 Provider의 선제적 생성
- 코드값을 Enum으로 변환하는 도메인별 정책

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: 없음
- Reason a new domain is required: 여러 기능이 공유하는 저장 데이터지만, 공통 응답·예외를 담는 `common` 패키지와는 다른 조회 도메인이다.
- Relationships to other domains: `account` 등 호출 도메인이 `LoadCommonCodePort`를 사용한다. `commoncode`는 호출 도메인의 비즈니스 규칙을 소유하지 않는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| Common code group | 관련 상세 코드의 논리적 묶음 | `common_code_group`, `groupCode` |
| Common code | 그룹에 속한 하나의 코드 항목 | `domain.commoncode.CommonCode` |
| Active code | `use_yn = TRUE`인 코드 | Domain의 `active` 또는 조회 조건 |
| Code key | 그룹 코드와 상세 코드의 복합 식별자 | `(groupCode, code)` |

### Domain model

`CommonCodeItem`이라는 범용 이름 대신 조회 대상의 의미를 드러내는 `domain.commoncode.CommonCode`를 사용한다.

| Domain field | Type | Rule |
|---|---|---|
| `groupCode` | `String` | 필수, 공통 코드 그룹 식별자 |
| `code` | `String` | 필수, 그룹 내 코드 식별자 |
| `name` | `String` | 필수, 표시명 |
| `description` | `String` | 선택 |
| `sortOrder` | `int` | 기본값 `0` |
| `attr1`~`attr3` | `String` | 선택, 공통 Domain은 의미를 해석하지 않음 |
| `active` | `boolean` | `use_yn`과 매핑 |

`created_at`, `updated_at`, `attr1_name`~`attr3_name`은 현재 조회 계약에서 사용하지 않으므로 Domain 값에 노출하지 않는다. JPA Entity가 저장 구조를 보존하고, 실제 요구가 생길 때 명시적으로 추가한다.

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 인증 사용자 | 연동 가능한 은행 목록 조회 | `GetBankCatalogUseCase` |
| `BankAccountService` | 요청 기관이 지원 은행인지 검증 | `BankCatalog.validate` |

공통 코드 전체를 노출하는 범용 API는 만들지 않고, `account` 도메인의 `BankCatalog`만 `BANK` 그룹을 해석한다.

### State and invariants

- State transitions: 이 범위에서는 읽기 전용이다.
- Rules that must always hold:
  - `groupCode`와 `code`는 비어 있지 않다.
  - 하나의 `(groupCode, code)` 조합은 하나의 코드만 식별한다.
  - 비활성 코드(`use_yn = FALSE`)는 활성 조회 결과에 포함하지 않는다.
  - 그룹이 존재하지 않거나 코드가 존재하지 않으면 단건 조회는 `Optional.empty()`를 반환한다.
- Invalid states:
  - `groupCode` 또는 `code`가 `null`·공백인 Domain 값
  - 같은 그룹 안에서 중복된 `code`
  - 존재하지 않는 그룹을 참조하는 상세 코드

## 3. Scenarios

### Normal flow

1. Application Service가 `groupCode`와 `code`로 활성 코드 조회를 요청한다.
2. `LoadCommonCodePort`가 `CommonCode` Domain 값 또는 `Optional.empty()`를 반환한다.
3. Application Service가 해당 값을 자기 UseCase의 Result 또는 정책 입력으로 사용한다.

### Failure flow

- 조건: 그룹 또는 코드가 없거나 코드가 비활성이다.
- 결과: 단건 조회는 `Optional.empty()`, 목록 조회는 빈 목록을 반환한다. 사용자에게 오류를 노출해야 하는 UseCase가 필요한 경우 그 UseCase의 Application 정책이 적절한 오류 코드로 변환한다.

### Boundary conditions

- 빈 `groupCode`·`code`: Adapter 진입 전 또는 Application 경계에서 입력을 거부한다.
- 같은 그룹의 정렬: 목록은 `sort_order ASC`, 동률이면 `code ASC`를 기본 정렬로 한다.
- 속성값: `attr1`~`attr3`는 저장·조회하되 공통 코드 Domain에서 의미를 해석하지 않는다.
- 그룹 메타데이터: 상세 코드 조회에 필요하지 않으므로 첫 구현에서는 JPA 연관관계를 만들지 않는다. 외래키는 DB가 보장한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetBankCatalogUseCase
Command: 없음
Result: BankCatalogResult
Web Request DTO -> Command: 요청 본문 없음
Result -> Web Response DTO: BankCatalogResult -> BankCatalogWebResponse
```

### Output Ports

권장 이름은 기술 역할인 `Provider`보다 Application이 요구하는 능력을 드러내는 `LoadCommonCodePort`다.

```java
public interface LoadCommonCodePort {
    Optional<CommonCode> findActiveByGroupCodeAndCode(String groupCode, String code);

    List<CommonCode> findActiveByGroupCode(String groupCode);

    boolean existsActiveByGroupCodeAndCode(String groupCode, String code);
}
```

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadCommonCodePort` | 활성 공통 코드 단건·목록·존재 여부 조회 | `CommonCodeService`, `BankCatalog` | `CommonCodePersistenceAdapter` |

원래 제안된 메서드의 처리 방향:

| 기존 메서드 | 처리 | 이유 |
|---|---|---|
| `get` | 제외 | `find...().orElseThrow(...)`가 호출 UseCase의 오류 의미를 보존한다. |
| `find` | `findActiveByGroupCodeAndCode`로 변경 | 활성 조건과 의도를 이름에 포함한다. |
| `getAll` | `findActiveByGroupCode`로 변경 | 목록 조회와 활성 조건을 명확히 한다. |
| `exists` | `existsActiveByGroupCodeAndCode`로 제공 | 검증 시 Domain 변환 없이 존재 여부만 조회한다. |
| `isValid` | 초기 제외 | 저장소 조회와 비즈니스 검증을 한 인터페이스에 섞지 않는다. |
| `validate` | `CommonCodeService`가 제공 | 오류 코드와 검증 목적은 Application이 소유한다. |
| `getMap` | `CommonCodeService`가 제공 | Port의 목록 조회 한 번을 Application에서 불변 Map으로 변환한다. |
| `getSnapshot` | 제외 | 캐시 일관성·갱신 정책이 결정되기 전에는 만들지 않는다. |

### Application Service

- Location: `application.commoncode.service.CommonCodeService`
- Transaction: 클래스 단위 `@Transactional(readOnly = true)`
- `validate`: 활성 코드 존재 여부를 조회하고 없으면 `INVALID_COMMON_CODE`를 반환한다.
- `getMap`: 활성 목록을 한 번 조회해 `code`를 Key로 하는 불변 Map으로 변환한다.
- Persistence Adapter는 트랜잭션과 Application 예외 결정을 소유하지 않는다.

`application.account.service.BankCatalog`는 `BANK` 그룹만 해석한다.

- `getBanks`: 활성 은행 코드를 정렬된 `BankCatalogResult(organization, name, iconUrl)` 목록으로 변환한다.
- `validate`: 활성 은행 코드가 아니면 `BANK_NOT_SUPPORTED`를 반환한다.
- `BankAccountService`는 CODEF 호출 전에 `validate`를 수행한다.

### Application Policy

- Decision owned by the Policy: 특정 도메인에서 코드가 허용되는지 여부, 코드 미존재 시 사용자 오류로 변환하는 규칙
- Facts loaded by the Service and passed to the Policy: `CommonCode` 또는 `Optional<CommonCode>`와 해당 도메인의 상태
- Why the rule is not an Entity-local invariant: 코드 허용 여부는 공통 코드 자체가 아니라 호출 도메인의 업무 맥락에 따라 달라진다.
- Future inputs deliberately not implemented yet: 은행 코드의 금융기관별 규칙, 코드 스냅샷, 캐시 만료 정책

첫 단계에서는 실제 다중 사실 판단이 없으므로 별도 Policy를 만들지 않는다.

### Web API

```text
GET /api/v2/accounts/banks
Request: 없음
Response: [{ organization, name, iconUrl }]
Errors: 없음(활성 은행이 없으면 빈 목록)
Authentication: bearerAuth
```

공통 코드 전용 Controller는 만들지 않는다. 계좌 연동 화면에 필요한 은행 정보만 `account` API로 제공한다.

### Persistence and external integrations

- Database Entity: `adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity`
- Composite key: `CommonCodeJpaId` with `groupCode` and `code`, mapped with `@EmbeddedId`
- Repository: `adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository`
- Mapper: `adapter.out.persistence.commoncode.mapper.CommonCodeMapper`
- Persistence Adapter: `adapter.out.persistence.commoncode.CommonCodePersistenceAdapter`
- JPA columns: `group_code`, `code`, `name`, `description`, `sort_order`, `attr1`, `attr2`, `attr3`, `use_yn`, `created_at`, `updated_at`
- JPA-to-Domain mapping: `use_yn` -> `active`; timestamps와 `attr*_name`은 JPA에만 유지
- Query: `use_yn = TRUE`, `group_code`와 `code` 조건, 목록은 `sort_order ASC` 후 `code ASC`
- Seed: `V6__create_bank_common_code.sql`에 `BANK` 그룹과 스크린샷 기준 20개 기관코드·공식 도메인 favicon URL을 저장한다.
- Group JPA Entity: 초기에는 만들지 않는다. `common_code_group` 외래키와 Flyway 스키마만 사용한다.
- Redis, external API, environment variable: 변경 없음

## 5. Design decisions

### Business logic

- Rules and their locations:
  - 복합 식별자·필수값: `CommonCode` Domain 값
  - 활성 코드 조회: `CommonCodeJpaRepository` 쿼리 조건
  - 코드가 특정 업무에서 유효한지 판단: 해당 Application Service 또는 Policy
- Behavior owned by Domain: 공통 코드 값의 기본 불변식과 활성 상태 표현
- Decisions owned by Application Policy: 호출 도메인별 허용 코드와 오류 변환
- Flow orchestrated by Application: Port 호출과 결과 조립

### Technology and patterns

- Constraint to solve: JPA 타입과 공통 코드 저장 구조가 Application·Domain으로 새지 않아야 한다.
- Selected approach: `application.port.out.commoncode.LoadCommonCodePort`와 `adapter.out.persistence.commoncode.CommonCodePersistenceAdapter`를 둔다.
- Reason for selection: 현재 프로젝트의 Output Port와 Persistence Adapter 규칙에 맞고, 실제 호출자가 생겼을 때만 인터페이스를 사용한다.
- Alternatives considered:
  - `common.CommonCodeProvider`: 공통 응답·예외 패키지에 Domain 저장소 개념이 섞인다.
  - `CommonCodeProvider`의 `get`, `validate`, `getMap` 일괄 제공: 조회·오류 변환·캐시 책임이 하나의 인터페이스에 섞인다.
  - `BankCodeProvider` 선제 생성: 현재는 은행 코드가 같은 공통 코드 테이블의 그룹일 뿐 별도 경계가 확인되지 않았다.
- Trade-offs and limits: 처음에는 조회 기능만 제공한다. 실제 여러 사용처가 서로 다른 규칙을 요구할 때 Domain별 Application Policy 또는 별도 Port를 추가한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `GET /api/v2/accounts/banks` 은행 목록 조회 | `AccountController`, `AccountDocs` |
| Application | `LoadCommonCodePort`, `CommonCodeService`, `BankCatalog` | `src/main/java/com/amateur/howmuchbackend/application` |
| Domain | `CommonCode` 조회 값 | `src/main/java/com/amateur/howmuchbackend/domain/commoncode/CommonCode.java` |
| Persistence | 공통 코드 JPA Entity·Repository·Mapper·Adapter와 은행 seed | `src/main/resources/db/migration/V5__create_common_code.sql`, `V6__create_bank_common_code.sql` |
| Redis | 영향 없음 | 해당 없음 |
| Security | 영향 없음 | 해당 없음 |
| Configuration | 영향 없음 | 해당 없음 |
| Documentation | 본 Feature Plan 추가 | `docs/features/active/common-code.md` |

Existing behavior that must remain unchanged:

- 기존 `account`, `auth`, `user` UseCase의 공개 API와 저장 동작
- 기존 `application.port.out`의 Port 이름과 책임
- `common` 패키지의 응답·예외 계약

## 7. Implementation plan

- [x] `application.port.out.commoncode.LoadCommonCodePort` 정의
- [x] `domain.commoncode.CommonCode` 정의
- [x] `CommonCodeJpaId`, `CommonCodeJpaEntity` 정의
- [x] `CommonCodeJpaRepository`의 활성 단건·목록 조회 추가
- [x] `CommonCodeMapper`와 `CommonCodePersistenceAdapter` 구현
- [x] 검증·Map 변환과 읽기 전용 트랜잭션을 `CommonCodeService`로 분리
- [x] `BANK` 그룹과 20개 은행 기관코드 seed 추가
- [x] `BankCatalog`와 `GetBankCatalogUseCase` 구현
- [x] CODEF 연결·계좌 연동 전에 지원 은행 코드 검증
- [x] `BankCatalogWebResponse`, `AccountController`, Swagger 연결
- [x] Infra configuration and Bean wiring: N/A — 기존 Spring component scan을 사용함
- [x] 문서와 검증 결과 갱신

### Expected commit boundaries

```text
docs(commoncode): 공통 코드 조회 구조 명세
feat(commoncode): 공통 코드 도메인과 JPA 조회 구현
feat(account): 은행 카탈로그를 계좌 연동에 연결
```

## 8. Verification

### Commands

문서 단계에서는 Java 컴파일을 수행하지 않는다. 구현 후 다음을 실행한다.

```bash
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

구현 전 Flyway 마이그레이션을 먼저 확인한다. `V6__create_bank_common_code.sql`은 공식 은행 도메인을 기준으로 한 Google favicon proxy URL을 사용한다. 아이콘을 장기적으로 직접 관리해야 하면 별도 정적 스토리지로 이전한다.

### Completion criteria

- [ ] 활성 단건 조회가 정상 코드·미존재 코드·비활성 코드에서 각각 기대한 결과를 반환한다.
- [ ] 활성 목록 조회가 `sort_order ASC`, `code ASC` 순서를 보장한다.
- [ ] 복합키 매핑과 `group_code` 외래키가 실제 PostgreSQL 스키마와 일치한다.
- [x] `BANK` 그룹과 20개 기관코드가 중복 없이 정의된다.
- [ ] Application과 Domain이 JPA 타입을 직접 참조하지 않는다.
- [ ] `getSnapshot`, 별도 `BankCodeProvider`가 필요한 실제 호출자 없이 추가되지 않는다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 현재 구조 확인 | Complete | `AGENTS.md`, `docs/architecture.md`, `V5__create_common_code.sql` |
| 계약·패키지 명세 | Complete | 본 문서 4장 |
| Domain·Port·JPA 구현 | Complete | `src/main/java/com/amateur/howmuchbackend/{domain,application,adapter}` |
| 은행 Catalog·계좌 연동 연결 | Complete | `BankCatalog`, `BankAccountService`, `AccountController` |
| Java 컴파일 | Complete | `./gradlew compileJava --no-daemon` |
| Flyway·런타임 검증 | Not run | PostgreSQL 실행 환경 확인 후 진행 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-21 | SQL·현재 패키지 규약 확인 및 조회 계약 작성 | 실제 첫 호출 도메인 확정 후 구현 | PostgreSQL에서 Flyway와 Hibernate 검증 필요 |
| 2026-08-21 | `CommonCode`·`LoadCommonCodePort`·JPA 조회 Adapter 구현 및 컴파일 확인 | 실제 호출 UseCase 연결 | PostgreSQL에서 Flyway와 Hibernate 검증 필요 |
| 2026-08-21 | `BANK` 그룹과 20개 기관코드·favicon URL seed 추가 | PostgreSQL migration 실행 확인 | 외부 favicon proxy 의존 |
| 2026-08-22 | 검증·Map 변환과 트랜잭션 경계를 `CommonCodeService`로 이동 | 실제 호출 UseCase 연결 | `getMap` 캐시는 측정 후 결정 |
| 2026-08-22 | `BankCatalog` 조회 API와 CODEF 호출 전 지원 은행 검증 연결 | PostgreSQL에서 목록·오류 응답 확인 | 외부 favicon proxy 의존 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-21 | `CommonCodeProvider` 대신 `LoadCommonCodePort`를 우선 사용 | 현재 프로젝트의 Output Port 명명 규칙과 조회 책임에 맞음 | 실제 외부 제공자 경계가 생기면 Provider 명칭을 별도 검토 |
| 2026-08-21 | `CommonCodeJpaEntity`만 먼저 만들고 그룹 JPA Entity는 보류 | 상세 코드 조회에 그룹 객체가 필요하지 않음 | 그룹 메타데이터 API가 생기면 추가 |
| 2026-08-21 | 원래 제안의 `getMap`, `getSnapshot`, `validate`는 초기 계약에서 제외 | 호출자·캐시·업무 검증 규칙이 아직 없음 | 실제 요구 발생 시 목적별 계약으로 추가 |
| 2026-08-22 | 트랜잭션과 검증·Map 변환은 Application Service가 소유 | UseCase 단위 트랜잭션과 Adapter의 기술 책임을 분리 | 독립 커밋이 필요한 작업만 `REQUIRES_NEW` 검토 |
| 2026-08-22 | 범용 공통 코드 API 대신 `account`의 `BankCatalog`를 제공 | 저장 구조의 `groupCode`, `attr1`을 Web 계약에 노출하지 않음 | 다른 도메인은 필요할 때 자체 Catalog 추가 |

## 11. Completion record

- Completed on:
- Final behavior: 활성 `BANK` 공통 코드를 은행 Catalog로 조회하고 CODEF 연결·계좌 연동 전에 지원 기관 여부를 검증함
- Unverified items: Flyway 실행, PostgreSQL 매핑과 실제 HTTP 응답 검증
- Remaining risks: Google favicon proxy 가용성에 따라 아이콘 표시가 영향을 받을 수 있음
- Follow-up work: PostgreSQL migration 실행 확인, 필요 시 favicon URL을 정적 스토리지로 이전
