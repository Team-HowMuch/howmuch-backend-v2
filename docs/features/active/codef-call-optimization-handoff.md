# 인수인계: CODEF 호출 최적화와 계좌 거래 동기화 분리

- 작성일: 2026-10-18
- 상태: 2~5단계 로컬 구현 완료, 커밋·PR·배포 전
- 관련 계획: [codef-call-optimization.md](codef-call-optimization.md)

## 1. 한 줄 요약

계좌 조회(GET)가 CODEF를 호출하던 구조를 **저장 데이터 조회 + 명시적 동기화 API**로 분리하고, 이미 수집한 계좌·월은 정산/위젯/자동 대조/압축이 재사용하도록 바꿨습니다. DB 마이그레이션 없이 PostgreSQL과 Redis만 사용했습니다.

## 2. 작업 위치

- worktree: `/Users/wars/Github/howmuch/howmuch-backend-v2-codef-sync`
- 브랜치: `codex/codef-sync-optimization` (base `origin/develop` `87b223e9`)
- 주의: 기존 작업 폴더 `/Users/wars/Github/howmuch/howmuch-backend-v2`에는 **사용자 소유의 미커밋 변경**이 있습니다. 건드리지 마세요.
- 커밋/푸시: **하지 않았습니다.** 규약상 명시 요청 시에만 수행합니다.

## 3. 무엇을 바꿨나 (요약)

| 단계 | 내용 |
|---|---|
| 2 | 조회/동기화 분리. `GET /accounts/{id}`·`/transactions`에서 CODEF 제거, `POST /accounts/{id}/transactions/sync` 추가. 외부 호출은 트랜잭션 밖, 저장은 짧은 트랜잭션 + 계좌 재검증 |
| 3 | Redis claim으로 같은 계좌·월 동시 동기화 1건만 CODEF 호출 (`refreshed=false`, `syncInProgress=true` 반환) |
| 4 | coverage로 이미 수집한 월 재사용. 정산 fetch 경로가 CODEF 대신 DB 저장 거래를 반환. 현재·직전 월은 항상 재조회 |
| 5 | coverage에 수집 시각 저장 + 계좌 상세에 `synced`/`lastSyncedAt` 노출, 계좌 후보 목록 30초 캐시 |

## 4. 변경 파일

### 신규
- `application/port/in/account/SyncAccountTransactionsUseCase.java`
- `application/port/in/account/result/AccountTransactionSyncResult.java`
- `application/account/service/AccountTransactionSyncService.java`
- `application/account/service/AccountTransactionSyncWriter.java`
- `application/account/model/AccountCandidate.java`
- `application/port/out/account/AccountTransactionCoveragePort.java`
- `application/port/out/account/BankAccountCandidateCachePort.java`
- `adapter/in/web/account/dto/response/AccountTransactionSyncWebResponse.java`
- `adapter/out/redis/AccountTransactionCoverageRedisAdapter.java`
- `adapter/out/redis/BankAccountCandidateCacheRedisAdapter.java`
- 테스트: `AccountTransactionSyncServiceTest`, `SettlementBankTransactionFetchServiceTest`

### 수정
- `application/account/service/AccountQueryService.java` — 조회에서 CODEF 제거, `readOnly`, 상세에 `synced`/`lastSyncedAt`
- `application/account/service/BankAccountService.java` — 후보 캐시 연동(connect 저장, 후보 조회 hit, link 무효화)
- `application/settlement/service/SettlementBankTransactionFetchService.java` — coverage 재사용 + 수집 시각 기록
- `application/port/out/account/AccountTransactionRefreshStorePort.java` — `tryClaim`/`release` 추가
- `adapter/out/redis/AccountTransactionRefreshRedisAdapter.java` — claim Lua compare-and-delete
- `adapter/in/web/account/AccountController.java`, `docs/AccountDocs.java`, `dto/response/AccountDetailWebResponse.java`, `port/in/account/result/AccountDetailResult.java`
- 테스트: `AccountQueryServiceTest`, `AccountControllerTest`, `BankAccountServiceTest`, `SettlementDepositServiceTest`
- 문서: `docs/features/active/codef-call-optimization.md`, `docs/features/completed/account-query-and-transactions.md`, `docs/features/completed/account-current-month-refresh-cache.md`

## 5. API 계약 변화 (프론트가 반드시 알아야 함)

| API | Before | After |
|---|---|---|
| `GET /api/v2/accounts` | DB 조회 | 동일 |
| `GET /api/v2/accounts/{id}` | 현재 월 stale/과거 월 summary 없음이면 CODEF 적재, 트랜잭션+행 락 유지 | **CODEF 없음.** 저장 요약만. 응답에 `synced`, `lastSyncedAt` 추가 |
| `GET /api/v2/accounts/{id}/transactions` | 위와 같은 자동 적재 | **CODEF 없음.** 저장 거래만(없으면 빈 배열) |
| `POST /api/v2/accounts/{id}/transactions/sync?yearMonth=` | 없음 | **신규.** 성공 시 `refreshed=true`, `syncedAt`, `fetchedFrom/To`, `fetchedCount`, 요약. 1분 내 재요청 또는 동시 진행 시 `refreshed=false`, `syncInProgress` |
| `GET /api/v2/accounts/connections/{id}/accounts` | 매번 CODEF | 30초 캐시 hit 시 CODEF 생략(응답 동일) |
| `POST /api/v2/accounts/connections/banks` | CODEF 후보 조회 | 동일 + 결과 캐시 |
| `POST /api/v2/accounts/external` | CODEF 후보로 연동 | 동일(항상 fresh) + 캐시 무효화 |
| 정산 입금 대조/위젯/자동/압축 | 스냅샷 생성월부터 매번 CODEF | 이미 수집한 월은 DB 재사용, 현재·직전 월만 CODEF |

- 기존 GET 응답 필드는 유지됩니다(추가만). 다만 **"화면 진입만 하면 최신화"가 사라졌으므로** 프론트가 sync를 호출해야 합니다.
- 권장 흐름: 화면 진입 GET → 새로고침/월 이동 시 `sync(yearMonth)` 후 GET 재조회. `synced=false`면 "아직 불러오지 않음" 상태로 표시.
- CODEF 계열 에러(502/503/409) 처리는 GET에서 sync로 이동해야 합니다.

## 6. Redis 키와 상태

| 키 | 값 | TTL | 의미 |
|---|---|---|---|
| `account:transaction-refresh:v2:{accountId}:{YYYY-MM}` | `"1"` | 1분 | 성공 동기화 사후 표식 → sync 재요청 시 CODEF 생략 |
| `account:transaction-sync-claim:{accountId}:{YYYY-MM}` | UUID token | 6분 | in-flight claim. Lua로 token 일치 시에만 해제 |
| `account:transaction-coverage:v2:{accountId}:{YYYY-MM}` | epoch millis\|from\|through | 30일 | 커밋된 수집 범위와 시각. 과거 월 전체 여부를 별도 검증 |
| `account:connection-candidates:{userId}:{connectionId}:{organization}` | 후보 JSON | 30초 | client-visible 형태(해시 key·마스킹 번호)만 |

- 모든 Redis 접근은 best-effort: 실패 시 로그만 남기고 기능은 계속합니다. coverage/claim은 정합성 원본이 아닙니다(원본은 PostgreSQL).

## 7. 트랜잭션·동시성 규칙

- `AccountTransactionSyncService.sync`는 `@Transactional`이 아닙니다. 읽기 → claim → **트랜잭션 밖 CODEF** → `AccountTransactionSyncWriter.persist`(짧은 트랜잭션, `findActiveByIdAndUserIdForUpdate` 재검증) 순서입니다.
- `AccountTransactionSyncWriter`가 거래 저장 + 월 요약 재집계 + `QueueNettedDepositDetectionPort.observed`를 한 트랜잭션에서 수행합니다.
- 조회 메서드는 `@Transactional(readOnly=true)`이며 외부 호출·락이 없습니다.
- claim은 best-effort입니다. 6분 TTL 만료 후 이전 작업이 늦게 저장해도 계좌가 유효하면 멱등 저장되고, 중복 CODEF 호출만 남을 수 있습니다.

## 8. 검증 상태

실행한 것:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
./gradlew test --tests '*AccountQueryServiceTest' \
  --tests '*AccountTransactionSyncServiceTest' \
  --tests '*AccountControllerTest' \
  --tests '*SettlementBankTransactionFetchServiceTest' \
  --tests '*SettlementDepositServiceTest' \
  --tests '*BankAccountServiceTest'
# AccountQueryServiceTest 9, AccountTransactionSyncServiceTest 8, AccountControllerTest 12,
# SettlementBankTransactionFetchServiceTest 3, SettlementDepositServiceTest 17, BankAccountServiceTest 11 — 전부 통과
```

- `compileJava`, `compileTestJava`, `git diff --check` 통과.

실행하지 못한 것:

- 전체/통합 테스트(PostgreSQL·Redis 필요). 이 환경에서 Docker 데몬이 응답하지 않았습니다.
- 실제 CODEF 호출, 배포 후 흐름.

인수자는 DB·Redis를 띄우고 전체 테스트를 먼저 돌리는 것을 권장합니다.

## 9. 인수자가 이어서 할 일

1. **전체 테스트 실행** (PostgreSQL 18 + Redis 8). `./gradlew test`.
2. **프론트 반영**: `howmuch-frontend-v2`에서 `GET`은 그대로, 새로고침/월 이동 시 sync 호출, `synced`/`lastSyncedAt`로 빈 상태 구분. `howmuch-frontend-v2/AGENTS.md`의 FSD 규칙 준수.
3. **예산 정책 결정**: 계좌 sync는 현재 CODEF 예산 밖입니다. `SettlementBankLookupBudgetPort`(정산 전용, 일 100/자동 80)와 합칠지, 별도 한도를 둘지 결정.
4. **배포/PR**: 계획서의 커밋 경계대로 커밋하고 PR 생성(사용자 명시 요청 필요).
   ```
   feat(account): 계좌 조회에서 CODEF 호출 제거
   feat(account): 계좌 거래 명시적 동기화 API 추가
   feat(account): 동시 동기화 중복 호출 억제 claim 추가
   feat(account): 수집 완료 월 재사용 coverage 추가
   feat(account): 계좌 후보 캐시와 수집 시각 노출 추가
   test(account): 조회 무호출·동기화 1회·진행 중·실패 보존 검증
   docs(account): 조회·동기화 계약 문서 갱신
   ```

## 10. 함정과 주의사항

- **GET이 더 이상 적재하지 않습니다.** 프론트를 같이 배포하지 않으면 신규 계좌 거래 화면이 빈 상태로 보입니다. 배포 순서를 조율하세요.
- coverage는 Redis hint입니다. eviction/재시작 시 과거 월을 다시 호출할 수 있습니다(정합성은 안전).
- 현재 월·직전 월은 항상 재조회합니다. 오래된 월만 재사용하므로 짧은 정산은 절감이 없습니다.
- 후보 캐시에 **원본 계좌번호를 저장하지 마세요.** `candidateKey`(해시)와 마스킹 번호만 저장합니다. `link`는 반드시 CODEF로 재검증합니다.
- 후보 캐시 key에 `birthDate`가 없습니다(연결별 고정 전제). 이 전제가 깨지면 key에 추가해야 합니다.
- `AccountTransactionRefreshStorePort`의 `tryClaim`은 테스트에서 기본 mock이 `Optional.empty()`일 수 있으니, 새 테스트에서 성공 경로를 검증할 때 명시적으로 stub 해야 합니다.
- 새 테스트에서 `SettlementBankTransactionFetchService`를 직접 생성하면 생성자 인자가 6개(`coverage`, `loadTransactionPort` 포함)입니다. 기존 `SettlementDepositServiceTest` 두 곳이 이미 반영돼 있습니다.
- Redis adapter는 `ObjectMapper`(Spring bean)를 주입받습니다. 별도 설정 불필요.
- 테스트에서 Mockito strict stubbing이 켜져 있습니다. always-revalidate 월(현재·직전)에는 `coverage.coversMonth` stub을 넣지 마세요(불필요 stub으로 실패).

## 11. 미해결/추가 검증

- coverage의 DB 영속화 필요 여부(현재는 Redis + PostgreSQL 거래로 충분 판단)
- 비동기 sync job(V32 `account_transaction_sync_*`, 미사용)과의 정렬. 별도 브랜치 `feat/account-transaction-sync-request`에 미병합 slice가 있으니 중복 구현 금지.
- 지연 반영·과거 거래 정정 대응 범위(현재: 현재·직전 월 재조회 + 과거 월 30일 재검증)
- 실제 CODEF 응답 시간 대비 claim TTL(6분) 적정성

## 12. 2026-10-18 로컬 실측 추가

- 측정 결과: [CODEF sync 로컬 QA 데이터](../../qa/codef-sync-local-2026-10-18.json).
- 실제 CODEF·우리은행 계좌와 Android Galaxy A71 개발 빌드로 확인했다. 기존 SMS 환경을 복구한 뒤, DB 복제본과 변경 전 커밋 `87b223e9`의 별도 서버로 비교했다. 원본 SMS DB에는 테스트 정산만 생성했다.
- 동일 프론트 조회 흐름의 화면 데이터 준비 시점: 변경 전 refresh 표식 만료 **7,740ms**, 변경 전 표식 유효 **744ms**, sync 저장 데이터 조회 **747ms**. 조건별 단일 관측이며 첫 픽셀/TTI 또는 운영 p95가 아니다. 약 90% 감소는 만료 조건과의 비교에만 해당한다.
- 변경 전 만료 상태의 동시 상세/거래 GET은 **7,166ms / 3,082ms**였다. 코드상 freshness 검사 후 행 락을 잡으며 락 취득 후 freshness를 재검사하지 않아, 두 요청이 동시에 만료를 관측하면 외부 조회가 중복될 수 있다. 제공자 측 호출 수 계측은 별도로 하지 않았다.
- sync 실제 수집 **3,633ms**, 수집 중 저장 거래 GET **29ms**, 동시 sync **20ms**(`syncInProgress=true`), 완료 직후 sync **17ms**(`refreshed=false`). 실기기 새로고침은 **3,162ms**, 그동안 기존 목록과 갱신 안내가 함께 표시됐다. 양쪽 저장 거래 응답 24건이 동일했다.
- 정산 생성 API는 기존 SMS 환경 **55ms**, sync **69ms**. 당월 입금 대조는 **2,849ms → 3,182ms**로 개선을 확인하지 못했다. 정산 상세/입금 조회는 양쪽 모두 수십~백여 ms였다.
- 테스트 정산 생성 및 `AWAITING_DEPOSIT`, 미납 1명/완료 0명 표시와 새로고침을 확인했다. 실제 입금은 하지 않았으므로 입금 후 완료 전환 검증은 남아 있다.
- 기동 시 `BankAccountCandidateCacheRedisAdapter`의 Jackson 2 `ObjectMapper` 빈 부재를 발견해 기존 어댑터와 동일한 `tools.jackson` import로 수정했다. 수정 후 `bootJar`, 실제 서버 기동, 관련 Java **63개**, 스크립트 JavaScript **35개**, QA 프론트 타입 검사 통과. 전체 테스트는 이번에도 실행하지 않았다.
- QA 프론트 복사본: `/Users/wars/Github/howmuch/howmuch-frontend-v2-codef-qa`. sync 호출·진행 안내·미수집 자동 수집·수집 시각 표시를 연결한 비교용 구현이다. 기존 SMS 프론트는 수정하지 않았다. 운영 반영 전 진행 중 요청의 완료 추적과 월 이동/실패 UX를 추가 검토해야 한다.
- 최신 SMS 작업과 이 브랜치의 V58이 서로 다르다. 비교 DB에서는 Flyway를 끄고 기존 스키마를 사용했으므로 마이그레이션 검증으로 해석하지 않는다. 이 브랜치에 최신 SMS API가 없어 비교 앱의 `/api/v2/finance-authentications` 조회가 500이었다. 실배포 전 SMS 기능 통합이 필요하다.
- 코드 검토상 추가 검증 필요: 정산 경로는 계좌 sync의 claim/1분 제한을 공유하지 않는다. 또한 정산의 부분 기간 수집이 월 전체 coverage를 표시하고, 호출자 DB 저장 전 coverage를 기록한다. 과거 월 누락이나 저장 실패 후 잘못된 재사용이 없는지 배포 전에 확인해야 한다.


## 13. 2026-10-18 coverage / 실제 프론트 보완

앞의 12절은 보완 전 비교 기록이다. 아래 내용이 현재 로컬 구현 상태다. 커밋·push·PR·배포는 하지 않았다.

- coverage v2는 `epochMillis|from|through`로 실제 범위와 수집 시각을 기록한다. 과거 월은 월 전체 범위 및 월 종료 후 수집 여부를 모두 만족해야 재사용한다. 월말 당일 수집, 부분 수집, 구형/손상 표식은 월 전체로 인정하지 않는다. 월 변경 직후 1분 refresh marker가 남아 있어도 불완전한 전월 수집을 건너뛰지 않는다.
- 계좌 sync의 DB writer 커밋 후에만 coverage를 기록한다. 정산 fetch는 호출자 저장 성공을 보장하지 못하므로 coverage 쓰기를 제거했다. 따라서 정산만으로 수집한 과거 월은 이후 재조회될 수 있다. 안전한 호출 절감은 계좌 sync로 전체 수집한 월에서 적용된다. 저장 거래를 재사용할 때도 정산의 요청 시작/종료일로 필터링한다.
- GET 상세는 `synced`, `lastSyncedAt`, `syncInProgress`를 제공한다. 과거 월이 부분 수집이면 시각은 있어도 `synced=false`다. sync 생략 응답에도 기존 성공 시각을 제공한다. claim TTL은 6분이다.
- 실제 `howmuch-frontend-v2`에 반영했다. 기존 거래를 유지하며 갱신 안내, 실패/수동 재시도, 미수집과 0건 구분, 계좌·월별 독립 상태, 같은 월 중복 POST 억제, 진행 중 응답 후 GET polling을 구현했다. 실패를 성공으로 오인하지 않도록 성공 시각 전진을 검사한다. 로그아웃 후 늦은 응답의 캐시 복원도 막는다. 진행 상태는 디스크에 persist하지 않는다.
- 프론트 검증: `npm run typecheck`, `node --test docs/qa/account-sync.test.mjs` 9개 통과. 빠른 월 이동 시 응답 역전, 실패/재시도, 중복 요청, 대기 시간 초과, 0건, 로그아웃은 실제 coordinator를 사용하는 회귀 테스트로 검증했다.
- 실제 Android + CODEF: 10월 저장 거래 24건을 표시한 채 초기 sync 5,794ms. 9월 최초 조회는 수집 중 상태에서 4,485ms 뒤 완료했고, 10월 복귀 시 24건 유지. 이는 서버 요청 시간이며 화면 TTI 측정은 아니다.
- 로컬 프록시로 sync 한 건에만 503을 주입했다. 실패 문구·재시도 버튼과 기존 24건 유지 확인. 실기기 수동 재시도는 실제 CODEF 3,684ms 후 성공, 후속 상세/거래 GET 15/17ms. 재시도 진행 중 상세 24ms(`syncInProgress=true`), 거래 27ms(24건), 중복 sync 23ms(`refreshed=false`, `syncInProgress=true`)를 확인했다. 실제 CODEF 자체 장애를 일으킨 테스트는 아니다.
- 정산 생성/입금 대조 성능과 실제 입금 미검증 범위는 12절과 동일하다. 이번 보완으로 당월 입금 확인이 빨라졌다는 근거는 없다. 비교 DB Flyway 비활성화 및 최신 SMS 기능 통합은 별도 범위다.
- 안전하게 요약한 실측: [보완 후 로컬 QA 데이터](../../qa/codef-sync-repair-2026-10-18.json). 인증정보 및 거래 내용은 포함하지 않는다.

- 종료 시 로컬 백엔드 `18085`, 실제 프론트 Metro `18087`를 유지한다. 휴대폰은 ADB reverse로 직접 연결한다. 일회성 실패 프록시는 종료했고 임시 인증정보 파일도 삭제했다. 최종 jar 재기동 후 health 및 계좌 상세 HTTP 200 확인.

- 백엔드 전체 검증: 첫 실행 Java 2,296개/JS 35개 통과. 월 경계 회귀 2개 추가 후 전체 2,298개 재실행에서는 계좌 변경 관련 실패 없이 기존 통합 테스트 3개가 실패했다. `GroupNettingDirectIntegrationTest` 2개는 Java 가입 시각 대비 DB 탈퇴 시각이 약 0.35ms 앞서 `ck_group_members_left_after_join` 위반, `KafkaNotificationFlowIntegrationTest` 1개는 20초 비동기 대기 초과다. 데이터 중복 오류는 아니며 제품 코드 변경으로 우회하지 않았다. 분리 재실행 결과는 아래에 기록한다.

- 최종 분리 재실행: 변경 관련 6개 suite + 앞서 실패한 통합 2개 suite, 총 Java 100개 모두 통과(실패/오류/skip 0), JS 35개 통과. 전체 재실행에서 드러난 비결정적 시각/대기 실패는 위 기록을 유지하며, 마지막 실행을 전체 suite 통과로 표현하지 않는다.


## 15. 앱 실행·복귀 자동 갱신과 느린 CODEF mock QA (2026-10-18)

- 앞선 계좌 화면 보완과 별개로 빠져 있던 앱 실행·복귀 트리거를 실제 프론트에 연결했다. 로그인/잠금 해제 및 query cache 복원 완료 후 실행한다. background→active에서만 재실행하고 권한 팝업의 inactive→active 및 화면 이동/토큰 회전은 수집을 유발하지 않는다.
- 당월 계좌 sync → 위젯용 진행 정산 일괄 입금 대조 → 홈·정산 상세/목록·거래·알림 GET 갱신 순서다. 기존 화면을 유지한다. 같은 세션의 진행 작업을 공유하며 완료 후 60초 이내 자동 재실행을 억제한다. 명시적 재시도와 기존 수동 새로고침도 제공한다.
- 계좌 sync는 화면과 같은 runner를 공유한다. 진행 상태는 페이지가 열리기 전 생성돼도 persist하지 않는다. 거래 조회 미지원 상품은 자동 갱신 전체 실패로 취급하지 않는다. 계좌 하나의 실패가 다른 계좌/대조를 막지 않는다.
- 위젯 대조는 커밋된 동일 날짜 범위 및 수집 시작 후 60초 내 snapshot만 재사용한다. 범위 부족·만료·미래 시각·Redis 오류 시 기존 CODEF 조회를 한다. 일반 정산 수동/자동 대조 정책은 유지한다. 실패한 계좌 sync 이후 대조도 외부 조회를 시도할 수 있다(실패 QA에서는 8초 지연 실패가 두 번, 약 16초). 자동 POST 반복 재시도 루프는 없다.
- 홈과 정산 상세에 자동 확인 안내를 연결했고, 자동 작업 중 수동 정산 새로고침의 중복 실행을 막았다. 실패는 성공으로 바꾸지 않으며 저장 데이터와 기존 성공 시각을 유지한다. 로그아웃 후 후속 요청/캐시 반영을 막는다.
- CODEF mock에 transaction-list, 0~120초 지연, 429/500/503 응답, 호출/진행 수 집계를 추가했다. 계좌·요청 날짜 필터와 OAuth/등록 계좌 검증을 유지한다. [Mock 사용법](../../../tools/codef-mock/README.md).
- 격리 DB `howmuch_codef_foreground_20261018`, 격리 Redis `16381`, CODEF mock `18080`, API `18085`, Metro `18087`. 앱 인증을 위한 기존 로컬 사용자/세션만 재사용하고 은행 연결은 가상 계좌로 교체했다. 원본 DB/실제 은행에는 쓰지 않았다. 배포·커밋·push 없음.
- 프론트 TypeScript·회귀 19개, Java 관련 35개·JS 35개, mock Python 9개, compileJava/bootJar, diff check 통과. 이번 변경 후 전체 Java suite는 반복하지 않았으며 관련 계좌/coverage/위젯 통합 suite를 실행했다.
- 실제 HTTP QA는 production TS coordinator를 Node에서 실행하여 API→DB/Redis→실제 CODEF HTTP Adapter→mock까지 연결했다. 화면 렌더링 시간이나 실기기 AppState 이벤트의 검증으로 표현하지 않는다. CODEF 8초 지연, 저장 거래/진행 상태/중복 sync 24ms, 정상 수집 8,118ms 후 대조 229ms. 가상 정산 AWAITING_DEPOSIT→COMPLETED, 저장 거래 1→2건. 복귀 3회 시뮬레이션+동시 sync에도 CODEF 수집 1회.
- 503 mock 실패 시 기존 거래 2건 및 AWAITING_DEPOSIT 유지, 프론트 상태 error. 지연 성공 재시도는 CODEF 1회(8,145ms) 및 대조 101ms 후 COMPLETED. 즉시 재시도 QA에서만 격리 Redis의 cooldown을 제거해 시간을 앞당겼다. 제품 제한을 해제한 것은 아니다.
- 실기기 PIN 잠금 해제 후 네이티브 실행·복귀 검증까지 완료했다(16절). [측정 원본 및 제한](../../qa/codef-foreground-slow-2026-10-18.json). 프론트 회귀 명령: `node --test docs/qa/account-sync.test.mjs docs/qa/foreground-financial-refresh.test.mjs`. 실제 HTTP 재현 harness는 프론트 `docs/qa/foreground-financial-refresh.http.mjs`이고 가상 fixture 및 임시 인증 파일을 환경변수로 받는다.

- 종료 준비: mock을 정상 HTTP 200 + 8초 지연으로 돌려놓고, 실기기 로그인 후 자동 완료를 확인할 새 가상 미납 정산을 준비했다. API/Metro/mock과 격리 Redis를 유지한다. 일회성 인증 캡처 프록시는 종료하고 휴대폰은 API로 직접 연결한다. 임시 인증 파일은 삭제한다.


## 16. Android 네이티브 실행·복귀 QA 완료 (2026-10-18)

- 앱 잠금 해제 후 자동 당월 sync(8,190ms)와 위젯 일괄 대조(235ms) 실행을 HTTP 기록으로 확인했다. 지연 중 홈의 기존 1,000원과 `최신 거래를 확인하고 있어요…` 안내가 함께 표시됐다. 수동 대조 없이 가상 정산이 AWAITING_DEPOSIT→COMPLETED, 홈 받을 돈 1,000원→0원으로 갱신됐다.
- 실제 HOME→앱 복귀를 빠르게 3회 반복해 CODEF 수집 1회가 유지됨을 확인했다. 60초가 지난 뒤 복귀에서는 수집이 2회째로 증가했고(8,093ms), 새 가상 입금 2,000원이 대조 111ms 후 자동 완료됐다. 두 정산의 COMPLETED 상태를 DB로 확인했다.
- 계좌 화면을 열어둔 채 background로 보낸 뒤 실제 60초 만료를 기다려 복귀했다. 수집 3회째(8,063ms) 진행 중 기존 거래 7건과 `최신 거래 불러오는 중…`, 새로고침 disabled를 확인했다. 완료 후 7건이 유지되고 안내가 사라졌다. 계좌 화면과 전역 runner가 중복 POST를 만들지 않았다.
- 이 네이티브 QA 중 `/deposits/reconcile` 수동 호출은 0회다. 세 번의 자동 갱신마다 CODEF 월 수집은 각각 1회이며 위젯 대조에서 추가 은행 조회는 없었다. 마지막 대조는 미완료 생성자 정산이 없어 14ms에 반환됐다.
- 실제 돈/은행 대신 로컬 CODEF HTTP mock을 사용했고 응답 지연은 8,000ms였다. API 요청 시간 및 UI 상태 확인이며 운영 성능 지표/화면 TTI는 아니다. [통합·네이티브 QA 원본](../../qa/codef-foreground-slow-2026-10-18.json).
- API(18085), 실제 프론트 Metro(18087), CODEF mock(18080, 정상 200/8초 지연)은 유지한다. 임시 HTTP 관측 프록시를 종료하고 휴대폰은 API로 직접 연결한다. 커밋·push·배포 없음.


## 18. #579 게시 브랜치 정렬 및 재검증

- `feat/579-codef-call-optimization`, base develop `3cdceaf0`에서 작성했다. 기존 로컬 QA 디렉터리는 변경하지 않았다. #578 SMS와 #575 정기 정산이 포함된 최신 기준이다. migration 추가·수정 없음.
- 계좌 GET에서 외부 수집과 SMS 재개 callback을 제거하고, 명시적 Sync Service/Writer 및 FinanceAuthenticationCompletionService로 연결했다. 새 SMS operation에 원래 수집 시각/종료일을 담되 구형 저장 payload도 읽는다. outer worker transaction의 afterCommit 이후에만 coverage를 기록한다.
- SMS feature flag 활성화 시 기존 사용자·기관 admission transaction/advisory lock을 보존한다. 이 경로의 최초 CODEF 호출에는 여전히 DB transaction이 열린다. 기본 비활성화 경로와 GET의 DB-only 동작은 유지한다. 이 제약을 PR에 명시한다.
- 최신 기준 focused Java 121개/11 suites, 기존 JS 35개, mock Python 9개 통과. 추가 SMS metadata serialize/resume round-trip 검증 및 compileJava/compileTestJava/bootJar 통과. 최초 새 callback 회귀 3개는 공통 actor mock의 불필요 stub으로 실패했고, 사용하지 않는 stub을 제거한 최종 실행에서 통과했다.
- 앞 절의 실기기·실 CODEF·8초 지연 수치는 기존 로컬 구현에서 확보한 기록이다. 게시 브랜치에서 전체 Java suite, 실제 은행 또는 네이티브 QA를 재실행한 것으로 표현하지 않는다. GET 동작 변경은 프론트 연동 PR과 함께 배포해야 한다.
