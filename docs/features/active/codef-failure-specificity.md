# 기능 계획: CODEF 실패 원인 세분화

- 상태: 구현 완료, PR 대기
- 관련 이슈: [#367 CODEF 미분류 오류와 응답 구조 오류 분리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/367)
- Flyway migration: 필요 없음

## 목적

CODEF 계좌 연결에서 `BANK_CONNECTION_FAILED` 하나로 표현되던 미분류 CODEF 오류와 응답 계약 위반을
분리한다. 운영자는 CODEF 결과 코드와 요청 trace를 기준으로 원인을 확인하고, 사용자는 자격증명 오류·잠금·추가인증·외부 장애를 구분된 안내로 받는다.

## 변경 범위

- 공식 CODEF 오류 코드는 기존 매핑을 유지한다.
- 공식 목록에 없는 `CF-xxxxx`는 `CODEF_UNMAPPED_ERROR`(502)로 반환한다.
- HTTP 4xx 응답에 CODEF 오류 코드가 없거나, 성공 응답에 `successList`·`connectedId`가 없으면 `CODEF_RESPONSE_INVALID`(502)로 반환한다.
- 계좌 연결과 거래내역 조회가 동일한 공통 매퍼를 사용한다. 거래내역의 `CF-04000` 기능 미지원 매핑은 유지한다.
- 로그·trace에는 `api`, `organization`, `outcome`, `codefCode`만 기록하고 자격증명·계좌 원문은 기록하지 않는다.

## 완료 조건

- [x] 미분류 CODEF 오류와 응답 구조 오류의 API 코드가 기존 은행 연결 실패와 다르다.
- [x] 계좌 연결·거래내역 공통 매핑 회귀 테스트가 통과한다.
- [x] `git diff --check`와 관련 Gradle 테스트가 통과한다.
