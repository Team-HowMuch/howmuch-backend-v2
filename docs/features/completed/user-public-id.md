# Feature Plan: 사용자 공개 ID 생성 및 프로필 관리

- Status: Complete
- Last updated: 2026-08-22
- Related issue: [#68 사용자 공개 ID 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/68), [#69 자동 생성 및 중복 검증](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/69), [#73 프로필 계약 전환](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/73)
- Related ADR: N/A

## 1. Purpose

### Problem

현재 사용자는 가입 시 이름을 복사한 `nickname`만 가지며, 이 값은 고유하지 않아 다른 사용자가 정산 참여자를 검색할 식별자로 사용할 수 없다. 사용자는 자신에게 자동 부여된 고유 ID를 확인하고 원하는 값으로 변경할 수도 없다.

### Why now

정산 참여자를 사용자 ID로 검색하는 기능은 이름과 독립된 고유 식별자를 전제로 한다. 참여자 기능을 구현하기 전에 신규·기존 사용자 모두에게 `publicId`가 존재하고 프로필 계약이 이를 제공해야 한다.

### Success criteria

- [x] 회원가입 요청을 바꾸지 않고 서버가 `user_` 접두사의 고유한 `publicId`를 생성한다.
- [x] 내 프로필 조회 응답이 `nickname` 대신 `publicId`를 반환한다.
- [x] 사용자는 프로필 수정 API에서 `publicId`를 변경할 수 있다.
- [x] `publicId`는 소문자로 정규화되고 영문 소문자, 숫자, 점, 밑줄 4~20자만 허용한다.
- [x] 중복 `publicId` 변경은 `409 PUBLIC_ID_ALREADY_EXISTS`로 거부한다.

### Out of scope

- `publicId` prefix 기반 정산 참여자 검색
- `nickname` 데이터와 컬럼 제거
- `publicId` 변경 횟수 또는 대기 기간 제한
- V7의 호환용 DB 기본값과 sequence 제거

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `user`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: `auth` 회원가입이 초기 `publicId`를 생성하며, 이후 `settlement`가 사용자 검색에 사용한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 공개 ID | 이름과 별개로 다른 사용자에게 노출되는 고유 식별자 | `publicId` |
| 자동 생성 공개 ID | 가입 시 서버가 생성하는 `user_`와 12자리 난수 조합 | `PublicIdPolicy.generate()` |
| 공개 ID 충돌 | 생성 또는 변경하려는 값이 이미 다른 사용자에게 존재하는 상태 | `PUBLIC_ID_ALREADY_EXISTS` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 가입 사용자 | 별도 입력 없이 고유 공개 ID를 부여받는다. | `SignUpUseCase` |
| 로그인 사용자 | 자신의 공개 ID를 확인한다. | `GetMyProfileUseCase` |
| 로그인 사용자 | 자신의 공개 ID를 변경한다. | `UpdateMyProfileUseCase` |

### State and invariants

- State transitions: 프로필 수정 시 기존 `publicId`를 정규화·검증된 새 값으로 교체한다.
- Rules that must always hold: 모든 사용자는 4~20자의 유효한 `publicId`를 하나 가지며 데이터베이스 범위에서 고유하다.
- Invalid states: 빈 값, 허용되지 않은 문자, 4자 미만 또는 20자 초과, 다른 사용자가 사용 중인 값.

## 3. Scenarios

### Normal flow

1. 가입 서비스가 `user_`와 소문자 영문·숫자 난수 12자리로 후보를 생성한다.
2. 이미 사용 중인 후보이면 최대 5회까지 새 후보를 생성한다.
3. 사용자를 저장하고 프로필 조회에서 생성된 `publicId`를 반환한다.
4. 사용자가 프로필에서 새 값을 입력하면 앞뒤 공백 제거와 소문자 변환 후 저장한다.

### Failure flow

- 5개의 자동 생성 후보가 모두 사용 중이다: `PUBLIC_ID_GENERATION_FAILED`를 반환하고 사용자를 저장하지 않는다.
- 수정할 공개 ID의 형식이 잘못됐다: `INVALID_PUBLIC_ID`를 반환한다.
- 수정할 공개 ID가 이미 존재한다: `PUBLIC_ID_ALREADY_EXISTS`를 반환한다.
- 사전 중복 확인 뒤 DB unique 충돌이 발생한다: 영속 Adapter가 제약 이름을 해석해 `PUBLIC_ID_ALREADY_EXISTS`로 변환한다.

### Boundary conditions

- 현재 값과 정규화 결과가 같으면 중복 조회나 저장을 생략한다.
- 공개 ID와 아바타를 함께 변경하다 저장이 실패하면 새로 저장한 아바타 파일을 정리한다.
- 프로필 수정은 사용자 행을 잠가 동시에 수정한 다른 프로필 값을 덮어쓰지 않는다.

## 4. Contracts

### Input Port and boundary mapping

```text
SignUpRequest -> SignUpCommand -> SignUpUseCase
UpdateMyProfileRequest(publicId, avatar, resetAvatar)
  -> UpdateMyProfileCommand(publicId, avatarContent, resetAvatar)
  -> UpdateMyProfileUseCase
  -> MyProfileResult
  -> MyProfileWebResponse
```

회원가입 Web 요청에는 `publicId`를 추가하지 않는다. 프로필 Result와 Web Response의 `nickname` 필드는 `publicId`로 교체한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadUserPort.existsByPublicId` | 자동 생성과 프로필 변경 전에 공개 ID 사용 여부를 확인한다. | `SignUpService`, `UpdateMyProfileService` | `UserPersistenceAdapter` |
| `SaveUserPort` | 공개 ID가 포함된 사용자를 저장·수정한다. | 가입·프로필 수정 서비스 | `UserPersistenceAdapter` |

### Application Policy

- Decision owned by the Policy: 공개 ID 생성, 소문자 정규화, 형식 검증
- Facts loaded by the Service and passed to the Policy: 사용자가 입력한 공개 ID
- Why the rule is not an Entity-local invariant: 자동 생성과 입력 정규화는 가입·프로필 Application 흐름이 공유하는 정책이다.
- Future inputs deliberately not implemented yet: 변경 횟수, 마지막 변경 시각

### Web API

```text
GET /api/v2/user/me
Response: { userId, name, publicId, ... }
Authentication: required

PATCH /api/v2/user/me/profile
Content-Type: multipart/form-data
Request: publicId?, avatar?, resetAvatar?
Response: { userId, name, publicId, ... }
Errors: 400 INVALID_PUBLIC_ID, 409 PUBLIC_ID_ALREADY_EXISTS
Authentication: required
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: V7의 `users.public_id`를 JPA에 연결하고 `existsByPublicId` 조회를 추가한다. 새 마이그레이션은 없다.
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 없음.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 형식·생성은 `PublicIdPolicy`, 가용 후보 선택과 최대 재시도는 `SignUpService`, 변경 중복 검증은 `UpdateMyProfileService`가 담당한다.
- Behavior owned by Domain: `User.changeProfile`이 공개 ID와 아바타 파일명을 함께 변경한다.
- Decisions owned by Application Policy: 입력 정규화와 공개 ID 형식 검증.
- Flow orchestrated by Application: 후보 조회, 현재 사용자 잠금, 저장, 아바타 보상 처리.

### Technology and patterns

- Constraint to solve: 공개 ID를 사용자 입력 없이 생성하면서 DB unique 제약과 프로필 수정 경쟁 조건을 보존해야 한다.
- Selected approach: `SecureRandom` 후보 생성과 사전 중복 조회를 사용하고, 최종 DB unique 충돌은 제약 이름으로 409에 변환한다.
- Reason for selection: V7 스키마를 그대로 사용하면서 Application 계약에서 충돌 원인을 구분할 수 있다.
- Alternatives considered: DB 호환 기본값을 신규 앱의 공개 ID로 계속 사용하는 방식은 사용자용 `user_` 형식을 보장하지 못해 제외한다.
- Trade-offs and limits: 사전 확인과 INSERT 사이의 극히 드문 경쟁은 재시도하지 않고 409로 반환하며, 사용자는 가입을 다시 요청할 수 있다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 프로필의 `nickname` 필드를 `publicId`로 교체 | User profile DTO, Swagger |
| Application | 공개 ID 생성·정규화·중복 검사 | SignUpService, UpdateMyProfileService |
| Domain | `User.nickname`을 `User.publicId`로 전환 | User |
| Persistence | V7 컬럼 매핑과 존재 조회, unique 오류 변환 | User persistence adapter |
| Redis | 없음 | N/A |
| Security | 기존 로그인 사용자 본인 프로필 권한 유지 | CurrentActorPort |
| Configuration | `NicknamePolicy` Infra Bean 제거, `PublicIdPolicy` Application Component 사용 | AuthPolicyConfig |
| Documentation | 공개 ID 프로필 계약과 오류 문서화 | 이 문서, UserDocs |

Existing behavior that must remain unchanged:

- 회원가입 요청 필드와 SMS challenge 흐름은 변경하지 않는다.
- 이름, 전화번호, PIN, 아바타 조회·수정 동작은 유지한다.
- V7의 `nickname` 컬럼과 호환 기본값은 후속 migration-only PR 전까지 유지한다.

## 7. Implementation plan

- [x] 공개 ID 정책과 오류 코드를 추가한다.
- [x] User Domain·JPA·Mapper를 `publicId`로 전환한다.
- [x] 회원가입 공개 ID 자동 생성과 중복 후보 재시도를 구현한다.
- [x] 프로필 조회·수정 계약과 중복 검증을 구현한다.
- [x] 공개 ID 정책, 가입, 프로필 변경 테스트를 추가한다.
- [x] Swagger와 Feature Plan을 실제 결과로 갱신한다.

### Expected commit boundaries

```text
docs: 사용자 공개 ID 구현 계획 추가
feat: 회원가입 공개 ID 자동 생성 추가
feat: 프로필 공개 ID 조회와 수정 추가
test: 공개 ID 생성과 회원가입 흐름 검증 추가
test: 프로필 공개 ID 변경 흐름 검증 추가
docs: 사용자 공개 ID 구현 결과 반영
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

실제 PostgreSQL에 V1~V7을 적용하고 회원가입 또는 테스트 사용자로 로그인한 뒤 프로필 조회, 공개 ID 변경, 형식 오류, 중복 오류와 DB 저장 결과를 HTTP로 확인한다.

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| Compile | Pass | `./gradlew compileJava` |
| Automated tests | Pass | 실제 PostgreSQL·Redis 연결 후 38개 테스트 성공 |
| Signup HTTP | Pass | Redis challenge로 가입 후 `user_[a-z0-9]{12}` DB 저장과 challenge 삭제 확인 |
| Profile HTTP | Pass | JWT 조회·정규화 수정·400 형식 오류·409 중복 오류·DB 유지 확인 |
| Swagger | Pass | multipart `publicId`, 응답 `publicId`, 400·409 계약 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-22 | V7 병합 확인 및 구현 계획 작성 | 공개 ID 생성과 사용자 모델 전환 | 프로필 응답 필드 교체는 클라이언트 계약 변경임 |
| 2026-08-22 | 공개 ID 생성·프로필 전환·자동화 및 HTTP 검증 완료 | PR 리뷰 | nickname 컬럼과 DB 호환 기본값은 후속 migration-only PR에서 제거해야 함 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-22 | 가입 요청에 publicId를 받지 않는다. | 가입 UX를 늘리지 않고 서버가 고유 기본값을 제공한다. | 사용자는 가입 후 프로필에서 변경한다. |
| 2026-08-22 | 자동 생성 후보는 최대 5회 확인한다. | 사실상 희박한 충돌을 제한된 비용으로 처리한다. | 모두 충돌하면 가입을 저장하지 않는다. |
| 2026-08-22 | nickname API 필드를 publicId로 교체한다. | nickname 개념을 유지하지 않는 제품 결정을 반영한다. | 클라이언트는 publicId 필드로 전환해야 한다. |

## 11. Completion record

- Completed on: 2026-08-22
- Final behavior: 회원가입이 공개 ID를 자동 생성하고 프로필 API가 `nickname` 없이 `publicId`를 조회·정규화·중복 검증하여 수정한다.
