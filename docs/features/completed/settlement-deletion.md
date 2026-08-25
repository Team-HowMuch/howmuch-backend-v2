# Feature Plan: 정산 삭제 가능 정책 및 삭제 API

- Status: Completed
- Last updated: 2026-08-25
- Related issue: [#159 정산 삭제 가능 정책 및 삭제 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/159)
- Related parent issue: [#137 정산 수동 중단·재개·삭제 상태 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/137)
- Related issues: [#136 정산 입금 상태 확인 및 종료 처리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/136), [#158 정산 수동 중단·재개 UseCase 및 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/158)

## 1. 목적

### 문제

생성자가 정산을 잘못 만들었을 때 초안·참여 진행 중인 정산은 삭제할 수 있어야 한다. 반면 `SettlementShare`가 생성되어 참여자의 송금 단계가 시작된 정산을 삭제하면 송금 대상·수령 계좌 스냅샷·입금 확인 이력이 사라질 수 있다. 현재 정산 전체를 삭제하는 UseCase와 API가 없다.

### 지금 필요한 이유

삭제 허용 경계를 송금 단계 전으로 고정하고, 송금 단계 이후에는 삭제를 차단해야 #137의 정산 수명주기와 후속 #136 입금 확인 흐름이 데이터 유실 없이 연결된다.

### 성공 기준

- [ ] 정산 생성자만 정산을 삭제할 수 있다.
- [ ] `DRAFT`, `REQUESTED`, `SUSPENDED` 상태에서 `SettlementShare`가 없으면 삭제할 수 있다.
- [ ] `SettlementShare`가 있거나 `AWAITING_DEPOSIT`·`COMPLETED` 상태이면 삭제를 차단한다.
- [ ] 삭제 허용 시 참여자·지출·품목·선택·제출·더치페이 배분·링크·수령 계좌 스냅샷이 함께 삭제되고 고아 데이터가 남지 않는다.
- [ ] 삭제 차단 시 데이터가 변경되지 않는다.
- [ ] 삭제와 상태 변경·입금 확인이 같은 정산 행 잠금으로 직렬화된다.
- [ ] 반복 삭제·다른 사용자·없는 정산 요청이 일관된 오류를 반환한다.
- [ ] Swagger와 실제 API 계약이 일치한다.

### 범위 제외

- `SettlementShare`가 생성된 정산의 이력 삭제·soft delete
- 입금 확인 상세 상태·이력 모델 추가(`#136`)
- 계좌·CODEF 자격 증명 삭제
- 삭제 감사 로그 또는 복구 기능
- #160 통합 검증 전용 테스트 보강

## 2. 도메인 분석

### 도메인 경계

- Existing domain to reuse: `Settlement`와 정산 하위 저장 테이블
- Reason a new domain is required, if applicable: 없음. 삭제 가능 여부는 Settlement 수명주기 정책이다.
- Relationships to other domains: `user`는 생성자 권한을 제공하고, `account`는 수령 계좌 스냅샷의 원본 계좌 기록을 보존한다. 송금 단계는 `SettlementShare`와 Settlement 상태를 통해 판단한다.

### 용어

| Term | Meaning | Code representation |
|---|---|---|
| 송금 단계 도달 | 참여자별 확정 송금 금액이 만들어진 상태 | `SettlementShare` 존재 |
| 삭제 가능 정산 | 송금 단계에 도달하지 않아 전체 정산 데이터를 제거할 수 있는 상태 | `DRAFT`, `REQUESTED`, `SUSPENDED` + no Share |
| 삭제 불가 정산 | 송금·입금 흐름의 이력 보존이 필요한 상태 | Share 존재 또는 `AWAITING_DEPOSIT`, `COMPLETED` |
| 정산 전체 삭제 | Settlement root 삭제와 DB cascade로 소유 하위 행을 함께 제거하는 동작 | `DeleteSettlementPort` |

### 행위자와 UseCase

| Actor | Goal | UseCase |
|---|---|---|
| 로그인한 생성자 | 삭제 가능한 정산 전체를 삭제한다. | `DeleteSettlementUseCase` |

### 상태와 불변식

- 삭제 판단은 잠금으로 읽은 최신 Settlement와 같은 트랜잭션에서 Share 존재 여부를 확인한다.
- `SettlementShare`가 하나라도 있으면 상태가 `SUSPENDED`여도 삭제하지 않는다.
- `AWAITING_DEPOSIT`와 `COMPLETED`는 Share 데이터가 비어 있는 비정상 상태에서도 삭제하지 않는다.
- 삭제 허용 시 root `settlements` 삭제만 수행하고 기존 Flyway `ON DELETE CASCADE`에 하위 행 삭제를 맡긴다.
- `account_records`와 User 데이터는 정산 삭제 대상이 아니다.

## 3. 시나리오

### 정상 흐름

1. 생성자가 `DELETE /api/v2/settlements/{settlementId}`를 호출한다.
2. Application Service가 현재 사용자와 생성자 여부를 확인하면서 Settlement 행을 잠근다.
3. Service가 Share 존재 여부를 조회하고 `SettlementDeletionPolicy`에 상태와 사실을 전달한다.
4. Policy가 삭제 가능하면 `DeleteSettlementPort`가 Settlement root를 삭제한다.
5. PostgreSQL cascade가 정산 소유 하위 행을 함께 삭제하고 200 응답을 반환한다.

### 실패 흐름

- 정산이 없으면 `SETTLEMENT_NOT_FOUND`.
- 생성자가 아니면 `SETTLEMENT_ACCESS_DENIED`.
- Share가 존재하거나 상태가 `AWAITING_DEPOSIT`·`COMPLETED`이면 `SETTLEMENT_NOT_DELETABLE`.
- 첫 삭제가 끝난 뒤 반복 삭제하면 `SETTLEMENT_NOT_FOUND`.
- 상태 변경·입금 확인이 먼저 같은 행을 잠근 경우, 삭제 Service는 최신 상태와 Share를 다시 확인해 허용 또는 차단한다.

### 경계 조건

- 하위 데이터가 없는 DRAFT도 정상 삭제한다.
- `SUSPENDED` 정산은 Share가 없으면 삭제하고, Share가 있으면 삭제하지 않는다.
- Share 존재 조회가 false인 `REQUESTED` 정산은 현재 품목 참여 진행 중으로 간주해 삭제할 수 있다.
- cascade가 실패하면 트랜잭션을 롤백해 Settlement와 하위 데이터가 부분 삭제되지 않도록 한다.

## 4. 계약

### Input Port

```text
DeleteSettlementUseCase.delete(UUID settlementId) -> void
Web Request DTO: 없음
Web Response: ApiResponse<Void>
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementSharePort.existsBySettlementId` | 송금 단계 도달 여부 확인 | `SettlementService` | `SettlementTransferInstructionPersistenceAdapter` |
| `DeleteSettlementPort.delete` | Settlement root 삭제 | `SettlementService` | `SettlementPersistenceAdapter` |

기존 `LoadSettlementPort.findByIdForUpdate`를 재사용한다. `SettlementShare` 전체 목록을 읽지 않고 `exists` 쿼리만 추가한다.

### Application Policy

- Decision owned by the Policy: Settlement 상태와 Share 존재 사실을 조합한 삭제 가능 여부
- Facts loaded by the Service and passed to the Policy: `Settlement.status()`, `hasShares`
- Why the rule is not an Entity-local invariant: Share는 외부 Persistence Port에서 조회하는 다른 Aggregate 데이터이고, 삭제 정책은 상태와 저장 사실을 함께 판단한다.
- Future inputs deliberately not implemented yet: #136에서 추가될 Share별 입금 상태·이력은 해당 Port 계약이 확정된 뒤 정책 입력으로 연결한다.

### Web API

```text
DELETE /api/v2/settlements/{settlementId}
Request: 없음
Response: ApiResponse<Void>, 200
Errors: 401, SETTLEMENT_ACCESS_DENIED(403), SETTLEMENT_NOT_FOUND(404), SETTLEMENT_NOT_DELETABLE(409)
Authentication: bearerAuth
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: Share 존재 파생 쿼리와 Settlement root delete adapter 추가. 기존 FK cascade를 사용한다.
- Flyway migration required: No
- Planned migration: N/A
- Migration pull request: N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. 설계 결정

### Business logic

- Domain: 삭제 전 상태 전이는 별도로 만들지 않는다. Entity-local 값 검증이 아니라 Application Policy가 삭제 가능 여부를 결정한다.
- Application Policy: `DRAFT/REQUESTED/SUSPENDED`이고 Share가 없을 때만 허용한다.
- Application Service: CurrentActor 조회, Settlement 행 잠금, Share 존재 조회, Policy 검증, root 삭제를 한 트랜잭션으로 조정한다.
- Persistence: 기존 `ON DELETE CASCADE`를 통해 정산 소유 하위 데이터를 원자적으로 제거한다.

### Technology and patterns

- Constraint to solve: Share·입금 이력이 있는 정산 삭제와 cascade 누락으로 인한 부분 삭제를 막아야 한다.
- Selected approach: Share `exists` 조회 후 잠긴 Settlement root를 삭제하고 DB cascade를 사용한다.
- Reason for selection: 삭제 대상 하위 테이블을 Application에서 하나씩 지우는 것보다 FK가 정의한 소유 경계를 그대로 사용해 누락·순서 오류를 줄인다.
- Alternatives considered: soft delete는 기존 모든 조회 계약과 상태 필터를 바꿔야 하므로 이번 기능에서 제외한다. 하위 Port별 수동 삭제는 새 Port와 삭제 순서가 늘어난다.
- Trade-offs and limits: 삭제 가능 정산은 복구·감사할 수 없다. Share가 생성된 이후 삭제를 차단하는 보존 경계로 제한한다.

## 6. 영향 범위

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 정산 root DELETE 추가 | `SettlementController`, `SettlementDocs` |
| Application | Delete UseCase·Policy·Service 흐름 추가 | `application.port.in.settlement`, `SettlementDeletionPolicy`, `SettlementService` |
| Domain | 삭제 전용 상태 메서드 없음 | `Settlement` 재사용 |
| Persistence | Share exists와 root delete 추가 | `SettlementTransferInstructionPersistenceAdapter`, `SettlementPersistenceAdapter` |
| Redis | 없음 | N/A |
| Security | bearer 인증과 생성자 권한 재사용 | `CurrentActorPort`, `Settlement.isCreatedBy` |
| Configuration | 없음 | N/A |
| Documentation | Feature Plan·Swagger·정산 상태 문서 갱신 | `docs/features/active`, `SettlementDocs` |

기존에 유지할 동작:

- #158의 수동 중단·재개 상태 전이는 변경하지 않는다.
- Share가 생성된 정산의 송금 안내·입금 확인 대상 데이터는 삭제하지 않는다.
- 계좌와 `account_records` 이력은 정산 삭제로 삭제하지 않는다.

## 7. 구현 계획

- [x] Migration-only pull request (`N/A`, 기존 FK·상태·테이블 재사용)
- [x] `DeleteSettlementUseCase`
- [x] Required Output Ports
- [x] Domain models and rules (`N/A`, Application Policy로 처리)
- [x] `SettlementDeletionPolicy`
- [x] `SettlementService`
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controller, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`)
- [x] Domain·Application·Web 단위 테스트
- [x] PostgreSQL cascade 통합 테스트 코드 작성
- [x] PostgreSQL cascade 통합 테스트 실행

### 예상 커밋 경계

커밋·push는 사용자가 별도로 요청하기 전까지 수행하지 않는다.

```text
feat(settlement): 정산 삭제 가능 정책과 API 추가
test(settlement): 정산 삭제 권한과 연관 데이터 무결성 검증 추가
```

## 8. 검증

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementDeletionPolicyTest' --tests '*SettlementServiceTest' --tests '*SettlementControllerTest'
./gradlew test --tests '*SettlementDeletionPersistenceIntegrationTest'
git diff --check
git status --short --untracked-files=all
```

PostgreSQL이 필요한 cascade 통합 테스트는 서비스 상태를 확인한 뒤 실행한다.

### 완료 기준

- [x] 생성자·다른 사용자·없는 정산 권한 경계 확인
- [x] Share 전 삭제 허용·Share 후 삭제 차단 확인
- [x] 삭제 후 모든 정산 하위 테이블의 고아 데이터 없음 확인
- [x] 반복 삭제·동시 상태 변경에서 500/무결성 오류 없음 확인
- [x] Swagger와 실제 응답 일치 확인
- [x] 컴파일·관련 테스트·diff 검증 기록

### Results

| Item | Result | Evidence |
|---|---|---|
| Java compilation | Pass | `./gradlew compileJava compileTestJava` |
| Deletion policy/service/web tests | Pass | `SettlementDeletionPolicyTest`, `SettlementServiceTest`, `SettlementControllerTest` |
| PostgreSQL cascade integration | Pass | `.env` DB 설정으로 삭제·요청·품목 배분 통합 테스트 통과 |
| Redis-dependent runtime verification | Pass | 전체 테스트 스위트 316건 통과 |
| Runtime Swagger | Pass | 삭제·중단·재개 경로와 `200/401/403/404/409` 응답 정의 확인 |
| Diff validation | Pass | `git diff --check` |

## 9. 진행 로그

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-25 | Share 생성 시점, Settlement 상태, 정산 하위 FK cascade, 기존 행 잠금 패턴을 조사했다. 마이그레이션 불필요로 판단했다. | UseCase·Policy·Port·삭제 API 구현 | #136 입금 이력 모델은 아직 없어 현재는 Share와 상태를 보존 경계로 사용해야 함 |
| 2026-08-25 | 삭제 UseCase·Policy·Share exists·root delete·DELETE API와 단위 테스트, cascade 통합 테스트 코드를 추가했다. PostgreSQL·Redis·Swagger 검증까지 완료했다. | 없음 | #136 입금 상태 모델 추가 시 삭제 차단 정책 입력을 확장할 수 있음 |

## 10. 결정 로그

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-25 | Share 존재 또는 `AWAITING_DEPOSIT`·`COMPLETED`이면 삭제 차단 | 송금 대상과 입금 흐름의 시작 이후 이력 보존 | #136의 실제 입금 상태가 추가되면 Policy 입력을 확장한다 |
| 2026-08-25 | 삭제 허용 시 Settlement root만 삭제하고 FK cascade 재사용 | 하위 삭제 순서와 누락을 DB 소유 경계에 맡기기 위해 | soft delete·수동 하위 삭제는 이번 범위에서 제외 |

## 11. 완료 기록

- Completed on: 2026-08-25
- Final behavior: Share가 없는 송금 전 정산만 생성자가 삭제할 수 있고, 삭제 시 정산 소유 하위 데이터는 cascade로 제거된다.
- Unverified items: 없음
- Remaining risks: #136 입금 상태 모델 도입 전까지 `SettlementShare` 존재를 송금 단계 기준으로 사용한다.
- Follow-up work: #136 입금 상태 모델과 삭제 정책 연결
