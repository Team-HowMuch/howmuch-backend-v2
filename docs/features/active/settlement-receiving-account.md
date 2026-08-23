# Feature Plan: 정산 수령 계좌 조회와 선택

- Status: In Progress
- Last updated: 2026-08-23
- Related issues: [#51 수령 결제수단 조회](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/51), [#53 정산 수령 계좌 선택 API](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/53)
- Parent issue: [#26 정산 요청 링크 및 수금 계좌 설정](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/26)
- Product requirement: [일회성 정산 PRD](../../product/settlement-prd.md)
- Prerequisite: [V15 수령 계좌 선택 스키마 PR](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/114)

## 1. Purpose

### Problem

정산 생성자는 현재 연결된 계좌 중 송금받을 계좌를 정산 초안에 지정할 수 없다. 정산 요청을 확정하려면 참여자에게 안내할 실제 수령 계좌가 먼저 정해져야 한다.

### Success criteria

- [ ] 생성자는 자신이 연결한 활성 원화 입출금 계좌를 기본 계좌 우선 순서로 조회한다.
- [ ] 생성자는 DRAFT 정산에 조회된 계좌 하나를 수령 계좌로 선택하거나 다른 계좌로 교체한다.
- [ ] 다른 사용자의 계좌, 비활성 계좌, 외화·비입출금 계좌는 선택할 수 없다.
- [ ] 정산 조회 응답에서 현재 선택된 `receivingAccountId`를 확인할 수 있다.
- [ ] DRAFT가 아닌 정산의 수령 계좌는 변경할 수 없다.

### Out of scope

- 정산 요청 링크 발급, 수령 계좌 정보 스냅샷, Share 생성
- 계좌 연결·해제·기본 계좌 변경
- Toss 송금 실행과 CODEF 입금 완료 판정
- 수령 계좌 선택 해제 API

## 2. Domain and rules

- 기존 `Settlement`에 nullable `receivingAccountId`를 추가한다. 요청 전에는 미선택일 수 있다.
- `Settlement.selectReceivingAccount`는 DRAFT에서만 양수 계좌 ID를 선택하도록 보장한다.
- 수령 가능 계좌는 활성 상태이면서 현재 생성자 소유이고, `AccountType.DEPOSIT_TRUST`, `productCode=11`, `currency=KRW`를 모두 만족해야 한다.
- 계좌 소유권과 활성 여부는 기존 `LoadAccountPort`를 통해 확인한다. 이슈 #51의 `PaymentMethodLookupPort`와 개발 Stub은 현재의 실제 Account 경계를 중복하므로 추가하지 않는다.
- 조회와 수정 모두 정산 생성자만 가능하다. 존재하지 않거나 타인 소유·비활성인 계좌는 소유 정보 노출을 막기 위해 `ACCOUNT_NOT_FOUND`로 처리한다.
- 계좌가 존재하지만 수령 조건을 만족하지 않으면 `SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED`를 반환한다.

## 3. Contracts

### Input Ports

```text
GetSettlementReceivingAccountsUseCase.get(settlementId)
  -> List<SettlementReceivingAccountResult>

SelectSettlementReceivingAccountUseCase.select(settlementId, command)
  -> SettlementResult
```

### Web API

```text
GET /api/v2/settlements/{settlementId}/receiving-accounts
PUT /api/v2/settlements/{settlementId}/receiving-account
Request: { "receivingAccountId": 1 }
Authentication: required, creator only, DRAFT only
```

후보 응답에는 `accountId`, 기관 코드·이름·아이콘, 계좌명, 마스킹 번호, 기본 계좌 여부, 현재 선택 여부를 포함한다. 정산 응답에는 `receivingAccountId`를 추가한다.

## 4. Persistence and migration decision

- V15에서 `settlements.receiving_account_id`와 `(creator_id, receiving_account_id) -> accounts(user_id, id)` 복합 외래 키가 이미 추가됐다.
- 이번 구현은 기존 컬럼의 JPA 매핑만 추가하므로 새 Flyway 마이그레이션이 필요하지 않다.
- 정산 수정은 기존 비관적 쓰기 잠금과 JPA version 검증을 재사용한다.

## 5. Impact surface

| Area | Change |
| --- | --- |
| Domain | `Settlement` 수령 계좌 선택 상태와 DRAFT 불변식 |
| Application | 후보 조회·선택 UseCase, Command, Result, Service |
| Account boundary | 기존 `LoadAccountPort`, `BankCatalog` 재사용 |
| Persistence | `SettlementJpaEntity`와 Mapper에 V15 컬럼 매핑 |
| Web | GET 후보 조회, PUT 선택, Swagger 계약 |
| Existing API | `SettlementResult`와 `SettlementWebResponse`에 nullable ID 추가 |
| Tests | Domain, Application, Web, PostgreSQL 영속 매핑 검증 |

## 6. Implementation and commit plan

1. `docs: 정산 수령 계좌 구현 계획 추가`
2. `feat: 정산 수령 계좌 도메인과 영속 매핑 추가`
3. `feat: 정산 수령 계좌 조회와 선택 기능 추가`
4. `feat: 정산 수령 계좌 API 추가`
5. `test: 정산 수령 계좌 기능 검증 추가`
6. `docs: 정산 수령 계좌 구현 결과 반영`

각 Java 커밋 전 `./gradlew compileJava`를 실행하고, 완료 전 관련 테스트와 전체 테스트를 실행한다.

## 7. Verification

- Domain: 유효 ID 선택, 0 이하 ID 거부, DRAFT 외 상태 거부
- Application: 기본 계좌 우선 정렬, 비대상 계좌 제외, 본인 계좌 선택, 타인·비활성 계좌 거부, 비지원 계좌 거부
- Web: 후보 응답, 선택 요청 검증, 현재 선택 ID 응답, Swagger path 노출
- PostgreSQL: V15 전체 Flyway 적용, 수령 계좌 ID 저장·조회, 소유자 복합 FK 유지
- Actual HTTP: JWT 인증으로 정산 생성 → 후보 조회 → 계좌 선택 → 정산 재조회, 타인 계좌 선택 실패
- Commands: `./gradlew compileJava`, `./gradlew test`, `git diff --check`, `git status --short --untracked-files=all`

## 8. Progress log

| Date | Completed | Next |
| --- | --- | --- |
| 2026-08-23 | V15 머지와 실제 Account 경계 확인, 구현 범위 확정 | Domain·Application·Web 구현 |

## 9. Results

| Item | Result | Evidence |
| --- | --- | --- |
| 구현 | Pending | 구현 전 |
| 자동 테스트 | Pending | 구현 전 |
| 실제 HTTP·Swagger | Pending | 구현 전 |

