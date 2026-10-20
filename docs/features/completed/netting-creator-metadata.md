# Feature Plan: 압축 정산 생성자 정보

- Status: Completed
- Last updated: 2026-10-20
- Related issue: [#607](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/607)
- Related ADR: N/A — 기존 조회 계약 확장

## 1. Purpose

압축 정산 한 건마다 누가 언제 만들었는지 표시한다. 기존 `createdAt`은 유지하고 최상위 `creatorId`, `creatorName`을 제공한다.

### Success criteria

- [x] 그룹장과 실제 압축 실행자가 다른 경우 실행자를 표시한다.
- [x] 목록·현재·상세와 공통 응답 매핑에 생성자와 기존 UTC 생성 시각을 제공한다.
- [x] 송금 없이 완료되는 압축 및 재압축 세대에도 올바른 생성자가 보존된다.
- [x] 기존 접근 권한·원본 정산·송금 계약과 개인정보 노출 범위를 유지한다.

### Out of scope

프론트 UI, `sources` 확장, 이름의 생성 당시 스냅샷, 생성 시각 변경, 운영 배포.

## 2. Domain analysis

기존 그룹 정산 압축 도메인을 사용한다. 생성자는 해당 세대의 CREATE 실행을 요청한 사용자이며 현재 그룹장·원본 생성자·송금 당사자와 구분한다. 생성일은 `group_settlement_nettings.created_at`이다. 사용자 이름은 조회 시점의 사용자 이름을 제공한다.

기존 `GetGroupNettingsUseCase`와 `GroupNettingQueryService`를 재사용한다. Domain 상태 전이는 변경하지 않는다.

## 3. Scenarios

- 일반 그룹원이 압축: 그룹장 대신 실행한 그룹원의 ID와 이름을 반환한다.
- 송금 0건: 송금 당사자 목록이 비어 있어도 생성자 이름을 조회한다.
- 재압축: 이전·새 세대는 각각 연결된 실행자와 생성 시각을 유지한다.
- 재조회/대조: 조회자나 대조 실행자가 바뀌어도 최초 CREATE 실행자를 반환한다.
- 미인증·비회원: 기존 권한 검증을 통과해야 하며 생성자 조회 때문에 접근 범위를 늘리지 않는다.

## 4. Contracts

`LoadGroupNettingPort.Netting`에 `creatorId`를 추가한다. `GroupNettingResult`와 `GroupNettingWebResponse` 최상위에 필수 `creatorId`, `creatorName`을 추가한다. `createdAt`은 UTC offset 없는 기존 계약 그대로다.

`GroupNettingJdbcAdapter.find`에서 세대의 `execution_id`로 실행 기록을 JOIN하고 `actor_id`를 읽는다. `GroupNettingQueryService`는 생성자를 기존 `LoadUserPort.findAllByIds`의 조회 대상에 포함해 송금 사용자와 함께 조회한다. 새 Port나 Policy는 필요하지 않다.

### Persistence and external integrations

- Flyway migration required: **No**.
- 사유: V53의 필수 `execution_id` 외래키와 변경 불가한 실행 `actor_id`를 재사용한다. 현재 그룹장 snapshot의 `owner_id`를 생성자로 오인하지 않는다.
- Redis, 외부 API, 프로필 및 보안 설정 변경: 없음.

## 5. Design decisions

DB 컬럼 중복 저장 대신 기존 실행 기록을 조회한다. 실행자 식별자는 불변이며 사용자 FK로 보존된다. 이름은 기존 사용자 조회를 재사용해 추가 개별 조회를 만들지 않는다. 기존 응답에 필드만 추가하며 원본·송금·생성일 계약을 유지한다.

## 6. Impact surface

| Area | Impact |
|---|---|
| Persistence | `GroupNettingJdbcAdapter.find` JOIN 및 `creatorId` 매핑 |
| Application | `LoadGroupNettingPort.Netting`, `GroupNettingResult`, `GroupNettingQueryService` |
| API | `GroupNettingWebResponse` 매핑·OpenAPI 필수 필드와 설명 |
| Tests | `GroupNettingDirectIntegrationTest` 실제 DB·HTTP·OpenAPI 회귀 |
| Domain / Redis / Security / Configuration | 변경 없음 |

## 7. Implementation plan

- [x] Migration-only PR: N/A — 기존 외래키·실행자 재사용
- [x] Application Result 및 기존 Output Port 확장
- [x] Domain / Policy: N/A — 상태·판단 규칙 불변
- [x] 조회 Service·JDBC Adapter
- [x] Web Response·Swagger
- [x] Infra: N/A — 설정 변경 없음
- [x] 회귀 테스트·문서 결과 기록

### Expected commit boundaries

`feat(settlement): 압축 정산 응답에 생성자 정보 제공` 한 커밋에 연결된 구현·회귀 테스트·문서를 포함한다.

## 8. Verification

Java 21과 격리 PostgreSQL 18·Redis를 사용한다. 앱 `.env`나 운영 DB를 테스트에 사용하지 않는다.

```bash
./gradlew compileJava --no-daemon
bash scripts/test.sh compileJava test --tests '*GroupNettingDirectIntegrationTest' --tests '*GroupNettingEstimateServiceTest'
git diff --check
git status --short --untracked-files=all
```

격리 PostgreSQL 18(127.0.0.1:25432)과 Redis(127.0.0.1:26379), Java 21에서 위 명령을 실행했다. 앱 `.env`와 실제 은행은 사용하지 않았다.

| Item | Result | Evidence |
|---|---|---|
| compileJava / compileTestJava / verifyMigrationVersions | 통과 | 최종 회귀 실행에서 컴파일 및 마이그레이션 버전 검증 |
| GroupNettingDirectIntegrationTest | 60개 통과 | 실행자/조회자 구분, 송금 0건, 비송금 당사자 실행, 이름 변경, 재압축 이력, 기존 권한·입금·원본 회귀 |
| GroupNettingEstimateServiceTest | 12개 통과 | 기존 압축 예상 조회 회귀 |
| 실제 DB·HTTP / OpenAPI | 통과 | 그룹 목록·현재·상세, ID 단독 상세, 원본 연결 조회 다섯 경로 및 필수 creatorId/creatorName·UTC createdAt |
| JavaScript | 35개 통과 | scripts/test.sh가 실행하는 기존 회귀 |
| diff 및 문서 링크 | 통과 | git diff --check, 관련 코드·테스트 경로 확인 |

Java 72개와 JavaScript 35개 모두 실패·오류·건너뜀 0개다. 새 케이스는 6개이며 기존 OpenAPI 회귀도 보강했다. 전체 Java suite는 실행하지 않았으며 실제 모바일 화면과 운영 배포는 수행하지 않았다.

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-20 | develop 60291ec2에서 설계·구현, 게시 전 최신 5d9d73bf 반영 및 회귀 완료 | PR 게시 | 생성자는 현재 그룹장과 구분 |

## 10. Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-10-20 | CREATE 실행의 actor_id 사용 | 현재 그룹장과 실행자는 다를 수 있음 |

## 11. Completion record

- Completed on: 2026-10-20
- Final behavior: 다섯 압축 정산 조회 경로의 최상위 creatorId/creatorName과 기존 createdAt 제공
- Unverified items: 모바일 UI·운영 배포
- Remaining risks: 조회 시점 이름을 반환하며 생성 당시 이름은 저장하지 않음
- Follow-up work: 프론트에서 생성자·생성일 표시
