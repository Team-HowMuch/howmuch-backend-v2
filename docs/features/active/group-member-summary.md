# Feature Plan: 그룹 목록 구성원 요약

- 상태: 진행 중
- 작성일: 2026-09-09
- 관련 이슈: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/333

## 목적과 완료 기준

정산 인원 선택에서 접힌 그룹에도 구성원 요약을 표시한다. `GET /api/v2/groups`의 기존 필드를 유지하고 `memberCount`와 최대 3개의 `memberNames`를 추가한다.

- 본인 포함, `group_members.status = ACTIVE`이면서 `users.active = true`인 사용자만 집계한다.
- 기존 구성원 순서인 OWNER 우선, joined_at ASC, member id ASC를 적용한다. 동명이인은 인원별로 유지한다.
- 기존 그룹 목록 정렬·접근 범위와 이미지 URL은 유지한다. 빈 목록은 빈 배열이다.
- 그룹별 추가 DB 요청 없이 목록과 요약을 한 SQL에서 가져온다.
- 상세 구성원 API·정산 후보 API·사용자 탈퇴 절차 변경은 제외한다.

## 도메인과 계약

기존 group 도메인의 `GetMyGroupsUseCase`를 확장한다. 상태 변경이나 새 Policy는 없다. user 도메인의 현재 이름과 활성 상태를 조회한다.

`GroupController -> GroupQueryService -> LoadGroupMemberPort -> GroupMemberPersistenceAdapter -> GroupMemberJpaRepository` 흐름을 유지한다. `GroupSummaryReadModel -> GroupResultAssembler -> GroupSummaryResult -> GroupSummaryWebResponse` 전 구간에서 두 필드를 전달한다.

## 저장소 설계와 영향

- Flyway migration required: No. 기존 groups, group_members, users를 조회하며 스키마 변경이 없다.
- PostgreSQL LATERAL 집계에서 활성 구성원에 순번을 부여하고 전체 수와 상위 3개 이름만 투영한다. 문자열 구분자 결합 없이 이름을 보존한다.
- 전체 구성원을 애플리케이션에 적재하거나 그룹마다 쿼리를 호출하는 대안을 제외한다.
- API DTO, Result, 조회 모델, 매핑, 기존 Repository SQL, Swagger와 관련 테스트가 영향 범위다.
- 인증, Redis, 환경 설정, Domain, Infra 변경은 없다.

## 실행 계획과 커밋 경계

1. `docs(group): 그룹 목록 구성원 요약 설계 기록`
2. `feat(group): 그룹 목록에 활성 구성원 요약 제공` — 조회부터 응답까지 연결하고 기존 테스트 fixture를 적응시킨다.
3. `test(group): 구성원 요약 정렬과 제외 조건 검증` — DB·Service·Web 계약 검증과 완료 문서를 포함한다.

새 Port, Domain, Policy, Infra, migration 단계는 기존 조회 확장이므로 N/A다.

## 검증 계획

Java 21에서 `./gradlew compileJava`, 그룹 Service·Web·PostgreSQL 통합 테스트, `git diff --check`를 실행한다. 통합 테스트는 격리 PostgreSQL·Redis와 공개 테스트 설정만 사용한다. 본인, 3명 초과, 동명이인, 동일 가입 시각, 비활성 사용자, LEFT/REMOVED 멤버, 삭제 그룹과 빈 결과를 확인한다.

## 진행 및 결과

- 설계 완료. 구현 및 검증 대기.
