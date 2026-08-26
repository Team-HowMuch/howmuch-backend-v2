# Feature Plan: 정산 상세 납부 현황 조회

- Status: Completed
- Last updated: 2026-08-26
- Related issue: [#138](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/138), [#161](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/161), [#162](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/162), [#163](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/163)
- Related ADR: N/A

## 1. Purpose

### Problem

`GET /api/v2/settlements/{settlementId}`는 생성자에게 정산 기본 정보만 반환한다. 생성자는 누가 납부했는지, 얼마가 입금됐는지, 전체 진행도가 얼마인지 확인할 수 없고 연결 참여자도 자신의 납부 상태를 상세 화면에서 확인할 수 없다.

### Success criteria

- [x] 생성자는 정산 기본 정보, 생성자 이름, 전체 납부 요약과 모든 납부 대상자의 상태를 조회한다.
- [x] 연결 참여자는 동일한 요약과 자신의 납부 행만 조회한다.
- [x] 미연결 사용자는 정산을 조회할 수 없다.
- [x] Share가 없는 참여자는 금액 미확정 상태로 반환한다.
- [x] 완료 금액, 미납 금액과 진행률은 실제 입금 대조 결과와 일치한다.
- [x] 내부 사용자 ID, 연락처 원본, 계좌번호는 응답하지 않는다.

### Out of scope

- 입금 대조 실행 및 정산 상태 전이 변경
- 토스 송금 링크 생성과 독촉 알림
- 거래내역 원문 또는 수령 계좌번호 노출

## 2. Domain analysis

- Existing domain to reuse: `Settlement`, `SettlementParticipant`, `SettlementShare`, `SettlementDeposit`
- Actor: 정산 생성자, `linkedUserId`로 연결된 참여자
- State transitions: 없음. 저장된 정산과 입금 대조 결과를 읽는 기능이다.
- Invariants:
  - 생성자 Share는 수금 대상에서 제외한다.
  - `confirmedAmount`는 `SettlementDeposit`에 연결된 실제 입금 거래 금액 합이다.
  - 참여자 납부 상태는 `NOT_READY`, `UNPAID`, `PARTIALLY_PAID`, `PAID` 중 하나다.
  - 생성자는 전체 납부 대상자를, 연결 참여자는 자신만 볼 수 있다.

## 3. Contracts

### Application

```text
GetSettlementUseCase.get(UUID settlementId) -> SettlementDetailResult

SettlementDetailResult:
  settlementId, title, totalAmount, allocationType, status, createdAt
  viewerRole, creatorName
  paymentSummary(expectedAmount, confirmedAmount, unpaidAmount,
                 progressPercent, participantCount, completedParticipantCount)
  participants(participantId, displayName, expectedAmount,
               confirmedAmount, paymentStatus)
```

### Web API

```text
GET /api/v2/settlements/{settlementId}
Authentication: required
Errors: 401 UNAUTHORIZED, 403 SETTLEMENT_ACCESS_DENIED, 404 SETTLEMENT_NOT_FOUND
```

### Persistence

- 기존 정산, 참여자, Share 조회 Port를 재사용한다.
- `SettlementDepositStorePort`에 정산별 Share 입금 합계 조회를 추가한다.
- `settlement_deposits`와 `account_transactions`를 조인해 Share별 금액을 합산한다.
- Flyway migration required: No
- Redis, 외부 API, 환경 변수 변경: 없음

## 4. Design decisions

- `SettlementResult`는 생성·수정 결과로 유지하고 상세 조회 전용 `SettlementDetailResult`를 추가한다.
- 기존 단건 경로의 응답을 상세 전용 `SettlementDetailWebResponse`로 교체해 내부 `creatorId`를 제거한다.
- 진행률은 `confirmedAmount * 100 / expectedAmount`의 내림 정수값이다. 납부 예정액이 없으면 진행 중은 0, 완료 상태는 100이다.
- Share가 아직 없는 참여자는 `expectedAmount = null`, `paymentStatus = NOT_READY`로 반환한다.
- 부분 입금은 `0 < confirmedAmount < expectedAmount`로 표시한다.

## 5. Impact surface

| Area | Impact |
|---|---|
| API | 단건 상세 응답 확장, Swagger 수정 |
| Application | 상세 Result와 조회 Service 추가 |
| Persistence | Share별 확인 입금액 집계 쿼리 추가 |
| Domain | 기존 모델 재사용, 변경 없음 |
| Security | 생성자 또는 연결 참여자만 조회 |
| Migration | 불필요 |

Existing behavior that must remain unchanged:

- 정산 생성·수정·중단·재개 응답은 기존 `SettlementWebResponse`를 유지한다.
- 입금 대조와 완료 전환 로직은 변경하지 않는다.
- 목록 조회 계약은 변경하지 않는다.

## 6. Implementation plan

- [x] Migration-only pull request: N/A
- [x] 상세 Application Result와 납부 상태 enum 추가
- [x] Share별 확인 입금액 조회 Port와 Adapter 추가
- [x] 생성자·연결 참여자 권한 및 상세 조합 Service 구현
- [x] 상세 Web Response, Controller, Swagger 수정
- [x] Application, Web, Persistence 검증 추가
- [x] Feature Plan 결과 갱신 및 완료 문서 이동

### Expected commit boundaries

```text
docs(settlement): 정산 상세 납부 현황 계약 작성
feat(settlement): 정산 상세 납부 현황 조회 구현
test(settlement): 정산 상세 권한과 입금 집계 검증
```

## 7. Verification

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementDetailServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementDepositPersistenceIntegrationTest'
./gradlew test
git diff --check
git status --short --untracked-files=all
```

### Results

| Item | Result | Evidence |
|---|---|---|
| 컴파일 | Passed | `./gradlew compileJava` |
| 상세 Application·HTTP 테스트 | Passed | `SettlementDetailServiceTest`, `SettlementControllerTest` |
| PostgreSQL 입금 합계 검증 | Passed | `SettlementDepositPersistenceIntegrationTest` |
| 전체 회귀 테스트 | Passed | PostgreSQL 16·Redis 7 격리 환경에서 407 tests, 0 failures |
| Swagger 런타임 | Passed | `/v3/api-docs`에서 `SettlementDetailWebResponse`, 납부 요약·상태 schema 확인 |
| 저장소 검사 | Passed | `git diff --check` |

## 8. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-26 | `origin/develop`의 단건 조회, Share, 입금 대조 저장 흐름과 #138, #161~#163 분석 | 상세 계약 구현 | 기존 단건 응답의 `creatorId` 제거는 응답 계약 변경 |
| 2026-08-26 | 상세 Result, 권한, Share별 입금 합계, Web·Swagger 계약과 검증 구현 | PR 검토 및 게시 | 참여자에게는 전체 요약과 본인 행만 노출 |

## 9. Completion record

- Completed on: 2026-08-26
- Final behavior: `GET /api/v2/settlements/{settlementId}`가 생성자·연결 참여자에게 역할별 정산 상세와 실제 입금 기준 납부 현황을 반환한다.
- Unverified items: 없음
- Remaining risks: 기존 단건 응답의 `creatorId`, `receivingAccountId`를 제거했으므로 해당 필드를 사용하던 클라이언트는 새 상세 계약으로 전환해야 한다.
- Follow-up work: 토스 송금 링크 #176, 정산 독촉 #177
