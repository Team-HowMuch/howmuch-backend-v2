# Feature Plan: 송금 탭 기반 지연 입금 대조

- 상태: 구현 중, 선행 #532 병합 완료
- 작성일: 2026-10-14
- 관련 이슈: #530, 선행 #529

## 목적과 완료 조건

참여자가 은행 앱으로 이동한 뒤 돌아오지 않아도 송금 버튼을 누른 신호로 입금을 대조한다. 탭은 송금 완료 증거가 아니며 기존 입금 매칭만 결제를 확정한다. 생성자는 새 입금·남은 금액·전체 완료 알림을 받고 앱이 정산 상세와 위젯을 다시 조회한다.

- 회원과 링크 참여자의 본인 송금 탭을 접수한다.
- 같은 정산의 활성 작업은 하나로 합치며 연타가 예약 시각이나 재시도 예산을 늘리지 않는다.
- 최초 30초 후 대조하고 미납이면 90초, 300초 뒤 재시도한다. 한 사이클은 최대 3회, 최초 신호 후 10분 이내이다.
- 서버 재시작과 다중 인스턴스에서 예약·claim을 유지한다. 실제 CODEF 호출은 DB 트랜잭션 밖에서 실행한다.
- 수동·자동 대조에서 새 매칭이 생긴 경우만 생성자에게 알리며 기존 매칭은 재알림하지 않는다.
- 이번 후속 작업은 병합된 V52를 사용하며 추가 Flyway migration은 필요하지 않다. 서버 API·워커·알림·실제 PostgreSQL/Redis/JWT 회귀를 구현하고 PR을 만든다. 앱·웹 버튼 호출과 위젯 리로드는 해당 클라이언트 후속 작업이다.

## 도메인과 기존 흐름

기존 settlement 도메인을 사용한다. 회원은 CurrentActorPort와 linkedUserId로 본인을 찾고, 링크 참여자는 기존 link token hash + participant context의 정산·링크·만료 일치를 검사한다. SettlementTransferInstructionReader의 송금 가능 상태 및 미납 Share 판정을 공통으로 재사용한다. 생성자·타인·만료 링크·선택 미완료·중단·삭제·완료·이미 완납한 참여자는 새 작업을 만들 수 없다.

현재 SettlementDepositService → SettlementBankTransactionFetchService → SettlementDepositWriter가 외부 조회, 거래 저장, 계좌→정산 잠금, 매칭과 알림을 수행한다. writer는 이미 SHARE_PAID 키로 완납 알림과 SETTLEMENT_COMPLETED 키로 완료 알림을 기록한다. 부분 입금 알림은 없다. NotificationRecorder는 inbox와 push outbox를 같은 트랜잭션에 저장한다. 이 흐름을 확장하며 자동 워커는 HTTP 현재 사용자를 가장하지 않는다.

## 계약과 후속 구현 설계

- 회원: `POST /api/v2/settlements/{settlementId}/my-transfer-intent`
- 링크: `POST /api/v2/settlement-links/{token}/transfer-intent`, 기존 participant context 헤더 사용
- Input Ports: `RecordMySettlementTransferIntentUseCase`, `RecordSettlementTransferIntentUseCase`, `ProcessSettlementDepositReconciliationJobsUseCase`
- 입력은 식별자 또는 plain Command, 출력은 `SettlementTransferIntentResult` → Web Response. 접수 응답은 `202`, 예약 시각을 반환한다. 송금 확정으로 해석하지 않는다.
- `SettlementDepositReconciliationJobStorePort`: 활성 사이클 병합, 만기 작업 claim, token/generation 검증 후 결과 반영. JDBC Adapter가 atomic upsert 및 `FOR UPDATE SKIP LOCKED`를 구현한다.
- `SettlementTransferReconciliationPolicy`: 지연·재시도·종료·재접수 조건. 설정은 Infra에서 기술적 활성화/스케줄 간격만 제공한다.
- scheduler는 신호 없는 정산을 주기 대조하지 않는다. 정산별 DB 작업을 claim한 후 현재 송금 가능 상태와 계좌를 다시 검사한다. 조회 중 상태/계좌 변경은 writer 잠금 아래 재검증한다.
- 활성 PENDING/RETRY/PROCESSING에 추가 신호가 오면 `last_signaled_at`만 갱신한다. `generation`, `attempt_count`, `next_attempt_at`, `expires_at`, lease는 바꾸지 않는다. PROCESSING 중 탭도 lease를 무효화하지 않는다.
- terminal 이후 재접수는 최소 최초 신호 + 10분의 cooldown과 실제 미납·권한을 검사하고 generation 증가, 시각·예산 초기화를 원자 실행한다. 응답 재전송은 이전 결과로 수렴한다.
- claim에서 attempt_count를 증가시키고 무작위 lease_token/lease_until을 저장한다. 결과 반영 시 generation+token+미만료 lease를 검사한다. 만료 워커는 DB 결과를 쓰지 못한다. lease는 외부 HTTP timeout보다 길게 잡는다. 만료 claim도 이미 사용한 시도에 포함하며 3회나 expires_at에 도달하면 EXHAUSTED로 종료한다.
- CODEF quota는 실제 월별 조회 호출마다 원자 예약하고 서버 내 수동/위젯/자동 경로에서 같은 예산을 사용한다. #529 구현과 조율하며 Redis 기존 기술 경계를 사용한다. #531의 SettlementWidgetRefreshStorePort는 사용자별 lease/60초 cooldown/마지막 대조 시각만 제공하므로 일일 quota가 이미 구현됐다고 간주하지 않는다. 기본 자동 상한은 사용자당 KST 일일 100회 이하이고 수동 조회 여유분을 둔다. quota 부족·인증 오류를 미납으로 해석하지 않는다. Redis 장애 시 자동 조회를 중단한다. 이미 소비한 호출은 DB rollback으로 복원하지 않는다.
- 한 사이클의 여러 참여자 탭은 정산 하나의 조회로 묶는다. 동일 계좌의 여러 정산까지 외부 조회를 공유하는 범위는 #531의 SettlementWidgetRefreshService 그룹화/월별 beforeEachPeriod callback과 함께 후속 작업에서 검증한다.
- 새 입금은 `SETTLEMENT_DEPOSIT_CONFIRMED`, `DEPOSIT_CONFIRMED:<depositId>` 키로 recipient-scoped dedup을 한다. 메시지는 정산명·입금자·남은 금액을 담고 기존 settlement detail target/settlementId로 상세·위젯 재조회를 유도한다. 마지막 입금은 기존 완료 알림의 “모두 받았어요” 문구로 구분하며 동일 recipient에 완납·부분입금·전체완료 푸시가 중첩되지 않게 writer에서 통합한다.
- SQL 배포 시에는 새 타입의 inbox를 작성하지 않는다. 새 enum을 읽는 서버/워커를 배포한 뒤 producer를 활성화한다. 구버전 Java는 새 타입을 읽을 수 없다.

## 저장 구조와 migration 판정

추가 Flyway migration: **No**. 선행 [#532](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/532)의 V52를 그대로 사용한다. 기존 설계 당시 migration은 필요했다. 기존 outbox는 푸시 발송용이며 외부 은행 대조 예약·종료 상태와 의미가 다르다. 메모리 예약은 재시작에 유실되고 Redis TTL만으로는 DB 입금 변경과 claim fencing을 검증하기 어렵다.

`V52__create_transfer_triggered_reconciliation_schema.sql`을 추가한다. 최신 origin/develop(`1d0c9826`, #531 포함)의 최댓값은 V51이고 열린 PR #342에는 SQL 변경이 없다. 기존 SQL·데이터를 수정하지 않는다.

- `settlement_deposit_reconciliation_jobs`: settlement_id PK/FK, 한 정산당 한 행, reusable generation, PENDING/PROCESSING/RETRY/SUCCEEDED/EXHAUSTED/CANCELLED, 최대 3회, 다음 시각·lease·종료 시각, 최초/최종 신호·만료·감사 시각. UTC timestamp(6). 개인정보나 raw token은 저장하지 않는다.
- lifecycle/시도 수/시간/실패 코드 제약과 due/lease partial index. 정산 물리 삭제 시 cascade, soft delete는 후속 worker에서 CANCELLED 처리한다.
- `user_notifications` 타입과 타입별 target 제약에 SETTLEMENT_DEPOSIT_CONFIRMED를 허용한다. SETTLEMENT_DETAIL로 제한하고 기존 타입의 허용 범위를 유지한다. 기존 recipient dedup과 notification delivery outbox를 재사용한다.
- 상태 전이, cooldown, lease 소유자 비교, quota, 현재 참여자 권한은 후속 Application/Adapter 구현 책임이다. SQL 제약만으로 feature가 동작하지 않는다.

## 영향과 검증 계획

기존 API·CODEF 매칭·알림 발송 동작은 이번 SQL로 바뀌지 않는다. 기존 Java enum은 새 생산자가 비활성인 동안 호환된다. 새 테이블은 Flyway로 만들고 Hibernate의 기존 entity validate에 영향이 없는지 compile 및 기존 DB 통합 테스트로 확인한다.

격리 PostgreSQL에 실제 Flyway로 기존 baseline → V51 → V52 적용, validate, 재실행 0건을 확인한다. 유효/잘못된 작업 상태·lease·시도 수·FK·중복·시간·신규 및 기존 알림 타입/target을 SQL로 검증한다. quota·은행·JWT·API·실제 푸시는 후속 작업 검증 항목이다.

## 예상 커밋 경계

1. `docs(settlement): 송금 탭 기반 입금 대조 구현 계획 기록`
2. `feat(notification): 새 입금과 정산 완료 알림 통합`
3. `feat(settlement): 송금 탭 접수와 제한된 자동 입금 대조 구현`
4. `test(settlement): 송금 신호 권한과 대조 경쟁 회귀 검증`
5. `docs(settlement): 자동 입금 대조 검증 결과 기록`

각 기능 커밋은 실제 호출과 구현을 포함하고 compileJava를 통과한다.

## 진행 기록

- 2026-10-14: 이슈·선행 이슈·기존 입금 writer와 inbox/outbox·develop 버전·열린 PR를 확인하고 설계를 완료했다. SQL 선행 PR 생성 후 repository gate에 따라 종료한다.

## 검증 결과

- 첫 커밋: Flyway 12.4 / PostgreSQL 18.3으로 빈 DB에 V51까지 50개 적용 후 V52 1개 업그레이드, validate 및 재실행 0개. 작업 상태·예산·시간·FK·중복·coalesced tap·fenced SQL·cascade probes 통과.
- 최종 SQL: 별도 빈 DB에 V51까지 적용하고 기존 7종 inbox fixture를 저장한 후 V52 1개 업그레이드. validate/재실행 0개, 7종 알림 데이터 완전 동일, 신규 타입·detail target·allocation·dedup·legacy route trigger probes 통과.
- 두 실제 DB connection에서 SKIP LOCKED로 같은 작업을 중복 claim하지 않는 SQL 예시 통과. 실제 worker 구현/네트워크 경쟁의 보장은 후속 검증 대상.
- 최초 기준 5개 Java 회귀 클래스와 JavaScript 35개 통과. 최신 develop(`1d0c9826`) 반영 후 compileJava + 같은 클래스 + 위젯 HTTP 회귀 통과: Java 58개 / 6 suites, JavaScript 35개, 실패·오류·skip 0.
- 실제 CODEF·푸시·신규 API·신규 worker는 미구현/미검증. 전체 suite는 SQL 변경 영향 범위를 벗어나 실행하지 않았다.

## 후속 구현 결정

- 자동 대조는 REQUESTED/AWAITING_DEPOSIT에만 허용한다. 기존 송금 안내의 SUSPENDED 조회 동작은 유지하지만, 중단 정산 자동 대조는 접수하지 않는다.
- CODEF 기본 read timeout은 305초이다. 각 조회 직전 lease를 360초 갱신하고 결과 쓰기는 현재 generation/token/lease 및 사이클 만료를 잠금 아래 확인한다. 외부 호출은 재시도 예산에 포함한다.
- 새 입금이 여러 건이면 정산·입금자별로 한 알림을 만들고 마지막 신규 deposit ID를 dedup key로 사용한다. 전체 완료 시 생성자에게 별도 입금 알림을 보내지 않고 완료 알림에 입금자와 남은 0원을 담는다.
- 모든 정산 은행 조회의 월별 호출은 Redis에서 사용자·KST 일별 총 100회로 제한한다. 자동은 80회 이내로 제한해 수동 여유 20회를 둔다. 계좌 일반 거래조회 등 정산 외 CODEF 기능의 한도와 외부 제공자의 실제 집계 범위는 이 제한으로 보장하지 않는다.
- claim 작업과 신호 접수의 잠금 순서는 계좌(결과 저장 시) → 정산 → job이다. lease 갱신은 별도 짧은 트랜잭션으로 끝내며 외부 호출 중 DB 잠금을 보유하지 않는다.
