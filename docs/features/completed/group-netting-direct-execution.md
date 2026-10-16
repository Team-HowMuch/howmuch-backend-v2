# Feature Plan: 그룹 압축 정산 직접 실행 및 재압축

- Status: Completed (local verification)
- Last updated: 2026-10-17
- Related issue: #506
- Baseline: origin/develop a30875b1, V53 병합 완료 (#539)


2026-10-17 권한 정책 변경: 활성 그룹 멤버 모두 압축·재압축할 수 있다. 이 정책의 DB 전제는 별도 [SQL PR #571](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/571)의 V58이다. [멤버 실행 후속 계획](group-netting-member-execution.md)에 구현·검증 범위를 기록한다. 아래 이전 날짜의 검증 기록은 당시 수행 범위다.

## 목적

활성 그룹 멤버가 압축 버튼을 누르면 preview/전원 동의 없이 HTTP 202로 실행을 접수한다. Spring Batch가 은행 거래를 먼저 동기화하고 그룹 전체의 현재 미납 관계를 압축한다. 기존 ACTIVE 압축의 미납 잔액은 새 원본 정산과 함께 재압축한다. 사용자별로 다른 결과를 만들지 않는다.

## 불변식과 계약

- 기존 Greedy/DP/Pair 조건부 전략과 공통 Validator를 재사용한다.
- `POST /api/v2/groups/{groupId}/settlement-nettings`: 활성 그룹 멤버, UUID Idempotency-Key, request body 없음. 실행 조회 및 공통 결과/송금 안내 조회를 유지한다.
- 새 실행 입력은 내부 `group_netting_execution_snapshots`에 저장한다. 사용자 미리보기/동의 API·알림은 제공하지 않는다.
- 재압축은 기존 송금의 `amount-confirmedAmount`만 이월한다. 실제 입금·거래 귀속·이전 송금·원본 이력을 지우거나 다시 부채로 만들지 않는다.
- 외부 은행 I/O는 DB transaction/잠금 밖. 최종 확정은 관련 사용자/계좌/정산/그룹/세대 잠금, snapshot/fingerprint, lease_token, 최신 입금/검토를 재확인하고 원자적으로 수행한다.
- OPEN review가 있으면 재압축을 중단한다. 이전 송금 reference 입금은 중복 자동 인정하지 않고 review로 보낸다.
- 계좌 조회는 계좌 소유자의 연결을 사용한다. 요청자의 연결로 다른 사용자의 계좌를 조회하지 않는다.
- CREATE가 QUEUED/SYNCING/RETRY인 동안 해당 그룹의 원본·이전 압축 송금 안내는 `NETTING_PROCESSING`으로 차단한다. 이미 열린 은행 앱이나 외부 송금은 중단할 수 없으며 늦은 입금은 review로 보존한다.
- 기존 원본 응답의 `status`에 `NETTING_IN_PROGRESS`를 전달한다. 별도 boolean을 중복 추가하지 않는다. 완료 후 `COMPLETED`, 이전 압축 세대는 `SUPERSEDED`로 조회한다.

## 저장 구조와 범위

- Flyway migration required: **No**. 병합된 V53의 실행/snapshot/rollover/claim/review/Batch 테이블을 사용한다. 기존 임시 V48/V49 및 recurring SQL은 가져오지 않는다.
- 구 작업 폴더의 로컬 변경을 보존하고 최신 develop 작업 공간으로 기능을 이식한다. 기존 정산 refresh/중단/분류/계좌 lifecycle와 호환한다.
- 새 Output Port는 실제 실행 저장/스냅샷/확정/조회 capability에만 둔다. 기존 은행 조회 및 거래 저장 경계를 재사용한다.
- UI 제작, 실제 송금, 신규 계산 알고리즘, SQL/배포/commit/push/PR은 이번 범위 밖이다.

## 구현 순서

- [x] 최신 develop/V53/기존 status 응답 확인, 새 migration 불필요 확정
- [x] 기존 계산기 및 공통 검증 이식
- [x] 직접 실행 Command/Result/Ports 및 lease 기반 저장
- [x] 내부 입력 수집·은행 동기화·원자적 확정·재압축 lineage
- [x] 조회/송금 안내/입금 대조·알림과 기존 정산 lifecycle 연결
- [x] status 응답/Swagger/사용 예시
- [x] 단위/실제 DB Batch·재압축·경합·금액 보존 검증

## 예상 commit 경계 (게시 요청 시만)

1. feat(settlement): 직접 압축 실행과 Batch 및 V53 저장 연결
2. feat(settlement): 미납 잔액 재압축과 정산 조회 및 송금 차단 연결
3. test(settlement): 압축 상태 응답과 입금 보존 및 재압축 회귀 검증

## 검증

Java 21 compileJava, 계산기 단위 테스트, 격리 PostgreSQL/Redis에서 V53 기반 최초 압축→일부 입금→새 정산→재압축→이전 이력/입금/상태/금액 보존 및 API 응답을 확인한다. 멱등/OWNER/worker lease/입력 변경/OPEN review/이전 reference/0 송금도 확인한다. Docker가 응답하지 않으면 로컬 별도 DB 프로세스를 사용하고 실제 수행 범위를 기록한다. 모든 변경의 diff/link 검증을 수행한다.

## 구현 결과와 UI 연결

- 작업 브랜치: `feat/506-group-netting-direct`, 최신 `origin/develop` 기반의 별도 managed worktree. 기존 `howmuch-backend-v2` 작업 폴더의 로컬 변경은 보존했다.
- [직접 실행 Controller](../../../src/main/java/com/amateur/howmuchbackend/adapter/in/web/settlement/netting/GroupNettingController.java): body 없이 요청을 접수하고 실행 ID를 반환한다. 기존 사용자 미리보기/동의 계약은 가져오지 않았다.
- [Spring Batch 설정](../../../src/main/java/com/amateur/howmuchbackend/infra/settlement/netting/NettingBatchConfiguration.java): 은행 동기화 → 원자적 확정 2개 Tasklet Step. 은행 요청 중 DB transaction을 열어두지 않는다.
- [확정 Writer](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/GroupNettingCommitWriter.java): 관련 입력 잠금 → 활성 멤버/lease/저장된 계산기 key/version/입금/계좌 identity 재검증 → snapshot/새 세대/원본 상태/알림/실행 성공을 같은 transaction으로 저장한다.
- [응답 DTO](../../../src/main/java/com/amateur/howmuchbackend/adapter/in/web/settlement/dto/response/SettlementListItemWebResponse.java)의 기존 `status`를 재사용한다. 별도 `isNetted` 필드는 추가하지 않는다. 진행 중인 압축에 속한 원본은 `NETTING_IN_PROGRESS`, 최종 송금 완료 또는 전액 상계 후에는 `COMPLETED`다.
- 압축 카드의 목록은 기존 원본 목록과 별개로 current 결과의 `transfers`로 구성한다. `amount - confirmedAmount`가 남은 송금액이다. payer/receiver ID는 그룹 멤버 정보에 연결한다. 원본 정산의 실제 입금 진행률을 압축 송금 진행률로 바꾸거나 가상 입금을 추가하지 않는다.
- 원본의 압축 연결 이력은 `GET /api/v2/settlements/{settlementId}/netting`으로 조회한다. 재압축 후에는 가장 최근 후속 세대를 반환한다. 완료 원본의 `COMPLETED`만으로 압축 여부를 구분할 수는 없으므로 상세 이력은 이 조회 또는 압축 결과의 `sources`를 사용한다.

### 요청 순서

```http
POST /api/v2/groups/{groupId}/settlement-nettings
Authorization: Bearer <access-token>
Idempotency-Key: <UUID>
```

본문 없이 실행한다. HTTP 202는 접수이며 완료가 아니다. 같은 접수의 네트워크 재시도에는 같은 UUID를 사용한다.

1. `GET /api/v2/groups/{groupId}/settlement-netting-executions/{executionId}`로 상태를 조회한다. `QUEUED`, `SYNCING`, `RETRY` 동안 대기하고, `SUCCEEDED`의 `nettingId`로 결과를 읽는다. `STALE`/`FAILED`는 `errorCode`로 안내한다.
2. `GET /api/v2/groups/{groupId}/settlement-nettings/current`로 공통 최신 결과를 조회한다. 최초 압축과 재압축 모두 같은 API를 사용한다. 이력은 `/settlement-nettings`, 세대별 상세는 `/settlement-nettings/{nettingId}`다.
3. `GET /api/v2/groups/{groupId}/settlement-nettings/{nettingId}/transfers/{transferId}/instruction`은 해당 송금자만 호출할 수 있다. 전체 계좌번호는 이 응답에만 제공하며 공통 결과에는 마스킹된 번호를 제공한다.
4. 본인 수취 계좌 입금 확인은 `POST /api/v2/groups/{groupId}/settlement-nettings/{nettingId}/deposits/reconcile`과 새 `Idempotency-Key`로 접수한다. 이전 reference/모호한 거래는 자동 중복 인정하지 않고 관리자의 검토 대상으로 보존한다.

### 재압축·안전성

- 일부 입금된 이전 송금은 실제 인정액을 유지하고 미납 잔액만 새 원본 부채와 합친다. 이전 세대는 `SUPERSEDED`이고 송금 안내를 재발급하지 않는다.
- 원본/이전 압축의 송금 안내는 새 CREATE 실행의 `QUEUED`/`SYNCING`/`RETRY` 동안 HTTP 409 `NETTING_PROCESSING`으로 차단한다. 일반 원본이 압축된 이후의 안내는 `NETTING_TRANSFER_REPLACED`다. 실패 후에는 기존 ACTIVE 안내를 다시 사용할 수 있다.
- 실제 은행에서 이미 실행된 송금이나 열려 있는 은행 앱은 취소할 수 없다. 늦은 거래는 영구 claim과 review로 보존한다. RETURNED/UNRELATED 처리도 거래를 다른 정산에 재사용하도록 claim을 해제하지 않는다.
- ACTIVE 압축 및 OPEN review가 의존하는 멤버/사용자/계좌는 기존 탈퇴·삭제 경계에서 보호한다. 압축 완료 후 들어온 거래의 OPEN review도 삭제를 막는다.
- 독촉은 OPEN review·부분 입금·재압축 대기 중에 억제한다. 기존 정산 알림 경로도 NETTING 원본의 이전 송금 안내를 재발송하지 않는다.
- 계좌 조회 후 입금 감지는 별도 DETECT 실행을 멱등 접수한다. 거래 저장과 DETECT 접수는 기존 Application 호출 단위이며 별도 outbox로 원자 결합한 것은 아니다. 수취인의 명시적 reconcile 경로를 유지한다.

### 계산 전략과 운영 설정

- 기존 Greedy/Baseline/Validator와 Bitmask DP, 조건부 Pair Reduction DP를 재사용한다. Pair는 exact-pair가 있고 잔여 비영 잔액 참여자가 12명 이하일 때 후보가 된다. Greedy가 최적성 하한에 도달하면 정확 최적화를 생략한다.
- 신규 선택 정책 버전은 3이다. `PAIR_REDUCED_DP_NET` version 1 등 선택 결과를 snapshot에 고정하고 최종 확정 시 임의 재선택하지 않는다.
- `SETTLEMENT_NETTING_ENABLED=true`에서 Controller/Batch/worker를 활성화한다. 기본값은 false다. Batch 자동 Job 시작과 자동 schema 생성은 비활성화하며 병합된 V53 metadata를 사용한다.
- `SETTLEMENT_NETTING_PAIR_REDUCTION_ENABLED=false`는 새 실행의 선택을 이전 정책으로 되돌린다. 저장된 Pair 선택의 실행은 유지한다. ACTIVE 압축이 존재하는 상태에서 전체 기능 플래그를 끄면 해당 API도 사라지므로 전체 플래그를 계산 전략 롤백 용도로 사용하지 않는다.
- dispatcher fixed delay 5초, worker lease 90초, 별도 heartbeat 20초, 실행 deadline 10분, 기본 최대 3회 시도를 사용한다. 만료 작업은 `SKIP LOCKED`로 다른 그룹을 막지 않고 회수하고, 이전 lease token의 확정은 거부한다.

## 최종 검증 결과 (2026-10-16)

- Java 21 `compileJava`, `bootJar`: 성공.
- 선택한 Java 테스트 **185개: 성공, 실패/오류/skip 0**. 기존 계산기·선택/Fallback, 기존 계좌 조회·미납 요약·송금 안내·알림·정산 목록·참여자·입금·계좌 삭제 경로를 함께 검증했다.
- [실제 DB/Batch 통합 테스트](../../../src/test/java/com/amateur/howmuchbackend/application/settlement/netting/GroupNettingDirectIntegrationTest.java) **16개** 포함: 직접 HTTP 202, 원본 list/detail status, OWNER/송금자 권한, 멱등/동시 요청, 부분 입금 이월, zero-net, 이전 reference review, review 적용액/영구 claim, 선행 세대 완료, worker 복구/lease fencing, Pair 선택 고정, 계좌 identity 경합, 탈퇴/삭제 의존성.
- `scripts/test.sh`가 실행한 기존 JavaScript 테스트 **35개 성공**.
- Docker가 응답하지 않아 격리된 PostgreSQL 18.6/Redis 8.10.2 프로세스를 사용했다. `local` Spring Application context와 실제 병합 migration을 사용했다. 실 금융기관/CODEF 요청 및 조회 quota는 해당 통합 테스트에서 mock 처리했다.
- 회귀 중 발견한 기존 월별 목록 테스트의 직접 NETTING 상태 삽입은 V53 불변 snapshot 제약과 충돌했다. 그 상태의 검증을 실제 Batch 통합 흐름으로 분리했으며 DB 제약을 완화하지 않았다.
- 전체 프로젝트 회귀, JMH 재실행, 실 CODEF/실 송금, dev/prod 배포는 수행하지 않았다. SQL 변경, commit/push/PR 생성도 하지 않았다.

### 재현 명령

아래 외부 환경 변수는 격리 PostgreSQL/Redis에 지정한다. `scripts/test.sh`는 실제 `.env`를 읽지 않고 테스트용 외부 연결/키를 사용한다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) bash scripts/test.sh compileJava bootJar test \
  --tests '*GroupNettingDirectIntegrationTest' \
  --tests '*NettingCalculationEngineTest' --tests '*NettingCalculatorTest' \
  --tests '*BitmaskDpSettlementNettingCalculatorTest' --tests '*NettingPolicyTest' \
  --tests '*PairReductionSelectionTest' --tests '*NettingCalculatorConfigurationTest' \
  --tests '*AccountQueryServiceTest' --tests '*SettlementUnpaidSummaryServiceTest' \
  --tests '*SettlementNotificationOutboxWorkerTest' \
  --tests '*SettlementTransferInstructionServiceTest' \
  --tests '*SettlementMemberTransferInstructionServiceTest' \
  --tests '*GroupMemberExitPolicyTest' --tests '*SettlementDeletionPolicyTest' \
  --tests '*SettlementListPersistenceIntegrationTest' \
  --tests '*SettlementParticipantPersistenceIntegrationTest' \
  --tests '*SettlementDepositPersistenceIntegrationTest' \
  --tests '*SettlementUnpaidSummaryPersistenceIntegrationTest' \
  --tests '*AccountDeletionPersistenceIntegrationTest' \
  --tests '*NotificationTargetTest' --tests '*NotificationTargetAccessIntegrationTest' \
  --tests '*NotificationDeliveryServiceTest'
```

## 남은 운영 한계

은행 거래 조회는 기간/계좌 수와 금융기관 응답 시간에 좌우된다. 계산기의 μs 단위 JMH 수치를 전체 API 완료 시간으로 표현하지 않는다. 이미 전달된 은행 송금 안내의 외부 실행은 서버가 취소할 수 없다. 모호한 거래를 해소할 운영자 review 절차가 필요하다. 새 압축 카드 UI 자체는 이번 backend 작업에 포함하지 않았다.

## 2026-10-17 UI 정책 보완

[압축 목록·상세와 건수 안내](group-netting-list-and-estimate.md)에 따라 일반 목록에서는 압축에 포함된 원본을 제외한다. 기존 status는 원본 상세와 압축 상세 sources에 유지한다. 상단 안내는 저장 없는 estimate 조회로 제공한다. 위 2026-10-16 검증 기록은 당시 구현 결과이며 최신 정책/추가 검증은 연결 문서를 따른다.

## 게시 계획 · 2026-10-17

사용자 요청으로 로컬 구현 전체를 PR로 게시한다. 브랜치는 기존 규약의 `feat/506-group-netting-direct`를 유지한다. 최신 develop a30875b1에서 분리한 SQL-only PR #571의 4589dd6f를 기반으로 연결하여 SQL PR을 수정하지 않고 Java PR의 diff에서 SQL을 제외한다. V58은 이미 검증된 선행 migration이며 새 migration을 작성하지 않는다.

commit 경계:

1. feat(settlement): 압축 계산 전략과 조건부 Pair Reduction 선택 추가 — 계산 모델/기존 Greedy·Baseline·DP/Pair/Selector/Engine과 계산기 단위 검증.
2. feat(settlement): 그룹 압축 재압축과 Batch 입금 처리 연결 — 실제 UseCase/Ports/Domain 상태/Adapter/API/설정/기존 lifecycle·알림·캐시 및 기존 테스트 생성자 호환 수정.
3. test(settlement): 압축 입금 보존과 멤버 권한 캐시 회귀 검증 — 실제 DB·Batch/Redis/예상 건수와 정책 테스트.
4. docs(settlement): 압축 정산 API와 검증 및 선행 SQL 절차 정리 — 최종 기능·운영·UI 계약과 검증 문서.

각 코드 commit의 staged snapshot을 별도 임시 폴더에서 compileJava/compileTestJava로 확인한다. PR 게시 전 새 격리 DB에 classpath V1~V58을 적용해 CI와 같은 전체 Java/JavaScript 회귀를 수행한다. SQL 브랜치를 base로 하는 연결 PR이며 SQL PR #571 머지 후 base를 develop으로 바꿔 최종 리뷰/머지한다. 해당 base는 기존 pull_request CI 대상(develop/main)이 아니므로 구현 branch의 workflow_dispatch로 같은 CI를 요청한다. 기존 SQL PR/주 작업 폴더는 수정하지 않는다.

게시 전 전체 회귀에서 압축 알림 enum 3개와 GROUP_SETTLEMENT_NETTING action이 기존 알림 OpenAPI allowableValues에서 누락된 것을 발견했다. NotificationPageWebResponse의 문서 enum을 실제 계약에 맞춰 보완하며 기존 exact-enum 통합 검증은 완화하지 않는다. 로컬 Docker가 응답하지 않아 Testcontainers를 직접 사용하는 기존 6개 테스트 클래스는 실행 불가 상태이며, 해당 환경 실패와 코드 실패를 구분하여 최종 결과에 기록한다.

### 게시 검증 결과

- 구현 branch: feat/506-group-netting-direct. SQL-only 선행 branch의 V58은 parent로 재사용하며 구현 diff에는 SQL 변경이 없다.
- 6789e700 계산 전략, 17b7bed7 Batch/API/기존 기능 연동, 91760d46 실제 회귀 테스트로 분리했다. 각 staged snapshot의 compileJava/compileTestJava 성공.
- 게시 전 초기 전체 회귀 2,155개 중 7개 실패: Docker 컨테이너 시작 6개와 알림 OpenAPI enum 누락 1개. OpenAPI 누락을 수정했다.
- Docker가 응답하지 않아 해당 기존 Testcontainers 6개 클래스만 로컬 외부 Gradle init script로 제외하고 재검증했다. Java 전체 일반 회귀 **2,149개 성공(failure/error/skip 0)**, 기존 JavaScript **35개 성공**. 실제 압축 DB/Batch **36개 포함**.
- 저장소의 테스트 및 CI workflow는 제외 설정을 추가하지 않았다. 원격 branch의 workflow_dispatch로 Docker 전체 CI를 요청하며 수동 CI는 이미지 게시·배포하지 않는다. 원격 CI 결과는 PR에서 확인한다.
- Java 21 compileJava/compileTestJava/bootJar, verifyMigrationVersions, git diff --check 및 기능 문서 상대 링크 검증 성공. 최종 로컬 로그: /tmp/howmuch-netting-publish-local-tests-final.log.
- PR base는 codex/feat-506-netting-member-schema다. #571 머지 후 develop으로 바꿔 최종 검증/머지한다. 기존 SQL PR과 주 작업 폴더는 수정하지 않았다. dev/prod 배포 및 실 CODEF/실 송금은 수행하지 않았다.

로컬 전체 재현에는 DB_URL/DB_USERNAME/DB_PASSWORD/REDIS_HOST/REDIS_PORT를 격리 서비스로 지정하고 `bash scripts/test.sh --init-script /tmp/howmuch-netting-publish-no-docker.gradle compileJava bootJar test`를 실행했다. 외부 init script의 제외 클래스는 CertificateHttpIntegrationTest, CertificateFailureMigrationTest, CertificatePersistenceIntegrationTest, SettlementCategoryMigrationTest, KafkaNotificationFlowIntegrationTest, KafkaTransportIntegrationTest다. Docker가 준비된 CI에서는 init script 없이 기존 `bash scripts/test.sh`를 실행한다.

## PR #572 리뷰 후속 보완

[재시도·건수 안내 리뷰 반영](group-netting-review-fixes.md): 일시적 CODEF/호출 예산/경합 오류를 기존 3회 한도 내 RETRY로 분류하고 인증·권한·설정 오류의 즉시 종료를 유지했다. Worker/dispatcher는 같은 Application Policy를 사용한다. canCompress는 전후 건수 직접 비교로 명확히 했으며 전액 상계 허용 및 비감소 미표시를 실제 DB로 확인했다. 후속 일반 전체 회귀 Java 2,205개·JavaScript 35개 성공(기존 Docker 의존 6개 클래스는 로컬 환경상 제외). GitHub CI는 billing/spending limit으로 시작하지 못했다. SQL PR/스키마/계산기 정책/배포는 변경하지 않았다.
