# Feature Plan: 정산 목록 페이지네이션

- 상태: 완료
- 최종 갱신: 2026-09-10
- 관련 이슈: [#310](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/310)
- 기준: `origin/develop` (`09bf98d`)

## 1. 목적과 완료 조건

정산 이력이 많은 사용자의 목록 응답과 참여·납부 집계가 전체 이력에 비례해 커지는 문제를 개선한다. 운영 장애나 지연 수치를 전제로 하지 않는다.

- [x] 기본 20건, 최대 50건으로 목록 반환을 제한한다.
- [x] 고정 데이터에서 동일 요청 시각을 포함한 첫·중간·마지막·빈 페이지를 누락·중복 없이 조회한다.
- [x] RECEIVE/PAY, 초안 제외, 상태·그룹 권한·종료 연월 조건과 집계 정확성을 유지한다.
- [x] 집계 대상은 다음 페이지 확인용 1건을 포함한 최대 `size + 1`개 정산으로 제한한다.
- [x] PostgreSQL 대량 데이터의 실행 계획과 API 계약을 검증한다.

범위 제외: CODEF, 총 건수/페이지 번호, 인덱스 선제 추가, 프론트엔드 구현.

## 2. 도메인과 시나리오

기존 settlement 도메인을 사용한다. 로그인 사용자가 자신이 생성한 RECEIVE 또는 다른 사람이 생성하고 본인이 연결 참여자인 PAY 목록을 조회한다. 정산 상태 전이와 정책은 변경하지 않는다. 그룹 필터와 그룹 요약에는 활성 그룹·활성 멤버 조건을 유지한다.

첫 요청은 cursor 없이 조회한다. 다음 요청은 같은 탭·상태·그룹·연월과 응답의 nextCursor를 보낸다. 마지막/빈 페이지는 `hasNext=false`, `nextCursor=null`이다. 정렬은 `COALESCE(link.created_at, settlement.created_at) DESC, settlement.id DESC`를 유지한다. 유효하지 않은 크기·커서는 400으로 거부한다.

## 3. 계약과 호환성

`SettlementListWebRequest -> GetSettlementsCommand -> GetSettlementsUseCase -> SettlementListPageResult -> SettlementListPageWebResponse`.

- `GET /api/v2/settlements`: 기존 `tab`, `status`, `groupId`, `yearMonth` 유지. `cursor`와 `size` 추가.
- `size`: 생략 시 20, 허용 범위 1~50. 범위 밖 Web 요청은 `INVALID_ARGUMENT`.
- `cursor`: 생략 시 첫 페이지. 요청 시각(UTC)과 UUID를 담은 불투명한 URL-safe 문자열. 잘못된 값은 `INVALID_SETTLEMENT_CURSOR`.
- Output Port: 기존 `LoadSettlementListPort.findAll`을 `findPage`로 교체하고 시각·ID 경계와 조회 상한을 전달한다. 새 Port는 필요하지 않다.
- 응답 `data`는 기존 배열에서 `{ "items": [...], "nextCursor": null, "hasNext": false }` 객체로 변경한다. 항목 필드는 유지한다.
- 클라이언트는 `data` 대신 `data.items`를 사용하고 `hasNext=true`일 때만 `nextCursor`로 추가 조회해야 한다. 필터 변경 시 cursor를 비운다. 기존 앱의 전체 배열 가정과 호환되지 않으므로 앱·서버 배포를 조율해야 한다. 무제한 반환 호환 경로는 두지 않는다.
- 고정 데이터의 페이지 경계를 보장한다. 페이지 요청 사이 삭제·상태·권한·요청 시각 변경에 대한 스냅샷은 제공하지 않으며 새로고침으로 다시 조회한다.

## 4. 설계와 영향 범위

| 영역 | 변경 |
|---|---|
| Application | Command 크기 기본값/상한, 커서 검증, 페이지 결과 구성, 읽기 트랜잭션 |
| Persistence | 제한된 정산 ID를 MATERIALIZED CTE로 먼저 선택하고 참여자·Share·입금 합계에 동일 ID 범위를 적용 |
| Web | 페이지 DTO, 파라미터 검증, Swagger와 클라이언트 전환 설명 |
| Domain | 기존 상태 코드에 잘못된 페이지 입력 코드 추가, 상태/정책 불변 |
| Redis/Security/Infra | 변경 없음, 기존 CurrentActorPort 인증 유지 |

OFFSET 방식 대신 기존 알림 페이지와 같은 커서 방식을 사용해 깊은 페이지의 OFFSET 누적을 피한다. 전체 count 쿼리는 수행하지 않는다. UUID를 동률 비교에 사용하고 DB 요청 시각을 UTC로 명시적으로 변환해 커서 기준 시각을 유지한다.

Flyway migration required: **No**. Entity·컬럼·제약·인덱스 변경 없이 기존 조회를 제한한다. 인덱스는 실행 계획을 측정한 뒤 필요성이 확인되면 별도 결정한다.

## 5. 구현과 커밋 계획

- [x] 설계와 영향 범위, migration 불필요 판단
- [x] UseCase/Command/Result와 기존 Output Port 수정
- [x] Application 페이지 구성 및 Persistence 쿼리 제한
- [x] Web DTO/Controller/Swagger 연결
- [x] 회귀 테스트와 대량 데이터 실행 계획 검증
- [x] 완료 결과 기록과 completed 이동
- N/A: migration PR, Domain 상태 전이, Application Policy, Infra 구성 — 변경 필요 없음

예상 커밋 경계(각 커밋 컴파일 가능):

1. `docs(settlement): 정산 목록 페이지네이션 설계 기록`
2. `feat(settlement): 정산 목록 커서 페이지네이션과 집계 범위 제한` — 실제 호출까지 연결하고 기존 테스트 계약 갱신
3. `fix(settlement): DB 커서 비교 시각을 UTC로 고정` — DB 시각 변환과 페이지 경계·집계 회귀 검증
4. `test(settlement): 페이지 API 계약과 실제 HTTP 흐름 검증` — 크기·커서 입력, 응답·Swagger·인증 HTTP
5. `docs(settlement): 페이지네이션 검증 결과와 전환 계약 정리`

## 6. 검증 계획과 결과

테스트 전용 `compose.test.yml` PostgreSQL 18·Redis 8 및 `scripts/test.sh` 환경을 사용한다. 애플리케이션 `.env`는 읽지 않는다. 루트 AGENTS.md의 변경 동작 테스트 지시를 우선하여 테스트를 추가한다.

- `./gradlew compileJava`
- 관련 Application/Web/PostgreSQL/OpenAPI 테스트: 기본값·1/50건·범위 오류·커서 오류·동률·접근 조건·월 필터·부분/완납 집계
- 대량 정산·참여·입금 fixture에서 반환 건수와 `EXPLAIN (ANALYZE, BUFFERS)` 집계 입력 범위 확인
- 문서 상대 링크/경로 확인, `git diff --check`, `git status --short --untracked-files=all`

| 검증 | 결과 |
|---|---|
| `./gradlew compileJava` | 통과 |
| `bash scripts/test.sh compileJava test --tests '*SettlementListServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementListPersistenceIntegrationTest' --tests '*SettlementOpenApiIntegrationTest' --tests '*SettlementListHttpIntegrationTest'` | Java 72건, 실패·건너뜀 0 |
| 같은 스크립트의 JavaScript 테스트 | 35건 통과 |
| 실제 서버 HTTP | JWT·Redis 세션을 사용하는 첫/다음/빈 페이지, 미인증 401, size/커서 오류 400 — 6개 요청 통과 |
| PostgreSQL 페이지 경계 | 동일 요청 시각, 링크 시각 우선, UUID 부호 경계, UTC·Asia/Seoul JVM, 마지막 이후 빈 결과 검증 |
| 기존 조회 조건과 집계 | RECEIVE/PAY·DRAFT 제외·부분/완납·생성자 Share 제외·월 경계·그룹 필터 유지, 다음 페이지에서 월·그룹 탈퇴·다른 사용자 범위 재검증 |
| 문서·diff | 변경 문서의 상대 링크와 참조 파일 존재, `git diff --check` 통과 |

### 실행 계획 측정

테스트용 PostgreSQL 18에 정산 1,200건, 참여자 2,400명, Share 1,200건, 입금/거래 2,400건을 추가하고 `ANALYZE` 후 실제 Repository SQL로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 실행했다. 기본 fixture의 정산 2건은 별도로 존재한다.

- 크기 50 요청: 응답 50건, 다음 페이지 확인용 CTE 51건.
- 목록/납부 완료/입금 합계 Aggregate 출력은 각각 51건이며 입력은 각각 102/51/102행이다. 각 노드는 1회 실행됐다.
- 로컬 한 번의 측정에서 Planning 7.614 ms, Execution 8.303 ms. 운영 성능 기준이나 전후 개선율로 해석하지 않는다.
- 정산·Share·입금 테이블의 sequential scan은 남는다. 페이지 제한은 후보 탐색 전체를 제거하는 것이 아니라 집계 입력과 응답량을 제한한다. 실제 운영 분포·쿼리 빈도의 추가 측정 없이 새 인덱스를 결정하지 않는다.
- 원본 실행 계획은 테스트 실행 시 `build/reports/settlement-pagination-explain.json`에 생성한다. 생성물은 커밋하지 않는다.

전체 Java 테스트, 운영 트래픽 부하 측정, 실제 앱 화면의 추가 조회는 실행하지 않았다. 서버 계약은 실제 HTTP로 검증했으며 클라이언트 변경은 별도 범위다.

## 7. 진행 및 결정 기록

| 날짜 | 결정/진행 | 근거 |
|---|---|---|
| 2026-09-10 | 최신 develop에서 `feat/310-settlement-pagination` 생성 | 사용자 브랜치 요청과 기존 PR 관례 |
| 2026-09-10 | cursor/size와 items/nextCursor/hasNext 채택 | 기존 알림 API 관례, 기존 정렬 보존 |
| 2026-09-10 | 스키마 변경 없이 페이지 대상 먼저 선택 | 이슈의 선제 인덱스 추가 제외 요구 |
| 2026-09-10 | DB 커서 입력도 UTC Instant로 전달 | 한국 시간 JVM에서 LocalDateTime 바인딩이 9시간 이동해 다음 페이지가 비는 회귀 테스트로 확인 |
| 2026-09-10 | 검증 fixture의 시각을 raw UTC로 고정 | JPA auditing 시각의 JVM별 변환과 목록 계약 검증을 분리 |
| 2026-09-10 | 관련 검증 완료, completed로 이동 | Java 72건·JavaScript 35건과 실제 HTTP·실행 계획 통과 |

## 8. 완료 기록

- 완료일: 2026-09-10
- 최종 동작: 최대 50건의 커서 목록과 페이지 대상 집계, UTC 커서 비교, 기존 필터·항목 필드 유지.
- 검증: 컴파일, Java 72건, JavaScript 35건, 실제 HTTP 6개 요청, PostgreSQL 대량 데이터 실행 계획 통과.
- 잔여 사항: 클라이언트 응답 전환 및 배포 조율. 운영 분포에서 인덱스 필요성은 별도 측정 후 판단.
