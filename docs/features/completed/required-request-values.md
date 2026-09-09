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
- 격리 테스트는 DB·Redis·환경 변수가 필요 없다. 전체 통합 테스트와 실제 서버 실행은 별도 서비스·환경 설정이 필요하여 이번 검증에서 제외한다.
- `git diff --check`, 문서 경로, 작업 상태를 확인한다.

## 검증 결과

- Java 21 `./gradlew compileJava`: 통과.
- `./gradlew compileJava test --tests '*GlobalExceptionHandlerTest' --tests '*SettlementTransferInstructionControllerTest' --tests '*SettlementPaymentExpenseControllerTest'`: 21개 통과(신규 14개).
- 실제 Advice와 실제 정산 Controller에서 누락·빈 값·형식 오류가 400이며 UseCase를 호출하지 않음을 확인했다.
- 선택 파라미터 생략, 정상 송금 안내, 본문 파싱·필드 검증, ApplicationException 401·403 및 예상하지 못한 500을 확인했다.
- `git diff --check`: 통과. 문서에 참조하는 코드 및 테스트 경로 확인.
- 전체 통합 테스트·실제 서버 HTTP·보안 필터 체인 통합 테스트는 수행하지 않았다. 이번 검증은 외부 서비스 없이 실행하는 MockMvc 범위이며, 실제 서비스·환경 설정을 사용하는 테스트는 제외했다.
- 보안 필터 자체는 변경하지 않았으며 401·403 검증은 Advice의 ApplicationException 처리 범위이다.
- DB 변경 및 추가 후속 결정 없음.
