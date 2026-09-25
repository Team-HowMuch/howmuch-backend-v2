# Feature Plan: CI Controller 검증 및 정산 만료 테스트 복구

- Status: Completed
- Last updated: 2026-09-25
- Related issue: N/A
- Related ADR: N/A

## 1. 목적

### 문제와 이유
최신 GitHub Actions에서 기존 Controller 테스트 19개가 ConstraintDeclarationException으로 HTTP 500을 받고, 만료 시각을 고정 날짜로 둔 정산 테스트 5개가 현재 시각 기준 만료되어 실패한다. #415의 요청 검증 변경 후 CI가 계속 실패한다.

### 완료 기준
- 계좌·그룹·정산 요청값 검증 선언이 override 규칙에 맞고 Controller 테스트가 기대 응답을 반환한다.
- 정산 링크 만료 fixture가 테스트 실행 시각 및 JPA 생성 시각보다 미래다.
- 기존 잘못된 경로 ID의 400 응답과 정산 요청 도메인 동작을 유지한다.

### 범위 제외
새 API, Application UseCase, Domain 규칙, 저장 구조, 인증·인가 변경은 제외한다.

## 2. 경계와 시나리오

기존 adapter.in.web 요청 경계와 정산 테스트만 수정한다. DB schema와 Flyway migration은 변경하지 않는다.

- 정상 요청은 기존 성공 또는 도메인 오류 응답을 반환한다.
- 잘못된 DTO와 0 이하 숫자 경로 ID는 INVALID_ARGUMENT 400으로 유지한다.
- 링크 만료 시각은 생성 시각보다 뒤이고, 서비스 검증 시점에도 미래여야 한다.

## 3. 계약과 영향 범위

| 영역 | 영향 |
|---|---|
| Account/Group/Settlement Controller와 Docs | 요청 DTO의 @Valid를 override 계약인 Docs 인터페이스에 두고 구현체 중복 선언을 제거 |
| 정산 링크 persistence 테스트 | 고정 과거 시각을 UTC 현재 기반 fixture로 교체 |
| 정산 요청 service 테스트 | 만료 시각을 UTC 현재보다 30일 뒤로 계산 |
| Application/Domain/API/DB 구조 | 변경 없음 |

- Flyway migration required: No.
- Expected commit boundary: fix(validation): CI 요청 검증과 정산 만료 테스트 복구

## 4. 구현 및 검증

- [x] Account/Group/Settlement의 @Valid 인터페이스 계약과 Controller 선언을 일치시킨다.
- [x] 만료 관련 세 테스트 fixture를 실행 시각 기준으로 바꾼다.
- [x] 실패한 Controller 테스트와 만료 관련 테스트를 실행한다.
- [x] ./gradlew compileJava, 적절한 ./gradlew test, git diff --check를 실행한다.
- [x] 완료 결과를 기록하고 이 문서를 docs/features/completed/로 옮긴다.

## 5. 완료 기록

- 완료일: 2026-09-25
- 원인: Docs 인터페이스에 메서드 제약이 추가되면서 Hibernate Validator가 Controller 전체의 override 메타데이터를 검사했다. 구현체에만 남은 @Valid가 인터페이스 선언과 일치하지 않아 HV000151이 발생했고 해당 Controller 요청이 500으로 처리됐다.
- 수정: Account, Group, Settlement의 요청 DTO cascade 검증을 Docs 인터페이스 계약에 모으고 구현체 중복 선언을 제거했다. 만료 테스트 시각은 UTC 현재 기준으로 계산하도록 바꿨다.
- 검증: compileJava 통과. 관련 Controller/예외 처리/Service 테스트 및 정산 persistence 통합 테스트 통과. 전체 ./gradlew test 결과 1,295 tests, 0 failures, 0 errors, 0 skipped.
- 실행 환경: compose.test.yml의 PostgreSQL·Redis와 발송되지 않는 로컬 Discord URL을 사용했다. DB 접속 설정 값은 출력하지 않았다.
