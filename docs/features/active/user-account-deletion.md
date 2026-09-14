# Feature Plan: 회원 탈퇴 API 및 보수적 개인정보 처리

- Status: Implemented (verification pending full environment)
- Last updated: 2026-09-14
- Related issue: Team-HowMuch/howmuch-backend-v2#376
- Related ADR: 없음

## 1. Purpose

### Problem

현재 인증된 사용자가 앱에서 자신의 계정을 탈퇴할 수 있는 API가 없습니다. 탈퇴 시 사용자 레코드를 물리 삭제하면 정산·그룹 이력의 외래키와 충돌하므로, 활성 계정을 종료하고 재식별 정보를 분리해야 합니다.

### Why now

모바일 탈퇴 화면이 `DELETE /api/v2/user/me` 계약을 요구합니다. 탈퇴 경계가 없으면 세션, 푸시 대상, 그룹 멤버십이 계속 남고 재가입 정책도 일관되지 않습니다.

### Success criteria

- [ ] 인증된 사용자가 PIN 재입력으로 `DELETE /api/v2/user/me`를 호출해 계정을 종료할 수 있다.
- [ ] 비활성 사용자·PIN 오류·미완료 정산·그룹 OWNER 상태는 각각 명시된 오류 계약으로 거부된다.
- [ ] 탈퇴 성공 시 사용자 로그인/갱신 세션과 푸시 대상이 폐기되고, 프로필 식별 정보는 익명화되며, 같은 전화번호로 즉시 재가입할 수 있다.
- [ ] 정산·그룹의 외래키를 깨지 않고 기존 금융 이력은 보존한다.

### Out of scope

- CODEF 서버의 연결/기관을 물리 삭제하는 외부 API 호출. 현재 `BankConnectionPort`에 삭제 능력이 없고 법무 보존 결정 전에는 보수적으로 로컬 금융 이력을 유지한다.
- 정산 참여자 스냅샷 이름을 일괄 변경하는 별도 마이그레이션.
- 프론트엔드 구현(프론트는 기존 공통 오류 처리와 `/api/v2/user/me` 경로를 사용).

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `user`, `group`, `settlement`, `auth`
- Reason a new domain is required, if applicable: 없음. 회원 탈퇴는 여러 도메인의 종료 대화를 Application에서 조율한다.
- Relationships to other domains: 활성 그룹 OWNER와 미완료 정산은 탈퇴를 차단하며, 일반 활성 MEMBER는 `LEFT`로 전환한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 탈퇴 | 계정 비활성화와 식별 정보 익명화 | `User.deactivateForDeletion` |
| 미완료 정산 | `COMPLETED`가 아닌 정산 | `LoadUserSettlementStatePort` |
| 탈퇴 정책 | 탈퇴 가능 여부와 보존 결정을 Application에서 판단하는 교체 지점 | `UserDeletionPolicy` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 인증된 사용자 | PIN을 확인하고 자신의 계정을 탈퇴 | `DeleteMyAccountUseCase` |

### State and invariants

- State transitions: 활성 사용자 → 익명화된 비활성 사용자; 활성 일반 그룹 멤버 → `LEFT`; 세션·푸시 대상 → 삭제/해제.
- Rules that must always hold: 활성 그룹 OWNER는 소유권 이전 전 탈퇴 불가; 사용자가 생성했거나 참여한 `COMPLETED` 이외 정산이 있으면 탈퇴 불가; PIN 형식과 해시 일치가 모두 확인돼야 한다.
- Invalid states: 비활성 사용자의 재탈퇴, 잘못된 PIN, 소유권/정산 의무가 남은 탈퇴, 익명화 후에도 원래의 `identityKey`·`phoneNumber`·`publicId`가 unique 충돌을 일으키는 상태.

## 3. Scenarios

### Normal flow

1. 활성 세션의 사용자가 `{"pin":"123456"}`으로 `DELETE /api/v2/user/me`를 호출한다.
2. Application Service가 사용자를 비관적 잠금으로 읽고 PIN을 확인한 뒤 정책에 사실을 전달한다.
3. 차단 사유가 없으면 활성 일반 멤버를 `LEFT`로 전환하고 사용자 식별 필드를 익명화·비활성화하며 세션과 푸시 대상을 폐기한다.
4. API는 200과 빈 `ApiResponse`를 반환한다. 기존 정산/금융 이력은 외래키를 유지한 채 보존된다.

### Failure flow

- Condition: PIN 형식 오류 → `INVALID_PIN_FORMAT` (400).
- Condition: PIN 불일치 → `USER_DELETION_PIN_MISMATCH` (401).
- Condition: 사용자가 없거나 이미 비활성 → `USER_NOT_FOUND` (404).
- Condition: 활성 그룹 OWNER → `USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED` (409).
- Condition: 미완료 정산 존재 → `USER_DELETION_BLOCKED_BY_SETTLEMENT` (409).

### Boundary conditions

- 동시 탈퇴는 `findByIdForUpdate`로 같은 사용자 행을 직렬화하고, 활성 그룹을 ID 오름차순으로 잠근 뒤 멤버 행을 다시 `FOR UPDATE`로 읽어 OWNER 여부를 재평가한다. 성공 직후 세션을 삭제하므로 기존 액세스 토큰의 재호출은 인증 단계에서 거부되고, 서비스가 다시 호출되는 경계에서는 `USER_NOT_FOUND`가 된다.
- 탈퇴 직전에 JWT 인증을 통과한 요청도 사용자 행 잠금 획득 후 `User::active`를 재확인한다. 프로필·푸시 등록·계좌/계좌 설정·그룹 생성/가입·정산 생성 및 계좌 삭제/은행 연동은 비활성 사용자이면 외부 저장·생성 작업을 시작하지 않는다. 사용자 잠금은 탈퇴 흐름과 같은 순서로 먼저 획득해 커밋 이후의 stale write를 차단한다.
- 같은 전화번호 재가입은 탈퇴 시 unique 필드를 `deleted-<id>`/`deleted:<id>`/`del_<id>`로 치환해 즉시 허용한다.
- 프로필 이미지 파일 삭제는 DB 커밋 후 수행하며 실패해도 이미 익명화된 사용자 레코드를 되돌리지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: DeleteMyAccountUseCase
Command: DeleteMyAccountCommand(pin)
Result: 없음
Web Request DTO -> Command: DeleteMyAccountWebRequest -> DeleteMyAccountCommand
Result -> Web Response DTO: 없음
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadUserPort` | 잠긴 사용자 조회 | `DeleteMyAccountService` | `UserPersistenceAdapter` |
| `SaveUserPort` | 익명화된 사용자 저장 | `DeleteMyAccountService` | `UserPersistenceAdapter` |
| `LoadGroupMemberPort` | 활성 멤버/OWNER 조회 | `DeleteMyAccountService` | `GroupMemberPersistenceAdapter` |
| `SaveGroupMemberPort` | 일반 멤버 `LEFT` 저장 | `DeleteMyAccountService` | `GroupMemberPersistenceAdapter` |
| `LoadUserSettlementStatePort` | 사용자의 미완료 정산 존재 확인 | `DeleteMyAccountService` | `SettlementPersistenceAdapter` |
| `AuthSessionPort` | 사용자 세션 삭제 | `DeleteMyAccountService` | `AuthSessionRedisAdapter` |
| `UserPushTargetStorePort` | 푸시 대상 해제 | `DeleteMyAccountService` | `UserPersistenceAdapter` |
| `ProfileImageStoragePort` | 이전 아바타 정리 | `DeleteMyAccountService` | 기존 프로필 이미지 어댑터 |

### Application Policy

- Decision owned by the Policy: 활성 OWNER/미완료 정산 차단과 탈퇴 시 익명화 값 생성. 금융·CODEF 이력은 현재 보존한다.
- Facts loaded by the Service and passed to the Policy: 사용자 활성 여부, 활성 그룹 멤버 목록, 미완료 정산 존재 여부.
- Why the rule is not an Entity-local invariant: 정산·그룹·보존 정책을 함께 판단하는 교차 도메인 규칙이기 때문이다.
- Future inputs deliberately not implemented yet: 법무 확정 후 CODEF 물리 삭제 또는 보존기간 만료 파기 규칙.

### Web API

```text
DELETE /api/v2/user/me
Request: { "pin": "123456" }
Response: 200 ApiResponse<Void>
Errors: INVALID_PIN_FORMAT(400), USER_DELETION_PIN_MISMATCH(401), USER_NOT_FOUND(404), USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED(409), USER_DELETION_BLOCKED_BY_SETTLEMENT(409)
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 기존 `users.active`와 unique 컬럼을 재사용하고 미완료 정산/활성 멤버 조회 쿼리만 추가한다.
- Flyway migration required (`Yes` / `No`): No.
- Planned migration: 없음.
- Migration pull request (`Pending` / URL): N/A.
- Redis key, TTL, or serialization changes: 없음. 기존 사용자 세션 키를 삭제한다.
- External API or message changes: 없음. CODEF 연결·금융 이력은 현재 보존한다.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: PIN 검증과 흐름은 `DeleteMyAccountService`, 교차 도메인 탈퇴 가능성과 익명화 전략은 `UserDeletionPolicy`, 사용자 익명화 불변식은 `User`가 소유한다.
- Behavior owned by Domain: 익명화된 식별 필드와 비활성 상태를 일관된 새 `User` 값으로 만드는 것.
- Decisions owned by Application Policy: OWNER/정산 차단과 현재 보존 전략.
- Flow orchestrated by Application: 사용자 잠금 → 검증/정책 → 멤버 이탈 → 사용자 익명화 → 세션/푸시 해제 → 커밋 후 이미지 삭제.

### Technology and patterns

- Constraint to solve: FK를 보존하면서 인증 식별자를 즉시 재사용해야 한다.
- Selected approach: 기존 `active` 플래그와 unique 필드 익명화, 교체 가능한 Application Policy.
- Reason for selection: migration 없이 현재 저장 구조로 동작하고, 법무 결정 후 정책/정리 포트만 확장할 수 있다.
- Alternatives considered: 물리 삭제(정산 FK 위반), `deleted_at` migration(현재 요구에 불필요), CODEF 삭제 호출(삭제 API/법무 기준 부재).
- Trade-offs and limits: 기존 정산 참여자 스냅샷 이름과 CODEF 암호문은 보존된다. 법무 확정 시 `UserDeletionPolicy`와 보존 전용 Port를 교체해야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 탈퇴 endpoint/요청/오류 코드 | `UserController`, `UserDocs` |
| Application | UseCase, Service, Policy | `application.user` |
| Domain | 익명화 상태 전이 | `domain.user.User` |
| Persistence | 사용자/그룹/정산 조회 재사용 및 존재 쿼리 | 관련 Output Port/Adapter |
| Redis | 세션 삭제 | `AuthSessionPort` |
| Security | bearer 인증과 PIN 재확인 | 기존 Security filter/`PasswordEncoder` |
| Configuration | 변경 없음 | N/A |
| Documentation | 본 Feature Plan | 이 문서 |

Existing behavior that must remain unchanged:

- 정산 이력과 외래키는 삭제하지 않는다.
- 기존 회원가입/로그인/프로필 API의 계약은 유지한다.
- 계정(은행 계좌) 삭제 API의 정산 사용 중 차단 규칙은 변경하지 않는다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`, 기존 스키마 재사용)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules
- [x] Application Policies
- [x] Application Service
- [x] 탈퇴 경합 시 stale write 활성 상태 재검증
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(user): 회원 탈퇴 보존·파기 정책 기록
feat(user): 회원 탈퇴 API 추가
test(user): 회원 탈퇴 차단·익명화 검증 추가
fix(user): 탈퇴 후 진행 요청의 활성 상태 재검증
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*DeleteMyAccount*' --tests '*UserDeletionPolicy*'
git diff --check
git status --short --untracked-files=all
```

수동 검증은 PostgreSQL과 Redis가 실행된 환경에서 정상 탈퇴, 잘못된 PIN, OWNER, 미완료 정산, 세션 삭제 후 재호출(인증 거부), 동일 전화번호 재가입을 확인한다.

### Completion criteria

- [x] API 정상/실패 계약과 세션·푸시 정리 흐름이 구현됐다.
- [ ] 정책 단위 테스트로 OWNER/정산 차단과 보수적 보존 결정을 확인한다. 레포 지침에 따라 신규 테스트는 추가하지 않았다.
- [x] 사용자·그룹·정산 persistence 경계와 Swagger가 구현과 일치한다.
- [x] 실행하지 못한 검증과 환경 원인을 기록한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| 문서/계약 | Pass | `DELETE /api/v2/user/me`, 요청/오류 코드 및 Swagger 반영 |
| Java 컴파일 | Pass | `./gradlew compileJava` (`BUILD SUCCESSFUL`) |
| 테스트 소스 컴파일 | Pass | `./gradlew compileTestJava` (`BUILD SUCCESSFUL`) |
| 기존 사용자 Web 계약 테스트 | Pass | `./gradlew test --tests 'com.amateur.howmuchbackend.adapter.in.web.user.UserControllerTest'` |
| 신규 탈퇴 단위 테스트 | Not added | 현재 레포 지침의 신규 테스트 생성 금지에 따름 |
| 탈퇴 후 stale write 회귀 범위 | Pass | 관련 프로필·푸시·그룹·정산·계좌 서비스 테스트 실행 (`BUILD SUCCESSFUL`) |
| 전체 테스트 | Not run | PostgreSQL/Redis 전제와 현재 작업 범위상 미실행 |
| 정적 검사 | Pass | `git diff --check` |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-14 | 보수적 보존 정책, API 경로, 차단 기준 확정 및 구현 | 전체 테스트/운영 환경 검증 | CODEF 물리 삭제 기준은 법무 확정 필요 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-14 | PIN 재입력으로 인증 | 기존 로그인 자격 증명 재사용 | SMS 재발송 흐름은 추가하지 않음 |
| 2026-09-14 | `/api/v2/user/me` 사용 | 현재 백엔드 사용자 API와 일치 | 프론트 제안의 plural 경로는 사용하지 않음 |
| 2026-09-14 | OWNER/모든 미완료 정산 차단 | 의무가 남은 상태에서 탈퇴로 인한 데이터 손상 방지 | 소유권 이전/정산 완료 후 재시도 |
| 2026-09-14 | 보수적 보존 정책 | 법무 결정 전 외부·금융 데이터 삭제를 단정하지 않음 | 추후 Policy와 삭제 Port 교체 |
