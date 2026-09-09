# Feature Plan: 정산 종료 시각과 연월 조회

- 상태: 완료
- 작성일: 2026-09-10
- 관련 이슈: #345

## 목적과 성공 기준

그룹 월 지출을 요청 월이 아닌 종료 월에 귀속할 수 있도록 기존 정산 목록을 확장한다.
- 완료 정산은 `completedAt`을 반환하고 나머지는 명시적인 null을 반환한다.
- `yearMonth`를 생략하면 기존 목록·정렬·기본 상태를 유지한다.
- 연월을 지정하면 해당 월에 완료된 정산만 기존 탭·상태·그룹 조건과 교집합으로 반환한다.
- 월 시작 포함, 다음 월 시작 제외 및 잘못된 입력의 400 응답을 검증한다.
- 별도 합계 API, 신규 도메인, 스키마 변경은 제외한다.

## 도메인과 시나리오

기존 settlement 도메인과 `GetSettlementsUseCase`를 재사용한다. 생성자는 RECEIVE, 연결 참여자는 PAY 탭을 조회한다.
`COMPLETED`는 종착 상태이며 이후 초안 수정과 상태 전이가 불가능하다. 저장된 `updated_at`은 JPA 감사 시각이다.
8월 요청·9월 완료 정산은 9월 조회에 포함한다. 미완료 정산은 연월 조회에서 제외한다.
`status` 기본값 IN_PROGRESS는 유지하므로 월 지출 조회는 `status=COMPLETED` 또는 `ALL`을 명시해야 한다.

## 계약과 설계

- `SettlementListWebRequest -> GetSettlementsCommand -> SettlementListService -> LoadSettlementListPort`에 nullable `YearMonth`를 전달한다.
- `SettlementJpaRepository`가 COMPLETED일 때만 `updated_at`을 투영하고, ReadModel → Result → WebResponse에서 `completedAt`을 전달한다.
- `GET /api/v2/settlements?status=COMPLETED&groupId=10&yearMonth=2026-09`
- 연월은 기존 계좌 조회와 같은 Asia/Seoul 기준이다. Persistence Adapter에서 월 경계를 UTC로 변환한다. 응답은 UTC offset 없는 LocalDateTime이다.
- 필터는 기존 SQL에 범위 조건을 추가하며 권한·그룹 접근 조건과 정렬을 보존한다.
- 새 Port, Policy, Domain 변경, Redis, 외부 API, 보안·설정 변경: N/A.
- Flyway migration required: No. 기존 컬럼만 사용한다.
- 한계: 완료 후 updated_at을 갱신하는 기능이 추가되면 명시적인 완료 시각 저장 설계를 재검토해야 한다.

## 영향 범위

Web Request/Response·SettlementDocs, Application Command/Result·Service·LoadSettlementListPort·ReadModel,
SettlementPersistenceAdapter·SettlementJpaRepository와 관련 Web/Application/PostgreSQL/OpenAPI 테스트.
도메인의 complete 전이, JPA 감사 매핑 및 계좌 연결 해제의 bulk update가 감사 시각을 변경하지 않음을 확인했다.

## 구현 및 커밋 경계

1. `feat(settlement): 정산 목록에 종료 시각 추가` — 조회 투영과 응답 연결, 기존 테스트 생성자 갱신.
2. `feat(settlement): 종료 연월 기준 목록 필터 추가` — 입력부터 SQL까지 필터 연결과 문서.
3. `fix(settlement): 종료 시각과 월 경계의 UTC 매핑 보장` — native query 시각의 JVM 시간대 독립성과 Swagger 타입 보완.
4. `test(settlement): 종료 시각과 연월 경계 회귀 검증` — 동작 테스트 및 검증 결과.

마이그레이션·신규 Domain·Policy·Infra 구현은 N/A. 각 구현 커밋은 컴파일 가능한 상태로 유지한다.

## 검증 계획

`./gradlew compileJava`, 관련 정산 목록 서비스·Web·Persistence·OpenAPI 테스트, `git diff --check`와 상태 확인.
PostgreSQL·Redis 가용성을 먼저 확인하고 격리된 테스트 DB 및 scripts/test.sh의 테스트 전용 설정을 사용한다.
실제 HTTP 서버 검증이 불가능하면 MockMvc 검증 범위와 미실행 사유를 기록한다.

## 결정과 진행 기록

- 2026-09-10: 최신 origin/develop에서 브랜치 생성. 마이그레이션 불필요 확인.
- 2026-09-10: 기존 계좌 API에 맞춰 KST 월 경계, UTC 응답을 선택. 기본 status 변경 없이 필터를 교집합으로 적용.

## 완료 기록

- 완료일: 2026-09-10
- [x] 완료 시각 투영과 응답 전달, 미완료 명시적 null 검증.
- [x] 연월 필터 전체 흐름과 Swagger 계약 검증.
- [x] RECEIVE/PAY, 월 시작 포함·다음 월 제외, 연도 경계, 그룹 탈퇴 접근 차단 검증.
- [x] 기본 상태 유지, 필터 생략, 잘못된 연월 400 검증.
- 검증: `./gradlew compileJava compileTestJava` 통과.
- 검증: 격리 PostgreSQL DB `howmuch_issue345`, Redis 16379에서 아래 명령으로 Java 46건 및 JavaScript 35건 통과.
  `bash scripts/test.sh test --tests '*SettlementListServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementListPersistenceIntegrationTest' --tests '*SettlementOpenApiIntegrationTest'`
- 미실행: 전체 프로젝트 테스트, 별도 서버를 띄운 실제 HTTP 요청. 관련 계층을 MockMvc와 실제 PostgreSQL로 검증했으며 전체 CI 검증은 PR에서 수행한다.
- 후속 위험: 기존 requestedAt native projection의 JVM 시간대 영향은 이번 completedAt 추가 범위에서 변경하지 않았다.
- 1차 검증에서 테스트 import 누락 수정 후 native timestamp의 9시간 이동과 Swagger의 null 타입을 발견했다.
  기존 입금 후보 조회의 `AT TIME ZONE 'UTC'` + `Instant` 패턴을 재사용하여 응답과 경계를 명시적으로 매핑하고 재검증했다.
- 문서의 저장 경로 참조 확인 및 `git diff --check` 통과.
