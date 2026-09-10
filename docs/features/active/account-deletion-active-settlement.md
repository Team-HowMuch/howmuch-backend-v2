# Feature Plan: 진행 중 정산의 수령 계좌 삭제 차단

- Status: Ready for review
- Last updated: 2026-09-10
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/356
- Related ADR: [ADR-0005](../../adr/0005-block-account-deletion-for-active-settlements.md)

## 1. 목적

### 문제

현재 계좌 삭제는 `DRAFT` 정산만 확인하고, `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED` 정산의 수령 계좌 참조를 분리한 뒤 계좌를 삭제한다. 이 상태들은 아직 정산 재개·입금·완료 처리가 남아 있어 진행 중인 정산의 수령 계좌를 잃을 수 있다.

### 지금 해결하는 이유

진행 중 정산이 계좌 삭제로 더 이상 정상적으로 입금을 대조하거나 재개할 수 없게 되기 전에 삭제 정책을 일관되게 제한해야 한다.

### 성공 기준

- [ ] `DRAFT`, `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED`가 수령 계좌로 사용 중이면 계좌 삭제가 `409 ACCOUNT_IN_USE_BY_SETTLEMENT`로 거부된다.
- [ ] 삭제가 거부되면 계좌와 정산의 값이 변경되지 않는다.
- [ ] `COMPLETED` 정산만 수령 계좌를 사용 중이어도 삭제할 수 있고, 보존된 snapshot과 Share는 유지된다.
- [ ] Swagger, Feature Plan, ADR이 실제 상태·오류 계약과 일치한다.

### 범위 제외

- 정산 상태 전이 자체 변경
- `account_records`, 거래내역, 정산 지출, 확정 수령 계좌 snapshot의 보존 정책 변경
- 계좌 삭제 API 경로와 인증 정책 변경

## 2. 도메인 분석

### 도메인 경계

- 재사용할 기존 도메인: `account`, `settlement`
- 새 도메인 필요 여부: 없음
- 관계: Account 삭제 가능 여부가 Settlement의 진행 상태와 수령 계좌 참조에 의존한다.

### 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 진행 중 정산 | 아직 입금·재개·완료 처리가 남은 정산 | `DRAFT`, `REQUESTED`, `AWAITING_DEPOSIT`, `SUSPENDED` |
| 종료 정산 | 추가 진행이 필요 없는 정산 | `COMPLETED` |
| 수령 계좌 사용 | 정산의 활성 수령 계좌 ID가 Account ID와 일치하는 상태 | `settlements.receiving_account_id` |

### 행위자와 UseCase

| 행위자 | 목표 | UseCase |
|---|---|---|
| 계좌 소유자 | 정산 진행을 깨뜨리지 않고 계좌를 삭제한다 | `DeleteAccountUseCase` |

### 상태와 불변식

- 상태 전이: 계좌 삭제 시 `COMPLETED` 정산의 활성 참조만 분리하며 정산 상태는 바꾸지 않는다.
- 항상 지킬 규칙:
  - 진행 중 정산은 수령 계좌가 삭제되기 전에 대체 계좌를 선택해야 한다.
  - 종료 정산은 immutable receiving-account snapshot으로 과거 정보를 유지한다.
- 잘못된 상태: 진행 중 정산이 수령 계좌로 사용하는 Account가 삭제되는 상태

## 3. 시나리오

### 정상 흐름

1. 사용자가 활성 Account 삭제를 요청한다.
2. Application이 소유권·외부 연결·잠금 후 활성 상태를 확인한다.
3. 진행 중 Settlement가 해당 Account를 수령 계좌로 사용하는지 확인한다.
4. 사용 중이면 409를 반환하고 아무 값도 변경하지 않는다.
5. 진행 중 참조가 없으면 종료 정산의 활성 참조를 분리하고 Account를 삭제한다.

### 실패 흐름

- 진행 중 정산이 해당 계좌를 사용 중: `409 ACCOUNT_IN_USE_BY_SETTLEMENT`
- 계좌가 없거나 타인 소유·비활성: `404 ACCOUNT_NOT_FOUND`

### 경계 조건

- 여러 진행 중 정산이 하나의 계좌를 사용해도 하나라도 있으면 삭제를 거부한다.
- `COMPLETED` 정산만 해당 계좌를 사용하면 삭제 후 snapshot과 Share를 유지한다.
- 계좌 삭제와 수령 계좌 선택은 기존 계좌 행 잠금 순서를 유지한다.

## 4. 계약

### Input Port와 경계 매핑

```text
UseCase: DeleteAccountUseCase.delete(Long accountId)
Command: 없음
Result: 없음
Web Request DTO -> Command: 없음
Result -> Web Response DTO: ApiResponse<Void>
```

### Output Ports

| Port | 목적 | 호출자 | Adapter |
|---|---|---|---|
| `SettlementReceivingAccountUsagePort` | 진행 중 수령 계좌 사용 여부 조회 및 종료 정산 참조 분리 | `AccountDeletionService` | `SettlementPersistenceAdapter` |

### Application Policy

- 별도 Policy 없음. 상태 비교와 삭제 순서는 기존 `AccountDeletionService`가 오케스트레이션한다.

### Web API

```text
DELETE /api/v2/accounts/{accountId}
Request: 없음
Response: HTTP 200, ApiResponse<Void>
Errors: 404 ACCOUNT_NOT_FOUND, 409 ACCOUNT_IN_USE_BY_SETTLEMENT
Authentication: required
```

### Persistence와 외부 연동

- DB Entity/제약/쿼리: 진행 중 상태 조회와 `COMPLETED` 참조 분리 JPQL 변경
- Flyway migration required: No
- Redis/외부 API/환경 변수: 변경 없음

## 5. 설계 결정

### 비즈니스 로직

- 진행 중 상태 차단은 `SettlementReceivingAccountUsagePort` 조회로 판단한다.
- 종료된 `COMPLETED` 정산만 기존 snapshot 보존 정책에 따라 활성 참조를 분리한다.
- 기존 `ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT` 대신 상태 범위를 표현하는 `ACCOUNT_IN_USE_BY_SETTLEMENT`를 반환한다.

### 기술 및 패턴

- 선택 방식: `status <> COMPLETED` 조건으로 단일 존재 조회
- 이유: 새 상태가 추가되어도 종료 상태만 명시적으로 허용하고, 삭제 흐름에서 불필요한 목록 조회를 하지 않는다.
- 대안: 모든 정산 상태를 차단하는 방식은 완료된 정산까지 계좌 삭제를 영구적으로 막을 수 있어 선택하지 않는다.
- 한계: 새로운 terminal 상태가 추가되면 이 조건과 테스트를 함께 검토해야 한다.

## 6. 영향 범위

| 영역 | 영향 | 확인 파일/계약 |
|---|---|---|
| API | 409 오류 코드와 설명 변경 | `AccountDocs`, `AccountStatusCode` |
| Application | 진행 중 정산 참조 확인 후 삭제 | `AccountDeletionService` |
| Persistence | 상태 기준 존재 조회, 완료 정산 참조 분리 | `SettlementJpaRepository`, `SettlementPersistenceAdapter` |
| Documentation | 기존 계좌 삭제 정책 갱신 | `account-deletion-and-reconnection.md`, `ADR-0005` |

기존에 유지할 동작:

- 계좌 소유권·활성 계좌·외부 연결 검증과 계좌 잠금 순서
- Account Record 및 과거 거래·정산 지출·snapshot 보존
- `COMPLETED` 정산의 snapshot·Share 보존

## 7. 구현 계획

- [x] 이슈 #356 등록
- [x] 최신 `develop` 기반 `fix/356-settlement-account-deletion` 브랜치 생성
- [x] Feature Plan 및 ADR 작성
- [x] 상태 코드·Port·Repository·Service 변경
- [x] 단위·Persistence·Controller 테스트 갱신
- [x] 기존 Feature Plan과 Swagger 문서 갱신
- [x] 컴파일·테스트·diff 검증
- [ ] 커밋·push 및 `develop` 대상 PR 생성

### 예상 커밋 경계

```text
fix(account): 진행 중 정산 수령 계좌 삭제 차단
```

## 8. 검증

### 명령

```bash
./gradlew test --tests '*AccountDeletionServiceTest' --tests '*AccountDeletionPersistenceIntegrationTest' --tests '*AccountControllerTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

### 완료 기준

- [x] 진행 중 네 상태의 삭제 차단 테스트를 추가한다.
- [x] `COMPLETED` 삭제와 snapshot·Share 보존 경로를 테스트한다.
- [x] 삭제 거부 시 계좌·정산 변경이 없음을 테스트한다.
- [x] 변경된 서비스·Controller 단위 테스트와 Java 컴파일을 통과한다.
- [ ] PostgreSQL Persistence 통합 테스트를 실행한다.

## 9. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견한 위험 |
|---|---|---|---|
| 2026-09-10 | 이슈 #356 등록, `develop` 기반 브랜치 생성, 정책·ADR·코드·테스트·문서 구현 | PR 생성 | 로컬 PostgreSQL이 `howmuch` 사용자 인증을 거부해 Persistence 통합 테스트 실행 불가 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 | 대안/영향 |
|---|---|---|---|
| 2026-09-10 | `COMPLETED`만 삭제 허용 | 진행 중 정산은 수령 계좌가 필요하고 완료 정산은 snapshot으로 보존됨 | 모든 상태 차단은 과거 완료 정산 때문에 삭제가 막힘 |

## 11. 완료 기록

- 완료일: PR 생성 후 기록
- 최종 동작: `COMPLETED`만 수령 계좌 참조를 분리하며, 진행 중 정산 참조가 있으면 `409 ACCOUNT_IN_USE_BY_SETTLEMENT`로 계좌 삭제를 거부한다.
- 검증하지 못한 항목: 로컬 PostgreSQL 인증 오류로 Persistence 통합 테스트 미실행
- 남은 위험: 운영/CI PostgreSQL에서 네 상태와 완료 정산 보존 테스트를 재검증해야 한다.
- 후속 작업: PR 리뷰 및 CI 결과 확인
