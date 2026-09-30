# Feature Plan: 미확정 품목 정산 목록 금액 보정

- Status: Completed
- Last updated: 2026-09-30
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/452
- Related ADR: N/A

## 1. 목적

품목 선택 전에는 Share가 없어 낼 정산 목록의 `amountToPay`가 null로 반환되고,
앱에서 중단 정산을 0원으로 표시한다. 선택 대기·중단 상태에서 정산 규모를 확인할 수 있도록
미확정 금액의 목록 표시값을 보정한다.

- [x] `ITEM_ALLOCATION` + `REQUESTED`/`SUSPENDED`이고 본인 Share가 없으면 정산 총액을 반환한다.
- [x] 본인 Share가 있으면 확정 개인 금액을 유지한다.
- [x] 더치페이, 선택 종료 후 상태, RECEIVE 계약과 기존 필터를 유지한다.
- 제외: 상세 요약, 품목 선택·납부 규칙, 프론트 변경, 개인 미확정 금액의 추정 배분.

## 2. 도메인 분석

- 기존 `settlement` 도메인과 `GetSettlementsUseCase`를 재사용한다.
- 행위자: 로그인한 정산 생성자·연결 참여자.
- 미확정 표시값: 개인 납부액이 정해지기 전 목록 카드에 사용할 정산 총액.
- 확정 개인 금액: `settlement_shares.amount`. 상태 전이와 납부 의무는 변경하지 않는다.

## 3. 시나리오

1. 6,000원 품목 정산을 요청하거나 중단한다.
2. Share가 없는 연결 참여자가 PAY 목록을 조회하면 `amountToPay = 6000`을 반환한다.
3. 본인 Share가 2,000원이면 상태와 무관하게 2,000원을 유지한다.

경계: 다른 참여자 Share가 있어도 본인 Share만 판단한다. 총액과 Share는 기존 양수 제약을 유지한다.
더치페이와 `AWAITING_DEPOSIT`/`COMPLETED`의 Share 부재는 기존 nullable 동작을 유지한다.
조회 권한·오류·논리 삭제·그룹·월 필터는 변경하지 않는다.

## 4. 계약

```text
GET /api/v2/settlements (인증 필요)
SettlementListWebRequest -> GetSettlementsCommand -> GetSettlementsUseCase
-> SettlementListService -> LoadSettlementListPort -> SettlementPersistenceAdapter
-> SettlementJpaRepository.findAllForUser
-> SettlementListReadModel -> SettlementListItemResult -> SettlementListItemWebResponse
```

새 UseCase·Command·Result·Output Port·Policy는 없다. SQL 읽기 모델의 기존 금액 투영과
Web 응답의 금액 설명을 수정한다. 응답 필드·타입은 유지한다.

- Flyway migration required: **No** — 기존 컬럼 조회식만 수정한다.
- DB Entity·제약·저장 데이터, Redis, 외부 API, 환경 설정: 변경 없음.

## 5. 설계 결정

본인 Share를 우선 사용하고, 품목 정산의 선택 대기·중단 상태에서 Share가 없을 때만
`total_amount`를 사용한다. 이는 이슈가 허용한 정산 총액 기준 표시값이며 실제 개인 납부액은 아니다.
상세의 `requestedItemExpectedAmount()`는 생성자 몫을 제외한 전체 미수금이므로 개인 금액으로
복사하지 않는다. 목록 총액에는 생성자 몫도 포함된다. 이 차이를 Swagger에 명시한다.

개인 예상 금액의 임의 균등 배분은 실제 품목 선택과 다를 수 있어 사용하지 않는다.
품목·선택을 목록마다 추가 로드하는 방식은 개인 금액을 확정할 수 없고 조회 비용만 늘려 제외한다.

## 6. 영향 범위

| 영역 | 변경 |
|---|---|
| Persistence | `SettlementJpaRepository.findAllForUser`의 `amountToPay` 투영 |
| Web API | `SettlementListItemWebResponse.amountToPay` Swagger 설명 |
| 검증 | `SettlementListPersistenceIntegrationTest`의 상태·배분 유형·Share 유무 회귀 검증 |
| 문서 | 이 계획과 검증 결과 |

Application·Domain·Security·Infra는 변경하지 않는다. RECEIVE와 총무 목록 계약도 유지한다.

## 7. 구현 계획과 커밋 경계

- [x] Migration-only PR: N/A — 스키마 변경 없음.
- [x] UseCase·Command·Result·Output Port·Domain·Policy·Service: N/A — 기존 조회 재사용.
- [x] Output Adapter 목록 쿼리 보정.
- [x] Web Response Swagger 설명 갱신. Request·Controller는 N/A.
- [x] Infra: N/A — 설정 변경 없음.
- [x] 회귀 검증과 완료 기록.

```text
fix(settlement): 미확정 품목 정산 목록 금액을 총액으로 보정
test(settlement): 목록 금액 보정의 상태별 회귀 검증 추가
docs(settlement): 미확정 목록 금액 보정 검증 기록
```

## 8. 검증

일회용 PostgreSQL·Redis와 `scripts/test.sh`의 테스트용 환경값을 사용한다.
아래 명령으로 컴파일과 실제 PostgreSQL 통합·응답 계약 검증을 수행했다.

```bash
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test \
DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 bash scripts/test.sh compileJava test \
  --tests '*SettlementListPersistenceIntegrationTest' \
  --tests '*SettlementListServiceTest' \
  --tests '*SettlementControllerTest' \
  --tests '*SettlementOpenApiIntegrationTest' \
  --tests '*TreasurerSettlementPersistenceIntegrationTest'
git diff --check
git status --short --untracked-files=all
```
문서의 링크·파일 경로도 확인했다.

| 항목 | 결과 |
|---|---|
| 컴파일 | Passed — `./gradlew compileJava` |
| 수정 전 재현 | `REQUESTED`·`SUSPENDED`에서 본인 Share 부재 시 예상 6,000원 대신 null을 반환하는 실패 확인 |
| 집중 테스트 | Passed — 목록 persistence·service, Controller, OpenAPI, 총무 persistence 68건, 실패·오류·skip 0 |
| JavaScript | Passed — `scripts/test.sh`에서 35건 |
| 문서·diff 검사 | Passed — 상대 링크·참조 소스 확인, `git diff --check` |

## 9. 진행 기록

| 날짜 | 작업 | 다음 작업 |
|---|---|---|
| 2026-09-30 | 최신 develop과 #451 분석, 수정 전 재현, 쿼리·Swagger 보정, 회귀 검증 완료 | PR 리뷰 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-09-30 | 미확정 시에만 총액을 표시값으로 사용 | 확정 개인 금액을 보존하면서 중단 카드의 0원 표시를 보정 |

## 11. 완료 기록

- 완료일: 2026-09-30
- 미검증 항목: 전체 Java 스위트와 운영 HTTP 수동 호출은 미수행. 변경된 SQL을 실제 PostgreSQL에서 집중 검증하고 기존 응답·OpenAPI 계약을 확인했다.
- 남은 제한: Share 생성 전 총액은 실제 개인 납부 의무를 뜻하지 않는다.
- 후속 작업: 별도 범위 확장 없음.
