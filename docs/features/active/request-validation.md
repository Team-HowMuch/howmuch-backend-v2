# Feature Plan: HTTP 요청값 Validation 보완

- Status: Active
- Last updated: 2026-09-24
- Related issue: #414
- Related ADR: N/A

## 1. 목적

잘못된 HTTP 입력이 UseCase 호출 전에 거부되도록 요청 경계를 보완한다. 더치페이 배분 목록의 `null` 항목은 DTO 변환 중 서버 오류를 일으킬 수 있고, 음수 `revision`과 0 이하 `Long` 경로 ID는 요청별로 다르게 처리된다.

완료 기준:

- `allocations`의 `null` 항목과 음수 `revision`은 `INVALID_ARGUMENT` 400으로 거부한다.
- 그룹·계좌·정산의 0 이하 `Long` 경로 ID는 `INVALID_ARGUMENT` 400으로 거부한다.
- 그룹 이름·메모·공개 ID의 기존 전용 오류 코드와 빈 메모의 제거 동작을 유지한다.
- DTO 검증 오류는 UseCase 호출 전에 기존 오류 응답 형식으로 처리한다.

이메일 입력, enum 변환 정책, 정상 응답, 저장 구조, 인증·인가 정책은 변경 범위에서 제외한다.

## 2. 경계와 계약

기존 `adapter.in.web`에서 HTTP 입력 형태를 검사한다. Application과 Domain의 비즈니스 불변식 및 Web 외 호출자 보호용 검증은 유지한다. 새로운 UseCase, Command, Result, Output Port, Policy는 만들지 않는다.

| 입력 | 현재 상태 | 변경 후 |
|---|---|---|
| 더치페이 `allocations`의 `null` 요소 | DTO 변환 중 실패 가능 | 중첩 항목 검증으로 400 |
| 공통 코드 음수 `revision` | 동시 수정 오류와 혼동 가능 | 0 이상 제약으로 400 |
| 그룹·계좌·정산의 0 이하 `Long` 경로 ID | 조회 경로에 따라 404 등 | 양수 제약으로 400 |
| 그룹 이름·메모·공개 ID | Domain·Application의 전용 오류 코드 | 기존 코드 유지 |
| 잘못된 enum·날짜 형식 | 변환 예외를 400으로 처리 | 유지 |

메서드 인자 제약은 Controller가 구현하는 Docs 인터페이스에 선언한다. Spring MVC 메서드 검증 오류인 `HandlerMethodValidationException`은 `GlobalExceptionHandler`에서 `INVALID_ARGUMENT` 400으로 변환한다. `@Valid`가 필요한 요청 DTO는 Controller에서 명시하고, 결제내역 Controller의 클래스 수준 `@Validated`는 제거한다.

## 3. 영향 범위와 결정

- Web DTO: 더치페이 배분 목록 요소, 공통 코드 수정 `revision`.
- Controller와 Docs: 그룹·계좌·정산의 `Long` 경로 ID, 그룹·프로필 multipart 입력, 결제내역 가져오기.
- 공통 예외 처리: 메서드 검증 오류를 기존 `BaseResponse` 형식으로 반환.
- Application·Domain: 변경 없음. 계좌 표시 순서와 공개 ID처럼 기존 전용 오류 코드를 가진 규칙은 유지.
- DB·Redis·외부 연동·설정: 변경 없음. Flyway migration: **No**.

`@NotBlank`를 선택적 문자열에 일괄 적용하지 않는다. 그룹의 빈 메모는 제거 요청이고, 공개 ID는 Application에서 정규화한 뒤 기존 전용 오류 코드로 검증한다. `@Email`을 적용할 요청 필드는 없다.

## 4. 구현과 커밋 경계

1. `docs(validation): 입력 검증 범위와 응답 계약 정리` — 이 문서 작성.
2. `fix(settlement): 더치페이 배분의 null 항목 거부` — 목록 요소에 `@NotNull @Valid` 적용.
3. `fix(commoncode): 음수 revision 입력 거부` — `revision`에 0 이상 제약 적용.
4. `fix(validation): 숫자 경로 ID와 검증 오류 400 처리` — 그룹·계좌·정산 ID 제약과 예외 처리, API 설명을 함께 변경.
5. `refactor(validation): 요청 DTO 검증 진입점 정리` — 그룹·프로필·결제내역의 검증 선언을 정리하고 이 문서의 완료 결과 기록.

각 Java 커밋은 독립적으로 컴파일되어야 한다. 이전 import 정리 브랜치의 변경을 포함하지 않는다.

## 5. 검증

- Java 변경마다 `./gradlew compileJava`.
- 최종 `git diff --check` 및 `git status --short --untracked-files=all`.
- `null` 배분 항목, 음수 `revision`, 0 이하 경로 ID의 400 응답과 기존 전용 오류 코드 유지 여부를 코드 경로로 검토한다.
- 사용자의 이번 요청에 따라 새 테스트를 작성하거나 테스트를 실행하지 않는다. 실행하지 않은 검증은 완료 기록과 PR에 명시한다.

## 6. 진행 기록

| 날짜 | 내용 | 결과 |
|---|---|---|
| 2026-09-24 | 원격 `develop` 확인, 이슈 #414와 작업 브랜치 생성 | 진행 중 |
