# 이미지 캐시 버전

- Status: Completed
- Last updated: 2026-09-14
- Related issue: [#383](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/383)
- Related ADR: N/A

## 1. Purpose

### Problem

프로필과 그룹 이미지 URL이 이미지가 교체되어도 동일해서 앱의 URL 기반 캐시가 이전 이미지를 계속 표시한다. 프론트엔드는 별도 시각이나 로컬 버전을 붙여 우회하고 있지만, 내 아바타는 불필요하게 다시 내려받고 다른 사용자의 아바타는 변경을 감지하지 못한다.

### Why now

서버가 이미지 리소스의 변경을 URL로 표현해야 모든 이미지 소비 화면이 같은 캐시 무효화 규칙을 사용할 수 있다. 현재 우회가 유지되면 화면마다 갱신 시점과 네트워크 사용량이 달라진다.

### Success criteria

- [x] 프로필·그룹 이미지가 있는 모든 API 응답 URL에 파일 식별자 기반 `v` 쿼리가 포함된다.
- [x] 같은 저장 파일은 같은 URL을 유지하고 파일이 교체되면 URL이 바뀐다.
- [x] 이미지 바이트 응답은 인증된 사용자 전용 캐시에서 1일간 재사용할 수 있다.
- [x] 이미지가 없을 때 기존처럼 URL을 `null`로 반환한다.

### Out of scope

- 이미지 저장 방식과 파일명 생성 규칙 변경
- ETag·Last-Modified 또는 CDN 도입
- 프론트엔드 캐시 우회 코드 제거

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `user`, `group`, `settlement`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 정산 응답은 연결 사용자 아바타와 참여자 출처 그룹 이미지를 참조한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 이미지 버전 | 저장 파일이 동일한 동안 유지되고 교체 시 바뀌는 캐시 식별자 | `ImageCacheVersion` |
| 버전 URL | 기존 이미지 경로에 이미지 버전을 `v`로 붙인 상대 URL | `...?v=<sha256>` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 인증 사용자 | 최신 프로필·그룹 이미지를 보고 같은 이미지는 캐시에서 재사용한다 | 기존 프로필·그룹·정산 조회 및 이미지 조회 UseCase |

### State and invariants

- State transitions: 저장 파일명이 바뀌면 이미지 버전도 바뀐다.
- Rules that must always hold: 이미지가 없으면 버전과 URL이 모두 없다. 같은 파일명은 항상 같은 버전을 만든다.
- Invalid states: 이미지 URL은 존재하지만 버전이 없는 응답.

## 3. Scenarios

### Normal flow

1. 사용자가 프로필·그룹·정산 데이터를 조회한다.
2. Application이 저장 파일명에서 불투명한 이미지 버전을 만든다.
3. Web Response가 이미지 경로에 `v` 쿼리를 붙인다.
4. 앱은 같은 URL을 1일간 재사용하고 이미지 교체 후 새 URL을 요청한다.

### Failure flow

- Condition: 이미지 저장 파일이 없거나 조회 권한이 없다.
- Returned error or state transition: 기존 `*_IMAGE_NOT_FOUND` 또는 접근 거부 계약을 유지한다.

### Boundary conditions

- 이미지가 없으면 URL은 `null`이다.
- 프로필 이름, 로그인 시각, 그룹 메모 등 이미지와 무관한 변경은 이미지 버전을 바꾸지 않는다.
- `/api/v2/user/me/avatar`는 사용자마다 의미가 달라 공유 캐시에 저장하지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 프로필·그룹·정산 조회 및 이미지 조회 UseCase 유지
Command: 변경 없음
Result: hasAvatar/hasImage 대신 nullable image version 전달
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 이미지 버전을 v 쿼리로 변환
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 조회 Port | 이미지 저장 파일 식별자 조회 | 기존 Service | 기존 Persistence Adapter |

새 Output Port는 필요하지 않다.

### Application Policy

- Decision owned by the Policy: N/A
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 캐시 버전은 외부 표현을 위한 파생 값이며 도메인 불변식이 아니다.
- Future inputs deliberately not implemented yet: 파일 내용 해시, ETag

### Web API

```text
기존 JSON 응답의 이미지 URL: <기존 경로>?v=<파일명 SHA-256>
기존 이미지 GET 응답: Cache-Control: private, max-age=86400
Request/response body, errors, authentication: 변경 없음
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음. `users.avatar_file_name`, `groups.image_file_name`을 사용한다.
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: 파일명 SHA-256 파생은 Application의 `ImageCacheVersion`, URL 조립과 HTTP 캐시 헤더는 Web Adapter가 담당한다.
- Behavior owned by Domain: 변경 없음
- Decisions owned by Application Policy: 없음
- Flow orchestrated by Application: 기존 조회 흐름에서 nullable 이미지 버전을 Result로 전달한다.

### Technology and patterns

- Constraint to solve: 이미지 교체만 정확히 캐시 키에 반영하면서 저장 파일명을 외부에 노출하지 않아야 한다.
- Selected approach: UTF-8 파일명의 SHA-256 hex를 `v` 값으로 사용한다.
- Reason for selection: 파일명 변경과 일대일로 연동되고 결정적이며 URL에 안전하다.
- Alternatives considered: Entity `updated_at`은 이미지 외 변경에도 바뀌고, 원본 파일명은 저장 구현을 Web 계약에 노출한다.
- Trade-offs and limits: 파일 내용이 달라도 저장 파일명이 같다면 버전이 바뀌지 않으므로 현재의 교체 시 새 파일명 생성 규칙에 의존한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 이미지 URL `v` 쿼리와 이미지 응답 캐시 헤더 | `MyProfileWebResponse`, 그룹·정산 Response DTO, 이미지 Controller |
| Application | nullable 이미지 버전 전달 | user/group/settlement Result 및 Service |
| Domain | 변경 없음 | `User.avatarFileName`, `Group.imageFileName` |
| Persistence | 정산 목록 그룹 조회가 파일명 기반 버전을 전달 | `SettlementPersistenceAdapter` |
| Redis | 변경 없음 | N/A |
| Security | 공유 캐시 방지를 위해 `private` 사용 | 이미지 바이트 응답 `Cache-Control` |
| Configuration | 변경 없음 | N/A |
| Documentation | Feature Plan과 Swagger 이미지 URL 설명 | 이 문서 및 기존 `*Docs` |

Existing behavior that must remain unchanged:

- 이미지 조회의 인증·인가와 오류 계약
- 이미지가 없을 때 nullable URL
- JSON 필드 이름과 이미지 경로 본문

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`: 기존 파일명 컬럼 사용)
- [x] UseCase and Application Command/Result (`UseCase`·`Command` 변경 없음, Result 변경)
- [x] Required Output Ports (`N/A`: 새 외부 대화 없음)
- [x] Domain models and rules (`N/A`: 도메인 변경 없음)
- [x] Application Policies (`N/A`: Policy 대상 규칙 없음)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(image): 이미지 캐시 버전 설계 기록
feat(image): 파일명 기반 이미지 버전과 캐시 정책 적용
test(image): 이미지 URL과 캐시 헤더 회귀 검증
docs(image): 이미지 캐시 버전 구현 결과 기록
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*UserControllerTest' --tests '*GroupControllerTest' --tests '*SettlementControllerTest'
./gradlew test --tests '*GroupQueryServiceTest' --tests '*GroupMembershipServiceTest' --tests '*SettlementListServiceTest' --tests '*SettlementDetailServiceTest' --tests '*SettlementParticipantServiceTest'
git diff --check
git status --short --untracked-files=all
```

PostgreSQL·Redis가 필요한 전체 테스트는 실행 전 환경을 확인한다.

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| Java compile | Passed | `./gradlew compileJava --console=plain` |
| Focused tests | Passed | Controller·Application 123건 |
| JavaScript tests | Passed | `node --test src/test/js/*.test.cjs` 35건 |
| Persistence integration | Not run | Docker daemon이 실행 중이 아니어서 PostgreSQL·Redis 기반 전체 테스트 미실행 |
| Repository checks | Passed | `git diff --check`, `git status --short --untracked-files=all` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 이슈·현재 코드·DB 컬럼 분석, 마이그레이션 불필요 결정 | Result와 Web 응답에 버전 전파 | `/me/avatar` 공유 캐시 방지 필요 |
| 2026-09-14 | 파일명 SHA-256 버전, 버전 URL, private 1일 캐시와 회귀 테스트 구현 | PR 생성 | Docker 미실행으로 Persistence 통합 테스트 미실행 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | 이미지 버전은 저장 파일명 SHA-256으로 만든다 | 이미지 변경에만 반응하고 저장 파일명은 숨긴다 | `updated_at`은 비이미지 변경에도 바뀜 |
| 2026-09-14 | 이미지 응답은 `private, max-age=86400`을 사용한다 | 인증된 사용자별 경로를 공유 캐시에 저장하지 않으면서 앱 캐시는 허용한다 | `public`은 `/me/avatar` 사용자 간 오염 위험 |

## 11. Completion record

- Completed on: 2026-09-14
- Final behavior: 프로필·그룹 이미지 URL은 저장 파일명 SHA-256을 `v`로 포함하고 이미지 바이트 응답은 `private, max-age=86400`을 반환한다.
- Unverified items: Docker daemon이 없어 PostgreSQL·Redis 기반 전체 테스트와 `SettlementListPersistenceIntegrationTest`는 실행하지 못했다. 테스트 소스 컴파일은 통과했다.
- Remaining risks: 현재 이미지 교체가 항상 새 저장 파일명을 만든다는 기존 저장 Adapter 계약에 의존한다.
- Follow-up work: 프론트엔드의 `dataUpdatedAt`, `bumpGroupImageVersion` 우회 제거는 별도 프론트 이슈 범위다.
