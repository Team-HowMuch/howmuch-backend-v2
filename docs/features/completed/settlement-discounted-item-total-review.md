# 할인 영수증 품목 합계 검증 공통화

- 상태: 완료
- 관련 이슈: #423
- 관련 PR: #424

## 목적과 범위

품목별 정산 선택과 정산 요청은 같은 품목 합계 규칙을 적용해야 한다. 현재 두 위치에서 별도로 계산하므로 할인 규칙 변경 시 결과가 달라질 수 있다. 기존 `SettlementRequestPolicy`의 품목 검증을 두 서비스 흐름에서 재사용한다. HTTP 계약, 저장 구조, 할인 금액 산정 방식은 변경하지 않는다.

## 설계와 영향

- `SettlementAllocationService`는 조회한 품목과 지출을 정책에 전달한다.
- `SettlementRequestPolicy`는 품목 할인 후 합계에서 영수증 전체 할인을 빼고 지출 합계와 비교한다. 합산·차감 오버플로는 기존 불일치 오류로 변환한다.
- 영향 파일: 두 Application 클래스와 배분 정책 관련 테스트.
- Flyway migration: 필요 없음. 저장 구조 변경이 없다.

## 예상 커밋 경계

1. `refactor(settlement): 품목 할인 합계 검증 정책 공통화` — 정책과 호출부, 필요한 테스트를 함께 변경한다.

## 검증 계획

- 할인 없음, 품목 할인, 영수증 전체 할인, 동시 할인, 합계 불일치 회귀 테스트
- `./gradlew compileJava` 및 관련 단위 테스트
- `git diff --check`, `git status --short --untracked-files=all`

## 검증 결과

- `SettlementAllocationServiceTest`, `SettlementRequestPolicyTest`, `SettlementRequestServiceTest` 통과
- 테스트 실행 중 `compileJava`, `compileTestJava` 통과
- `git diff --check` 통과
- 전체 테스트는 실행하지 않았다. 변경 범위의 단위 테스트로 검증했다.
