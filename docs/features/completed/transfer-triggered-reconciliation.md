# Feature Plan: 송금 탭 기반 자동 입금 대조

- 상태: 완료
- 작성·갱신일: 2026-10-14
- 관련 이슈: #530, 선행 #529 / #531
- 선행 스키마: [#532](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/532), V52
- 추가 Flyway migration: **No**. 병합된 V52를 그대로 사용한다.

## 목적과 범위

참여자가 송금 버튼을 누르고 은행 앱으로 이동하면 그 신호를 접수해 입금을 지연 대조한다. 은행 앱에서 돌아오거나 생성자가 새로고침해야 하는 의존을 줄인다. 신호 자체는 송금 완료 증거가 아니며 기존 거래 매칭만 입금을 확정한다.

회원과 링크 참여자의 API, 예약 저장·claim·복구, 제한된 자동 대조, 수동/위젯/자동 경로의 입금 알림, 호출 예산과 서버 계약을 구현한다. 클라이언트의 버튼 호출·상세 캐시 무효화·위젯 리로드와 실제 금융사/실기기 호출은 범위 밖이다.

## 완료 조건

- [x] 회원과 링크 참여자의 본인 미납 Share만 접수한다.
- [x] 동일 정산의 연타와 동시 요청은 한 예약으로 합치며 시간을 뒤로 밀거나 재시도 예산을 늘리지 않는다.
- [x] 최초 30초 후 대조하고 미납이면 실행 종료 후 90초, 300초 뒤 재시도한다. 최대 3회·최초 신호부터 10분 이내이다.
- [x] 예약·시도·lease를 PostgreSQL에 보관하고 재시작/만료 claim을 복구한다.
- [x] 외부 은행 호출은 DB 트랜잭션 밖에서 실행하며 오래된 워커는 새 세대에 결과를 쓰지 못한다.
- [x] 새 입금 알림과 전체 완료 알림을 생성자에게 중첩하지 않고 정산 ID로 상세/위젯을 다시 조회하게 한다.
- [x] 실제 HTTP/JWT·Redis·PostgreSQL·Spring 스케줄러로 정상·실패·권한·경쟁을 검증한다.
- [x] 전체 Java/JavaScript 회귀와 `bootJar` 검증 결과 기록

## 도메인과 계약

기존 settlement와 notification 도메인을 사용한다. 생성자, 타인, 미확정/완납 Share, DRAFT/SUSPENDED/COMPLETED 및 삭제 정산의 자동 대조 신호를 거절한다. 기존 SUSPENDED 송금 안내 조회는 유지한다. REQUESTED의 제출된 Share는 자동 대조할 수 있다.

| 사용자 | API | 인증 |
|---|---|---|
| 회원 참여자 | `POST /api/v2/settlements/{settlementId}/my-transfer-intent` | Access JWT와 현재 Redis 로그인 세션 |
| 링크 참여자 | `POST /api/v2/settlement-links/{token}/transfer-intent` | 활성 링크와 같은 링크·정산의 `Link-Participant-Context` UUID |

body는 없다. 두 API는 `202`와 `{ settlementId, status, scheduledAt }`를 반환한다. `scheduledAt`은 UTC이며 PROCESSING이면 null이다. 입금 결과가 아니다. 일반 비회원 회원 API/위젯 JWT는 401, 타인은 403, 무효 링크·문맥·삭제 정산은 404, 미납 몫 없는 상태는 409이다. 종료된 사이클은 최초 신호부터 10분이 지나기 전 재접수하면 429를 반환한다.

Input Ports는 `RecordMySettlementTransferIntentUseCase`, `RecordSettlementTransferIntentUseCase`, `ProcessSettlementDepositReconciliationJobsUseCase`이다. 식별자 입력만 있으므로 별도 Command record는 필요 없다. `SettlementTransferIntentResult`를 Web Response의 `from(result)`로 매핑한다.

| Output Port | 대화 | 구현 |
|---|---|---|
| `SettlementDepositReconciliationJobStorePort` | 예약 병합, claim/lease 갱신·검증, 종료 | `SettlementDepositReconciliationJdbcAdapter` |
| `SettlementBankLookupBudgetPort` | 공유·사용자 일일 호출 예산의 원자 예약 | `SettlementBankLookupBudgetRedisAdapter` |
| 기존 settlement/account/external/notification Ports | 참여자·Share·계좌·연결·매칭·알림함·push outbox | 기존 Adapters 재사용 |

`SettlementTransferReconciliationPolicy`는 상태/참여자/Share/확인 금액을 받아 접수 가능 여부를 판단하고 지연·재시도·종료를 결정한다. Infra는 기술 설정과 scheduler Bean만 소유한다.

## 예약과 대조

`settlement_deposit_reconciliation_jobs`의 settlement_id PK로 한 정산의 사이클을 직렬화한다. 접수는 정산을 잠근 뒤 현재 참여자·미납을 검사한다. 활성 사이클의 시도 수, 다음 시각, 만료 시각, generation과 lease는 유지한다. 만료 사이클은 종료 처리한 뒤 세대 번호를 올려 새 사이클을 만든다.

워커는 실행할 때마다 한 행을 `FOR UPDATE SKIP LOCKED`로 claim하고 시도 수를 올린다. 여러 작업을 미리 claim해 뒤쪽 lease가 대기 중 소진되는 방식은 사용하지 않는다. 만료 PROCESSING은 이미 사용한 시도를 유지한 채 RETRY 또는 EXHAUSTED로 복구한다. 대기 작업의 10분이 지나면 추가 은행 호출 없이 EXHAUSTED가 된다.

각 월별 은행 조회 직전 lease를 360초 갱신한다. 기본 CODEF read timeout은 305초다. 더 긴 외부 지연이나 만료는 결과 폐기와 제한된 복구로 수렴하며 무한 재시도하지 않는다. 외부 조회 중에는 DB 잠금을 보유하지 않는다.

결과 저장의 잠금 순서는 계좌 → 정산 → job이다. 기존 writer가 현재 정산 상태·계좌 snapshot·매칭 후보를 다시 검사하고 현재 generation/token/유효 lease/10분 만료를 확인한다. 거래 저장·입금 매칭·정산 완료·inbox/outbox·job 결과는 같은 DB 트랜잭션에 반영된다. 알림 저장 실패 시 입금/거래와 성공 상태가 함께 롤백된다.

신호 뒤 중단/삭제 또는 금융 연결·계좌가 없어졌으면 CANCELLED로 끝낸다. 조회 실패는 PROVIDER_ERROR, 호출 예산 소진은 QUOTA_EXHAUSTED로 구분한다. RETRY의 NO_NEW_DEPOSIT은 다음 입금 확인이 더 필요하다는 대기 표식이며, 부분 입금을 반영한 뒤에도 사용할 수 있다. 이 코드를 사용자에게 입금 실패로 반환하지 않는다.

## 호출 예산

정산의 수동·위젯·자동 은행 조회는 월별 실제 Port 호출마다 예약한다. 호출에 실패하거나 뒤의 DB 변경이 롤백돼도 소비한 예산을 돌려주지 않는다. Redis 장애 시 조회를 우회하지 않는다.

KST 날짜별 공유 예산과 사용자 예산을 한 Lua 실행으로 검사·증가한다. 총 100회, 자동은 80회까지 허용하며 수동 여유 20회를 둔다. 여러 사용자·서버의 동시 호출도 공유 상한을 넘지 못한다. 두 key는 같은 namespace hash slot을 사용하고 다음 KST 자정 + 1시간까지의 상대 TTL로 보존한다.

[CODEF 공식 소개서](https://www.codef.io/assets/pdf/202303_CODEF_API_%EC%86%8C%EA%B0%9C%EC%84%9C.pdf)는 데모 버전의 일일 100회 제한을 명시한다. 사용자별 예산만으로 전체 호출이 안전하다고 가정하지 않고 공유 예산도 보수적으로 제한했다. 정산 외 인증/계좌 조회 등 CODEF 기능의 소비량은 이 카운터에 포함되지 않으며 외부 제공자의 집계 범위·실제 한도를 대체하지 않는다.

같은 CODEF 이용 범위를 공유하는 서버는 같은 Redis와 namespace를 사용해야 한다. namespace를 다르게 설정하면 서로 다른 예산이다. Redis 카운터 유실 시 그날의 실제 호출 수를 복원하지 못하는 한계가 있다.

## 알림과 클라이언트 연동

새 매칭이 없으면 알리지 않는다. 여러 거래가 같은 Share에 새로 매칭되면 한 알림으로 합치고 마지막 신규 deposit ID로 dedup한다. `SETTLEMENT_DEPOSIT_CONFIRMED` / `DEPOSIT_CONFIRMED:<depositId>`를 사용하고 정산명·입금자·남은 금액을 본문에 담는다. REQUESTED는 아직 미확정 몫이 남을 수 있어 “현재 확정된 몫의 남은 금액”으로 표시한다.

정산이 COMPLETED로 전환되면 생성자에게 입금 알림을 추가하지 않고 `SETTLEMENT_COMPLETED` / `SETTLEMENT_COMPLETED:<settlementId>` 한 건을 보낸다. 제목은 “모두 받았어요”, 본문은 최근 입금자와 남은 0원이다. 기존 회원 참여자 완료 알림도 유지하며 생성자는 참여자 목록과 무관하게 대상에 포함한다. 이름은 최대 3명 + 추가 인원으로 줄여 기존 메시지 길이 제한을 지킨다.

기존 NotificationRecorder가 inbox와 push outbox를 함께 저장하고 기존 발송 경로가 `type`, `settlementId`, `SETTLEMENT_DETAIL` target을 전달한다. 앱·웹은 입금/완료 푸시 수신 시 해당 상세와 `GET /api/v2/settlements/widget-summary`를 다시 조회해야 한다. 서버의 값은 DB 반영으로 갱신되지만 실제 위젯 리로드는 클라이언트가 구현해야 한다. OS 푸시 수신이나 은행의 거래 반영 지연은 보장하지 않는다.

## 설정과 운영

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `SETTLEMENT_TRANSFER_RECONCILIATION_ENABLED` | `true` | 예약 작업 자동 실행 |
| `SETTLEMENT_TRANSFER_RECONCILIATION_POLL_INTERVAL` | `5s` | due 작업 poll 간격 |
| `SETTLEMENT_TRANSFER_RECONCILIATION_BATCH_SIZE` | `10` | poll 당 순차 실행 상한(1..100) |
| `SETTLEMENT_BANK_LOOKUP_BUDGET_NAMESPACE` | `default` | 공유 예산 Redis namespace |

스케줄러를 꺼도 접수 API는 예약을 보관한다. 다시 켜기 전 10분이 지난 예약은 대조하지 않고 종료한다. 워커는 전용 단일 scheduler를 쓰고 공용 scheduler를 별도로 유지해 느린 은행 호출이 알림·독촉 등 다른 예약 작업을 점유하지 않는다. `scripts/test.sh`는 자동 실행을 끄고 신규 통합 테스트는 긴 poll 간격으로 활성화한 서버를 검증한다.

## 영향과 검증

추가 DB SQL, 외부 SDK, 새 Domain Aggregate나 Kafka topic은 없다. 기존 transaction 저장/계좌별 모호한 입금 매칭, 수동/위젯 대조, 알림 delivery ledger를 재사용한다. 활성화된 `local` Spring 서버를 실제 HTTP 요청으로 검증한다.

신규 통합 시나리오 19개: JWT/타인/생성자/위젯 범위, 링크·문맥 만료/소속, 완납/미확정/상태/삭제, 회원+링크+동시 탭 병합, 부분/완납/REQUESTED 입금, 위젯 값, 수동 중복 대조, 실제 push outbox 준비·발송·중복 방지, 최대 재시도, provider 오류, 중단 취소, DB 롤백, 동시 claim, 만료 결과 차단·재접수, lease 복구·cooldown, 공유 예산 경쟁·자동/수동 여유·KST rollover, OpenAPI, 은행 조회 중 공용 scheduler 실행.

최신 develop 반영 후 전용 PostgreSQL 18.3·Redis·Testcontainers Kafka에서 `bash scripts/test.sh compileJava test bootJar` 통과: Java **1968개 / 246 suites**, JavaScript **35개**, 실패·오류·skip 0. 신규 HTTP 통합 19개와 실제 scheduler, 기존 notification·widget·settlement·certificate·treasurer 회귀를 포함한다. 변경 문서의 상대 링크와 `git diff --check`도 통과했다. 실제 금융사·FCM/APNs·클라이언트 앱/웹 화면·운영 배포는 수행하지 않았다.

## 커밋 경계와 진행 기록

1. `docs(settlement): 송금 탭 기반 입금 대조 구현 계획 기록`
2. `feat(notification): 새 입금과 정산 완료 알림 통합`
3. `feat(settlement): 송금 탭 접수와 제한된 자동 입금 대조 구현`
4. `test(settlement): 송금 신호 권한과 대조 경쟁 회귀 검증`
5. `docs(settlement): 자동 입금 대조 검증 결과 기록`

2026-10-14: #532 병합을 확인한 최신 develop에서 후속 구현을 시작했다. 선행 계획의 사용자별 한도 가정을 공유+사용자 한도로 보완하고, DB claim 경쟁·외부 I/O 트랜잭션 분리·전체 완료 알림 중복·scheduler 점유를 검증했다. Feature Plan을 완료 문서로 이동하고 PR에 핵심 동작과 결과만 기록한다.

## 리뷰 수정 (2026-10-14)

[#538의 스키마 누락 지적](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/538#discussion_r4269006597)을 확인했다. 알림 API가 반환하는 `SETTLEMENT_DEPOSIT_CONFIRMED`가 `NotificationItem.type`의 `allowableValues`에 없어 해당 값을 추가했다. OpenAPI 전체 문자열에서 이름만 찾던 검증을 해당 속성의 enum과 모든 `NotificationType` 값의 일치 검증으로 바꿨고, 실제 알림함 HTTP 응답도 확인한다. 추가 migration은 필요하지 않다.

수정 전 강화한 OpenAPI 테스트 1건이 새 타입 누락으로 실패하는 것을 재현했다. 수정 후 `compileJava`, `bootJar`, 입금 신호·알림함 통합 Java 43개 / 2 suites와 JavaScript 35개가 실패·오류·skip 없이 통과했다. 변경 범위가 스키마 선언과 기존 회귀 강화이므로 기존 전체 1,968개 검증은 다시 실행하지 않았다. 원격 CI의 기존 실행은 GitHub 결제·사용 한도 문제로 시작되지 못했다.

추가 커밋: `fix(notification): 입금 확인 알림의 OpenAPI 유형 누락 보완`.
