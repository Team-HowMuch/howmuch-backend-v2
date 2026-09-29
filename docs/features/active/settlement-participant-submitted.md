# 정산 상세 참여자별 품목 제출 여부

- Status: In progress
- Last updated: 2026-09-29
- Related issue: #448

## 목적과 완료 기준

할인 영수증 정산에서 일부 참여자가 품목을 제출해도 `share` 계산 전에는 `paymentStatus`가 모두 `NOT_READY`이므로 앱이 미제출자와 계산 대기자를 구별할 수 없다. `GET /api/v2/settlements/{settlementId}`의 각 참여자에 실제 제출 기록에 따른 `submitted: boolean`을 제공한다. 생성자는 모든 납부 대상 참여자, 일반 참여자는 자신에게 허용된 참여자 정보만 본다. 기존 납부 상태와 금액 계산은 유지한다.

## 현재 흐름과 설계

- `SettlementController` → `GetSettlementUseCase` → `SettlementDetailService` → `SettlementDetailResult` → `SettlementDetailWebResponse`.
- 서비스는 `SettlementItemAllocationStorePort.findAllSubmissions()`로 `submittedParticipantIds`를 이미 계산해 독촉 후보 및 무몫 납부 상태에 사용한다.
- `SettlementDetailResult.ParticipantPayment`와 웹 `ParticipantPayment`에 필드를 추가한다. 서비스의 `payment()`는 조회한 제출 여부를 Share 존재 여부와 관계없이 결과에 담는다.
- `@Schema(requiredProperties)`와 필드 설명을 갱신한다. 신규 UseCase, Port, Domain 규칙은 필요하지 않다.
- DB·Redis·외부 연동·보안 설정 변경 없음. **Flyway migration 불필요**.

## 영향과 검증

- 영향: 상세 결과, 서비스 매핑, 웹 응답, Swagger 및 기존 서비스·컨트롤러·OpenAPI 테스트.
- 경계: 할인 정산의 제출자와 미제출자가 모두 `NOT_READY`여도 `submitted`가 구분되어야 한다. Share가 이미 있는 제출자도 `true`를 유지해야 한다.
- 검증: `./gradlew compileJava`, 관련 테스트, `git diff --check`. 전체 테스트 전에는 PostgreSQL·Redis와 환경 설정을 확인한다.

## 커밋 경계

1. `docs(settlement): 참여자별 제출 여부 응답 계약 계획` — 설계와 검증 기준.
2. `feat(settlement): 상세 응답에 참여자별 제출 여부 추가` — 결과부터 웹 응답까지 연결, Swagger 갱신.
3. `test(settlement): 제출 여부 상세 응답 검증 보강` — 기존 테스트에 제출·미제출 경계 검증.

## 진행 기록

- 2026-09-29: 최신 `origin/develop`에서 설계 확인. 구현 및 검증 예정.
