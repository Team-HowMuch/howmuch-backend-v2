# Feature Plan: 그룹 관리

- Status: In Progress
- Last updated: 2026-08-25
- Related issue: [#139 그룹 생성·조회·멤버 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/139)
- Related ADR: [0001 Purpose-oriented Ports](../adr/0001-purpose-oriented-ports.md), [0002 Framework-neutral StatusCode](../adr/0002-framework-neutral-status-code.md), [0003 Capability-oriented Output Ports](../adr/0003-capability-oriented-output-ports.md)

## 1. Purpose

### Problem

반복해서 함께 정산하는 사용자가 매번 참여자를 다시 검색해야 하지만 현재 서버에는 실제 그룹 기능이 없다. 정산의 그룹 구성원 선택은 환경별 Stub에 의존하고, 선택한 참여자에서 그룹 ID가 유실되어 그룹 탈퇴·제거와 미완료 정산 의무를 연결할 수 없다.

### Why now

기능명세서의 `GRO-001~007`과 그룹 목록·상세가 최수아 담당 미구현 범위로 남아 있고, 기존 정산 참여자 기능은 실제 그룹 Adapter가 준비되면 Stub을 교체하도록 설계되어 있다. 그룹 Domain과 출처 저장을 먼저 확정하지 않으면 정산 연동과 멤버 종료 정책이 서로 다른 가정으로 구현될 위험이 있다.

### Success criteria

- [x] 로그인 사용자가 그룹을 생성하고 자신의 활성 그룹 목록·상세·이미지를 조회할 수 있다.
- [ ] OWNER가 설정·멤버·소유권을 관리하고 MEMBER는 허용된 조회·탈퇴만 수행한다.
- [x] 초대 코드 가입, `LEFT` 재가입과 `REMOVED` OWNER 복구가 정의된 상태 전이를 따른다.
- [ ] 정산 그룹 후보가 실제 활성 그룹멤버를 사용하고 참여자에 `source_group_id`를 저장한다.
- [ ] 미완료 그룹 출처 정산이 있으면 자기 탈퇴와 OWNER 제거가 같은 Policy로 차단된다.
- [x] 그룹 삭제 후 신규 접근은 닫히고 기존 정산 데이터는 보존된다.
- [ ] migration 전환 중 구버전 writer와 기존 참여자 데이터가 깨지지 않는다.

### Out of scope

- ADMIN 역할, 가입 승인과 초대 요청 대기열
- 초대 코드 만료·회전·재발급
- SMS·메신저 초대 발송과 그룹 알림
- 그룹·멤버 목록 페이지네이션
- 삭제 그룹 복구·물리 삭제
- 그룹별 정산 내역·통계

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: 인증의 `CurrentActorPort`, 사용자 `User`·`LoadUserPort`, 정산의 `SettlementParticipant`·`SettlementStatus`
- Reason a new domain is required: 그룹은 이름·초대 코드·소유자·생명주기와 멤버 역할·재가입·종료 상태라는 독립된 식별자와 불변식을 소유한다.
- Relationships to other domains: User는 멤버의 신원과 공개 ID를 제공하고, Settlement는 그룹 출처 참여자와 종료 차단 상태를 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 그룹 | 함께 정산할 구성원을 관리하는 단위 | `Group` |
| 그룹멤버 | 그룹과 사용자의 수명주기 관계 | `GroupMember` |
| 소유자 | 그룹에 정확히 한 명인 관리 역할 | `GroupMemberRole.OWNER`, `Group.ownerId` |
| 일반 멤버 | 조회·공유·자기 탈퇴가 가능한 역할 | `GroupMemberRole.MEMBER` |
| 활성 멤버 | 현재 그룹 기능을 사용할 수 있는 멤버십 | `GroupMemberStatus.ACTIVE` |
| 자발적 탈퇴 | 사용자가 스스로 나간 재가입 가능 상태 | `GroupMemberStatus.LEFT` |
| 제거 | OWNER가 내보내 초대 코드 재가입이 막힌 상태 | `GroupMemberStatus.REMOVED` |
| 초대 코드 | 그룹 가입에 사용하는 고정 8자리 코드 | `Group.inviteCode` |
| 그룹 출처 정산 | 그룹에서 선택해 `source_group_id`가 저장된 참여자 | `SettlementParticipantSource.GROUP_MEMBER` |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 로그인 사용자 | 그룹 생성 | `CreateGroupUseCase` |
| 활성 멤버 | 내 그룹 목록·상세·이미지 조회 | `GetMyGroupsUseCase`, `GetGroupUseCase`, `GetGroupImageUseCase` |
| OWNER | 그룹 설정 수정·삭제 | `UpdateGroupUseCase`, `DeleteGroupUseCase` |
| 로그인 사용자 | 초대 코드 가입·재가입 | `JoinGroupUseCase` |
| 활성 멤버 | 멤버 목록 조회 | `GetGroupMembersUseCase` |
| OWNER | 공개 ID 직접 추가·복구 | `AddGroupMemberUseCase` |
| OWNER | 소유권 이전 | `TransferGroupOwnershipUseCase` |
| MEMBER | 자기 탈퇴 | `LeaveGroupUseCase` |
| OWNER | 멤버 제거 | `RemoveGroupMemberUseCase` |
| 정산 생성자 | 실제 그룹멤버 후보·참여자 추가 | 기존 settlement UseCase와 `LoadGroupMembersPort` |

### State and invariants

- Group: `ACTIVE -> DELETED`, 복구 없음
- GroupMember: `ACTIVE -> LEFT -> ACTIVE`, `ACTIVE -> REMOVED -> ACTIVE`
- 역할: 소유권 이전에서만 `OWNER <-> MEMBER`
- 활성 그룹에는 `OWNER`가 정확히 한 명이다.
- 같은 그룹·사용자 멤버십은 하나만 존재한다.
- OWNER는 항상 ACTIVE이고 이전 없이 탈퇴할 수 없다.
- 초대 코드는 그룹 전체에서 고유하고 생성 후 바뀌지 않는다.
- 그룹 삭제는 멤버십과 정산을 삭제하지 않는다.
- GROUP_MEMBER 정산 참여자는 최종적으로 양수 `sourceGroupId`를 가져야 한다.

## 3. Scenarios

### Normal flow

1. 로그인 사용자가 그룹을 생성해 OWNER가 되고 초대 링크를 공유한다.
2. 다른 사용자가 코드로 가입하거나 OWNER가 공개 ID로 직접 추가한다.
3. 활성 멤버는 목록·상세·멤버를 조회하고 정산 생성 시 실제 그룹멤버를 고른다.
4. 정산 참여자에 `GROUP_MEMBER`와 그룹 ID가 저장된다.
5. OWNER가 필요하면 다른 MEMBER에게 소유권을 이전한다.
6. 미완료 정산 의무가 없는 MEMBER는 탈퇴하거나 OWNER에 의해 제거된다.
7. OWNER가 그룹을 삭제해 신규 접근을 닫아도 기존 정산은 계속 보존된다.

### Failure flow

- 비멤버가 그룹 상세를 조회하면 `GROUP_ACCESS_DENIED`다.
- MEMBER가 설정·추가·제거·이전을 요청하면 `GROUP_OWNER_REQUIRED`다.
- `REMOVED` 멤버가 코드로 가입하면 `GROUP_MEMBER_REMOVED`다.
- OWNER가 이전 없이 탈퇴하면 `GROUP_OWNER_TRANSFER_REQUIRED`다.
- 관련 `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED` 정산이 있으면 `GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT`다.
- 후보 조회 뒤 탈퇴한 그룹멤버를 정산에 추가하면 추가 시점 roster 재검증으로 거절한다.
- 삭제 그룹은 신규 작업에서 `GROUP_NOT_FOUND`로 숨긴다.

### Boundary conditions

- 이름 1·20자, 메모 0·60자, 이미지 5 MiB 경계를 검증한다.
- 초대 코드 입력의 공백·대소문자와 생성 충돌을 처리한다.
- 동시 가입·직접 추가는 `(group_id, user_id)` unique로 중복을 차단한다.
- 동시 소유권 이전은 group row lock과 활성 OWNER unique로 직렬화한다.
- 이미지 저장 뒤 DB rollback과 commit 이후 기존 파일 정리를 구분한다.
- migration과 애플리케이션 사이 legacy `GROUP_MEMBER/source_group_id NULL`을 안전하게 읽는다.

## 4. Contracts

상세 계약은 다음 문서를 기준으로 한다.

- [그룹 핵심 기능 Spec](group-core-spec.md)
- [그룹 멤버십·권한 Spec](group-membership-spec.md)
- [그룹-정산 연동 Spec](group-settlement-integration-spec.md)

### Input Port and boundary mapping

```text
Create/Update/Join/Add/Transfer Command: plain Application record
Simple groupId/memberId: UseCase method parameter
Web multipart/JSON Request DTO -> Command
UseCase -> Application Result -> Web Response DTO
Image UseCase -> GroupImage -> ResponseEntity<byte[]>
```

- 신규 Application 계약은 `command`, `result` package와 `<Verb><Target>UseCase` 이름을 사용한다.
- Web DTO는 Jakarta Validation과 multipart 변환만 담당한다.
- Controller는 Result를 직접 반환하지 않고 Web Response의 `from(result)`로 변환한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
| --- | --- | --- | --- |
| `LoadGroupPort` | ID·초대 코드 그룹 조회와 코드 존재 확인 | 그룹 Service | `GroupPersistenceAdapter` |
| `SaveGroupPort` | 그룹 생성·설정·소유자·삭제 저장 | 그룹 Service | `GroupPersistenceAdapter` |
| `LoadGroupMemberPort` | 멤버십·OWNER·활성 멤버 조회 | 그룹 Service | `GroupMemberPersistenceAdapter` |
| `SaveGroupMemberPort` | 멤버 생성·재활성화·종료·역할 저장 | 그룹 Service | `GroupMemberPersistenceAdapter` |
| `GroupImageStoragePort` | 그룹 이미지 저장·조회·삭제 | 그룹 Service | `LocalGroupImageStorageAdapter` |
| 기존 `LoadGroupMembersPort` | 정산용 활성 roster 조회 | `SettlementParticipantService` | `GroupMemberPersistenceAdapter` |
| `LoadGroupMemberSettlementStatePort` | 그룹·사용자 정산 상태 사실 조회 | 멤버 종료 Service | settlement Persistence Adapter |
| 기존 `LoadUserPort` | 현재 사용자·공개 ID 사용자 조회 | 그룹 Service | `UserPersistenceAdapter` |
| 기존 `CurrentActorPort` | 인증된 현재 user ID | 그룹 Service | `SpringSecurityAdapter` |

### Application Policy

- Decision owned by the Policy: 자기 탈퇴·OWNER 제거 가능 여부
- Facts loaded by the Service and passed to the Policy: 대상 역할·상태, 종료 요청 종류, 관련 그룹 출처 `SettlementStatus` 집합
- Why the rule is not an Entity-local invariant: 종료 가능 여부가 GroupMember 하나가 알 수 없는 Settlement 상태를 결합한다.
- Future inputs deliberately not implemented yet: 알림 발송, 승인 대기, 별도 채무 금액 정책

`GroupMemberExitPolicy`는 Application 소유 concrete component이고 self-leave와 owner-remove가 함께 사용한다.

### Web API

```text
POST   /api/v2/groups                                      multipart, 201 detail
GET    /api/v2/groups                                      200 summary list
GET    /api/v2/groups/{groupId}                            200 detail
PATCH  /api/v2/groups/{groupId}                            multipart, OWNER, 200 detail
DELETE /api/v2/groups/{groupId}                            OWNER, 200 empty
GET    /api/v2/groups/{groupId}/image                      image bytes
POST   /api/v2/groups/join                                 inviteCode, 200 detail
GET    /api/v2/groups/{groupId}/members                    200 member list
POST   /api/v2/groups/{groupId}/members                    OWNER/publicId, 201 member
PUT    /api/v2/groups/{groupId}/owner                      OWNER/targetMemberId, 200 empty
DELETE /api/v2/groups/{groupId}/members/me                 200 empty
DELETE /api/v2/groups/{groupId}/members/{memberId}         OWNER, 200 empty
```

Authentication: 모든 그룹 endpoint는 bearer 인증 필수다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: `groups`, `group_members`, `settlement_participants.source_group_id`, active OWNER partial unique, roster·exit-state query
- Flyway migration required: Yes, 두 단계
- Planned migration: 1차 그룹 스키마+nullable 출처, 2차 legacy backfill+strict source CHECK
- Migration pull request: 1차 완료 ([#165](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/165)), 2차 Pending (#152)
- Redis key, TTL, or serialization changes: N/A
- External API or message changes: N/A, 서버는 초대 메시지를 발송하지 않음
- Environment variable or profile changes: `howmuch.group.image.storage-dir`, `howmuch.group.invite.base-url`; group roster Stub profile 제거

## 5. Design decisions

### Business logic

- Rules and their locations: 이름·상태 전이는 Domain, 탈퇴·제거 정산 결합은 `GroupMemberExitPolicy`, 권한·잠금·Port 순서는 Service
- Behavior owned by Domain: Group 설정·삭제·소유자 변경, GroupMember 가입·재활성화·역할·종료 전이
- Decisions owned by Application Policy: 정산 상태별 멤버 종료 허용 여부
- Flow orchestrated by Application: 현재 actor와 외부 사실 조회, row lock, Domain/Policy 호출, 저장, Result 조립, 이미지 transaction callback

### Technology and patterns

- Constraint to solve: 그룹 기능 배포 전 정산 Stub과 GROUP_MEMBER 데이터가 이미 존재하며 Flyway와 앱 배포 시점이 다르다.
- Selected approach: 기존 정산 Port를 실제 GroupMember Adapter로 교체하고 `source_group_id`를 두 단계 migration으로 강화한다.
- Reason for selection: 정산 Application이 그룹 JPA에 의존하지 않으면서 출처·멤버 종료 정책을 복원할 수 있다.
- Alternatives considered: Stub 유지, 정산 참여자 source만으로 그룹 추측, 한 번에 NOT NULL/CHECK 추가, 그룹 삭제 cascade
- Trade-offs and limits: 전환 기간에는 legacy GROUP_MEMBER/null을 REGISTERED_USER로 읽으며 실제 그룹을 복원하지 않는다.

별도 ADR은 만들지 않는다. 목적 지향 Port, Application Policy와 상태 코드 방식은 기존 ADR을 그대로 적용하고 그룹에만 한정된 제품·배포 결정은 이 Feature Plan에 기록한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | 그룹 12개 endpoint 추가, 기존 정산 그룹 endpoint shape 유지 | Core/Membership Spec, `SettlementController` |
| Application | 그룹 UseCase·Command/Result·Service·Policy, 정산 sourceGroupId 전달 | `application.port`, `SettlementParticipantService` |
| Domain | `Group`, `GroupMember`, 상태·역할·StatusCode, SettlementParticipant 출처 강화 | Core/Settlement Spec |
| Persistence | 그룹 2개 테이블, 참여자 컬럼·제약, roster·상태 쿼리 | #146, #152 |
| Redis | 변경 없음 | N/A |
| Security | 모든 그룹 API 인증, 활성 멤버·OWNER 권한 | Membership Spec |
| Configuration | 그룹 이미지 저장 경로와 초대 base URL | Core Spec |
| Documentation | PRD, Spec 3종, 이 Feature Plan, Swagger | #140~#145 |

Existing behavior that must remain unchanged:

- 기존 user 프로필·공개 ID와 auth 세션 계약을 변경하지 않는다.
- 기존 REGISTERED_USER·CONTACT 정산 참여자와 중복·표시 순서 규칙을 유지한다.
- 기존 정산 링크·Share·송금 안내와 목록 API 응답을 변경하지 않는다.
- 연락처 원본, 내부 user ID, 이미지 파일명을 신규 응답에 노출하지 않는다.
- 모든 profile의 Hibernate `ddl-auto=validate`와 Flyway 기준을 유지한다.

## 7. Implementation plan

- [x] 1차 migration-only pull request to `develop` (#165)
- [ ] 2차 migration-only pull request to `develop` (애플리케이션 배포 호환 확인 후 #152에서 종료)
- [ ] UseCase and Application Command/Result
- [ ] Required Output Ports
- [ ] Domain models and rules
- [ ] Application Policies
- [ ] Application Service
- [ ] Output Adapters
- [ ] Web Request/Response DTOs, Controllers, and Swagger
- [ ] Infra configuration and Bean wiring
- [ ] Documentation and verification results

### Pull request boundaries

| 순서 | PR 범위 | 관련 이슈 | 병합·실행 조건 |
| --- | --- | --- | --- |
| 1 | PRD, Spec 3종, Feature Plan | #140~#145 | 현재 문서 PR, `Part of #139` |
| 2 | 1차 migration-only | #146 | 최신 migration·열린 PR 확인 후 SQL만 포함하고 종료 |
| 3 | 그룹 core와 settings | #147, #148 | #146 병합·적용 후 |
| 4 | 가입·멤버 목록·직접 추가 | #149 | core 병합 후 |
| 5 | 실제 roster와 정산 출처 쓰기 | #150 | membership 병합 후 |
| 6 | 소유권 이전·탈퇴·제거 | #151 | #149, #150 병합 후 |
| 7 | 2차 strict migration-only | #152 | 새 writer 배포 호환 확인 후 SQL만 포함하고 종료 |
| 8 | 교차 통합·OpenAPI 검증 | #153 | 모든 기능·migration 완료 후 |

### Expected commit boundaries

현재 문서 PR:

```text
docs(group): 그룹 제품 요구사항 정의
docs(group): 그룹 핵심 기능 계약 정의
docs(group): 그룹 멤버십 권한 계약 정의
docs(group): 그룹 정산 연동 계약 정의
docs(group): 그룹 구현 계획 정의
```

1차 migration PR:

```text
build(group): 그룹 관리 스키마 추가
```

Core·settings PR:

```text
feat(group): 그룹 도메인과 이미지 정책 추가
feat(group): 그룹 생성과 조회 애플리케이션 기능 추가
feat(group): 그룹 영속성과 이미지 저장 기능 추가
feat(group): 그룹 생성과 조회 API 추가
feat(group): 그룹 설정 수정과 이미지 교체 추가
feat(group): 그룹 소프트 삭제 추가
test(group): 그룹 핵심 기능 시나리오 검증
docs(group): 그룹 핵심 기능 구현 결과 반영
```

Membership PR:

```text
feat(group): 그룹 가입과 재가입 상태 전이 추가
feat(group): 그룹 멤버 조회와 직접 추가 기능 추가
feat(group): 그룹 가입과 멤버 API 추가
test(group): 그룹 가입과 멤버 권한 검증
docs(group): 그룹 멤버십 구현 결과 반영
```

Settlement integration PR:

```text
feat(settlement): 정산 참여자 그룹 출처 보존
feat(group): 실제 활성 그룹 roster 연동
feat(settlement): 실제 그룹 구성원 참여자 연동
test(settlement): 그룹 출처 참여자 호환 검증
docs(group): 그룹 정산 연동 결과 반영
```

Ownership·exit PR:

```text
feat(group): 그룹 소유권 이전 기능 추가
feat(group): 그룹멤버 종료 정책과 정산 상태 조회 추가
feat(group): 그룹 탈퇴와 멤버 제거 API 추가
test(group): 소유권과 멤버 종료 동시성 검증
docs(group): 그룹멤버 종료 구현 결과 반영
```

2차 migration과 최종 검증 PR:

```text
build(group): 정산 그룹 출처 제약 강화
test(group): 그룹 전체 통합 시나리오 검증
docs(group): 그룹 관리 완료 결과 반영
```

각 커밋은 compile 가능한 상태를 유지하고, 호출자 없는 Port-only 상태를 커밋 경계로 사용하지 않는다.

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

문서 PR은 Java를 변경하지 않으므로 상대 Markdown 링크와 `git diff --check`를 검증하고, `compileJava`·전체 테스트는 구현 PR에서 실행한다.

구현·통합 검증 전제:

- PostgreSQL과 Redis 실행
- 필요한 profile 환경 변수와 그룹 이미지 쓰기 가능 경로
- 1차 또는 2차 migration 단계에 맞는 격리 스키마
- 전체 테스트와 실제 HTTP·OpenAPI 확인은 비밀값을 출력하지 않는 환경에서 수행

### Completion criteria

- [ ] Verified the user outcome defined under Purpose.
- [ ] Verified normal, failure, and boundary flows.
- [ ] Confirmed documentation and Swagger match the actual contract.
- [ ] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| PRD·Spec·Feature Plan 작성 | Complete | [#164](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/164) |
| 1차 migration | Complete | [#165](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/165) |
| 그룹 core·settings | Complete, merged | [#167](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/167) |
| membership·권한 | #149 Complete, merged; #151 Pending | [#168](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/168), #151 |
| settlement integration | Implemented, PR pending | #150 |
| 2차 strict migration | Pending | #152 |
| 전체 통합·OpenAPI | Pending | #153 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-25 | 기능명세서, `develop`, 기존 PR·문서 형식 분석; PRD·Spec 3종·Feature Plan 초안 작성 | 문서 리뷰 후 #146 migration gate 실행 | GROUP_MEMBER legacy 행과 구버전 writer 사이 배포 호환 필요 |
| 2026-08-25 | #164·#165 병합; 그룹 생성·조회·이미지·설정·소프트 삭제와 계층별 테스트 구현 | #147·#148 PR 리뷰 후 #149 | 무페이지 목록은 규모 증가 시 재검토 필요 |
| 2026-08-25 | #167 병합; 초대 코드 가입·재가입, 멤버 목록과 OWNER 직접 추가·복구 구현 및 계층별 테스트 완료 | #149 PR 리뷰 후 #150 | 동시 신규 가입은 unique 제약 충돌을 중복 활성 오류로 변환 |
| 2026-08-25 | #168 병합; profile Stub 제거, 실제 활성 roster와 정산 참여자 `sourceGroupId` 저장·legacy 호환 구현 | #150 PR 리뷰 후 #151 | strict 제약 전까지 구버전 writer의 GROUP_MEMBER/null을 호환 읽기해야 함 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
| --- | --- | --- | --- |
| 2026-08-25 | 역할은 OWNER·MEMBER만 둔다 | 현재 제품 권한을 최소 상태로 표현 | ADMIN 제외 |
| 2026-08-25 | 이름 20자, 메모 60자, 이미지 JPEG·PNG·WebP 5 MiB | 기능명세와 기존 프로필 이미지 정책 정렬 | 기본 이미지는 클라이언트 처리 |
| 2026-08-25 | 초대 코드는 고정 8자리이고 서버는 링크만 반환한다 | MVP 공유 흐름 단순화 | 만료·회전·서버 발송 제외 |
| 2026-08-25 | LEFT는 코드 재가입, REMOVED는 OWNER 복구만 허용한다 | 자발적 이탈과 강제 제거 의도를 보존 | 직접 추가로 LEFT 강제 복구 금지 |
| 2026-08-25 | OWNER는 이전 후에만 탈퇴한다 | 활성 OWNER 한 명 불변식 보장 | OWNER 없는 그룹 상태 제외 |
| 2026-08-25 | self-leave와 owner-remove가 같은 ExitPolicy를 사용한다 | 정산 의무 우회 방지 | 별도 경로별 정책 제외 |
| 2026-08-25 | `source_group_id`를 두 단계 migration으로 강화한다 | migration/app 배포 호환 보장 | 즉시 NOT NULL/CHECK 제외 |
| 2026-08-25 | 목록은 페이지네이션 없이 전체 반환한다 | 확정된 MVP 범위 | 사용량 증가 시 후속 변경 |
| 2026-08-25 | 그룹은 소프트 삭제하고 정산을 보존한다 | 기능명세의 진행 중 정산 보존 | 복구·물리 cascade 제외 |
| 2026-08-25 | 문서 5종은 한 PR·다섯 커밋으로 리뷰한다 | 제품 규칙·API·migration·실행 순서를 함께 검토 | 구현 이슈와 최상위 #139는 닫지 않음 |

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: #150·#151 Java 구현, 2차 migration, 최종 HTTP·OpenAPI 교차 검증
- Remaining risks: 초대 base URL 운영값, 대규모 그룹에서 무페이지 목록의 성능, legacy writer 종료 시점
- Follow-up work: #146~#153
