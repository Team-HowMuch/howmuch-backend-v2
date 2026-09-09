# 부분 납부·완납 송금 안내 금액 반영

- 이슈: #299
- 상태: 진행 중
- 관련 규칙: [Architecture](../../architecture.md), [Coding Guidelines](../../coding-guidelines.md)

## 1. 목적과 범위

회원 및 공유 링크 참여자가 이미 확인된 입금액을 다시 송금하지 않도록 안내한다.
10,000원 중 4,000원 납부 시 응답과 링크는 모두 6,000원이어야 한다.
완납·초과 납부는 0원과 빈 링크 배열을 반환한다. CODEF, 입금 매칭, 인증 변경은 제외한다.

## 2. 도메인과 계약

기존 settlement 도메인과 두 송금 조회 UseCase, Command/Result 및 Web 응답 필드를 유지한다.
두 서비스가 참여자를 결정한 뒤 공통 `SettlementTransferInstructionReader`를 호출한다.
`LoadSettlementDepositSummaryPort`의 정산별 확정 입금 집계에서 해당 Share 금액만 읽는다.
기존 Adapter는 settlement_deposits와 account_transactions를 조인하고 Share별 합계를 반환한다.
잔액은 max(0, Share 원액 - 확정 입금 합계)이며, 집계가 없으면 미납이다.

## 3. API와 저장소

- 기존 회원·공유 링크 API에서 amount를 잔액으로 반환하고 같은 값으로 링크를 생성한다.
- 조회 가능한 정산의 완납·초과 납부: HTTP 200, amount=0, transferLinks=[]. 계좌와 식별자는 유지한다.
- 전체 COMPLETED 정산은 기존대로 조회 불가(404)다.
- 잔액이 양수일 때 지원 링크가 없으면 기존 422를 유지한다.
- Flyway migration: No. DB 구조, Redis, 환경 변수, 외부 API 변경 없음.

## 4. 설계와 영향

공통 Reader에서 집계를 한 번 읽고 동일 잔액을 응답·링크에 사용한다.
잔액이 0이면 링크 Adapter를 호출하지 않는다. 별도 Port/Policy/Infra는 필요 없다.
Domain, persistence, 인증·참여 문맥 유효성, REQUESTED/SUSPENDED 허용은 유지한다.
조회 이후 추가 입금까지 고정하는 기능은 아니며 조회 시점의 확인된 입금만 반영한다.

## 5. 구현 및 커밋 계획

1. `docs(settlement): 송금 잔액 반영 설계 기록`
2. `fix(settlement): 확인된 납부 금액을 송금 안내 잔액에 반영` — 구현, Swagger, 기존 테스트 생성자 적응
3. `test(settlement): 부분 납부와 완납 송금 안내 회귀 검증` — 양 경로 회귀 및 완료 기록

Migration, 신규 UseCase/Port, Domain/Policy, Output Adapter, Infra 단계는 기존 계약 재사용으로 N/A.

## 6. 검증

서비스 테스트에서 두 경로의 미납·부분 납부·완납·초과 납부와 다른 Share 집계 제외를 확인한다.
Web 테스트에서 0원·빈 배열 JSON 계약을 확인한다. 기존 상태·접근 제어·링크 테스트도 실행한다.
대상 테스트는 Mockito/standalone MockMvc로 PostgreSQL·Redis·환경 변수가 필요 없다.
전체 테스트와 실제 서버 검증은 별도 PostgreSQL·Redis 및 프로필 환경이 필요하다.

- 예정: `./gradlew compileJava`, 관련 송금 테스트, 문서 링크 확인, `git diff --check`.
- 결과: 검증 후 기록.

## 7. 진행 및 결정 기록

2026-09-09: 최신 origin/develop 기준으로 흐름과 저장 집계를 확인했다.
기존 응답 필드를 유지하며 0원·빈 링크로 완납을 표현한다. 전체 정산 상태 제한은 유지한다.
