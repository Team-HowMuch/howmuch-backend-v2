# Feature Plan: 그룹 멤버 아바타 노출

- Status: In progress
- Last updated: 2026-09-03
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

앱의 그룹 상세 화면은 모임원 목록을 아바타와 함께 보여준다. 그런데 `GET /api/v2/groups/{groupId}/members`
응답에는 `memberId` / `publicId` / `name` / `role` / `joinedAt`만 있고 아바타 정보가 없다.
클라이언트는 그릴 값이 없어 모든 모임원을 기본 이미지로 그리고 있다. 프로필 사진을 올린 사용자도
그룹 상세에서는 기본 이미지로 보인다.

정산 참여자 목록(`GET /api/v2/settlements/{settlementId}/participants`)은 이미 `avatarUrl`을 주고
있어서 같은 사용자가 화면에 따라 다르게 보인다.

### Why now

프론트에서 그룹/프로필 이미지 표시를 한 번에 정리하는 작업 중이고, 서버가 값을 주지 않는
그룹 멤버만 남았다. 지금 맞춰두지 않으면 클라이언트에 "그룹 멤버는 아바타 없음"이라는
예외 처리가 굳어진다.

### Success criteria

- [ ] 프로필 사진을 올린 모임원은 그룹 상세 모임원 목록에서 자기 사진으로 보인다.
- [ ] 사진을 올리지 않은 모임원은 `avatarUrl`이 `null`이고, 클라이언트가 기본 이미지를 쓴다.
- [ ] 그룹의 활성 멤버가 아닌 사용자는 아바타를 조회할 수 없다.

### Out of scope

- 그룹 자체 이미지(`/api/v2/groups/{groupId}/image`) 동작 변경
- 아바타 캐시 정책 변경(현재 `no-store` 유지)
- 정산 참여자 아바타 계약 변경

## 2. Domain analysis

### Domain boundary

- 재사용할 기존 도메인: `group`(GroupMember), `user`(User, ProfileImage)
- 새 도메인 필요 사유: 없음
- 다른 도메인과의 관계: `GroupMember.userId`로 `user`를 조회한다. 저장 구조는 그대로다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 모임원 아바타 | 그룹 멤버로 보이는 사용자의 프로필 사진 | `GroupMemberWebResponse.avatarUrl` |
| 아바타 보유 여부 | 해당 사용자가 프로필 사진을 올려 두었는지 | `GroupMemberResult.hasAvatar` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 그룹 활성 멤버 | 모임원 목록에서 각자의 사진을 본다 | `GetGroupMembersUseCase` (기존, 결과 확장) |
| 그룹 활성 멤버 | 특정 모임원의 아바타 이미지를 받는다 | `GetGroupMemberAvatarUseCase` (신규) |

### State and invariants

- 상태 전이: 없음. 조회 전용이다.
- 항상 지켜야 할 규칙:
  - 아바타는 그룹의 **활성 멤버에게만** 노출한다.
  - `avatarUrl`은 대상 사용자가 실제로 사진을 가진 경우에만 채운다.
- 잘못된 상태: 탈퇴/비활성 멤버의 아바타가 목록이나 이미지 응답에 남는 것.

## 3. Scenarios

### Normal flow

1. 사용자가 자신이 속한 그룹의 상세 화면을 연다.
2. 클라이언트가 `GET /api/v2/groups/{groupId}/members`를 호출한다.
3. 서비스가 활성 멤버와 각 멤버의 `User`를 조회해 `hasAvatar`를 계산한다.
4. 사진이 있는 멤버는 `avatarUrl`이 `/api/v2/groups/{groupId}/members/{memberId}/avatar`로 내려간다.
5. 클라이언트가 그 주소로 이미지를 받아 그린다.

### Failure flow

- 조건: 요청자가 그룹의 활성 멤버가 아니다.
  - 반환: 기존 `GroupMembershipService`의 멤버 검증과 동일한 오류.
- 조건: `memberId`가 해당 그룹의 활성 멤버가 아니다.
  - 반환: `GroupStatusCode.GROUP_MEMBER_NOT_FOUND`
- 조건: 대상 사용자가 사진을 올리지 않았거나 저장소에 파일이 없다.
  - 반환: `UserStatusCode.PROFILE_IMAGE_NOT_FOUND`

### Boundary conditions

- 사진이 없는 멤버: 목록의 `avatarUrl`이 `null`. 클라이언트가 기본 이미지를 쓴다.
- 사진 교체 직후: 응답이 `no-store`라 다음 요청에서 새 이미지를 받는다(기존 아바타 엔드포인트와 동일).

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetGroupMembersUseCase (기존), GetGroupMemberAvatarUseCase (신규)
Command: 없음 (groupId, memberId는 단순 식별자라 메서드 파라미터로 둔다)
Result: GroupMemberResult (hasAvatar 추가), ProfileImage (기존 재사용)
Web Request DTO -> Command: 없음 (PathVariable만 사용)
Result -> Web Response DTO: GroupMemberWebResponse.from(groupId, GroupMemberResult)
```

`GroupMemberWebResponse.from`은 `avatarUrl`을 만들기 위해 `groupId`가 필요하다.
정산 참여자(`SettlementParticipantListItemWebResponse.from(settlementId, result)`)와 같은 모양이다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadGroupMemberPort` | 그룹의 활성 멤버 조회 | `GroupMembershipService` | `GroupMemberPersistenceAdapter` (기존) |
| `LoadUserPort` | 멤버에 연결된 사용자 조회 | `GroupMembershipService` | `UserPersistenceAdapter` (기존) |
| `ProfileImageStoragePort` | 아바타 파일 로드 | `GroupMembershipService` | 기존 저장소 어댑터 |

신규 Output Port는 없다. 셋 다 이미 있는 것을 그대로 쓴다.

### Application Policy

- Policy가 가질 판단: 없음.
- 사유: "그룹의 활성 멤버만 볼 수 있다"는 기존 `GroupAccessService.requireActiveMember`가 이미
  담당하는 접근 제어이고, "사진이 있으면 노출"은 여러 엔티티에 걸친 규칙이 아니다.
  새 Policy를 만들면 판단 없는 껍데기만 생긴다.

### Web API

```text
GET /api/v2/groups/{groupId}/members
Request: 없음
Response: [{ memberId, publicId, name, role, joinedAt, avatarUrl }]
  - avatarUrl: 사진이 있으면 "/api/v2/groups/{groupId}/members/{memberId}/avatar", 없으면 null
Errors: 기존과 동일
Authentication: 필요. 그룹의 활성 멤버만.

GET /api/v2/groups/{groupId}/members/{memberId}/avatar
Request: 없음
Response: image/* 바이트, Cache-Control: no-store
Errors:
  - GROUP_MEMBER_NOT_FOUND: memberId가 해당 그룹의 활성 멤버가 아님
  - PROFILE_IMAGE_NOT_FOUND: 대상 사용자에게 사진이 없음
Authentication: 필요. 그룹의 활성 멤버만.
```

`avatarUrl`은 추가되는 필드라 기존 클라이언트를 깨뜨리지 않는다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음. `users.avatar_file_name`을 그대로 읽는다.
- Flyway migration required (`Yes` / `No`): `No`
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- 규칙과 위치:
  - 접근 제어(그룹 활성 멤버) → `GroupAccessService` (기존)
  - 아바타 보유 여부 계산 → `GroupMemberResult` 생성 지점(`GroupMembershipService.toResult`)
  - URL 조립 → `GroupMemberWebResponse.from` (Web 경계)
- Domain이 갖는 행위: 없음(조회 전용)
- Application Policy가 갖는 판단: 없음
- Application이 조율하는 흐름: 멤버 조회 → 사용자 조회 → 결과 조립 / 아바타 파일 로드

### Technology and patterns

- 해결할 제약: 다른 사용자의 아바타를 받을 수 있는 엔드포인트가 없다.
  `GET /api/v2/user/me/avatar`는 현재 로그인 사용자 것만 준다.
- 선택한 방식: 정산 참여자와 동일하게 **그룹 범위(scoped) 아바타 엔드포인트**를 새로 만든다.
- 선택 이유: `GET /api/v2/users/{publicId}/avatar` 같은 전역 엔드포인트를 열면 publicId만 알면
  누구든 남의 사진을 받을 수 있다. 그룹 범위로 두면 "같은 그룹 멤버끼리만"이라는 노출 범위가
  경로에 그대로 드러나고, 정산 쪽에 이미 있는 패턴과 맞는다.
- 검토한 대안:
  1. 전역 사용자 아바타 엔드포인트 — 노출 범위를 제어하기 어려워 탈락.
  2. 목록 응답에 이미지 바이트를 base64로 포함 — 응답이 커지고 캐시가 안 되어 탈락.
- 트레이드오프와 한계: 같은 사용자의 사진이 그룹/정산별로 다른 URL을 갖는다.
  클라이언트 이미지 캐시가 중복 저장되지만, 노출 범위 제어가 더 중요하다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 필드 1개 추가, 엔드포인트 1개 추가 | `GroupController`, `GroupApiSpec` |
| Application | Result 필드 추가, UseCase 1개 추가 | `GroupMemberResult`, `GetGroupMemberAvatarUseCase`, `GroupMembershipService` |
| Domain | 변경 없음 | `GroupMember`, `User` |
| Persistence | 변경 없음 | 기존 Port/Adapter 재사용 |
| Redis | 변경 없음 | - |
| Security | 그룹 활성 멤버 검증 재사용 | `GroupAccessService.requireActiveMember` |
| Configuration | 변경 없음 | - |
| Documentation | 본 Feature Plan | `docs/features/active/group-member-avatar.md` |

Existing behavior that must remain unchanged:

- 기존 모임원 목록의 필드와 정렬은 그대로여야 한다.
- `GET /api/v2/user/me/avatar`와 정산 참여자 아바타 계약은 그대로여야 한다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — 스키마 변경 없음)
- [x] UseCase and Application Command/Result — `GetGroupMemberAvatarUseCase`, `GroupMemberResult.hasAvatar`
- [x] Required Output Ports — `N/A` (기존 Port 재사용)
- [x] Domain models and rules — `N/A` (조회 전용, 도메인 변경 없음)
- [x] Application Policies — `N/A` (판단 없음, 5절 참고)
- [x] Application Service — `GroupMembershipService`
- [x] Output Adapters — `N/A` (기존 어댑터 재사용)
- [x] Web Request/Response DTOs, Controllers, and Swagger — `GroupMemberWebResponse`, `GroupController`, `GroupDocs`
- [x] Infra configuration and Bean wiring — `N/A` (기존 컴포넌트 스캔)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(group): 그룹 멤버 아바타 노출 설계 문서
feat(group): 모임원 목록에 아바타 주소 추가
```

## 8. Verification

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

수동 확인(로컬 DB/Redis 필요):

1. 프로필 사진을 올린 계정으로 로그인한다.
2. `GET /api/v2/groups/{groupId}/members` — 본인 항목의 `avatarUrl`이 채워지는지 본다.
3. 그 주소로 요청해 이미지 바이트가 오는지 본다.
4. 사진 없는 계정의 항목은 `avatarUrl`이 `null`인지 본다.
5. 그룹 멤버가 아닌 계정으로 같은 주소를 호출해 거절되는지 본다.

### Completion criteria

- [ ] Purpose에 적은 사용자 결과를 확인했다.
- [ ] 정상/실패/경계 흐름을 확인했다.
- [ ] 문서와 Swagger가 실제 계약과 일치하는지 확인했다.
- [ ] 수행하지 못한 검증과 그 이유를 남겼다.

### Results

| Item | Result | Evidence |
|---|---|---|
| `./gradlew compileJava` | Pass | `BUILD SUCCESSFUL` |
| `./gradlew compileTestJava` | Pass | `BUILD SUCCESSFUL` |
| `GroupControllerTest`, `GroupMembershipServiceTest` | Pass | `BUILD SUCCESSFUL` (avatarUrl 채움/null, 아바타 반환, 비멤버 거절, 사진 없음 거절) |
| `./gradlew test` 전체 | 미수행 | 로컬에 PostgreSQL과 Docker가 없어 `@SpringBootTest` 통합 테스트 65건이 컨텍스트 로드 단계에서 실패한다(`PSQLException: Connection refused`). 이 변경과 무관하며, 변경 전에도 동일하다. |
| `GroupOpenApiIntegrationTest` | 미수행 | 위와 같은 이유. 새 엔드포인트와 `avatarUrl` 스키마 단언은 추가해 두었으나 실행으로 확인하지 못했다. |
| 수동 확인 | 미수행 | 로컬 DB 없음. 배포 후 확인 필요. |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-03 | 설계 문서 작성 | 구현 | 없음 |
| 2026-09-03 | 구현 및 단위 테스트 | 배포 후 실기기 확인 | 통합 테스트를 로컬에서 못 돌림(DB 없음) |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-03 | 그룹 범위 아바타 엔드포인트를 새로 만든다 | 전역 엔드포인트는 노출 범위 제어가 안 됨 | 사용자별 전역 아바타 엔드포인트 |
| 2026-09-03 | Application Policy를 만들지 않는다 | 판단이 없어 껍데기만 생김 | 접근 제어는 기존 `GroupAccessService`가 담당 |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
