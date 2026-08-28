# Feature Plan: 정산 수명주기 통합 검증

- Status: Completed
- Last updated: 2026-08-25
- Related issue: [#160 정산 수동 중단·재개·삭제 통합 검증](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/160)
- Related issues: [#158 정산 수동 중단·재개 UseCase 및 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/158), [#159 정산 삭제 가능 정책 및 삭제 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/159), [#137 정산 수명주기 상태 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/137), [#193 정산 참여 진행 후 삭제 차단 정책 보강](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/193)
- Related ADR: N/A

## 1. 목적

### 문제

#158과 #159의 상태 전이·삭제 정책은 단위 테스트만으로는 PostgreSQL cascade, 행 잠금, 실제 Swagger 노출까지 확인할 수 없다. #160은 이미 구현된 계약이 실제 저장 스키마와 Web API 문서에서도 유지되는지 검증한다.

### 성공 기준

- [x] `REQUESTED -> SUSPENDED -> REQUESTED` 수동 전이와 자동 중단 회귀가 통과한다.
- [x] 생성자·다른 사용자·없는 정산·허용되지 않은 상태의 오류 계약이 통과한다.
- [x] Share 전 삭제 허용과 Share 후 삭제 차단이 통과한다.
- [x] PostgreSQL에서 정산 하위 행과 스냅샷·링크가 삭제 후 남지 않고 `account_records`는 보존된다.
- [x] 실제 `/v3/api-docs`와 Swagger UI에서 중단·재개·삭제 계약을 확인한다.

### 범위 제외

- 새로운 상태, 정책, Port, Flyway migration 추가
- #136의 실제 입금 상태·거래내역 모델 추가
- 삭제 이력·복구·soft delete

## 2. 검증 대상

| 영역 | 기존 구현·검증 | #160 확인 방법 |
|---|---|---|
| Domain | `SettlementTest` | 수동 중단·재개·잘못된 전이 |
| Application | `SettlementServiceTest`, `SettlementDeletionPolicyTest` | 권한·상태·Share 경계와 제출 초기화 |
| Web | `SettlementControllerTest` | 200 응답과 오류 응답 코드 |
| 자동 중단 | `SettlementItemAllocationServiceTest` | 기존 자동 `SUSPENDED` 전이 회귀 |
| PostgreSQL | `SettlementDeletionPersistenceIntegrationTest`, `SettlementRequestPersistenceIntegrationTest`, `SettlementItemAllocationPersistenceIntegrationTest` | cascade·제출 삭제·스냅샷 보존 |
| Swagger | `SettlementDocs`, `application.yaml` | `/v3/api-docs`, `/swagger-ui.html` 수동 확인 |

## 3. 불변식과 시나리오

- 생성자만 수동 중단·재개·삭제할 수 있다.
- 수동 전이는 `REQUESTED -> SUSPENDED -> REQUESTED`만 허용한다.
- 재개는 제출 행만 삭제하고 품목 선택은 보존한다.
- Share가 없고 상태가 `DRAFT`, `REQUESTED`, `SUSPENDED`이면 삭제할 수 있다.
- Share가 있거나 `AWAITING_DEPOSIT`, `COMPLETED`이면 삭제하지 않는다.
- 삭제는 Settlement root와 정산 소유 하위 행을 같은 트랜잭션에서 처리한다.
- 정산 삭제 뒤 `account_records`와 사용자 데이터는 보존한다.
- 삭제·상태 변경은 `findByIdForUpdate`를 사용해 최신 정산 상태를 기준으로 판단한다.

## 4. 계약

```text
POST /api/v2/settlements/{settlementId}/suspend
POST /api/v2/settlements/{settlementId}/resume
DELETE /api/v2/settlements/{settlementId}

Success: ApiResponse<SettlementWebResponse> or ApiResponse<Void>, 200
Errors: 401, SETTLEMENT_ACCESS_DENIED(403), SETTLEMENT_NOT_FOUND(404),
        INVALID_SETTLEMENT_STATUS_TRANSITION(409), SETTLEMENT_NOT_DELETABLE(409)
Authentication: bearerAuth
```

## 5. 구현 계획

- [x] #158 Domain·Application·Persistence·Web 계약 구현
- [x] [#159 Domain·Application·Persistence·Web 계약 구현](../active/settlement-deletion.md)
- [x] 단위·Web·자동 중단 회귀 테스트 작성
- [x] PostgreSQL cascade·제출 초기화 통합 테스트 작성
- [x] 누락된 Web 오류 계약 테스트 보강
- [x] PostgreSQL·Redis 선행 조건에서 통합 테스트 실행
- [x] 실제 `/v3/api-docs`·Swagger UI 확인

### 예상 커밋 경계

커밋·push는 사용자가 별도로 요청하기 전까지 수행하지 않는다.

```text
test(settlement): 정산 수명주기 Web 오류 계약 검증 추가
docs(settlement): 정산 수명주기 통합 검증 결과 기록
```

## 6. 검증

### 선행 조건

- local PostgreSQL: Flyway와 `@SpringBootTest` 구동 가능 상태
- Redis: Application context와 Redis 통합 테스트 구동 가능 상태
- Swagger 확인 시 local Application과 인증 외 공개 경로

### Commands

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests '*SettlementDeletionPolicyTest' --tests '*SettlementServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementTest' --tests '*SettlementItemAllocationServiceTest'
./gradlew test --tests '*SettlementDeletionPersistenceIntegrationTest' --tests '*SettlementRequestPersistenceIntegrationTest' --tests '*SettlementItemAllocationPersistenceIntegrationTest'
./gradlew bootRun
curl -i http://localhost:8080/v3/api-docs
curl -i http://localhost:8080/swagger-ui.html
git diff --check
git status --short --untracked-files=all
```

### Results

| Item | Result | Evidence |
|---|---|---|
| Java compilation | Pass | `./gradlew compileJava compileTestJava` |
| Domain/Application/Web/automatic suspension tests | Pass | `./gradlew test --tests '*SettlementDeletionPolicyTest' --tests '*SettlementServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementTest' --tests '*SettlementItemAllocationServiceTest'` |
| PostgreSQL integration tests | Pass | `.env` 접속 설정으로 관련 통합 테스트 통과 |
| Redis-dependent runtime verification | Pass | 전체 316개 테스트 통과 |
| Runtime Swagger | Pass | `/v3/api-docs` 200, Swagger UI redirect 후 200, 세 경로의 오류 응답 확인 |
| Diff validation | Pass | `git diff --check` |

## 7. 진행 로그

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-25 | PostgreSQL·Redis 접속 설정을 적용해 전체 316개 테스트와 실제 Swagger를 통과시켰다. 기존 WebMvc 테스트의 신규 UseCase mock 누락도 보완했다. | 없음 | 테스트 실행 시 `.env`를 자동 로드하지 않으므로 DB 환경변수와 테스트용 Discord webhook을 주입해야 함 |

## 8. 완료 기록

- Completed on: 2026-08-25
- Final behavior: #158/#159의 상태 전이·삭제 계약을 PostgreSQL·Redis·전체 테스트·실제 Swagger로 검증했다.
- Unverified items: 없음
- Remaining risks: #160은 Share 존재를 삭제 차단 기준으로 검증했으며, Share 전 품목 선택·제출 데이터 보호는 포함하지 않았다.
- Follow-up work: #193 정산 참여 진행 후 삭제 차단 정책 보강
