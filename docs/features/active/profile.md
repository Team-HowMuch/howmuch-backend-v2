# Feature Plan: Profile

- Status: Ready for runtime verification
- Last updated: 2026-08-11
- Related issue: #9, #10
- Related ADR: N/A

## 1. Purpose

### Problem

로그인한 사용자는 현재 자신의 전화번호와 실명 일부만 조회할 수 있고, 서비스에 표시할 닉네임과 아바타를 관리할 수 없다.

### Why now

F-MYP-001과 F-MYP-002 화면은 프로필 조회, 닉네임 변경, 아바타 변경 계약을 필요로 한다.

### Success criteria

- [ ] 인증된 사용자가 닉네임, 아바타 상태, 전화번호를 포함한 내 프로필을 조회할 수 있다.
- [ ] 사용자가 규칙에 맞는 닉네임과 지원되는 아바타 이미지를 변경하거나 기본 이미지로 되돌릴 수 있다.
- [ ] 실명과 전화번호는 이 기능으로 변경할 수 없다.

### Out of scope

- 타인 프로필 조회, 닉네임 중복 검사, 카메라 촬영과 이미지 크롭
- S3 등 외부 오브젝트 스토리지
- 개인정보 처리방침과 서비스 약관 본문 제공

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `user`
- Relationships: 인증된 현재 사용자는 `CurrentActorPort`로 식별한다. 파일 시스템은 `ProfileImageStoragePort`로 분리한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| Nickname | 서비스에 표시되는 변경 가능한 이름 | `User.nickname` |
| Avatar | 사용자가 등록한 선택적 프로필 이미지 | `User.avatarFileName` |
| Default avatar | 파일이 없는 경우 클라이언트가 표시하는 이미지 | `avatarUrl == null` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| Authenticated user | 내 프로필 조회 | `GetMyProfileUseCase` |
| Authenticated user | 프로필 변경 | `UpdateMyProfileUseCase` |
| Authenticated user | 내 아바타 조회 | `GetMyProfileImageUseCase` |

### State and invariants

- 닉네임은 앞뒤 공백 제거 후 한글·영문·숫자 2~20자다.
- 닉네임은 중복을 허용한다.
- 아바타는 JPG, PNG, WebP 중 하나이며 최대 5 MiB다.
- `resetAvatar=true`와 새 아바타 업로드는 동시에 요청할 수 없다.

## 3. Contracts

### Input Port

```text
UseCase: UpdateMyProfileUseCase
Request: nickname(optional), avatar bytes(optional), resetAvatar
Response: MyProfileResponse
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| UserRepositoryPort | 사용자 프로필 저장 | Profile Application Service | JPA |
| ProfileImageStoragePort | 이미지 저장·조회·삭제 | Profile Application Service | local filesystem |
| CurrentActorPort | 현재 사용자 식별 | Profile Application Service | Spring Security |

### Web API

```text
GET /api/v2/user/me
Response: 기존 필드 + nickname, avatarUrl
Authentication: Bearer access token

GET /api/v2/user/me/avatar
Response: 사용자 아바타 바이너리, 미등록 시 404
Authentication: Bearer access token

PATCH /api/v2/user/me/profile
Request: multipart/form-data (nickname?, avatar?, resetAvatar=false)
Response: 최신 프로필
Errors: 잘못된 닉네임, 이미지 형식/크기, avatar와 resetAvatar 동시 요청
Authentication: Bearer access token
```

### Persistence and external integrations

- `users.nickname`은 중복을 허용하는 최대 20자 값이고, `users.avatar_file_name`은 nullable 값이다.
- Flyway가 비활성화되어 있으므로 운영 전 수동 PostgreSQL DDL을 적용한다. nullable 컬럼 추가 후 실명으로 backfill하고 `nickname`을 NOT NULL로 전환한다.
- `howmuch.profile.image.storage-dir` 프로퍼티와 compose 볼륨으로 로컬 파일을 유지한다.

## 4. Design decisions

- HTTP URL은 Web Adapter에서 만들고 Application Response는 파일 존재 여부만 가진다.
- 파일 확장자와 MIME type은 클라이언트가 제출한 값이 아니라 매직 바이트로 판별한다.
- 새 파일은 UUID 이름으로 저장한다. DB 저장 실패 시 새 파일을 정리하고, 교체가 완료되면 이전 파일을 삭제한다.
- 기존 User의 닉네임은 실명으로 초기화한다. 데이터 길이 초과는 자동 절단하지 않고 DDL 사전 점검에서 확인한다.

## 5. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 조회 확장 및 수정/아바타 API 추가 | `UserController`, `UserDocs` |
| Application | 프로필 UseCase와 정책 추가 | `application.user` |
| Domain | User 닉네임·아바타 상태 추가 | `domain.user.User` |
| Persistence | users 컬럼 및 JPA Mapper 변경 | `adapter.out.persistence.user` |
| Security | `/api/**` 인증 규칙을 그대로 사용 | `SecurityConfig` |
| Configuration | 로컬 이미지 경로와 compose 볼륨 | `infra`, `compose.yml` |

Existing behavior that must remain unchanged:

- 기존 인증, 로그아웃, `/api/v2/user/me`의 기존 응답 필드는 유지한다.

## 6. Implementation plan

- [x] 기능 계획과 API 계약 기록
- [x] 사용자 프로필 데이터와 운영 DDL
- [x] 프로필 조회 및 아바타 반환 API
- [x] 닉네임 정책과 수정 UseCase
- [x] 로컬 아바타 저장 Adapter
- [x] 통합 multipart 수정 API와 Swagger
- [x] 정적 검증 결과 기록

### Expected commit boundaries

```text
docs(profile): 프로필 기능 구현 계획 작성
feat(user): 프로필 표시 데이터와 운영 DDL 추가
feat(user): 내 프로필 조회 API 확장
feat(user): 닉네임 수정 정책 추가
feat(user): 로컬 아바타 저장소 추가
feat(user): 프로필 수정 API 추가
```

## 7. Verification

### Commands

```bash
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### Results

| Item | Result | Evidence |
|---|---|---|
| Compile | Passed | `./gradlew compileJava` after each Java commit |
| Diff validation | Passed | `git diff --check` before each commit |
| Existing test | Not run | `contextLoads` requires PostgreSQL and Redis; localhost PostgreSQL is unavailable and Redis connection is blocked |
| Runtime API verification | Pending | PostgreSQL, Redis, authenticated access token, and writable image storage path required |

## 8. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-11 | Feature plan and issue hierarchy prepared | Add profile data model | Manual DDL required before prod deployment |
| 2026-08-11 | Profile data, retrieval, nickname policy, local storage, and multipart update API implemented | Run local API verification | Runtime dependencies are unavailable |

## 9. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-11 | Use local filesystem storage | S3 is unavailable | Deployment requires persistent volume |
| 2026-08-11 | Use one multipart PATCH API | Matches profile edit save action | Separate image API excluded |
