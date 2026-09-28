# 정산 목록 API 공통 종료 시각

- Status: Complete (local implementation; commit/PR not requested)
- Last updated: 2026-09-28
- Related issue: [#435](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/435)
- Related ADR: 없음

## 1. 목적

### 문제

정산 목록 API의 `completedAt`은 `COMPLETED` 상태에서만 제공된다. `SUSPENDED`에는 종료 시각이 없어 프론트엔드가 상태별 시각 필드를 분기해야 한다.

### 성공 기준

- [x] 목록 항목에 공통 `endedAt` 필드를 제공한다.
- [x] 현재 상태가 `COMPLETED` 또는 `SUSPENDED`이면 UTC 종료 시각을 반환하고 그 외 상태에서는 `null`을 반환한다.
- [x] 재개되어 `REQUESTED`인 정산은 `endedAt: null`이고 재중단 시 새 중단 시각을 반환한다.
- [x] 기존 `completedAt` 응답과 `yearMonth` 필터 동작을 유지한다.
- [x] Swagger 및 테스트가 실제 응답 계약과 일치한다.

### 범위 제외

- 상세 API 응답, 프론트엔드 변경
- `yearMonth` 필터에 `SUSPENDED` 포함
- `completedAt` 제거 또는 폐기 시점 결정

## 2. 도메인 및 흐름

- 기존 정산 도메인/목록 UseCase를 재사용한다.
- 상태 전이는 `REQUESTED -> SUSPENDED -> REQUESTED` 또는 `AWAITING_DEPOSIT -> COMPLETED`다.
- 상태 전이는 UTC `now`로 정산을 갱신한다. 중단 상태를 유지한 채 수정하는 별도 상태 전이 경로는 현재 조회 결과에서 확인되지 않았다.
- 영향 흐름: `GET /api/v2/settlements` -> `SettlementController` -> `GetSettlementsUseCase` / `SettlementListService` -> `LoadSettlementListPort` / `SettlementPersistenceAdapter` -> `SettlementJpaRepository` projection -> `SettlementListReadModel` -> `SettlementListItemResult` -> `SettlementListItemWebResponse`.

## 3. API 계약

`GET /api/v2/settlements` 목록 항목에 다음 필드를 추가한다.

| 필드 | 의미 |
|---|---|
| `endedAt` | 현재 상태가 `COMPLETED`이면 완료 시각, `SUSPENDED`이면 현재 중단 시각. 그 외 상태는 `null`. UTC 기준 offset 없는 시각. |
| `completedAt` | 기존 호환 필드. 현재 의미와 값은 변경하지 않는다. |

별도 UseCase, Port, Domain 필드는 필요하지 않으며 기존 목록 조회 계약에 결과 필드만 추가한다.

## 4. 저장 및 기술 결정

- Database Entity/스키마 변경: 없음.
- Flyway migration required: No.
- 근거: 완료 및 중단 상태 진입은 기존 상태 전이에서 `updated_at`을 갱신한다. `endedAt` projection은 현재 상태가 `COMPLETED` 또는 `SUSPENDED`일 때만 `updated_at`을 읽는다. 재개 시 상태가 `REQUESTED`로 바뀌어 `null`이 되며 재중단은 새로운 상태 전이 시각을 갖는다.
- 기존 `completedAt` projection과 완료 월 필터는 그대로 유지한다.

## 5. 영향 표면

| 영역 | 영향 |
|---|---|
| API | 목록 응답에 nullable `endedAt` 추가 |
| Application | `SettlementListReadModel`, `SettlementListItemResult`, `SettlementListService` 전달 필드 추가 |
| Persistence | `SettlementJpaRepository`의 상태별 시각 projection 추가 |
| Swagger | 상태별 의미와 UTC/null 규칙 명시 |
| Tests | 서비스 전달, 상태별 SQL projection, JSON/OpenAPI 스키마 검증 |

변경하지 않을 동작: `completedAt`, 완료 월 `yearMonth` 필터, 정산 상세 응답 및 상태 전이.

## 6. 검증 및 커밋 경계

- [x] `./gradlew compileJava` — 성공.
- [x] 목록 서비스/컨트롤러/OpenAPI 및 `SettlementListPersistenceIntegrationTest` 관련 테스트 — 성공.
- [x] 전체 `./gradlew test` — 1,330 tests, 0 failures, 0 errors, 0 skipped.
- [x] `git diff --check` — 성공; 격리 worktree에 이번 기능 파일만 변경.

### 검증 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 컴파일 | 성공 | `./gradlew compileJava` |
| 집중 테스트 | 성공 | `SettlementListServiceTest`, `SettlementControllerTest`, `SettlementListPersistenceIntegrationTest`, `SettlementOpenApiIntegrationTest` |
| 전체 테스트 | 성공 | `./gradlew test`, 1,330 tests / 0 failures / 0 errors / 0 skipped |
| diff 공백 검사 | 성공 | `git diff --check` |

예상 커밋 경계(이번 요청에서는 실제 커밋하지 않음):

```text
docs(settlement): 종료 시각 API 계획 기록
feat(settlement): 목록 응답에 공통 종료 시각 제공
```

## 7. 진행 기록

| 날짜 | 완료 | 다음 작업 | 위험 |
|---|---|---|---|
| 2026-09-28 | 이슈 #435 등록, 최신 `origin/develop`에서 `feat/435-settlement-ended-at` 생성, API 필드·Swagger·Feature Plan·상태별 테스트 구현 및 전체 검증 | 사용자 요청 시 기능별 커밋/PR 진행 | migration 없음; `completedAt`·완료 월 필터 호환 유지 |
