# Feature Plan: 그룹 요약 이름에서 요청자 제외

- 상태: 진행 중
- 관련 이슈: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/333

## 목적과 계약

`GET /api/v2/groups`의 memberNames는 요청자 userId를 먼저 제외한 구성원 중 기존 OWNER·joined_at·id 순서로 최대 3명을 반환한다. memberCount는 본인을 포함한 활성 사용자 전체 수를 유지한다. 혼자 있는 그룹은 1명과 빈 배열이며, 동명이인은 이름이 아닌 userId로 구분한다.

## 설계와 영향

기존 GroupController → GroupQueryService → LoadGroupMemberPort → GroupMemberPersistenceAdapter → GroupMemberJpaRepository 흐름과 Result/Web 매핑을 유지한다. SQL의 순번 계산을 요청자 여부로 분리하고 이름 투영에서 요청자 파티션을 제외한다. COUNT(*)는 전체 활성 구성원에 적용한다. 기존 활성 상태 필터·그룹 정렬·단일 SQL을 유지한다.

Flyway migration required: No. SQL 조회와 Swagger, 테스트, 관련 완료 문서만 변경한다. 새 Port·Domain·Policy·Infra는 N/A다.

## 커밋 계획

1. `fix(group): 그룹 요약 이름에서 요청자 제외` — 구현, Swagger 및 기존 fixture/기대값 적응.
2. `test(group): 요청자 제외 후 이름 상한과 인원수 검증` — 요청자 위치·동명이인·인원 경계 회귀와 완료 문서.

## 검증 계획

격리 PostgreSQL·Redis 상태를 확인하고 Java 21에서 compileJava 및 그룹 관련 테스트를 실행한다. 그룹장/일반 멤버 요청, 3명 초과, 혼자/둘인 그룹, 본인과 동명인 다른 회원, 비활성 제외를 확인한다. git diff --check와 문서 참조도 확인한다.
