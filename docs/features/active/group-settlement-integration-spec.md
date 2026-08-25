# 그룹-정산 연동 Backend Spec

## 1. 목적과 범위

이 문서는 현재 정산 모듈의 그룹 명단 Stub을 실제 그룹멤버 Persistence로 교체하고, 그룹에서 선택한 정산 참여자에 출처 그룹 ID를 보존하며, 그룹 멤버 종료 시 정산 상태를 조회하는 계약을 정의한다.

관련 문서와 이슈:

- [그룹 관리 PRD](../../product/group-prd.md)
- [그룹 멤버십·권한 Spec](group-membership-spec.md)
- [#143 그룹-정산 연동 Spec 작성](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/143)
- [#150 실제 그룹 구성원 기반 정산 참여자 연동](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/150)
- [#152 정산 그룹 출처 무결성 제약 강화](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/152)

## 2. 현재 구현과 문제

### 현재 흐름

```text
SettlementParticipantService
  -> LoadGroupMembersPort.findActiveRoster(groupId)
  -> GroupMemberRoster(groupId, activeUserIds)
  -> creator가 roster에 속하는지 확인
  -> 이미 추가된 user/phone hash 제외
  -> GROUP_MEMBER SettlementParticipant 생성
```

- `local`, `dev`의 `LocalGroupMembersStubAdapter`는 `[groupId, groupId + 1, groupId + 2]`를 반환한다.
- `prod`의 `UnavailableGroupMembersAdapter`는 항상 empty를 반환한다.
- 그룹 후보 endpoint와 `AddSettlementParticipantCommand`에는 이미 `groupId`가 존재한다.
- `ResolvedParticipant`가 `GROUP_MEMBER`를 만들 때 `groupId`를 버려 `settlement_participants`에는 출처 그룹을 저장하지 않는다.
- `SettlementParticipant`, JPA Entity와 V9 스키마에는 `source_group_id`가 없다.

### 해결해야 하는 문제

- 환경별 Stub이 실제 그룹 가입·탈퇴·제거 상태를 반영하지 않는다.
- 같은 사용자가 여러 그룹에 속하면 정산 참여자가 어느 그룹에서 선택되었는지 알 수 없다.
- 그룹멤버 탈퇴·제거 시 해당 그룹에서 시작된 미완료 정산 의무를 조회할 수 없다.
- migration과 애플리케이션을 한 번에 배포할 수 없으므로 구버전 writer와 기존 `GROUP_MEMBER` 데이터 호환이 필요하다.

## 3. 목표 데이터 계약

### 3.1 `SettlementParticipant`

다음 필드를 추가한다.

```text
sourceGroupId: Long?
```

최종 불변식:

| `source` | `linkedUserId` | `phoneHash` | `sourceGroupId` |
| --- | --- | --- | --- |
| `REGISTERED_USER` | 필수 | null | null |
| 연결된 `CONTACT` | 필수 | null | null |
| 미연결 `CONTACT` | null | 필수 | null |
| `GROUP_MEMBER` | 필수 | null | 필수·양수 |

- `groupMember` factory는 `groupId`를 필수로 받는다.
- 다른 factory는 `sourceGroupId=null`을 사용한다.
- `isLinkedTo(userId)`와 기존 중복 참여자 규칙은 유지한다.
- 현재 참여자 목록 응답에는 `sourceGroupId`를 새로 노출하지 않는다. 저장·정책 연결용 내부 출처로 사용한다.

### 3.2 Persistence

`settlement_participants`에 다음을 추가한다.

```sql
source_group_id BIGINT NULL
FOREIGN KEY (source_group_id) REFERENCES groups(id)
```

- 그룹은 소프트 삭제되므로 `ON DELETE CASCADE`를 사용하지 않는다.
- 종료 정책 조회를 위해 `(source_group_id, linked_user_id, source)` index를 둔다.
- 최종 단계에는 source와 group ID 일치 CHECK를 추가한다.

```sql
CHECK (
    (source = 'GROUP_MEMBER' AND source_group_id IS NOT NULL)
    OR
    (source <> 'GROUP_MEMBER' AND source_group_id IS NULL)
)
```

## 4. 실제 그룹 roster 연동

### 4.1 `LoadGroupMembersPort`

기존 계약을 유지한다.

```java
Optional<GroupMemberRoster> findActiveRoster(Long groupId);
```

실제 GroupMember Persistence Adapter 동작:

- 그룹이 없거나 `DELETED`면 `Optional.empty()`
- 그룹이 `ACTIVE`면 `ACTIVE` 멤버 user ID 전체를 반환
- OWNER를 첫 번째로, 나머지는 `joinedAt ASC`, 동률이면 member ID 순으로 반환
- `LEFT`, `REMOVED` 멤버 제외
- 비활성 User 행이 있더라도 roster는 멤버십 사실만 반환하고, Settlement Service의 기존 활성 User 필터가 후보 노출을 막는다.

### 4.2 Adapter 교체

- `LocalGroupMembersStubAdapter` 제거
- `UnavailableGroupMembersAdapter` 제거
- 모든 profile에서 실제 GroupMember Persistence Adapter를 사용
- 별도 Infra Config나 profile별 no-op Bean을 만들지 않음
- 기존 `GroupMemberRoster`와 `LoadGroupMembersPort`를 settlement 전용 새 Repository Port로 중복 생성하지 않음

### 4.3 후보 조회

기존 endpoint를 유지한다.

```http
GET /api/v2/settlements/{settlementId}/participant-candidates/groups/{groupId}/members
```

검증 순서:

1. 현재 사용자가 정산 생성자인지 확인한다.
2. 정산이 `DRAFT`여서 참여자를 수정할 수 있는지 확인한다.
3. 실제 활성 그룹과 roster를 조회한다.
4. 정산 생성자가 roster에 속하는지 확인한다.
5. 이미 정산에 포함된 linked user와 contact identity를 제외한다.
6. 활성 User 정보가 있는 후보만 현재 roster 순서로 반환한다.

- 삭제 그룹은 후보 없음이 아니라 기존 계약과 동일하게 그룹을 찾지 못한 결과로 처리한다.
- 활성 그룹의 빈 후보 목록은 정상 `200 []`다.

### 4.4 참여자 추가

기존 endpoint와 요청 shape를 유지한다.

```http
POST /api/v2/settlements/{settlementId}/participants

{
  "source": "GROUP_MEMBER",
  "groupId": 10,
  "publicId": "sua.choi"
}
```

`SettlementParticipantService.resolveGroupMember`는 roster와 User 검증을 마친 뒤 `ResolvedParticipant`에 `sourceGroupId`를 보존한다. `ResolvedParticipant.create`는 다음 factory를 호출한다.

```text
SettlementParticipant.groupMember(
    settlementId,
    groupId,
    linkedUserId,
    displayName,
    displayOrder,
    now
)
```

- 그룹 후보 조회와 추가 사이에 대상이 탈퇴·제거되면 추가 시점의 roster 재검증으로 거절한다.
- 기존 settlement row lock, 참여자 중복, 표시 순서와 allocation 무효화 규칙은 유지한다.

## 5. 멤버 종료용 정산 상태 조회

### 5.1 Output Port

그룹 Application에 실제 호출자와 함께 다음 Port를 추가한다.

```java
public interface LoadGroupMemberSettlementStatePort {
    Set<SettlementStatus> findStatuses(Long groupId, Long userId);
}
```

- 반환값은 정산 상태라는 외부 사실이며 차단 여부를 판단하지 않는다.
- 차단 상태 해석은 `GroupMemberExitPolicy`가 담당한다.
- Adapter는 settlement Persistence package에 위치하고 그룹 Application Port를 구현한다.

### 5.2 조회 기준

```text
settlement_participants.source = GROUP_MEMBER
AND settlement_participants.source_group_id = :groupId
AND settlement_participants.linked_user_id = :userId
```

위 참여자가 속한 `settlements.status`의 distinct 집합을 반환한다.

- `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED`가 하나라도 있으면 Policy가 종료를 차단한다.
- `DRAFT`, `COMPLETED`만 있거나 결과가 비어 있으면 허용한다.
- 그룹 삭제 여부와 무관하게 기존 정산의 출처 사실은 유지한다.
- 정산 참여자를 멤버 종료 시 삭제하거나 source를 변경하지 않는다.

## 6. 2단계 Migration과 배포 호환

저장소 Flyway gate에 따라 두 migration은 각각 SQL-only PR로 분리한다.

### 6.1 1차 migration: 그룹 스키마와 nullable 출처

1. 최신 `origin/develop`과 열린 migration PR을 확인해 버전을 선택한다.
2. `groups`, `group_members`를 생성한다.
3. `settlement_participants.source_group_id`를 nullable로 추가한다.
4. FK와 조회 index를 추가한다.
5. migration 시점에 존재하는 `GROUP_MEMBER/source_group_id NULL` 행은 출처를 확인할 실제 그룹이 없으므로 `REGISTERED_USER`로 정규화한다.
6. 구버전 writer 호환을 위해 source/group 일치 CHECK는 아직 추가하지 않는다.

구버전 애플리케이션은 1차 migration 후에도 새 `GROUP_MEMBER/null` 행을 만들 수 있다. 따라서 새 애플리케이션 Adapter는 전환 기간에 다음 legacy read를 허용한다.

```text
JpaEntity(source=GROUP_MEMBER, sourceGroupId=null)
  -> Domain(source=REGISTERED_USER, sourceGroupId=null)
```

- 이 변환은 실제 그룹을 추측하지 않는다.
- legacy 행은 그룹 종료 정책 조회 대상이 아니다.
- 새 애플리케이션이 만드는 `GROUP_MEMBER`에는 항상 group ID가 존재한다.

### 6.2 애플리케이션 배포

- 실제 roster Adapter와 `sourceGroupId` 쓰기를 배포한다.
- 새 writer가 group 출처에 ID를 저장하는지 DB 통합 테스트와 환경 관찰로 확인한다.
- 구버전 writer가 더 이상 실행되지 않는 배포 호환 시점을 확인한다.

### 6.3 2차 migration: strict constraint

1. 배포 사이에 생긴 남은 `GROUP_MEMBER/null` 행을 `REGISTERED_USER`로 정규화한다.
2. 예상하지 않은 `source <> GROUP_MEMBER/source_group_id NOT NULL` 행이 없는지 검증한다.
3. source/group 일치 CHECK를 추가한다.
4. 제약 검증 후 migration-only PR을 종료한다.

- 2차 migration은 #150이 병합된 것만으로 시작하지 않고 해당 애플리케이션 버전의 배포 호환을 확인한 뒤 수행한다.
- `source_group_id` 컬럼 자체는 GROUP_MEMBER 외 행 때문에 nullable로 유지한다.

## 7. Mapping과 Adapter 오류 처리

- JPA Entity에 `sourceGroupId`를 updatable=false로 추가한다.
- Mapper는 정상 새 행의 source와 group ID를 그대로 Domain에 전달한다.
- legacy `GROUP_MEMBER/null`만 `REGISTERED_USER/null`로 안전하게 정규화한다.
- 양수 아닌 group ID, 비그룹 source의 group ID는 Domain에서 `INVALID_SETTLEMENT_PARTICIPANT_IDENTITY`로 거절한다.
- FK·CHECK 위반은 원인을 보존해 정산 참여자 추가 실패로 변환하고 raw SQL 정보나 내부 ID를 응답하지 않는다.
- 기존 REGISTERED_USER, CONTACT mapping과 unique 제약은 변경하지 않는다.

## 8. 공개 API와 호환성

- 그룹 후보 조회 경로와 요청/응답은 변경하지 않는다.
- 그룹 참여자 추가 요청의 기존 `groupId`, `publicId` 필드를 유지한다.
- 참여자 응답의 `source=GROUP_MEMBER`를 유지한다.
- `sourceGroupId`는 이번 범위에서 응답에 추가하지 않는다.
- local/dev에서 Stub ID를 사용하던 테스트는 실제 그룹 fixture를 생성하는 Persistence/Web 통합 테스트로 교체한다.

## 9. 테스트 시나리오

### 실제 roster

- 활성 그룹의 OWNER·MEMBER 순서 반환
- `LEFT`, `REMOVED`, 삭제 그룹 제외
- 정산 생성자가 그룹 비멤버이면 `SETTLEMENT_GROUP_ACCESS_DENIED`
- 후보 조회와 추가 사이 탈퇴한 멤버 추가 거절
- local/dev/prod에서 동일 실제 Adapter 사용

### 참여자 출처

- 새 GROUP_MEMBER 참여자에 group ID 저장·복원
- REGISTERED_USER·CONTACT의 group ID null 유지
- 다른 그룹에 속한 공개 ID 추가 거절
- 같은 user의 기존 중복 참여자 규칙 유지
- legacy GROUP_MEMBER/null을 REGISTERED_USER로 읽기

### 종료 상태 조회

- 같은 그룹·같은 user의 group 출처 참여자만 조회
- 다른 그룹, 다른 user, REGISTERED_USER 출처 제외
- 각 `SettlementStatus` distinct 반환
- 삭제 그룹의 기존 정산도 출처 조회 가능

### Migration

- V1~현재 migration 위에 1차 migration 적용
- 기존 GROUP_MEMBER/null 정규화
- 구버전 shape insert가 1차 단계에서 허용되는지 확인
- 새 writer의 GROUP_MEMBER/group ID 저장 확인
- 2차 backfill 뒤 strict CHECK 적용
- GROUP_MEMBER/null과 non-group/non-null 저장 거절
- 기존 정산 참여자·Share·링크·송금 안내 회귀 없음

## 10. 완료 기준

- 모든 profile에서 정산 후보가 실제 활성 그룹멤버를 사용한다.
- 그룹 참여자 추가 시 `groupId`가 Web 요청부터 DB까지 유실되지 않는다.
- 자기 탈퇴와 OWNER 제거가 같은 정산 상태 사실을 조회한다.
- migration 전환 중 구버전 writer와 legacy 데이터 때문에 애플리케이션 기동·조회가 깨지지 않는다.
- 2차 migration 후 DB가 최종 source/group 불변식을 강제한다.
- 기존 비그룹 정산 참여자 흐름과 공개 API가 호환된다.
