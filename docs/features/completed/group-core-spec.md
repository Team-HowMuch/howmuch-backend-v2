# 그룹 핵심 기능 Backend Spec

## 1. 목적과 범위

이 문서는 [그룹 관리 PRD](../../product/group-prd.md)의 그룹 생성·목록·상세·수정·삭제·이미지 계약을 백엔드 구현 단위로 정의한다. 멤버 가입·직접 추가·소유권 이전·탈퇴·제거는 [그룹 멤버십·권한 Spec](group-membership-spec.md), 정산 연동은 [그룹-정산 연동 Spec](group-settlement-integration-spec.md)에서 정의한다.

관련 이슈:

- [#142 그룹 핵심 기능 Spec 작성](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/142)
- [#146 그룹·그룹멤버 및 정산 그룹 출처 스키마 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/146)
- [#147 그룹 생성·목록·상세 및 이미지 조회 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/147)
- [#148 그룹 설정 수정과 소프트 삭제 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/148)

## 2. 구현 전 상태

- `develop`에는 그룹 Domain, 그룹 Web API와 그룹 Persistence가 없다.
- 정산 모듈만 `LoadGroupMembersPort`와 `GroupMemberRoster`를 가지고 있다.
- `local`, `dev`는 `LocalGroupMembersStubAdapter`, `prod`는 `UnavailableGroupMembersAdapter`를 사용한다.
- 사용자 프로필 이미지는 multipart 업로드, 파일 signature 검증, 로컬 원자 저장, 트랜잭션 완료 후 기존 파일 정리를 사용한다.
- 그룹 이미지는 같은 기술 동작을 따르되 user 이름의 Port·Policy·Properties를 재사용하거나 노출하지 않는다.

## 3. Domain 계약

### 3.1 `Group`

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `id` | `Long` | 양수 식별자 |
| `name` | `String` | strip 후 1~20자 |
| `memo` | `String?` | strip 후 빈 값은 null, 최대 60자 |
| `imageFileName` | `String?` | 외부 응답에 직접 노출하지 않음 |
| `ownerId` | `Long` | 현재 활성 OWNER의 user ID |
| `inviteCode` | `String` | 대문자 영숫자 8자리 |
| `status` | `GroupStatus` | `ACTIVE`, `DELETED` |
| `creatorId` | `Long` | 최초 생성자 user ID, 소유권 이전 후에도 유지 |
| `createdAt` | `LocalDateTime` | UTC |
| `updatedAt` | `LocalDateTime` | UTC |
| `deletedAt` | `LocalDateTime?` | `DELETED`일 때 필수 |

필수 동작:

- `create`: 정규화된 이름·메모, 생성자, 초대 코드로 `ACTIVE` 그룹 생성
- `changeSettings`: 전달된 이름·메모·이미지 파일명을 변경
- `transferOwnership`: 새 OWNER user ID로 변경
- `delete`: `ACTIVE -> DELETED`, 삭제 시각 기록
- `requireActive`: 삭제 그룹의 신규 동작 차단

### 3.2 `GroupMember`

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `id` | `Long` | 양수 식별자 |
| `groupId` | `Long` | 소속 그룹 |
| `userId` | `Long` | 소속 사용자 |
| `role` | `GroupMemberRole` | `OWNER`, `MEMBER` |
| `status` | `GroupMemberStatus` | `ACTIVE`, `LEFT`, `REMOVED` |
| `joinedAt` | `LocalDateTime` | 현재 활성화된 가입 시각 |
| `leftAt` | `LocalDateTime?` | 비활성 상태 전이 시각 |
| `createdAt` | `LocalDateTime` | 최초 멤버십 생성 시각 |
| `updatedAt` | `LocalDateTime` | 최종 수정 시각 |

핵심 불변식:

- 같은 그룹·사용자 조합에는 멤버십이 하나만 존재한다.
- `OWNER` 역할은 항상 `ACTIVE` 상태다.
- 활성 그룹에는 정확히 한 명의 활성 `OWNER`가 존재한다.
- `ACTIVE`이면 `leftAt`은 null이고, `LEFT` 또는 `REMOVED`이면 `leftAt`이 존재한다.

## 4. Application 계약

### 4.1 Input Port와 Command/Result

| 사용자 목표 | UseCase | 입력 | 출력 |
| --- | --- | --- | --- |
| 그룹 생성 | `CreateGroupUseCase` | `CreateGroupCommand` | `GroupDetailResult` |
| 내 그룹 목록 | `GetMyGroupsUseCase` | 없음 | `List<GroupSummaryResult>` |
| 그룹 상세 | `GetGroupUseCase` | `groupId` | `GroupDetailResult` |
| 그룹 설정 수정 | `UpdateGroupUseCase` | `groupId`, `UpdateGroupCommand` | `GroupDetailResult` |
| 그룹 삭제 | `DeleteGroupUseCase` | `groupId` | 없음 |
| 그룹 이미지 조회 | `GetGroupImageUseCase` | `groupId` | `GroupImage` |

`CreateGroupCommand`:

```text
name: String
memo: String?
imageContent: byte[]?
```

`UpdateGroupCommand`:

```text
name: String?          # null이면 유지, 빈 값이면 검증 실패
memo: String?          # null이면 유지, 빈 값이면 memo 제거
memoProvided: boolean  # multipart에서 필드 미전달과 빈 값 구분
imageContent: byte[]?
resetImage: boolean
```

- `imageContent`와 `resetImage=true`를 함께 전달하면 `400 INVALID_GROUP_UPDATE`다.
- 이름·메모·이미지 중 실제 변경 지시가 하나도 없으면 `400 INVALID_GROUP_UPDATE`다.
- Application Command는 `MultipartFile`을 포함하지 않는다.

`GroupSummaryResult`:

```text
groupId: Long
name: String
imageUrl: String?
```

`GroupDetailResult`:

```text
groupId: Long
name: String
memo: String?
imageUrl: String?
memberCount: int
inviteCode: String
inviteUrl: String
myRole: OWNER | MEMBER
```

### 4.2 Output Port

| Port | 실제 호출자가 요구하는 capability |
| --- | --- |
| `LoadGroupPort` | ID·초대 코드로 그룹 조회, 고유 초대 코드 존재 확인 |
| `SaveGroupPort` | 그룹 생성과 설정·소유자·삭제 상태 저장 |
| `LoadGroupMemberPort` | 그룹·사용자 멤버십, 활성 OWNER와 활성 멤버 수·목록 조회 |
| `SaveGroupMemberPort` | 멤버십 생성·재활성화·종료와 역할 변경 저장 |
| `GroupImageStoragePort` | 검증된 그룹 이미지 저장·조회·삭제 |

- 그룹 Persistence Adapter 하나가 `LoadGroupPort`, `SaveGroupPort`를 구현할 수 있다.
- 그룹멤버 Persistence Adapter 하나가 `LoadGroupMemberPort`, `SaveGroupMemberPort`와 기존 정산용 `LoadGroupMembersPort`를 구현할 수 있다.
- Service는 자신이 사용하는 capability만 주입받는다.
- 초대 링크 조립은 Application 결과 조립 단계가 담당하고, 외부 공유 발송 Port는 만들지 않는다.

### 4.3 현재 사용자와 트랜잭션

- 모든 UseCase는 `CurrentActorPort`로 현재 user ID를 얻는다.
- 생성은 그룹과 OWNER 멤버십을 같은 트랜잭션으로 저장한다.
- 상세·수정·삭제·이미지 조회는 그룹이 `ACTIVE`이고 현재 사용자가 `ACTIVE` 멤버인지 확인한다.
- 수정·삭제는 추가로 현재 멤버 역할이 `OWNER`인지 확인한다.
- 조회 Service는 `@Transactional(readOnly = true)`, 변경 Service는 `@Transactional`을 사용한다.

## 5. Web API 계약

모든 JSON 성공 응답은 공통 `ApiResponse`로 감싼다. 이미지 byte 응답만 `ResponseEntity<byte[]>`를 사용한다.

### 5.1 그룹 생성

```http
POST /api/v2/groups
Content-Type: multipart/form-data
Authorization: Bearer {accessToken}
```

Form fields:

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `name` | 예 | 1~20자 |
| `memo` | 아니오 | 최대 60자, 빈 값은 null |
| `image` | 아니오 | JPEG·PNG·WebP, 최대 5 MiB |

- 성공: `201 Created`, `ApiResponse<GroupDetailWebResponse>`
- 생성 결과에는 초대 코드·링크와 `myRole=OWNER`가 포함된다.

### 5.2 내 그룹 목록

```http
GET /api/v2/groups
Authorization: Bearer {accessToken}
```

- 성공: `200 OK`, `ApiResponse<List<GroupSummaryWebResponse>>`
- 현재 사용자의 `ACTIVE` 멤버십과 `ACTIVE` 그룹만 반환한다.
- 현재 멤버십 `joinedAt DESC`, 동률이면 `groupId DESC`로 정렬한다.
- 페이지네이션과 임의 상한을 두지 않는다.

### 5.3 그룹 상세

```http
GET /api/v2/groups/{groupId}
Authorization: Bearer {accessToken}
```

- 성공: `200 OK`, `ApiResponse<GroupDetailWebResponse>`
- `memberCount`는 `ACTIVE` 멤버 수다.
- 초대 정보는 OWNER와 MEMBER 모두 볼 수 있다.

### 5.4 그룹 설정 수정

```http
PATCH /api/v2/groups/{groupId}
Content-Type: multipart/form-data
Authorization: Bearer {accessToken}
```

Form fields:

| 필드 | 의미 |
| --- | --- |
| `name` | 전달 시 이름 변경 |
| `memo` | 전달 시 변경, 빈 문자열은 메모 제거 |
| `image` | 전달 시 이미지 등록 또는 교체 |
| `resetImage` | `true`이면 저장 이미지를 제거하고 기본 이미지 상태로 전환 |

- OWNER만 호출할 수 있다.
- 성공: `200 OK`, 변경 후 `GroupDetailWebResponse`

### 5.5 그룹 삭제

```http
DELETE /api/v2/groups/{groupId}
Authorization: Bearer {accessToken}
```

- OWNER만 호출할 수 있다.
- 성공: `200 OK`, 빈 성공 응답
- 멤버십·정산 참여자·Share는 삭제하지 않는다.

### 5.6 그룹 이미지 조회

```http
GET /api/v2/groups/{groupId}/image
Authorization: Bearer {accessToken}
```

- 활성 멤버만 호출할 수 있다.
- 성공: 실제 `Content-Type`과 image bytes, `Cache-Control: no-store`
- 이미지가 없거나 파일을 찾을 수 없으면 `404 GROUP_IMAGE_NOT_FOUND`

### 5.7 URL 조립

- 이미지가 있으면 `imageUrl=/api/v2/groups/{groupId}/image`, 없으면 null이다.
- 초대 링크는 환경별 `howmuch.group.invite.base-url` 뒤에 URL encoding한 `code` query를 붙인다.
- 예: `https://howmuch.app/groups/join?code=AB12CD34`
- 운영 값은 클라이언트 App/Universal Link 또는 웹 진입 URL이며 API join endpoint 자체가 아니다.

## 6. Persistence 계약

### 6.1 `groups`

| 컬럼 | 제약 |
| --- | --- |
| `id BIGINT` | PK |
| `name VARCHAR(20)` | NOT NULL, trim 후 길이 1 이상 |
| `memo VARCHAR(60)` | nullable, 저장 시 blank 금지 |
| `image_file_name VARCHAR(100)` | nullable |
| `owner_id BIGINT` | NOT NULL, `users(id)` FK |
| `invite_code VARCHAR(8)` | NOT NULL, UNIQUE, 대문자 영숫자 CHECK |
| `status VARCHAR(20)` | `ACTIVE`, `DELETED` CHECK |
| `creator_id BIGINT` | NOT NULL, `users(id)` FK |
| `created_at`, `updated_at` | UTC timestamp, NOT NULL |
| `deleted_at` | nullable, status와 일치하는 CHECK |

조회 인덱스:

- `invite_code` unique index
- `owner_id`
- `status`

### 6.2 `group_members`

| 컬럼 | 제약 |
| --- | --- |
| `id BIGINT` | PK |
| `group_id BIGINT` | NOT NULL, `groups(id)` FK |
| `user_id BIGINT` | NOT NULL, `users(id)` FK |
| `role VARCHAR(20)` | `OWNER`, `MEMBER` CHECK |
| `status VARCHAR(20)` | `ACTIVE`, `LEFT`, `REMOVED` CHECK |
| `joined_at` | NOT NULL |
| `left_at` | nullable, status와 일치하는 CHECK |
| `created_at`, `updated_at` | UTC timestamp, NOT NULL |

제약과 인덱스:

- `UNIQUE(group_id, user_id)`
- `UNIQUE(group_id) WHERE role='OWNER' AND status='ACTIVE'`
- OWNER는 ACTIVE여야 하는 CHECK
- 내 그룹 조회용 `(user_id, status, joined_at DESC)` index
- 멤버 조회용 `(group_id, status, role, joined_at)` index

### 6.3 삭제 정책

- 그룹은 물리 삭제하지 않는다.
- 사용자 FK는 그룹·멤버·기존 정산 기록 보존을 위해 cascade delete를 사용하지 않는다.
- 삭제 그룹의 이미지 파일은 그룹 삭제 commit 이후 제거한다. DB의 기존 `image_file_name`은 null로 갱신한다.

## 7. 이미지 저장과 실패 처리

- `GroupImagePolicy`는 파일명이 아니라 bytes signature로 JPEG·PNG·WebP를 판별한다.
- 크기는 1 byte 이상 5 MiB 이하로 제한한다.
- `GroupImageStoragePort`는 UUID 기반 파일명만 허용하고 저장 경로 밖의 입력을 거절한다.
- 신규 파일 저장 후 DB 저장이 실패하면 신규 파일을 제거한다.
- 이미지 교체·초기화·그룹 삭제는 DB commit 이후 기존 파일을 제거한다.
- rollback이면 기존 파일을 유지하고 신규 파일만 제거한다.
- 기술 설정은 `GroupImageProperties`와 `GroupImageConfig`가 담당한다. 고정 이미지 형식·크기 규칙은 Application Policy에 둔다.

## 8. 오류 계약

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `INVALID_GROUP_NAME` | 400 | 이름이 비었거나 20자 초과 |
| `INVALID_GROUP_MEMO` | 400 | 메모 60자 초과 |
| `INVALID_GROUP_IMAGE` | 400 | 빈 파일, 5 MiB 초과, 지원하지 않는 signature |
| `INVALID_GROUP_UPDATE` | 400 | 변경 없음 또는 image/reset 충돌 |
| `GROUP_NOT_FOUND` | 404 | 그룹이 없거나 삭제되어 신규 동작에서 숨김 |
| `GROUP_IMAGE_NOT_FOUND` | 404 | 저장 이미지 없음 또는 파일 없음 |
| `GROUP_ACCESS_DENIED` | 403 | 활성 멤버가 아님 |
| `GROUP_OWNER_REQUIRED` | 403 | OWNER 전용 작업을 MEMBER가 요청 |

- 초대 코드 충돌은 DB unique 제약과 재생성으로 내부 처리하고 정상 요청에 충돌 오류를 노출하지 않는다.
- 예상 가능한 Persistence 오류는 원인을 보존해 `ApplicationException`으로 변환한다.

## 9. 테스트 시나리오

### Domain/Application

- 이름·메모 정규화와 경계 길이
- 생성자 OWNER 멤버십 동시 저장과 rollback
- 목록의 활성 그룹·활성 멤버십 필터와 정렬
- 비멤버·삭제 그룹·MEMBER 권한 차단
- 빈 PATCH, 메모 제거, 이미지/reset 충돌
- 이미지 저장 실패, DB rollback, commit 이후 기존 파일 정리
- 소프트 삭제와 기존 멤버십 보존

### Persistence

- 초대 코드와 그룹·사용자 멤버십 unique
- 활성 OWNER partial unique
- 역할·상태·삭제 시각 CHECK
- 목록·상세·활성 인원수 조회
- 물리 cascade가 없는지 검증

### Web/OpenAPI

- multipart 생성·수정 필드와 제한
- 생성 201, 조회·수정·삭제 200
- 응답 DTO가 Application Result를 명시적으로 변환
- image bytes의 Content-Type과 no-store
- bearer 인증과 400·403·404 계약

## 10. 제외와 후속 문서

- 가입·직접 추가·소유권 이전·탈퇴·제거의 세부 상태 전이는 [그룹 멤버십·권한 Spec](group-membership-spec.md)을 따른다.
- 실제 정산 roster와 `source_group_id` 호환은 [그룹-정산 연동 Spec](group-settlement-integration-spec.md)을 따른다.
- 구현·migration·PR 순서는 [그룹 관리 Feature Plan](group-management.md)을 따른다.

## 11. 구현 결과

- #147·#148 범위의 그룹 생성, 내 그룹 목록, 상세, 이미지, 설정 수정과 소프트 삭제 API를 구현했다.
- `Group`, `GroupMember` Domain과 목적 지향 Load/Save/Image/Invite Link Port를 추가했다.
- V18 스키마에 맞춘 JPA Entity·Mapper·Repository·Persistence Adapter를 추가했다.
- 그룹 이미지는 UUID 파일명, signature 검증, 원자 저장, rollback 신규 파일 정리와 commit 이후 기존 파일 정리를 사용한다.
- 단위·Controller·파일 저장·PostgreSQL 영속 통합 테스트를 추가했다.
- 임시 PostgreSQL·Redis에서 `./gradlew test` 전체 성공을 확인했다.
- 가입·멤버 목록·직접 추가는 #149, 실제 정산 roster와 그룹 출처 쓰기는 #150에서 후속 구현한다.
