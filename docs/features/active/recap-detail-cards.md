# Feature Plan: 월 리캡 카테고리와 의외의 기록

- 상태: 후속 구현 진행 중
- 최종 수정: 2026-10-20
- 관련 이슈: [#594](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/594)
- 기반 문서: [월 리캡](../completed/monthly-recap.md), [개발 흐름](../../development-workflow.md)

## 1. 목적과 범위

앱의 리캡 카테고리 TOP3·의외의 기록·혜택 카드에 필요한 값을 생성 시점에 저장한다.
조회 시 원본 정산이나 공통 코드가 바뀌어도 이미 생성된 리캡의 내용은 변하지 않아야 한다.

- [ ] 상세 응답에 nullable `categories`, `record`, `benefits`를 제공한다.
- [ ] 개인 카테고리는 수정값 → 자동 분류 값 순서로 선택하고 미분류는 집계에서 제외한다.
- [ ] 새 값과 기존 요약을 같은 스냅샷 INSERT에 저장하고 조회 때 재계산하지 않는다.
- [ ] 데이터 없는 카드·기록 칸을 `null`로 표현하고 OpenAPI에 명시한다.
- [ ] 기존 리캡·공유 응답·소유자 권한과 탈퇴 정리를 보존한다.

선행 저장 구조 [#596](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/596)은 2026-10-19 23:46 KST에 머지됐다.
이번 후속 작업은 집계·저장·상세 API·OpenAPI·회귀 테스트를 구현한다. 추가 마이그레이션은 필요 없다.
제휴 데이터 소스가 없어 이슈의 허용 대안대로 추천 생성은 제외하고 `benefits: null`을 저장·반환한다. 혜택 DTO와 저장 매핑은 nullable 계약으로 제공한다.

## 2. 도메인과 불변 조건

기존 recap 영역을 확장한다. 정산·회원별 카테고리·공통 코드에서 생성 시점의 사실만 읽는다.
카테고리 표시 정보와 기록의 제목도 스냅샷에 복사하며 원본 FK를 추가하지 않는다.

- 대상 월·본인 부담액·정산 중복 제거는 기존 `RecapSummaryPolicy`의 계약을 따른다.
- 완료 시각은 현재 리캡 원본 조회의 `settlements.updated_at` 계약을 따른다. 월과 요일은 KST로 해석한다.
- `categories`: 본인 부담액 합계 내림차순, 동률은 카테고리 코드순, 최대 3개, 순위 1부터 시작한다.
- `record.busiestWeekday`: 정산별 1회 집계, 동률이면 월요일부터 요일순으로 선택한다.
- `record.frequentCategory`: 미분류 제외, 횟수 내림차순·코드순으로 선택한다.
- `record.largestSettlement`: 본인 부담액 내림차순·정산 ID순으로 선택한다. 카테고리가 없으면 해당 하위 값은 null이다.
- `record.fastestSettlement`: 유효한 요청 시각부터 완료까지 실제 소요시간이 가장 짧은 정산을 선택한다. 동률은 정산 ID순이다. 분 단위 값은 버림하며 1분 미만은 0이다. `SettlementRequestWriter`가 생성하고 재발급 때 보존하는 `settlement_links.created_at`을 요청 시각으로 사용한다. 중단 시간도 실제 경과시간에 포함한다. 링크가 없거나 요청 시각이 완료 뒤인 정산은 이 기록 후보에서 제외하며 정산 생성 시각으로 대체하지 않는다.
- 기존 행은 새 컬럼이 SQL NULL이다. 이미 생성한 리캡을 재생성하거나 백필하지 않는다.

## 3. 계약과 흐름

### 기존 경계 재사용

| 경계 | 변경 계획 |
|---|---|
| `BuildMonthlyRecapUseCase` / `BuildMonthlyRecapService` | 기존 월 집계에 카드별 정책 계산을 연결한다. 새 UseCase는 필요 없다. |
| `LoadRecapSourcePort` / `RecapSourcePersistenceAdapter` | 요청 시각·제목 등 생성용 사실을 확장한다. |
| `LoadMySettlementCategoryPort` / `MySettlementCategoryReader` | 기존 Application reader를 재사용하여 모든 대상 정산의 개인 카테고리를 한 번에 읽는다. 정책에 독립적인 `RecapCategory` 값만 전달한다. |
| `StoreMonthlyRecapPort` / `MonthlyRecapPersistenceAdapter` | 카드 스냅샷을 기존 INSERT에 포함한다. JSON 직렬화는 Adapter가 소유한다. |
| `LoadMonthlyRecapPort` / `MyRecapQueryService` | 저장값만 읽고 기존 NULL을 유지한다. |
| `MonthlyRecapResult` → `MonthlyRecapWebResponse` | nullable 카드 구조를 명시적으로 매핑한다. |

`GET /api/v2/recaps/{yearMonth}`는 현재 인증·소유자 조회·404 동작을 유지한다.
새 Command는 필요 없다. Application 값은 plain record로 두고 Web DTO가 Swagger를 소유한다.

예정된 카드 값:

- `categories[]`: `rank`, `category {code, name, icon}`, `totalOwnAmount`
- `record`: nullable `busiestWeekday {weekday, count}`, `frequentCategory {category, count}`, `largestSettlement {totalOwnAmount, category}`, `fastestSettlement {durationMinutes, title}`
- `benefits[]`: `title`, `reason`, `expiresAt`, `url`, nullable `logoUrl` (제휴 데이터 준비 후)

중첩 필드 이름은 프론트 #405의 `toRecapDetail`에서 이름·금액·요일을 변환할 수 있도록 확정한다. 기존 DB 변경은 JSON 최상위 형태와 개수만 제한한다.
원본·카테고리·표시값이 변경되어도 저장한 카드 값을 조회하며, 누락 카드는 기존 NULL을 유지한다.

## 4. 저장 설계와 Flyway 판단

- Flyway migration required: **No (이번 후속 작업)** — 필요한 V61은 #596으로 반영 완료
- 마이그레이션: `V61__add_monthly_recap_card_snapshots.sql`
- 기반: 2026-10-19 조회한 `origin/develop`의 최신 버전 V60, 선택 시 열린 PR 없음. 게시 전 재확인한 #595에는 마이그레이션 변경 없음
- 마이그레이션 PR: [#596](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/596) (MERGED)
- 추가 컬럼: `monthly_recaps.categories_snapshot`, `record_snapshot`, `benefits_snapshot` (`JSONB`, nullable, 기본값 없음)
- 제약: categories는 배열 최대 3개, record는 객체, benefits는 배열 최대 2개. SQL NULL 허용, 최상위 JSON null·다른 타입 거부. 빈 배열 허용.
- 기존 `guard_monthly_recap_snapshot()`은 행 전체를 비교하므로 세 컬럼도 자동으로 수정 금지 대상이다.
- 기존 INSERT는 컬럼명을 명시하므로 새 nullable 컬럼을 생략할 수 있다. 조회는 추가 컬럼을 무시하므로 기존 배포와 호환된다.
- 카테고리·정산 FK, 별도 테이블, 인덱스, 백필, 기존 트리거 변경은 필요 없다.
- Redis·외부 API·환경 설정 변경은 없다.

고정된 카드 묶음을 하나의 리캡 행과 원자적으로 보존하기 위해 JSONB를 선택한다.
세부 필드마다 SQL 컬럼을 늘리거나 자식 테이블에 별도 불변 트리거를 만드는 대안보다 저장·조회가 단순하다.
중첩 필드의 유효성·집계 정합성은 후속 Application 정책과 타입이 보장한다. DB는 최상위 형태·개수와 불변성만 보장한다.

## 5. 영향 범위와 검증

| 영역 | 이번 작업 |
|---|---|
| DB | V61 재사용, JDBC JSONB 직렬화·역직렬화 |
| Application | `RecapCardPolicy`, 생성 서비스·스냅샷·Result 확장 |
| API / OpenAPI | nullable 상세 카드 DTO와 네 기록 칸, 배열 상한 |
| 권한 / 공유 / 이미지 / 탈퇴 | 기존 동작 보존 및 회귀 검증 |
| 문서 | 구현 계획과 결과 게시, 완료 후 completed로 이동 |

검증은 격리된 PostgreSQL 18·Redis 8에서만 수행하고 실제 `.env`는 읽지 않는다.

- [x] Flyway 빈 DB → V61, V60 + 기존 리캡 → V61, validate와 재실행 0건
- [x] 기존 행·기존 INSERT 호환성, nullable 컬럼 기본값 확인
- [x] NULL·빈 배열·부분 기록·최대 개수 허용
- [x] 최상위 JSON null·타입 오류·상한 초과 거부
- [x] 각 카드의 수정·NULL 백필 거부, 동일 값 UPDATE 허용
- [x] 소유자 삭제 시 리캡 CASCADE 유지
- [x] 기존 recap 테스트 및 compileJava, verifyMigrationVersions
- [x] git diff --check, 문서 상대 링크, SQL 한 파일만 게시 확인

## 6. 이번 구현 순서와 커밋 경계

1. `docs(recap): 리캡 상세 카드 후속 구현 계획 정리`
2. `feat(recap): 월별 카테고리와 기록 스냅샷 생성 및 저장` — 원본 조회·중복 제거·정책·생성 연결·JSONB 저장을 하나의 동작 단위로 완료하고 정책/영속 테스트 포함
3. `feat(recap): 상세 카드 응답과 nullable OpenAPI 계약 추가` — Result/Web DTO·설명·실제 HTTP/OpenAPI 테스트 포함
4. `docs(recap): 상세 카드 검증 결과 정리`

새 Port·외부 서비스·Infra 설정은 필요 없다. 기존 불완전 Share 실패 처리는 유지하고 정상 0원 제출은 집계한다.
동행자 조인으로 같은 정산이 여러 행이어도 카테고리·요일·횟수는 정산 ID당 한 번만 센다.
모든 동률은 순서를 고정하고 합계는 `Math.addExact`로 overflow를 거부한다.
`withCompanionAvatar()`도 새 카드 필드를 유지해야 한다.

### 이번 검증 계획

- [ ] 순위 3개 제한, 개인 수정 우선, 미분류·비활성 카테고리 제외, 0원, 동률, 중복, overflow
- [ ] KST 요일 경계, 가장 큰 본인 금액, 실제 시간순 비교 후 분 버림, 요청 없음·음수 시간 제외
- [ ] JSONB 왕복, 기존 NULL, 공유 제외, 스냅샷 재조회 불변, 이미지 복사 후 카드 유지
- [ ] 실제 HTTP 응답·권한·OpenAPI nullable 객체와 배열·상한·중첩 기록 계약
- [ ] 관련 회귀 테스트와 전체 suite, compileJava, verifyMigrationVersions, diff check, 문서 링크

## 7. 검증 결과와 진행 기록

- 2026-10-19: 기존 스냅샷 불변 트리거, 명시적 컬럼 INSERT, 기존 내 카테고리 계약 확인. V61 설계 확정.
- 검증 결과:
  - PostgreSQL 18.6 + Flyway 12.4.0: 빈 DB에 전체 V1→V61 적용 성공(버전 49가 없어 총 60개).
  - 별도 V60 DB의 기존 리캡을 보존한 V61 업그레이드, validate, 재실행 0건과 스키마 시나리오 35개 통과.
  - `bash scripts/test.sh compileJava verifyMigrationVersions test --tests '*Recap*'`: 기존 Java 테스트 81개(리캡 클래스 79개 + 메서드 이름으로 선택된 알림 테스트 2개)·JavaScript 테스트 35개 통과, 실패·건너뜀 0.
  - 문서 상대 링크와 `git diff --check` 통과. 게시 대상은 V61 SQL 한 파일이다.
  - 별도 검증 harness와 로그: `/tmp/howmuch-594-verification/` (게시 제외).
  - 전체 Java suite·새 API 검증·원격 CI는 수행하지 않았다. 이번 변경은 저장 구조이며 API는 후속 작업이다.
- 2026-10-20: #596 MERGED와 최신 `develop` (`ec6eeb7c`)을 확인하고 후속 구현 시작. 위 2026-10-19 결과는 선행 마이그레이션 검증이다. 이번 검증 결과는 완료 시 별도 기록한다.
- 제외: 제휴 혜택 추천 데이터 연결 (현재 공급원 없음).
