# 그룹 멤버십·권한 Backend Spec

- Status: Completed
- Last updated: 2026-08-25

## 1. 목적과 범위

이 문서는 [그룹 관리 PRD](../../product/group-prd.md)의 초대 코드 가입, OWNER 직접 추가, 멤버 목록, 소유권 이전, 자기 탈퇴와 OWNER 제거 계약을 정의한다. 그룹 정보·이미지·삭제는 [그룹 핵심 기능 Spec](group-core-spec.md), 정산 출처 저장과 종료 차단 조회는 [그룹-정산 연동 Spec](group-settlement-integration-spec.md)을 따른다.

관련 이슈:

- [#144 그룹 멤버십·권한 Spec 작성](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/144)
- [#149 그룹 가입·멤버 목록·직접 추가 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/149)
- [#151 그룹 소유권 이전·탈퇴·멤버 제거 구현](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/151)

## 2. 역할과 상태 계약

### 2.1 역할별 권한

| 작업 | `OWNER` | `MEMBER` | 비멤버 |
| --- | --- | --- | --- |
| 내 그룹 목록 | 허용 | 허용 | 자신의 다른 그룹만 반환 |
| 상세·이미지·멤버 목록 | 허용 | 허용 | 거절 |
| 초대 정보 조회·복사 | 허용 | 허용 | 거절 |
| 설정 수정·그룹 삭제 | 허용 | 거절 | 거절 |
| 공개 ID 직접 추가 | 허용 | 거절 | 거절 |
| 멤버 제거 | 허용 | 거절 | 거절 |
| 소유권 이전 | 허용 | 거절 | 거절 |
| 자기 탈퇴 | 이전 후 허용 | 허용 | 해당 없음 |
| 그룹 기반 정산 후보 사용 | 허용 | 허용 | 거절 |

### 2.2 상태 전이

| 이벤트 | 전 상태 | 후 상태 | 역할 |
| --- | --- | --- | --- |
| 그룹 생성 | 없음 | `ACTIVE` | `OWNER` |
| 초대 코드 신규 가입 | 없음 | `ACTIVE` | `MEMBER` |
| OWNER 신규 직접 추가 | 없음 | `ACTIVE` | `MEMBER` |
| 자발적 탈퇴 | `ACTIVE` | `LEFT` | `MEMBER` 유지 |
| 초대 코드 재가입 | `LEFT` | `ACTIVE` | `MEMBER` |
| OWNER 제거 | `ACTIVE` | `REMOVED` | `MEMBER` 유지 |
| OWNER 직접 복구 | `REMOVED` | `ACTIVE` | `MEMBER` |
| 소유권 이전-기존 OWNER | `ACTIVE OWNER` | `ACTIVE MEMBER` | `MEMBER` |
| 소유권 이전-대상 MEMBER | `ACTIVE MEMBER` | `ACTIVE OWNER` | `OWNER` |

- 재가입·복구 시 `joinedAt`을 현재 UTC 시각으로 갱신하고 `leftAt`을 null로 만든다.
- `createdAt`은 최초 멤버십 생성 시각으로 유지한다.
- `LEFT`와 `REMOVED` 상태에서 역할은 `MEMBER`만 허용한다.
- 그룹 삭제는 멤버 상태를 일괄 변경하지 않고 그룹 접근 경계에서 차단한다.

## 3. Application 계약

### 3.1 Input Port와 결과

| 사용자 목표 | UseCase | 입력 | 출력 |
| --- | --- | --- | --- |
| 초대 코드 가입 | `JoinGroupUseCase` | `JoinGroupCommand` | `GroupDetailResult` |
| 멤버 목록 | `GetGroupMembersUseCase` | `groupId` | `List<GroupMemberResult>` |
| OWNER 직접 추가 | `AddGroupMemberUseCase` | `groupId`, `AddGroupMemberCommand` | `GroupMemberResult` |
| 소유권 이전 | `TransferGroupOwnershipUseCase` | `groupId`, `TransferGroupOwnershipCommand` | 없음 |
| 자기 탈퇴 | `LeaveGroupUseCase` | `groupId` | 없음 |
| 멤버 제거 | `RemoveGroupMemberUseCase` | `groupId`, `memberId` | 없음 |

`JoinGroupCommand`:

```text
inviteCode: String
```

`AddGroupMemberCommand`:

```text
publicId: String
```

`TransferGroupOwnershipCommand`:

```text
targetMemberId: Long
```

`GroupMemberResult`:

```text
memberId: Long
publicId: String
name: String
role: OWNER | MEMBER
joinedAt: LocalDateTime
```

- 결과 시각은 서버 내부 UTC 값을 공통 JSON 시각 규칙으로 직렬화한다.
- 내부 `userId`, 멤버 상태 이력과 `leftAt`은 활성 멤버 목록 응답에 노출하지 않는다.

### 3.2 Output Port

- `LoadGroupPort`: ID·초대 코드로 활성 그룹 조회
- `LoadGroupMemberPort`: 그룹·사용자 멤버십, member ID, 활성 OWNER와 활성 멤버 목록 조회
- `SaveGroupMemberPort`: 신규 멤버 저장, 재활성화, `LEFT`·`REMOVED` 전이, 역할 교체 저장
- `SaveGroupPort`: 소유권 이전 시 `Group.ownerId` 갱신
- `LoadUserPort`: 현재 사용자와 정확한 `publicId`의 활성 사용자 조회
- `LoadGroupMemberSettlementStatePort`: 그룹·사용자의 그룹 출처 정산 상태 집합 조회

`LoadGroupMemberSettlementStatePort`는 자기 탈퇴와 OWNER 제거를 구현할 때 실제 호출자와 정산 조회가 함께 추가된다. 선행 구현에서 빈 Port나 no-op Adapter를 만들지 않는다.

### 3.3 `GroupMemberExitPolicy`

Service가 다음 값을 준비해 Policy에 전달한다.

- 대상 멤버 역할·상태
- 요청 종류: `SELF_LEAVE`, `OWNER_REMOVE`
- 관련 그룹 출처 정산의 `SettlementStatus` 집합

Policy 규칙:

- 대상이 활성 MEMBER가 아니면 종료할 수 없다.
- `SELF_LEAVE` 요청자가 OWNER면 `GROUP_OWNER_TRANSFER_REQUIRED`다.
- `OWNER_REMOVE` 대상은 요청자 자신 또는 현재 OWNER일 수 없다.
- 상태 집합에 `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED` 중 하나가 있으면 종료를 차단한다.
- `DRAFT`, `COMPLETED`만 있거나 관련 정산이 없으면 허용한다.
- 허용 후 Domain이 자기 탈퇴는 `LEFT`, OWNER 제거는 `REMOVED`로 전이한다.

Policy는 Output Port, JPA, HTTP, Infra 설정에 의존하지 않는다.

## 4. 가입과 직접 추가 규칙

### 4.1 초대 코드 가입

1. 입력을 strip하고 대문자로 변환한다.
2. `^[A-Z0-9]{8}$` 형식이 아니면 `INVALID_GROUP_INVITE_CODE`다.
3. 코드에 해당하는 `ACTIVE` 그룹을 조회한다.
4. 그룹·현재 사용자 멤버십을 쓰기 잠금으로 조회한다.
5. 상태에 따라 신규 생성, `LEFT` 재활성화 또는 거절한다.

| 기존 멤버십 | 결과 |
| --- | --- |
| 없음 | `ACTIVE MEMBER` 생성 |
| `ACTIVE` | `GROUP_MEMBER_ALREADY_ACTIVE` |
| `LEFT` | `ACTIVE MEMBER` 재활성화 |
| `REMOVED` | `GROUP_MEMBER_REMOVED` |

### 4.2 OWNER 직접 추가

1. 요청자의 활성 OWNER 권한을 확인한다.
2. 기존 `PublicIdPolicy`로 공개 ID를 정규화한다.
3. 정확히 일치하는 활성 사용자를 조회한다.
4. 대상 멤버십을 쓰기 잠금으로 조회한다.

| 기존 멤버십 | 결과 |
| --- | --- |
| 없음 | `ACTIVE MEMBER` 생성 |
| `ACTIVE` | `GROUP_MEMBER_ALREADY_ACTIVE` |
| `LEFT` | `GROUP_MEMBER_LEFT_REQUIRES_INVITE` |
| `REMOVED` | `ACTIVE MEMBER` 복구 |

- 자기 자신은 이미 활성 OWNER이므로 중복 활성 오류로 처리한다.
- 공개 ID에 해당하는 활성 사용자가 없으면 `GROUP_MEMBER_CANDIDATE_NOT_FOUND`다.
- 직접 추가는 알림이나 승인 요청을 발송하지 않는다.

## 5. 멤버 목록 규칙

- 그룹과 요청자 모두 `ACTIVE`여야 한다.
- `ACTIVE` 멤버만 반환한다.
- OWNER를 첫 번째로, 나머지는 `joinedAt ASC`, 동률이면 `memberId ASC`로 정렬한다.
- `publicId`, 이름은 현재 활성 User 값을 사용한다.
- 사용자 자체가 비활성화된 예외 데이터는 목록에서 숨기지 않고 멤버십 정합성 오류로 관찰 가능하게 처리하며, 별도 사용자 정리 정책 없이 자동 탈퇴시키지 않는다.

## 6. 소유권 이전과 동시성

1. 요청자가 현재 활성 OWNER인지 확인한다.
2. 그룹 row를 쓰기 잠금한다.
3. 대상 `memberId`가 같은 그룹의 활성 MEMBER인지 확인한다.
4. `Group.ownerId`를 대상 user ID로 변경한다.
5. 기존 OWNER 멤버십을 MEMBER로, 대상을 OWNER로 변경한다.
6. 그룹과 두 멤버십을 같은 트랜잭션으로 저장한다.

- 대상이 요청자 자신, 비활성 멤버, 이미 OWNER 또는 다른 그룹 멤버면 `GROUP_OWNER_TRANSFER_TARGET_INVALID`다.
- Persistence 저장은 활성 OWNER partial unique를 일시적으로 위반하지 않도록 기존 OWNER 강등과 대상 승격을 하나의 ownership transfer capability로 반영한다.
- 동시 소유권 이전은 그룹 row lock으로 직렬화한다.
- 그룹 출처 정산을 `DRAFT`에서 발행할 때도 관련 그룹 row를 같은 경계로 잠그고 참여자의 활성 멤버십을 재검증해, 정산 요청과 멤버 종료 중 한쪽만 먼저 커밋할 수 있게 한다.
- 응답 성공 시점에는 `groups.owner_id`와 활성 OWNER 멤버십이 같은 사용자를 가리켜야 한다.

## 7. Web API 계약

모든 endpoint는 bearer 인증이 필요하고 공통 `ApiResponse`를 사용한다.

### 7.1 초대 코드 가입

```http
POST /api/v2/groups/join
Content-Type: application/json

{ "inviteCode": "AB12CD34" }
```

- 성공: `200 OK`, `GroupDetailWebResponse`
- 재가입 성공도 같은 응답을 사용한다.

### 7.2 멤버 목록

```http
GET /api/v2/groups/{groupId}/members
```

- 성공: `200 OK`, `List<GroupMemberWebResponse>`
- 페이지네이션 없이 활성 멤버 전체를 반환한다.

### 7.3 OWNER 직접 추가

```http
POST /api/v2/groups/{groupId}/members
Content-Type: application/json

{ "publicId": "sua.choi" }
```

- 성공: `201 Created`, `GroupMemberWebResponse`
- 신규 추가와 `REMOVED` 복구가 같은 endpoint를 사용한다.

### 7.4 소유권 이전

```http
PUT /api/v2/groups/{groupId}/owner
Content-Type: application/json

{ "targetMemberId": 42 }
```

- 성공: `200 OK`, 빈 성공 응답

### 7.5 자기 탈퇴

```http
DELETE /api/v2/groups/{groupId}/members/me
```

- 성공: `200 OK`, 빈 성공 응답
- 성공 후 현재 사용자는 그룹 상세·멤버 목록에 접근할 수 없다.

### 7.6 OWNER 멤버 제거

```http
DELETE /api/v2/groups/{groupId}/members/{memberId}
```

- 성공: `200 OK`, 빈 성공 응답
- 경로의 `memberId`는 내부 user ID가 아니라 그룹 멤버십 ID다.

## 8. 오류 계약

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `INVALID_GROUP_INVITE_CODE` | 400 | 초대 코드 형식 오류 |
| `GROUP_NOT_FOUND` | 404 | 코드·ID에 해당하는 활성 그룹 없음 |
| `GROUP_MEMBER_NOT_FOUND` | 404 | 대상 멤버십 없음 또는 다른 그룹 |
| `GROUP_MEMBER_CANDIDATE_NOT_FOUND` | 404 | 공개 ID 활성 사용자 없음 |
| `GROUP_ACCESS_DENIED` | 403 | 활성 멤버가 아님 |
| `GROUP_OWNER_REQUIRED` | 403 | OWNER 전용 요청 |
| `GROUP_MEMBER_ALREADY_ACTIVE` | 409 | 이미 활성 멤버 |
| `GROUP_MEMBER_REMOVED` | 409 | 제거된 사용자의 초대 코드 재가입 |
| `GROUP_MEMBER_LEFT_REQUIRES_INVITE` | 409 | LEFT 사용자를 OWNER가 강제 복구하려 함 |
| `GROUP_OWNER_TRANSFER_REQUIRED` | 409 | OWNER가 이전 없이 탈퇴하거나 자신을 제거하려 함 |
| `GROUP_OWNER_TRANSFER_TARGET_INVALID` | 409 | 잘못된 소유권 이전 대상 |
| `GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT` | 409 | 미완료 그룹 출처 정산 의무 존재 |

- 삭제 그룹은 존재 여부를 노출하지 않고 `GROUP_NOT_FOUND`로 처리한다.
- 비OWNER가 OWNER endpoint를 호출할 때는 대상 멤버 존재 여부보다 권한을 먼저 판단한다.

## 9. 테스트 시나리오

### 가입·추가

- 초대 코드 trim·대소문자 정규화와 잘못된 형식
- 신규·ACTIVE·LEFT·REMOVED 각각의 초대 가입 결과
- 신규·ACTIVE·LEFT·REMOVED 각각의 직접 추가 결과
- 재가입·복구 시 `joinedAt`, `leftAt`, 역할 갱신
- 비OWNER 직접 추가와 삭제 그룹 차단
- 동시 가입·직접 추가에서 그룹·사용자 unique 유지

### 목록·권한

- 활성 멤버만 반환하고 OWNER 우선 정렬
- MEMBER의 조회 허용과 OWNER 전용 작업 거절
- 비멤버·LEFT·REMOVED의 조회 차단
- 응답에 내부 user ID와 종료 상태가 노출되지 않음

### 소유권·종료

- 정상 소유권 이전과 `groups.owner_id`·멤버 역할 일치
- 자기 자신·비활성·다른 그룹 대상 이전 거절
- 동시 이전에서 활성 OWNER 한 명 유지
- OWNER 이전 없는 자기 탈퇴 거절
- MEMBER 자기 탈퇴 `LEFT`, OWNER 제거 `REMOVED`
- `DRAFT`, `COMPLETED`, 관련 정산 없음은 종료 허용
- `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED`는 두 종료 경로 모두 차단

## 10. 제외와 후속

- 멤버 초대 승인, 알림, 차단 해제 요청은 구현하지 않는다.
- OWNER가 그룹을 삭제하는 작업은 멤버 종료 Policy를 호출하지 않는다. 삭제는 기록을 유지한 채 그룹 전체의 신규 접근을 닫는 별도 흐름이다.
- 정산 상태 조회 쿼리와 legacy 그룹 출처 데이터 호환은 [그룹-정산 연동 Spec](group-settlement-integration-spec.md)을 따른다.
- 구현 순서와 PR 경계는 [그룹 관리 Feature Plan](group-management.md)을 따른다.

## 11. 구현 결과

- #149 범위의 초대 코드 가입, 활성 멤버 목록과 OWNER 직접 추가 API를 구현했다.
- 초대 코드는 바깥 공백 제거와 대문자 정규화 후 8자리 영숫자 형식을 검증한다.
- 신규 멤버십 생성, `LEFT` 초대 코드 재가입, `REMOVED` OWNER 직접 복구와 상태별 거절을 구현했다.
- 멤버 목록은 활성 멤버만 OWNER 우선, `joinedAt ASC`, `memberId ASC` 순으로 반환하고 현재 User의 `publicId`와 이름을 결합한다.
- 그룹·사용자 멤버십 unique 충돌은 `GROUP_MEMBER_ALREADY_ACTIVE`로 변환하고 기존 멤버십 변경은 쓰기 잠금을 사용한다.
- Domain, Policy, Application, Controller 단위 테스트와 PostgreSQL 영속 통합 테스트를 추가했다.
- #151 범위의 소유권 이전, 자기 탈퇴와 OWNER 멤버 제거 API를 구현했다.
- 소유권 이전은 그룹 row lock으로 직렬화하고 `TransferGroupOwnershipPort`가 기존 OWNER 강등을 먼저 flush한 뒤 대상 MEMBER 승격과 `groups.owner_id` 변경을 한 트랜잭션에서 반영한다.
- 자기 탈퇴와 OWNER 제거는 같은 `GroupMemberExitPolicy`와 `LoadGroupMemberSettlementStatePort`를 사용하며 각각 `LEFT`, `REMOVED` 상태를 기록한다.
- OWNER의 이전 없는 탈퇴·자기 제거와 `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED` 정산이 있는 종료를 차단한다.
- 정산 요청이 관련 그룹 row를 잠그고 그룹 출처 참여자를 재검증해 `DRAFT` 조회와 `REQUESTED` 전이 사이의 종료 경쟁을 차단한다.
- Domain·Application·Controller 단위 테스트와 PostgreSQL 소유권 동시성 통합 테스트를 추가했다.
