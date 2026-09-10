# Feature Plan: 공통 코드 그룹 목록 N+1 집계 제거

- 상태: 진행 중
- 갱신일: 2026-09-10
- 관련 이슈: [#312](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/312)
- 기준: 최신 `origin/develop`의 `6216763`, 브랜치 `fix/312-common-code-list-n-plus-one`

## 1. 목적과 성공 기준

관리자 공통 코드 그룹 목록은 그룹 N개를 조회할 때 그룹 목록 1회와 상세 개수 N회를 실행한다. 그룹이 증가해도 추가 쿼리가 늘지 않도록 전체·검색 목록의 집계를 개선한다.

- [ ] 결과가 있는 목록은 그룹 수와 관계없이 SQL 2회로 조회한다.
- [ ] 빈 그룹은 `detailCount=0`, 비활성 상세도 기존처럼 개수에 포함한다.
- [ ] 그룹 코드 오름차순, 코드·이름의 대소문자 무시 부분 검색, 공백 검색어 처리를 유지한다.
- [ ] 쿼리 수와 응답 집계 정확성을 실제 PostgreSQL 회귀 테스트로 검증한다.

쓰기 무결성, revision 처리, 캐시 정책, CODEF 연동, 목록 페이지네이션은 범위에서 제외한다.

## 2. 도메인과 시나리오

기존 `commoncode` 도메인을 사용한다. 관리자가 전체 또는 검색 목록을 요청하면 그룹 요약과 전체 상세 개수를 반환한다. 상태 전이는 없으며 관리자 권한 검사는 기존 Service가 담당한다.

- 정상: 상세 0개·1개·여러 개의 그룹이 섞인 목록에서 개수와 정렬을 유지한다.
- 경계: 비활성 그룹과 비활성 상세를 포함한다. 검색 결과가 없으면 빈 목록을 반환하고 집계 쿼리를 생략한다.
- 실패: 기존 인증·인가 및 DB 오류 처리를 유지한다.

## 3. 계약과 영향 범위

```text
GET /api/v2/admin/common-codes/groups?keyword=...
CommonCodeGroupSearchWebRequest -> SearchCommonCodeGroupsCommand
SearchCommonCodeGroupsUseCase -> CommonCodeAdminService.search
  -> CurrentActorPort / LoadUserPort (관리자 확인)
  -> LoadCommonCodeGroupPort.findAll -> CommonCodePersistenceAdapter -> JPA/PostgreSQL
CommonCodeGroupSummary -> CommonCodeGroupSummaryResult -> CommonCodeGroupSummaryWebResponse
```

- `CommonCodePersistenceAdapter.findAll`: 그룹 목록을 먼저 읽고 해당 그룹 코드의 상세 개수를 일괄 조회해 기존 Mapper에 전달한다.
- `CommonCodeJpaRepository`: 단일 그룹 count를 `IN` + `GROUP BY` 집계로 교체한다. Repository 내부 projection은 그룹 코드와 `long` 개수만 제공한다.
- `CommonCodeGroupJpaRepository`, `CommonCodeGroupMapper`, API 응답 필드·검색·정렬 계약은 유지한다.
- Flyway migration required: **No**. 기존 테이블·복합 기본 키를 사용하며 SQL migration 추가·수정은 없다.
- UseCase/Command/Result, Output Port, Domain, Policy, Service, Web/Swagger, Redis, 보안, Infra 변경: **N/A**, 기존 계약으로 충분하다.

## 4. 설계 결정

기존 목록 쿼리를 유지하고 조회된 그룹만 일괄 집계한다. 상세 행 자체를 로딩하지 않으며, 집계 결과가 없는 그룹에는 0을 사용한다. 그룹 목록의 순서로 매핑하므로 집계 결과의 순서와 무관하다.

대안인 목록·상세 LEFT JOIN 단일 쿼리는 왕복을 한 번 줄일 수 있지만 기존 검색 쿼리와 요약 매핑까지 바꿔야 한다. 이번에는 변경 범위가 작은 두 쿼리 방식을 선택한다. 현재 목록이 무제한인 점과 두 조회 사이의 동시 쓰기 가시성은 기존과 같으며 별도 범위다.

## 5. 구현 및 커밋 계획

1. `docs(commoncode): 목록 N+1 집계 제거 설계 기록`
2. `fix(commoncode): 그룹 목록 상세 개수를 일괄 집계`
3. `test(commoncode): 목록 쿼리 수와 집계 정확성 회귀 검증`

- [x] 입력·출력 흐름, 관련 구현과 migration 필요 여부 확인
- [ ] Output Adapter·Repository 집계 변경
- [ ] PostgreSQL 회귀 테스트와 기존 공통 코드 테스트 실행
- [ ] 검증 결과 기록 및 완료 문서 이동
- Migration PR, 새 계약·Domain·Policy·Service·Web·Infra 구현: **N/A** (변경 불필요)

## 6. 검증 계획

- `./gradlew compileJava`: 각 커밋의 Java 컴파일 확인.
- `bash scripts/test.sh test --tests '*commoncode*'`: 일회용 PostgreSQL 18·Redis 8과 테스트 전용 환경변수를 사용한다.
- 새 통합 테스트는 영속성 컨텍스트와 Hibernate 통계를 초기화한 뒤 실제 prepared statement 수를 측정한다. 전체·검색 조회에서 그룹 수를 늘려도 2회인지, 빈 검색 결과는 1회인지 검증한다.
- 빈 그룹, 활성·비활성 상세, 코드·이름 검색, 공백·대소문자, 검색 제외 그룹, 요약 필드와 정렬을 검증한다.
- `git diff --check`, `git status --short --untracked-files=all`, 문서 참조 확인.
- 루트 `AGENTS.md`와 이슈의 회귀 테스트 요구를 따른다. 하위 개발 흐름 문서의 과거 테스트 생성 금지 문구보다 우선한다.

## 7. 진행 및 완료 기록

- 2026-09-10: 기존 작업 트리 변경 없음 확인. 최신 develop에서 브랜치 생성, 이슈·기존 PR 및 커밋 컨벤션 확인. 테스트용 PostgreSQL·Redis 컨테이너의 healthy 상태 확인.
- 검증 결과: 진행 예정.
- 미검증 범위 및 남은 위험: 완료 시 기록.
