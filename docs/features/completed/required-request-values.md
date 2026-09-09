# 필수 요청값 누락 상태 코드 수정

- 상태: 완료
- 관련 이슈: #306

## 목적과 계약

필수 `@RequestHeader`와 `@RequestParam` 누락이 500으로 처리되는 문제를 해결한다.
기존 `GlobalExceptionHandler`의 입력 변환 오류 처리에 두 누락 예외를 명시적으로 추가하여
HTTP 400, `success: false`, `error.code: INVALID_ARGUMENT` 및 기존 메시지를 반환한다.
정상 요청, 선택 파라미터, 본문 파싱·검증 오류, ApplicationException의 401·403 및 예상하지 못한 500은 유지한다.
로깅 개선(F20)과 CODEF는 제외한다.

## 영향과 설계

- 기존 common 예외 처리 경계를 사용하며 신규 Domain, UseCase, Command/Result, Output Port, Policy는 필요 없다.
- 요청 바인딩 실패는 Controller/UseCase 호출 전에 종료되어 저장소나 외부 API에 도달하지 않는다.
- Controller, Web DTO, Swagger의 필수값 정의와 보안 필터 설정은 변경하지 않는다.
- Flyway migration: No. DB, Redis, 외부 API, 환경 설정 변경이 없다.
- 포괄적인 바인딩 예외 전체를 400으로 바꾸지 않고 두 누락 예외만 지정한다.

## 실행 및 커밋 경계

1. `fix(common): 필수 헤더와 파라미터 누락을 400으로 매핑` — 설계와 동작 수정.
2. `test(common): 필수 요청값 누락과 기존 오류 응답 회귀 검증` — 실제 Advice를 등록한 MockMvc 및 검증 결과.

## 검증 계획

- Java 21에서 `./gradlew compileJava`.
- 격리 MockMvc로 실제 Controller의 헤더·파라미터 누락/빈 값/형식 오류 및 정상·선택값 생략을 검증한다.
- 본문 파싱·검증 오류와 ApplicationException 401·403 및 generic 500의 상태·응답 본문을 확인한다.
- 격리 테스트는 DB·Redis·환경 변수가 필요 없다. 실제 HTTP 통합 테스트는 별도 PostgreSQL 스키마와 로컬 Redis에서 실행한다.
- `git diff --check`, 문서 경로, 작업 상태를 확인한다.

## 검증 결과

- Java 21 `./gradlew compileJava`: 통과.
- `./gradlew compileJava test --tests '*GlobalExceptionHandlerTest' --tests '*SettlementTransferInstructionControllerTest' --tests '*SettlementPaymentExpenseControllerTest'`: 21개 통과(신규 14개).
- 실제 Advice와 실제 정산 Controller에서 누락·빈 값·형식 오류가 400이며 UseCase를 호출하지 않음을 확인했다.
- 선택 파라미터 생략, 정상 송금 안내, 본문 파싱·필드 검증, ApplicationException 401·403 및 예상하지 못한 500을 확인했다.
- `git diff --check`: 통과. 문서에 참조하는 코드 및 테스트 경로 확인.
- 초기 MockMvc 검증 후 실제 HTTP·보안 필터 체인 통합 검증을 추가했다. 결과는 아래와 같다.
- DB 변경 및 추가 후속 결정 없음.

## 실제 HTTP 추가 검증 (2026-09-09)

- 추가 커밋: `test(common): 실제 HTTP 필수값 오류와 보안 응답 검증`.
- 로컬 PostgreSQL 준비 상태와 Redis PING을 확인했다. DB는 `issue306_http_test` 스키마에서 Flyway migration 및 Hibernate 검증을 실행했다. 기존 스키마는 수정하지 않았다.
- `SecurityFilterChainIntegrationTest`의 RANDOM_PORT 실제 Tomcat 서버에 Java HttpClient로 요청했다. MockMvc나 인증 대역을 사용하지 않고 실제 JWT, Redis 세션, PostgreSQL 사용자 및 보안 필터를 통과했다.
- 신규 실제 HTTP 10개: 공개 송금 안내의 헤더 누락·빈 값·형식 오류, 인증된 결제내역 후보 조회의 파라미터 누락·빈 값·형식 오류, 미인증 파라미터 누락의 선행 401, 로그인 본문의 누락·파싱 오류·필드 검증.
- 기존 보안 HTTP 11개: 정상 프로필, 미인증·만료·잘못된 서명·잘못된 형식·Refresh Token·로그아웃·세션 교체의 401, 일반 사용자 및 권한 회수의 403, 관리자·공개 페이지 접근.
- 실제 HTTP 21개와 기존 MockMvc 21개, 총 42개 통과. 상태, Content-Type, success, 오류 코드와 신규 입력 오류의 메시지·timestamp·data 부재 및 검증 details를 확인했다.
- 테스트가 생성한 사용자·Redis 세션을 정리하고 서버가 정상 종료됨을 확인했다. 전체 프로젝트 테스트는 실행하지 않았다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DB_URL='jdbc:postgresql://localhost:5432/howmuch?currentSchema=issue306_http_test' \
SPRING_FLYWAY_SCHEMAS=issue306_http_test \
./gradlew compileJava test --tests '*SecurityFilterChainIntegrationTest' \
  --tests '*GlobalExceptionHandlerTest' \
  --tests '*SettlementTransferInstructionControllerTest' \
  --tests '*SettlementPaymentExpenseControllerTest'
```
