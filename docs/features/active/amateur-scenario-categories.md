# 부스 시나리오 정산 카테고리

- 상태: 구현 완료, PR 검토 대기
- Flyway migration required: **No**. `settlement_categories.category_code`와 `recurring_settlements.category_code`를 사용한다.

## 목적

시연 운영자가 시나리오에 일반 정산이나 정기 정산을 추가할 때 카테고리를 선택하고, 생성된 앱 데이터에도 같은 카테고리가 표시되게 한다.

## 설계

- 운영 화면은 관리자 API 응답과 함께 서버 공통 코드 중 정산에 사용하도록 활성화된 카테고리 목록을 받는다.
- 정산 행마다 카테고리 선택을 제공한다. 시나리오 미리보기에도 카테고리 이름을 표시한다.
- 시나리오 저장 때 카테고리 코드를 서버에서 다시 검증한다. 기존에 저장한 카테고리 없는 시나리오는 기존 동작과 같이 `FOOD`를 사용한다.
- 시나리오 적용 시 일반 정산은 `settlement_categories`, 정기 정산은 그 회차의 `settlement_categories`와 고정 지출 정의의 `recurring_settlements.category_code`에 선택한 값을 저장한다.
- 기존 기본 시나리오는 정산 기준 데이터의 분류를 유지하고, 정기 지출 기본 행은 이용권 `EDUCATION`, 대관 `HOBBY_LEISURE`, 구독 `SUBSCRIPTION`으로 표시한다.

## 영향과 확인

- 변경 범위: 부스 운영 화면, 관리자 전용 시나리오 처리, 운영 README.
- 인증·정산 API·기존 그룹·기존 정산 데이터는 변경하지 않는다. DB 마이그레이션은 없다.
- GitHub Actions가 PR에서 서버 검증을 실행한다. 로컬에서는 저장 변경 범위와 diff를 검토한다.
