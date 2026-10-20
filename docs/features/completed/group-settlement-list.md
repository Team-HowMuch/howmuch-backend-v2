# Feature Plan: 그룹 전체 정산 목록

- Status: Completed
- Last updated: 2026-10-20
- Related issue: [#598](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/598)
- Related ADR: 기존 Application/Port/Adapter 경계 유지
- 기준: `origin/develop` `b7f7c3ad` (최초 분석 기준 `92ec9799` 이후 #601/#602 반영)

## 1. Purpose

그룹 상세에서 RECEIVE/PAY를 합치거나 정산별 상세를 추가 호출하지 않고, 참여하지 않은 제3자 정산까지 한 목록으로 표시한다.

### Success criteria

- [x] 활성 그룹원만 조회 가능하며 탈퇴·강퇴·비회원과 비활성 그룹은 거절한다.
- [x] 그룹 출처 참여자가 있는 정산 전체를 기존 상태 필터와 요청 시각 내림차순·ID 내림차순으로 반환한다.
- [x] DRAFT·삭제된 정산·압축에 포함된 원본을 제외한다.
- [x] `viewerRole`은 REQUESTER / PARTICIPANT / NONE이며 요청자가 우선한다.
- [x] 모든 항목에 요청자 이름/publicId, 금액·인원·상호명·요청자 카테고리를 제공한다.
- [x] `receivableAmount`·`reminderNextAvailableAt`은 REQUESTER, `amountToPay`는 PARTICIPANT에게만 제공한다. NONE은 세 필드 모두 null이다.
- [x] 송금 계좌번호·전화번호·인증 정보는 공개하지 않는다.
- [x] 기존 개인 목록·상세 계약과 전체 로컬 테스트를 유지한다.

Out of scope: #599/#600, 새 저장 구조, cursor/page 도입, 송금·독촉 권한 확대, 모바일 UI·운영 배포.

## 2. Domain analysis

기존 GroupAccessService로 활성 그룹과 멤버십을 검증한다. 그룹 출처는 `GROUP_MEMBER` 참여자의 `source_group_id`로 판정하며, 조회자 본인이 해당 정산의 참여자일 필요는 없다. REQUESTER는 creator 일치, PARTICIPANT는 linked participant 존재, 나머지는 NONE이다. 최종 더치페이의 0원 참여자도 역할은 PARTICIPANT이며 납부액은 0원이다.

## 3. Scenarios

1. 현재 회원의 그룹 접근 권한을 먼저 검증한다.
2. 기존 정산 목록 쿼리의 명시적 그룹 조회 경로로 모든 원본을 읽는다. 기존 개인 경로의 소유·참여 조건은 그대로 유지한다.
3. 요청자 역할인 항목만 기존 받을 금액 계산기와 독촉 TTL Reader로 보강한다.
4. 요청자의 지정 카테고리 → 자동 분류 카테고리를 활성 카탈로그와 대조하고 없거나 유효하지 않으면 UNCATEGORIZED로 표시한다.
5. 역할별 개인 필드를 제한한 새 그룹 목록 응답을 반환한다.

빈 그룹·기본 IN_PROGRESS·COMPLETED·ALL·잘못된 필터·미인증·멤버십 상실·다른 그룹 출처·0원·미확정 품목 금액·중단·압축 원본 제외를 검증한다. 기존 정산 상세와 송금 API의 당사자 권한은 확대하지 않는다.

## 4. Contracts

### Input Port and mapping

`GetGroupSettlementsUseCase.get(GetGroupSettlementsCommand)` → `List<GroupSettlementListItemResult>`.
`GroupSettlementListWebRequest.toCommand(groupId)` → Command → Service → Result → `GroupSettlementListItemWebResponse.from(result)`.

### Output Port and query

`LoadGroupSettlementListPort`는 실제 그룹 목록 호출용이다. 기존 SettlementPersistenceAdapter가 기존 목록 projection을 재사용해 구현한다. 원시 ReadModel은 공통 목록 사실과 요청자 ID·publicId·카테고리 코드·조회자 참여 여부만 추가한다. 카테고리/그룹/계좌를 항목마다 추가 조회하지 않는다.

### Web API

`GET /api/v2/groups/{groupId}/settlements?status=IN_PROGRESS`.
상태 필터는 기존 `SettlementListStatusFilter`를 사용하며 기본 IN_PROGRESS, 그룹 목록에서 DRAFT는 항상 제외한다. 무효 enum과 0 이하 그룹 ID는 400, 미인증은 401, 그룹 접근 거부는 기존 403, 없는 그룹은 기존 404 계약을 유지한다. 초기 버전은 기존 목록처럼 페이지네이션 없는 배열이다.

### Persistence

- Flyway migration required: **No**. 기존 그룹 출처·참여자·카테고리·입금·Redis TTL 사용.
- 기존 `findAllForUser` 호출과 반환 계약은 유지한다. 공유 SQL의 별도 그룹 경로는 groupId가 있어야만 활성화되며 SQL에서도 활성 그룹원 조건을 적용한다.
- 새 Response에는 계좌·송금 정보가 없다. 기존 개인 `myCategory` 의미를 바꾸지 않도록 그룹용 `category` DTO를 사용한다.

## 5. Design decisions

기존 개인 목록 SQL을 공유해 상태·인원·입금·상호명·압축 제외 규칙이 갈라지지 않게 한다. 그룹 여부는 명시적 query 인자이고 개인 조회의 기존 메서드는 그룹 범위를 false로 고정한다. Controller는 actor를 해석하지 않고 UseCase만 호출한다. 역할별 금액·쿨다운 조립과 카테고리 fallback은 Application 책임이다.

## 6. Impact surface

| Area | Impact |
|---|---|
| Web | 별도 그룹 정산 Controller/Docs/Request/Response |
| Application | 그룹 목록 UseCase·Command·Result·Service, 실제 호출되는 Output Port |
| Persistence | 기존 Adapter와 공유 목록 query/projection의 그룹 경로 |
| Domain/Security/Infra/Schema | 새 변경 없음, 기존 그룹 권한과 계산기 재사용 |
| Tests | 기존 목록·그룹·OpenAPI/HTTP·압축 회귀를 확장 |

## 7. Implementation plan

- [x] Migration-only PR: N/A
- [x] Command/Result/Port 및 실제 호출 연결
- [x] 그룹 권한·역할·개인 필드 마스킹·카테고리·금액·쿨다운 조립
- [x] 기존 목록 SQL 공유와 Web 계약
- [x] 기존 회귀 확장·실제 HTTP/DB/Redis 검증
- [x] 전체 Java/JavaScript/Python 검증 및 문서 완료
- [x] 새 Domain/Infra: N/A

예상 커밋: `feat(group): 그룹 전체 정산 목록 조회 추가`. 기존 대화의 작업 방식에 따라 검증 후 `feat/598-group-settlements` 원격 브랜치로 게시한다.

## 8. Verification

전용 PostgreSQL 18·Redis 8과 Testcontainers를 사용한다. `RECURRING_TEST_DB_URL`, `NETTING_HTTP_PERF_QA=true`를 지정한 `bash scripts/test.sh clean test`로 전체 Java/JavaScript를 확인하고 Python 배포 테스트 8개는 독립 실행한다. 기존 테스트에 실제 그룹 제3자 HTTP 조회·역할별 금액/null·요청자 카테고리·정렬/필터·보안·압축 제외 검증을 추가한다. `git diff --check` 및 문서 경로를 확인한다.

| Item | Result |
|---|---|
| 기능/권한/HTTP 회귀 | 경로 ID 검증을 포함한 집중 Java 40개 통과. 최종 전체 실행에도 포함 |
| 전체 로컬 테스트 | `b7f7c3ad` 통합 후 Java **2,533개 / 297 suites**, JavaScript **35개** 통과. 실패·오류·건너뜀 **0**. Python 배포 테스트 **8개** 통과 |
| 문서/diff | `compileJava`·`compileTestJava`·`verifyMigrationVersions`·`git diff --check` 통과. 문서를 completed로 이동 |

## 9. Progress log

- 2026-10-20: 최신 이슈 3건에서 사용자가 #598만으로 범위를 확정했다. 최신 develop과 기존 목록·권한·카테고리를 확인했다.

- 2026-10-20: Application과 Web을 연결하고 공유 목록 SQL에 그룹 경로를 추가했다. 역할·제3자 HTTP·기존 상세 403·기존 개인 목록·카테고리 fallback·0원·미확정 품목 금액과 요청자 받을 금액을 기존 테스트에서 확인했다. 정렬 검증용 감사 시각과 조회자/참여자 fixture 연결을 바로잡았다. 전체 테스트는 비어 있는 전용 DB에서 조건부 suite를 활성화해 실행 중이다.

- 2026-10-20: 첫 전체 실행은 2,514개 중 검증 애노테이션 중복에 따른 정상 HTTP 요청 500 한 건만 실패했다. 기존 그룹 Controller처럼 `@Valid` 선언을 Docs에만 남겼으며 경로 ID 400을 포함한 집중 검증 40개가 통과했다. Python 배포 테스트 8개도 통과했다. 비어 있는 DB에서 최종 전체 회귀를 재실행한다.

- 2026-10-20: 중복 검증 선언 수정 후 전체 Java 2,514개/297 suites와 JavaScript 35개가 실패·오류·건너뜀 없이 통과했다. 검증 중 #601/#602가 develop에 병합되어 `b7f7c3ad`를 통합하고 최종 전체 회귀를 추가 실행한다. #599/#600 기능은 해당 PR의 변경을 기준 브랜치로만 반영하며 이 PR의 구현 범위는 #598이다.

- 2026-10-20: `b7f7c3ad` 통합 후 비어 있는 전용 DB·Redis에서 전체 Java 2,533개/297 suites와 JavaScript 35개를 재검증했다. 실패·오류·건너뜀은 0건이며 소요 시간은 4분 30초였다. Python 8개도 통과했다. 최종 로그와 집계는 무시되는 `build/reports/issue598/`에 보존했다.

## 10. Decision log

- 제3자 목록 조회만 허용하며 상세·송금 권한은 유지한다.
- 요청자 카테고리를 공유하고 조회자 개인 카테고리와 구분한다. 유효하지 않은 카테고리도 UNCATEGORIZED로 반환한다.
- 전체 조회를 요청한 이슈 범위대로 초기 cursor는 추가하지 않는다.

## 11. Completion record

- Completed on: 2026-10-20
- Final behavior: 활성 그룹원이 제3자 정산을 포함한 그룹 전체 목록을 조회한다. 요청자·참여자·제3자별 개인 필드와 기존 목록/상세 권한을 분리하고 요청자 카테고리·상태/정렬·압축 원본 제외를 제공한다.
- Unverified items: 실제 모바일 UI·은행/푸시·운영 배포
- Remaining risks: 목록 이후 권한·납부 상태가 달라질 수 있으므로 액션 API는 기존 검증을 수행한다.
