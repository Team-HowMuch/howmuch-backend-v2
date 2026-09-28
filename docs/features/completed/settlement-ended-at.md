# 정산 API 공통 종료 시각

- Status: Complete (PR #436)
- Last updated: 2026-09-28
- Related issue: [#435](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/435)
- Related ADR: 없음

## 1. 목적

### 문제

정산 목록 API에는 `endedAt`이 추가됐지만 상세 API에는 아직 없어, 목록과 상세의 완료·중단 시각 계약이 다르다.

### 성공 기준

- [x] 목록 항목에 공통 `endedAt` 필드를 제공한다.
- [x] 현재 상태가 `COMPLETED` 또는 `SUSPENDED`이면 UTC 종료 시각을 반환하고 그 외 상태에서는 `null`을 반환한다.
- [x] 재개되어 `REQUESTED`인 정산은 `endedAt: null`이고 재중단 시 새 중단 시각을 반환한다.
- [x] 상세 API에도 같은 nullable `endedAt` 필드와 상태별 의미를 제공한다.
- [x] 기존 `completedAt` 응답과 `yearMonth` 필터 동작을 유지한다.
- [x] Swagger 및 목록·상세 테스트가 실제 응답 계약과 일치한다.

### 범위 제외

- 프론트엔드 변경
- `yearMonth` 필터에 `SUSPENDED` 포함
- `completedAt` 제거 또는 폐기 시점 결정

## 2. 도메인 및 흐름

- 기존 정산 도메인/목록 UseCase를 재사용한다.
- 상태 전이는 `REQUESTED -> SUSPENDED -> REQUESTED` 또는 `AWAITING_DEPOSIT -> COMPLETED`다.
- 상태 전이는 UTC `now`로 정산을 갱신한다. 중단 상태를 유지한 채 수정하는 별도 상태 전이 경로는 현재 조회 결과에서 확인되지 않았다.
- 목록 흐름: `GET /api/v2/settlements` -> `SettlementController` -> `GetSettlementsUseCase` / `SettlementListService` -> `LoadSettlementListPort` / `SettlementPersistenceAdapter` -> `SettlementJpaRepository` projection -> `SettlementListReadModel` -> `SettlementListItemResult` -> `SettlementListItemWebResponse`.
- 상세 흐름: `GET /api/v2/settlements/{settlementId}` -> `SettlementController` -> `GetSettlementUseCase` / `SettlementDetailService` -> `LoadSettlementPort`의 `Settlement.updatedAt` -> `SettlementDetailResult` -> `SettlementDetailWebResponse`.

## 3. API 계약

`GET /api/v2/settlements` 목록 항목과 `GET /api/v2/settlements/{settlementId}` 상세 응답에 다음 필드를 제공한다.

| 필드 | 의미 |
|---|---|
| `endedAt` | 현재 상태가 `COMPLETED`이면 완료 시각, `SUSPENDED`이면 현재 중단 시각. 그 외 상태는 `null`. UTC 기준 offset 없는 시각. |
| `completedAt` | 기존 호환 필드. 현재 의미와 값은 변경하지 않는다. |

별도 UseCase나 Port는 필요하지 않다. 상세는 이미 로드된 `Settlement.status`와 `Settlement.updatedAt`으로 계산하고, 목록은 기존 SQL projection을 사용한다.

## 4. 저장 및 기술 결정

- Database Entity/스키마 변경: 없음.
- Flyway migration required: No.
- 근거: 완료 및 중단 상태 진입은 기존 상태 전이에서 `updated_at`을 갱신한다. `endedAt` projection은 현재 상태가 `COMPLETED` 또는 `SUSPENDED`일 때만 `updated_at`을 읽는다. 재개 시 상태가 `REQUESTED`로 바뀌어 `null`이 되며 재중단은 새로운 상태 전이 시각을 갖는다.
- 기존 `completedAt` projection과 완료 월 필터는 그대로 유지한다.

## 5. 영향 표면

| 영역 | 영향 |
|---|---|
| API | 목록·상세 응답에 nullable `endedAt` 제공 |
| Application | 목록 결과 전달 및 `SettlementDetailResult` / `SettlementDetailService` 조립 추가 |
| Persistence | 목록의 `SettlementJpaRepository` 상태별 시각 projection 유지; 상세 저장 조회 변경 없음 |
| Swagger | 상태별 의미와 UTC/null 규칙 명시 |
| Tests | 서비스 전달, 상태별 SQL projection, JSON/OpenAPI 스키마 검증 |

변경하지 않을 동작: `completedAt`, 완료 월 `yearMonth` 필터, 상태 전이.

## 6. 검증 및 커밋 경계

- [x] `./gradlew compileJava`
- [x] 상세 서비스/컨트롤러/OpenAPI와 기존 목록 projection 테스트
- [x] 전체 `./gradlew test`
- [x] `git diff --check`, PR worktree 상태 확인

### 검증 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 컴파일 | 성공 | `./gradlew compileJava` |
| 집중 테스트 | 성공 | `SettlementDetailServiceTest`, `SettlementControllerTest` |
| 전체 테스트 | 성공 | `./gradlew test`, 1,333 tests / 0 failures / 0 errors / 0 skipped |
| diff 공백 검사 | 성공 | `git diff --check` |
| 원격 CI | 실행 전 차단 | GitHub account payment/spending-limit 사유로 runner가 시작되지 않음; 테스트 실패가 아님 |

예상 커밋 경계:

```text
docs(settlement): 종료 시각 API 계획 기록
feat(settlement): 목록 응답에 공통 종료 시각 제공
feat(settlement): 상세 응답에 공통 종료 시각 제공
```

## 7. 진행 기록

| 날짜 | 완료 | 다음 작업 | 위험 |
|---|---|---|---|
| 2026-09-28 | 이슈 #435 및 PR #436에 목록·상세 `endedAt` 계약 구현, Swagger·Feature Plan·상태별 테스트 반영, 전체 로컬 검증 완료 | GitHub Actions billing 제한 해제 후 CI 재실행 | migration 없음; `completedAt`·완료 월 필터 호환 유지 |
