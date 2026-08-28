# Feature Plan: 정산 조회 클라이언트 계약 보정

- Status: Implemented (pending merge)
- Last updated: 2026-08-29
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

정산 목록에서 납부 대상 Share가 없으면 `completedParticipantCount`가 `null`이 되어 앱 문구에 `null명`이 노출된다. 또한 `SettlementListItemWebResponse`와 `SettlementDetailWebResponse`의 OpenAPI schema에 필수 필드와 nullable 필드가 구분되지 않는다.

정산에는 대표 그룹이 없고 참여자마다 `source_group_id`가 저장되며 복수 그룹 출처를 허용한다. 기존 출처를 사용한 `groupId` 필터와 `groups[]` 응답으로 실제 저장 구조를 그대로 표현한다.

### Success criteria

- [x] RECEIVE 목록의 `completedParticipantCount`는 완납자가 없거나 Share가 없어도 `0` 이상 정수다.
- [x] 목록·상세 OpenAPI schema가 모든 응답 속성의 존재 여부와 nullable 여부를 구분한다.
- [x] `groupId`가 전달되면 해당 그룹 출처 참여자가 있는 정산만 반환한다.
- [x] 각 정산은 모든 그룹 출처를 `groups[]`의 `groupId`, `groupName`, `groupImageUrl`로 반환한다.
- [x] 기존 RECEIVE/PAY 탭과 상태 필터 동작은 유지한다.

### Out of scope

- 단일 대표 그룹을 임의로 선정하는 응답
- 정산 생성 시 대표 `group_id`를 저장하는 schema/API 변경

## 2. Domain analysis

- 기존 정산 목록 Application/영속 조회 계약을 재사용한다.
- 상태 전이와 Domain 규칙은 변경하지 않는다.
- `completedParticipantCount = 0`은 현재 확인된 완납자가 없다는 UI 의미로 통일한다.

## 3. Scenarios

1. 로그인 사용자가 RECEIVE 정산 목록을 조회한다.
2. 납부 대상 Share 또는 확인 입금이 없으면 서버가 `completedParticipantCount = 0`을 반환한다.
3. 정산 결과 ID 목록으로 그룹 메타데이터를 한 번 더 조회해 `groups[]`를 조립한다.
4. 클라이언트는 OpenAPI의 required/nullable 정보를 사용해 응답 타입을 생성한다.

## 4. Contracts

```text
GET /api/v2/settlements?tab=RECEIVE&status=IN_PROGRESS&groupId=10
SettlementListWebRequest -> GetSettlementsCommand
SettlementListItemResult -> SettlementListItemWebResponse
```

- `SettlementListItemWebResponse`의 모든 JSON 속성은 required다.
- `groupId`는 선택 query parameter이며 해당 그룹 출처 참여자가 있는 정산만 남긴다.
- `groups`는 required 배열이며 비그룹 정산은 `[]`다. 그룹 이미지를 보유한 경우에만 `groupImageUrl=/api/v2/groups/{groupId}/image`를 반환한다.
- 탭에 따라 값이 없는 `totalAmount`, `participantCount`, `completedParticipantCount`, `amountToPay`, `requesterName`은 nullable로 문서화한다.
- `SettlementDetailWebResponse`의 모든 속성은 required이며 DRAFT의 `allocationType`과 Share 생성 전 `ParticipantPayment.expectedAmount`는 nullable이다.
- Flyway migration required: No

## 5. Impact surface

| Area | Impact |
|---|---|
| API | 목록 0 반환, 선택 `groupId` 필터, `groups[]`, 목록·상세 OpenAPI required/nullable |
| Application | 목록 Command에 선택 `groupId` 전달, 그룹 요약 Result 조립 |
| Persistence | projection null을 RECEIVE의 0으로 변환하고 그룹 출처 필터·메타데이터 조회 |
| Documentation | 기존 목록 제한 설명 갱신 |
| Test | 영속 조회, Controller, OpenAPI schema |

## 6. Implementation plan

- [x] Migration-only pull request: N/A
- [x] Output Adapter의 완료 인원 기본값 보정
- [x] 기존 참여자 그룹 출처를 사용하는 목록 필터 추가
- [x] 정산 결과 전체의 그룹 메타데이터를 두 번째 일괄 쿼리로 조회
- [x] `groups[]` Web 응답과 이미지 URL 매핑
- [x] Web Response OpenAPI schema 보정
- [x] 관련 문서와 테스트 갱신

### Expected commit boundaries

```text
fix(settlement): 정산 조회 클라이언트 계약 보정
```

## 7. Verification

```bash
./gradlew test --tests '*SettlementListPersistenceIntegrationTest' --tests '*SettlementControllerTest' --tests '*SettlementOpenApiIntegrationTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

## 8. Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-08-29 | Share가 없는 RECEIVE도 완료 인원 0 반환 | 앱은 표시용 완료 인원을 항상 정수로 사용한다. |
| 2026-08-29 | 그룹 응답은 `groups[]`로 제공 | 현재 저장 구조의 복수 그룹 출처를 손실 없이 표현하며 migration이 필요 없다. |

## 9. Verification results

| Item | Result | Evidence |
|---|---|---|
| Compile | Passed | `./gradlew compileJava` |
| Controller, Service, OpenAPI schema | Passed | 관련 테스트 30건 |
| PostgreSQL 목록 집계 | Passed | 임시 PostgreSQL 18에서 완료 인원·그룹 필터·`groups[]` 검증 |
| 전체 회귀 테스트 | Passed | 임시 PostgreSQL 18·Redis 7에서 481 tests, 0 failures |
| Diff | Passed | `git diff --check` |

## 10. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-29 | 완료 인원 기본값, OpenAPI 계약, `groupId` 필터, `groups[]` 구현 | PostgreSQL 재검증 | 그룹이 삭제되면 이름은 유지하고 삭제 시 이미지가 제거되어 URL은 null이 됨 |
