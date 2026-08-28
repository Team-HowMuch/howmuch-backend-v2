# 기능 계획: 공통 코드 관리자

- 상태: 부분 구현 - 관리자 Persistence 통합·브라우저 E2E 검증 대기
- 최종 수정일: 2026-08-28
- 기준 브랜치: `develop`
- 관련 이슈: #22
- 관련 ADR: [목적 중심 Port](../../adr/0001-purpose-oriented-ports.md), [Framework-neutral StatusCode](../../adr/0002-framework-neutral-status-code.md), [Capability 중심 Output Port](../../adr/0003-capability-oriented-output-ports.md)

## 1. 목적

### 문제

현재 공통 코드는 Flyway seed와 JPA 조회 기능으로만 관리된다. 운영자가 새로운 공통 코드 그룹을 만들거나 기존 그룹의 설명·속성 이름을 변경하고, 그룹에 속한 상세 코드의 실제 값과 사용 여부·순서를 수정하려면 SQL을 직접 변경해야 한다.

첨부 레퍼런스처럼 그룹을 선택하고 상세 행을 편집한 뒤 한 번에 저장하는 관리자 화면과 그에 필요한 관리자 전용 API가 필요하다.

### 지금 필요한 이유

은행 코드뿐 아니라 통화·전표·카테고리 등 여러 공통 코드가 추가될 예정이므로, 코드값을 배포와 SQL 변경에 의존하지 않는 최소 관리자 흐름을 먼저 고정한다.

### 성공 기준

- [x] `ADMIN` 사용자가 그룹을 검색·조회할 수 있다.
- [x] `GET /admin/common-codes`에서 Thymeleaf 관리자 화면을 열 수 있다.
- [ ] 화면에서 기존 로그인 API로 관리자 인증을 하고, 그룹·상세 편집 기능을 사용할 수 있다.
- [x] `ADMIN` 사용자가 그룹을 생성하고 `attr1Name`~`attr3Name`을 설정할 수 있다.
- [x] `ADMIN` 사용자가 상세 코드의 `name`, `description`, `attr1`~`attr3`, `active`, `sortOrder`를 수정할 수 있다.
- [x] 한 화면의 저장 요청으로 그룹 정보와 상세 변경사항이 하나의 트랜잭션에서 저장된다.
- [x] 일반 사용자는 관리자 API에 접근할 수 없고 `403 FORBIDDEN`을 받는다.
- [x] 동일 그룹을 다른 관리자가 먼저 저장한 경우 기존 변경사항을 덮어쓰지 않고 `409`로 거부한다.
- [x] 그룹 또는 상세를 비활성화해도 물리 삭제 없이 기존 참조를 보존한다.
- [x] 공개 공통 코드 조회와 `BankCatalog`는 활성 그룹·활성 상세만 반환한다.

### 제외 범위

- 별도 React·Next 프론트엔드 프로젝트 생성 및 배포
- 관리자 계정 생성·권한 변경 화면
- 공통 코드 변경 이력·감사 로그
- 물리 삭제 및 삭제 이력 복구 기능
- `attr1`~`attr3`별 타입·검증식·선택지 설정
- 다중 관리자 실시간 편집 상태 표시
- Redis 캐시 및 코드 스냅샷

## 2. 도메인 분석

### 도메인 경계

- 재사용 도메인: `commoncode`의 기존 `CommonCode`, `LoadCommonCodePort`, JPA 조회 구조
- 추가 모델: `CommonCodeGroup`과 그룹에 속한 `CommonCode` 상세 목록
- 관계:
  - `account`의 `BankCatalog`는 기존 `LoadCommonCodePort`를 통해 활성 코드만 조회한다.
  - `commoncode` 관리자 기능은 그룹·상세의 전체 상태를 조회하고 저장한다.
  - `UserRole.ADMIN`은 관리자 API 접근 정책의 기준이다.

### 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 그룹 코드 | 공통 코드 묶음의 변경 불가 식별자 | `groupCode` |
| 그룹 속성 이름 | 상세의 `attr1`~`attr3`가 의미하는 표시 이름 | `attr1Name`~`attr3Name` |
| 상세 코드 | 그룹 안에서 유일한 코드값 | `CommonCode.code` |
| 상세 속성값 | 상세 코드에 저장되는 추가 값 | `CommonCode.attr1`~`attr3` |
| 활성 | 공개 조회에 노출할지 여부 | `active`, DB `use_yn` |
| revision | 관리자 편집 화면의 낙관적 동시성 기준 | `common_code_group.revision` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| `ADMIN` 사용자 | 그룹 목록 검색 | `SearchCommonCodeGroupsUseCase` |
| `ADMIN` 사용자 | 그룹과 상세 전체 조회 | `GetCommonCodeGroupUseCase` |
| `ADMIN` 사용자 | 그룹 생성 | `CreateCommonCodeGroupUseCase` |
| `ADMIN` 사용자 | 그룹·상세 편집본 저장 | `UpdateCommonCodeGroupUseCase` |

### 상태와 불변식

- 그룹 코드와 상세 코드는 생성 후 변경하지 않는다. 이미 `BankCatalog`와 외부 연동 데이터가 참조할 수 있기 때문이다.
- 그룹 코드와 상세 코드 조합 `(groupCode, code)`는 유일하다.
- `attr1Name`~`attr3Name`은 그룹 단위로 관리하며, 값이 없어도 된다.
- `attr1`~`attr3`은 상세 단위로 관리하며, 값이 없어도 된다.
- `sortOrder`는 0 이상이며, 같은 순서에서는 `code ASC`를 보조 정렬로 사용한다.
- 비활성 그룹은 공개 그룹 조회에서 제외하고, 비활성 상세는 공개 상세 조회에서 제외한다.
- 관리자 편집 요청의 `revision`이 현재 값과 다르면 저장하지 않는다.
- 비활성화는 가능하지만 물리 삭제는 하지 않는다.

## 3. 시나리오

### 정상 흐름

1. `ADMIN` 사용자가 관리자 화면을 연다.
2. `GET /api/v2/admin/common-codes/groups`로 그룹 목록을 조회하고 검색한다.
3. 그룹을 선택하면 그룹 메타데이터와 활성·비활성 상세를 함께 조회한다.
4. 그룹명, 설명, `attr1Name`~`attr3Name`, 상세의 `name`, `description`, `attr1`~`attr3`, `active`, `sortOrder`를 수정하거나 새 상세를 추가한다.
5. 화면은 최초 조회 시 받은 `revision`과 전체 편집 상태를 저장 요청으로 보낸다.
6. Application Service가 ADMIN 권한과 입력 중복을 검증한 뒤 그룹과 상세를 하나의 트랜잭션으로 저장한다.
7. 저장된 새 `revision`과 전체 그룹 상태를 반환한다.

### 실패 흐름

- 인증되지 않은 요청: `401 UNAUTHORIZED`
- `ADMIN`이 아닌 인증 사용자: `403 FORBIDDEN`
- 존재하지 않는 그룹: `404`
- 중복 그룹 코드 또는 그룹 안의 중복 상세 코드: `409`
- 현재 `revision`과 요청 `revision`이 다름: `409 CONCURRENT_UPDATE`
- 빈 코드·이름, 음수 `sortOrder`, 속성값 길이 초과: `400`

### 경계 조건

- 그룹 생성 시 상세 목록은 비어 있을 수 있다.
- 그룹 상세 조회는 비활성 데이터도 반환해야 관리자 화면에서 다시 활성화할 수 있다.
- 저장 요청의 상세 목록에 새 코드가 포함되면 추가하고, 기존 코드는 갱신한다.
- 기존 상세 코드가 저장 요청에서 빠지면 물리 삭제 대신 `active=false`로 비활성화한다.
- 상세 행의 삭제 버튼은 저장 요청에서 해당 코드를 제외해 비활성화한다.
- 비활성 상세는 관리자 화면에 남겨 다시 활성화할 수 있다.
- 같은 화면에서 여러 상세를 수정해도 저장은 한 번의 DB 트랜잭션으로 처리한다.
- 다른 관리자가 먼저 저장하면 그룹 `revision`을 증가시키고 현재 요청은 거부한다.

## 4. 계약

### Input Port와 경계 매핑

```text
SearchCommonCodeGroupsUseCase
SearchCommonCodeGroupsCommand(keyword)
List<CommonCodeGroupSummaryResult>
CommonCodeGroupSearchWebRequest -> SearchCommonCodeGroupsCommand
CommonCodeGroupSummaryResult -> CommonCodeGroupSummaryWebResponse

GetCommonCodeGroupUseCase
groupCode
CommonCodeGroupResult
CommonCodeGroupResult -> CommonCodeGroupWebResponse

CreateCommonCodeGroupUseCase
CreateCommonCodeGroupCommand
CommonCodeGroupResult
CreateCommonCodeGroupWebRequest -> CreateCommonCodeGroupCommand
CommonCodeGroupResult -> CommonCodeGroupWebResponse

UpdateCommonCodeGroupUseCase
UpdateCommonCodeGroupCommand
CommonCodeGroupResult
UpdateCommonCodeGroupWebRequest -> UpdateCommonCodeGroupCommand
CommonCodeGroupResult -> CommonCodeGroupWebResponse
```

`SearchCommonCodeGroupsCommand`와 나머지 Command/Result는 Application·JDK 타입만 사용한다. Jakarta Validation과 Swagger는 Web DTO에만 둔다.

### Output Ports

| Port | 목적 | Caller | Adapter |
|---|---|---|---|
| `LoadCommonCodeGroupPort` | 관리자용 그룹 목록·그룹 전체 상태 조회 | `CommonCodeAdminService` | `CommonCodePersistenceAdapter` |
| `SaveCommonCodeGroupPort` | 그룹 메타데이터와 상세 목록의 원자적 저장 | `CommonCodeAdminService` | `CommonCodePersistenceAdapter` |
| `LoadCommonCodePort` | 공개용 활성 상세 코드 조회 | `BankCatalog`, 기존 Application Service | `CommonCodePersistenceAdapter` |
| `CurrentActorPort` | 현재 사용자 식별 | `CommonCodeAdminService` | 기존 Security Adapter |
| `LoadUserPort` | 현재 사용자의 `UserRole` 조회 | `CommonCodeAdminService` | 기존 User Persistence Adapter |

Persistence Adapter 하나가 여러 Port를 구현할 수 있지만, Application은 필요한 Port만 주입한다.

### Application Policy

- Decision owned by the Policy: 이번 범위에서는 별도 Policy를 만들지 않는다.
- ADMIN 검증은 `CommonCodeAdminService`의 공통 private 검증 흐름으로 처리한다.
- 그룹·상세 코드 중복, `revision`, 정렬값 검증은 Application Service와 Domain 값 생성 경계에서 처리한다.
- 별도 Policy는 권한 규칙이 다른 관리자 종류나 여러 도메인에서 재사용될 때 추가한다.

### Web API

```text
GET /api/v2/admin/common-codes/groups?keyword={keyword}
Request: keyword? — groupCode 또는 groupName 검색어
Response: [{ groupCode, groupName, description, active, revision, detailCount }]
Errors: 401, 403
Authentication: bearerAuth + ADMIN

GET /api/v2/admin/common-codes/groups/{groupCode}
Request: path groupCode
Response: {
  groupCode, groupName, description,
  attr1Name, attr2Name, attr3Name,
  active, revision,
  details: [{ code, name, description, sortOrder, attr1, attr2, attr3, active }]
}
Errors: 401, 403, 404
Authentication: bearerAuth + ADMIN

POST /api/v2/admin/common-codes/groups
Request: { groupCode, groupName, description, attr1Name, attr2Name, attr3Name, active }
Response: 생성된 CommonCodeGroupWebResponse
Errors: 400, 401, 403, 409
Authentication: bearerAuth + ADMIN

PUT /api/v2/admin/common-codes/groups/{groupCode}
Request: {
  revision,
  groupName, description,
  attr1Name, attr2Name, attr3Name,
  active,
  details: [{ code, name, description, sortOrder, attr1, attr2, attr3, active }]
}
Response: 저장된 CommonCodeGroupWebResponse
Errors: 400, 401, 403, 404, 409 CONCURRENT_UPDATE
Authentication: bearerAuth + ADMIN
```

`groupCode`와 기존 상세 `code`는 PUT에서 수정하지 않는다. `PUT`은 화면의 저장 버튼에 대응하며, 그룹 메타데이터와 상세 추가·수정을 하나의 트랜잭션에서 처리한다.

### Thymeleaf 관리자 화면

```text
GET /admin/common-codes
View: src/main/resources/templates/admin/common-codes.html
Assets: src/main/resources/static/admin/common-codes.css
        src/main/resources/static/admin/common-codes.js
```

- Thymeleaf는 화면 뼈대만 렌더링하고 공통 코드 데이터는 HTML에 주입하지 않는다.
- 바닐라 JavaScript가 기존 로그인 API와 관리자 REST API를 호출한다. 별도 frontend build tool이나 UI 라이브러리는 추가하지 않는다.
- 로그인 성공 후 access token과 request token은 관리자 도구의 브라우저 탭 범위인 `sessionStorage`에 보관한다. API 요청에는 access token을 Bearer로 전달한다.
- access token이 만료되면 기존 refresh API를 한 번 호출하고, 갱신에 실패하면 저장된 인증 정보를 지우고 로그인 화면을 표시한다.
- 화면 경로는 데이터가 없는 정적 shell만 반환하도록 공개할 수 있지만, `/api/v2/admin/**`는 계속 인증·ADMIN 검증을 적용한다.
- Controller는 view 이름만 반환하며 JPA Repository나 Application Service를 직접 호출하지 않는다.

### Persistence and external integrations

- Database Entity: `CommonCodeGroupJpaEntity` 추가, `CommonCodeJpaEntity` 수정
- Existing table: `common_code_group`, `common_code_detail`
- Existing columns reused: `attr1_name`~`attr3_name`, `attr1`~`attr3`, `revision`, `use_yn`, `sort_order`
- JPA concurrency: `common_code_group.revision`을 `@Version`으로 매핑한다.
- Public query: 그룹 `use_yn = TRUE`와 상세 `use_yn = TRUE`를 함께 적용한다.
- Flyway migration required: No
- Planned migration: N/A — 필요한 컬럼과 `revision`이 V5에 이미 존재한다.
- Migration pull request: N/A
- Redis: 변경 없음
- External API: 변경 없음
- Environment variable/profile: 변경 없음
- UI: Thymeleaf starter 의존성, 관리자 view template, 정적 CSS/JavaScript 추가

## 5. 설계 결정

### Business logic

- Group code와 detail code의 변경 불가 규칙: Application Service
- 값 필수·길이·`sortOrder >= 0` 검증: Web DTO의 형식 검증과 Application/Domain 경계
- 그룹·상세 코드 중복 검증: Application Service와 DB Primary Key/Unique 제약
- 활성 그룹·상세 공개 여부: `LoadCommonCodePort`의 조회 조건
- 저장 순서와 트랜잭션: `CommonCodeAdminService`
- 관리자 권한: `CurrentActorPort`로 현재 사용자를 찾은 뒤 `LoadUserPort`의 `UserRole.ADMIN` 확인

### Technology and patterns

- Constraint to solve: 관리자 화면의 다중 상세 편집을 부분 저장으로 쪼개지 않고 일관되게 저장해야 한다.
- Selected approach: 그룹 단위 `PUT` 요청과 `common_code_group.revision` 기반 낙관적 동시성 제어
- Reason for selection: 레퍼런스의 한 번 저장 흐름과 맞고, 새 DB 컬럼 없이 기존 `revision`을 재사용한다.
- Alternatives considered:
  - 상세별 PATCH: 요청 수가 늘고 일부 성공·일부 실패가 발생할 수 있어 제외
  - 물리 삭제: 기존 참조와 BankCatalog 사용처를 깨뜨릴 수 있어 제외
  - JWT에 ADMIN 권한 추가: 현재 JWT와 인증 필터 변경 범위가 커서 제외
  - `attr1`만 관리: `attr2`·`attr3` 스키마를 이미 지원하고 관리자 기능의 일관성이 떨어져 제외
- Trade-offs and limits:
  - 상세 버전은 별도로 두지 않고 그룹 `revision`으로 전체 편집 충돌을 제어한다.
  - 관리자가 한 그룹을 크게 수정할 때 전체 상세 목록을 전송한다. 상세 수가 대량화되면 페이지 편집 또는 상세별 저장으로 분리한다.
  - 별도 프론트엔드 저장소를 만들지 않고 현재 Spring 앱의 Thymeleaf와 바닐라 JS로 관리자 화면을 제공한다.
  - 화면은 REST API의 얇은 소비자이므로 서버 렌더링과 API 계약을 중복하지 않는다.

## 6. 영향 범위

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 관리자 그룹 검색·조회·생성·저장 API 추가 | `adapter.in.web.commoncode` 예정 |
| Application | 관리자 UseCase, Command/Result, `CommonCodeAdminService` 추가 | `application.commoncode` 예정 |
| Domain | `CommonCodeGroup`과 상세 편집 불변식 추가 | `domain.commoncode` 예정 |
| Persistence | `CommonCodeGroupJpaEntity`, 그룹 Repository/Mapper, 상세 저장 매핑 추가 | 기존 `common_code_group`, `common_code_detail` |
| Redis | 영향 없음 | 해당 없음 |
| Security | Application에서 `UserRole.ADMIN` 확인 | `CurrentActorPort`, `LoadUserPort` |
| Web UI | Thymeleaf view와 바닐라 JS/CSS 관리자 화면 추가 | `adapter.in.web.commoncode`, `resources/templates`, `resources/static` |
| Configuration | Thymeleaf starter와 shell 경로 규칙 추가 | `build.gradle`, `SecurityConfig` |
| Documentation | 본 Feature Plan과 Swagger Docs 갱신 | `docs/features/active/common-code-admin.md` |

Existing behavior that must remain unchanged:

- `GET /api/v2/accounts/banks`는 활성 그룹·활성 상세만 반환한다.
- 기존 `LoadCommonCodePort`의 Domain 반환 계약을 유지한다.
- 일반 사용자용 인증·계좌 연동 API의 공개 경로와 응답 필드를 변경하지 않는다.
- JPA 감사 시간은 UTC `LocalDateTime`과 기존 timezone-free 컬럼 규약을 유지한다.

## 7. 구현 계획

- [x] Migration-only pull request: N/A — 기존 스키마 컬럼만 사용
- [x] `CommonCodeGroup` Domain과 `CommonCodeGroup` 상세 편집 불변식 정의
- [x] `Search/Get/Create/UpdateCommonCodeGroupUseCase`와 Command/Result 정의
- [x] `LoadCommonCodeGroupPort`, `SaveCommonCodeGroupPort` 정의
- [x] `CommonCodeGroupJpaEntity`, Mapper, Repository 추가
- [x] `CommonCodeJpaEntity` 상세 생성·수정 매핑 추가
- [x] `CommonCodeAdminService`의 ADMIN 검증·revision·저장 트랜잭션 구현
- [x] 활성 그룹을 반영하도록 공개 `LoadCommonCodePort` 조회 수정
- [x] 관리자 Web Request/Response DTO, Controller, Swagger 구현
- [x] Thymeleaf starter, 관리자 view template, 바닐라 JS/CSS 구현
- [x] 정적 shell 경로만 공개하고 관리자 REST API의 인증·ADMIN 검증 유지
- [x] Service 테스트 추가
- [ ] Persistence 통합 테스트 추가 및 PostgreSQL 환경 검증
- [x] Controller 테스트와 Feature Plan 검증 결과 갱신

### 예상 커밋 경계

실제 commit은 요청받은 경우에만 만든다.

```text
docs(commoncode): 공통 코드 관리자 기능 계획
feat(commoncode): 관리자 그룹과 상세 코드 관리 구현
feat(commoncode): Thymeleaf 공통 코드 관리자 화면 추가
test(commoncode): 관리자 공통 코드 검증 추가
```

## 8. 검증

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

### 수동 시나리오

1. ADMIN 토큰으로 그룹 목록 검색 → 그룹 코드·그룹명 검색 결과 확인
2. 그룹 선택 → 활성·비활성 상세와 `attr1Name`~`attr3Name` 확인
3. 그룹 생성 → `groupCode`, 그룹명, 설명, `attr1Name`~`attr3Name` 저장 확인
4. 상세 추가 → `code`, `name`, `description`, `attr1`~`attr3`, `active`, `sortOrder` 저장 확인
5. 기존 상세 수정 → 세 속성값과 사용 여부·순서 변경 확인
6. 일반 사용자 토큰으로 관리자 API 호출 → `403 FORBIDDEN`
7. 동일 `revision`으로 두 번 저장 → 두 번째 요청 `409 CONCURRENT_UPDATE`
8. 그룹 또는 상세 비활성화 → 일반 `LoadCommonCodePort`와 `BankCatalog` 결과에서 제외 확인
9. `/admin/common-codes` 접속 → 화면 shell과 로그인 UI 렌더링 확인
10. 관리자 로그인 → 그룹 검색·선택·상세 편집·저장까지 브라우저에서 확인
11. 상세 삭제 → 행 제거 후 저장하고 해당 상세가 비활성 상태로 남는지 확인
12. 일반 사용자 로그인 또는 만료 토큰 → 관리자 데이터가 노출되지 않고 오류 안내 확인

### 완료 기준

- [x] 관리자 API의 Request/Response와 Swagger 문서가 일치한다.
- [x] `attr1Name`~`attr3Name` 및 `attr1`~`attr3`이 누락 없이 저장된다.
- [x] 그룹·상세 저장이 하나의 트랜잭션으로 동작한다.
- [x] ADMIN 권한과 `revision` 충돌이 테스트로 검증된다.
- [x] 비활성 그룹·상세가 공개 조회에서 제외되도록 조회 조건을 반영했다.
- [x] Thymeleaf 화면이 관리자 REST API와 연결되고 인증 만료를 처리한다.
- [x] 상세 삭제 버튼이 물리 삭제 대신 비활성화로 저장된다.
- [x] `./gradlew compileJava`와 관리자 Service 테스트가 통과한다.

### 결과

| Item | Result | Evidence |
|---|---|---|
| 화면 요구사항 분석 | Complete | 첨부 관리자 화면 레퍼런스, 본 문서 1·3장 |
| 현재 공통 코드 스키마 확인 | Complete | `V5__create_common_code.sql`, `V6__create_bank_common_code.sql` |
| 관리자 API 계약 | Complete | `CommonCodeAdminController`, `CommonCodeAdminDocs`, Web DTO |
| Thymeleaf 관리자 화면 | Complete | `common-codes.html`, `common-codes.js`, `common-codes.css` |
| PostgreSQL/JPA 매핑 | Complete | 임시 PostgreSQL에서 전체 412개 테스트와 Hibernate schema validation 통과 |
| 권한·동시성 테스트 | Complete | `CommonCodeAdminServiceTest` |

## 9. 진행 기록

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-23 | `develop` 병합 결과가 이미 최신 상태임을 확인하고, 선행 공통 코드·`BankCatalog` 구현을 기준으로 관리자 계약을 재작성 | 계약 검토 후 구현 | 별도 프론트엔드 프로젝트 없이 Thymeleaf shell과 REST API 조합으로 구현 |
| 2026-08-23 | 관리자 API·JPA 매핑·Thymeleaf 화면과 Service 단위 테스트 구현 | PostgreSQL 환경에서 통합 검증 및 브라우저 수동 확인 | 전체 테스트는 PostgreSQL 연결 실패로 통합 테스트 6개가 실행되지 않음 |
| 2026-08-28 | 임시 PostgreSQL·Redis에서 전체 412개 테스트와 schema validation 통과 확인 | 실제 관리자 브라우저 수동 검증은 필요 시 수행 | 관리자 전용 Persistence 통합 테스트는 별도로 추가하지 않음 |

## 10. 결정 로그

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-23 | `attr1Name`~`attr3Name`과 `attr1`~`attr3`을 모두 관리자 계약에 포함 | 현재 DB가 세 속성 컬럼을 이미 지원하며 그룹별 의미를 관리해야 함 | 화면에는 필요 없는 속성은 빈 값으로 저장 가능 |
| 2026-08-23 | 그룹 단위 `PUT` 저장과 기존 `revision` 재사용 | 화면의 저장 버튼과 맞고 새 migration 없이 충돌을 제어할 수 있음 | 상세 수가 커지면 상세별 저장으로 재검토 |
| 2026-08-23 | 물리 삭제 대신 `active=false` 사용 | 은행·계좌 연동 등 기존 참조 보존 | 삭제 이력이 필요하면 별도 감사 기능 추가 |
| 2026-08-23 | ADMIN 권한은 Application Service에서 확인 | 현재 JWT Authentication에는 `NO_AUTHORITIES`만 설정되어 있음 | 권한 종류가 늘면 Security authority 구조를 별도 설계 |
| 2026-08-23 | 관리자 화면은 현재 Spring 앱의 Thymeleaf로 구현 | 별도 프론트엔드 프로젝트와 배포 구성을 만들지 않고 기존 REST 계약을 재사용할 수 있음 | 화면이 복잡해지거나 독립 배포가 필요해지면 별도 frontend로 분리 |
| 2026-08-23 | 신규 그룹 JPA `revision`은 null로 시작 | `@Version` 값을 미리 넣으면 Hibernate가 detached entity로 판단해 `persist()`를 거부함 | Hibernate가 신규 version을 생성하고 기존 그룹은 DB의 현재 revision을 사용 |

## 11. 완료 기록

- 완료일: 미완료
- 최종 동작: ADMIN 사용자가 공통 코드 그룹과 상세를 조회·생성·수정하고, 그룹 revision으로 동시 편집 충돌을 방지한다.
- 검증: Service·Controller 테스트, 임시 PostgreSQL·Redis 전체 412개 테스트와 schema validation 통과. 관리자 전용 Persistence 통합·브라우저 E2E는 미수행.
- 미검증 항목: 관리자 그룹·상세 저장의 실제 PostgreSQL 통합 테스트와 브라우저 로그인·편집·저장 E2E
- 남은 위험: 관리자 화면 규모가 커지면 전체 상세 PUT 계약의 payload와 편집 충돌 범위를 재검토해야 한다.
