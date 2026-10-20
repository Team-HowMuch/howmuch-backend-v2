# Feature Plan: 압축 예상 건수 Redis 캐시

- Status: Completed (local verification)
- Date: 2026-10-17
- Related issue: #506


2026-10-17 권한 정책 변경: 활성 그룹 멤버 모두 압축·재압축할 수 있다. 이 정책의 DB 전제는 별도 [SQL PR #571](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/571)의 V58이다. [멤버 실행 후속 계획](group-netting-member-execution.md)에 구현·검증 범위를 기록한다. 아래 이전 날짜의 검증 기록은 당시 수행 범위다.


게시·전체 회귀 후속 기록은 [직접 실행 계획의 게시 검증 결과](group-netting-direct-execution.md#게시-검증-결과)를 따른다. 아래 로컬 단계의 “미게시” 설명과 테스트 수는 당시 수행 기록이다.

## 목적·범위

기존 estimate API와 프런트 계약은 유지하면서 같은 그룹의 현재 미납을 요청마다 다시 읽고 계산하지 않는다. 그룹 공통 건수만 Redis에 30초 캐시하고, 정산 입력 변경 커밋 후 무효화한다. 실제 압축은 캐시를 사용하지 않고 은행 동기화·입력 검증·기존 계산을 유지한다. Kafka 선계산, SQL, UI, commit/push/PR은 제외한다.

## 설계

- Migration required: No. 기존 PostgreSQL 구조와 Redis를 재사용한다.
- Output Port: NettingEstimateCachePort. 조회, 계산 선점, 현재 세대의 결과 저장, 선점 해제, 무효화는 동일 캐시 capability다. 기술적 JSON/key/TTL/Lua는 Redis Adapter가 소유한다.
- 키는 그룹 공통 counts만 저장한다. 매 요청 활성 그룹·멤버, pending CREATE 및 OPEN review는 DB에서 확인한다. 권한/canCompress/계좌번호/연결 ID는 캐시하지 않는다.
- Redis lock token과 revision으로 동시 계산을 합치고 늦은 결과 저장을 거부한다. 커밋 후 revision 변경·data/lock 삭제를 원자적으로 실행한다. TTL은 무효화 누락/프로세스 중단의 오래된 값 상한을 보완한다.
- 캐시 miss만 별도 짧은 read-only transaction에서 기존 InputReader/Validator를 실행한다. 대기 중 DB transaction을 유지하지 않는다. 오류 결과는 캐시하지 않는다.
- 정산 생성/수정/삭제/중단/재개/요청 확정, 영수증 배분 확정, 실제 입금 반영, 압축 확정/재압축/검토 해결에 명시적으로 연결한다. draft 자체는 압축 대상이 아니지만 그룹 참가자 연결 후 확정 때 무효화한다.
- 기존 TransactionSynchronization 관례로 커밋 후만 무효화하고 같은 transaction의 그룹을 묶는다. Redis 무효화 실패는 이미 성공한 DB 쓰기를 실패로 보고하지 않으며 TTL로 보완한다. 조회에서 Redis 장애/장기 경합은 제한된 503을 반환해 무제한 재계산을 막는다.
- 변경 이벤트를 별도 Kafka/outbox로 저장하지 않는다. 커밋 직후 프로세스 종료 등으로 무효화가 유실될 수 있으며 30초 예상 값 TTL을 허용한다. 실제 실행 결과는 캐시로 확정하지 않는다.
- hit/miss/대기/계산 횟수, 요청·계산 시간은 기존 Micrometer로 기록하고 그룹/사용자 ID는 metric label에 넣지 않는다.

## 예상 commit 경계 (게시 요청 시만)

1. feat(settlement): 예상 건수 Redis 캐시와 중복 계산 방지
2. feat(settlement): 정산 변경 커밋 후 예상 건수 무효화
3. test(settlement): 캐시 경합·권한·커밋 및 실제 압축 회귀 검증

## 검증 계획

Java 21 compileJava. 실제 PostgreSQL/Redis에서 miss→hit 재계산 방지, 멤버 권한 차이, 동시 조회 한 번 계산, 커밋 전/롤백 비무효화, 변경 중 오래된 계산 저장 방지, TTL/lock 만료 및 다른 worker lock 보호를 검증한다. 기존 실제 Batch/목록, 정산 요청·수정·중단·입금·영수증 배분 단위 회귀를 수행한다. 전체 프로젝트 회귀와 실 금융기관 호출은 제외한다.

## 구현 결과

- [기존 estimate Service](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/GroupNettingEstimateService.java)의 API/6개 응답 필드를 유지했다. 활성 그룹·멤버, OPEN review, pending CREATE는 매 요청 DB에서 확인한다. canCompress는 캐시에 저장하지 않는다.
- [계산 Reader](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/NettingEstimateCalculationReader.java)는 miss에서만 기존 InputReader·Calculator·Validator를 호출한다. 별도 REQUIRES_NEW read-only transaction으로 커밋된 입력을 읽고, 선점 대기/Redis 저장 동안 DB transaction을 열어 두지 않는다.
- [Redis Adapter](../../../src/main/java/com/amateur/howmuchbackend/adapter/out/redis/NettingEstimateCacheRedisAdapter.java)는 counts만 JSON 저장한다. 계좌/개인정보/사용자별 권한은 저장하지 않는다. hash tag `{groupId}`를 사용한 v2 네임스페이스로 Lua의 data/lock/revision 키를 같은 Redis slot에 둔다.
- cache TTL 기본 30초, 계산 선점 lease 기본 10초다. 무효화는 새 UUID revision 설정과 data/lock 삭제를 하나의 Lua로 처리한다. 만료/이전 claim은 저장·다른 worker 선점 해제를 할 수 없다. 변경 도중 끝난 계산은 반환/재저장하지 않고 현재 세대를 다시 조회한다.
- 동시 miss는 유효한 선점 한 개를 공유한다. 다른 요청은 20ms 간격으로 최대 약 1.5초 대기하고 완료 결과를 사용한다. 대기 시간은 DB 계산 시간/Redis 연결 timeout을 포함하는 강제 HTTP deadline이 아니다. 긴 경합에는 `NETTING_ESTIMATE_BUSY`(503)를 반환한다. 프로세스 종료/lease 만료 시 다음 요청이 선점을 복구한다.
- Redis 조회 장애는 `NETTING_ESTIMATE_UNAVAILABLE`(503)로 변환하며 무제한 DB 재계산 fallback을 하지 않는다. 오류 결과는 캐시하지 않는다. 손상/null 캐시 값은 버리고 재계산한다.
- [무효화 Service](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/NettingEstimateInvalidationService.java)는 기존 TransactionSynchronization 관례를 사용한다. 한 transaction의 그룹을 묶어 afterCommit에서 한 번씩 무효화하며 rollback에서는 유지한다. Redis 무효화/선점 해제 실패는 로그만 남기고 이미 커밋된 쓰기를 실패로 바꾸지 않는다. 전체 netting 기능이 비활성화되면 기존 쓰기에 추가 그룹 조회나 Redis 호출을 하지 않는다.

### 연결한 변경 경로

| 변경 | 연결 지점 |
|---|---|
| 생성·수정·삭제·재개 | SettlementService |
| 수동 중단 | SettlementSuspensionWriter |
| 정산 요청/Share·수취 계좌 snapshot 확정 | SettlementRequestService |
| 영수증 전원 제출 후 AWAITING_DEPOSIT/SUSPENDED 전환 | SettlementItemAllocationWriter |
| 원본 실제 입금 반영 | SettlementDepositWriter: 새 입금의 settlementId별 |
| 압축/재압축 확정 | GroupNettingCommitWriter |
| 압축 입금 반영·검토 생성·완료 | NettedDepositCommitWriter |
| 운영자 입금 검토 해결 | NettingDepositReviewService |

Draft 생성 시에는 그룹이 아직 지정되지 않았으므로 지울 그룹 키가 없다. 이후 그룹 참가자를 연결하고 요청을 확정하면 무효화한다. Draft/SUSPENDED의 품목·참가자 수정만으로 압축 대상 부채가 생기지 않으며 재개/최종 요청 확정 시 연결한다. 새로운 부채 변경 경로를 추가할 때도 이 Service에 연결해야 한다. 기본 계좌/외부 연결 등 금융 설정의 변경을 모두 이벤트로 추적하는 시스템은 아니며 해당 변경의 캐시 반영은 TTL로 보완한다. 실제 압축에서는 최신 계좌 상태를 다시 검증한다.

새 입금/검토/상태 변화가 없는 압축 reconcile은 캐시를 무효화하지 않는다. 공개 미리보기/동의 저장 및 Kafka 이벤트 발행은 추가하지 않았다.

### 설정과 관측

```yaml
SETTLEMENT_NETTING_ESTIMATE_CACHE_TTL: 30s
SETTLEMENT_NETTING_ESTIMATE_CACHE_LEASE: 10s
```

- 기존 Redis 연결 설정을 재사용한다. 추가 서비스나 SQL 배포가 필요하지 않다.
- `settlement.netting.estimate.cache` Counter: outcome=hit/miss/shared/wait/busy. wait는 요청 수가 아니라 대기 polling 횟수다.
- `settlement.netting.estimate.request.duration` Timer: outcome=hit/miss/shared/busy/unavailable/error별 Service 처리 시간(HTTP 인증 필터·전송 시간 제외).
- `settlement.netting.estimate.calculation.count` Counter와 `.calculation.duration` Timer: 실제 InputReader 계산 횟수/시간.
- groupId/userId를 metric tag에 넣지 않는다. 실제 부하의 hit/miss 시간과 계산 횟수를 비교해 Kafka 선계산의 필요성을 판단한다. 이번에는 부하 테스트/RPS/속도 감소율을 측정하지 않았으므로 수치 개선을 주장하지 않는다.

## 검증 결과

- Java 21 compileJava/bootJar 성공.
- 기존 정산 변경·입금·영수증 배분·목록·참여자·총무 경로와 실제 PostgreSQL/Redis/Spring Batch를 포함한 Java **136개 성공, 실패/오류/skip 0**.
- 실제 DB/Batch 통합 23개 포함: 같은 그룹 반복 요청의 실제 계산 1회, 동시 요청 4개의 계산 1회 공유, 변경 중 이전 계산 재저장 거부, rollback 유지/commit 무효화, 실제 중단 경로 및 변경 없는 reconcile의 캐시 유지. 기존 압축·재압축·금액 보존·권한 시나리오를 함께 실행했다.
- Redis TTL/선점 만료·이전 token 저장/해제 거부·손상 값·장애 처리, 대기 제한, transaction 내 그룹별 무효화 병합 및 disabled 경로를 검증했다.
- 기존 scripts/test.sh의 JavaScript 35개 성공. 캐시 값 null 처리 보완 후 집중 Java 테스트 8개와 compileJava/bootJar, JavaScript 35개를 다시 확인했고 모두 성공했다.
- 실 CODEF 호출/quota는 기존 통합 테스트에서 mock 처리했다. 전체 프로젝트 테스트, 부하/JMH, dev/prod 배포, commit/push/PR은 수행하지 않았다.

### 재현 명령

격리 PostgreSQL/Redis 환경 변수 DB_URL/DB_USERNAME/DB_PASSWORD/REDIS_HOST/REDIS_PORT를 설정한다. 기존 scripts/test.sh가 테스트 전용 키/외부 연결 설정을 제공한다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) bash scripts/test.sh compileJava bootJar test \
  --tests '*GroupNettingDirectIntegrationTest' \
  --tests '*NettingEstimateCacheRedisAdapter*' \
  --tests '*GroupNettingEstimateServiceTest' --tests '*NettingEstimateInvalidationServiceTest' \
  --tests '*SettlementServiceTest' --tests '*SettlementRequestServiceTest' \
  --tests '*SettlementDepositServiceTest' --tests '*SettlementSuspensionWriterTest' \
  --tests '*SettlementItemAllocationServiceTest' \
  --tests '*SettlementCreatorItemAllocationServiceTest' \
  --tests '*SettlementMemberItemAllocationServiceTest' \
  --tests '*SettlementDiscountedAllocationWriterTest' \
  --tests '*SettlementListPersistenceIntegrationTest' \
  --tests '*SettlementParticipantPersistenceIntegrationTest' \
  --tests '*TreasurerSettlementPersistenceIntegrationTest'
```

## 남은 한계

DB와 Redis를 단일 transaction으로 묶지 않았으므로 커밋 직후 프로세스 종료/Redis 장애 시 무효화가 유실될 수 있다. 캐시 TTL은 저장 시점부터 적용되며 계산/장애 시간까지 포함한 강한 최신성 보장은 아니다. 예상 건수는 화면 안내용이고, 실제 압축은 기존 은행 동기화·잠금·입력 재검증·선택한 계산기의 재계산을 계속 사용한다. Kafka 선계산이 필요해지면 같은 API/그룹 공통 Counts 캐시 경계를 유지하고 별도 consumer를 연결할 수 있다.

## 최소 압축 대상 보완 (2026-10-17)

내부 Counts에 compressionTargetCount를 추가했다. 새 미납 원본 수 + ACTIVE 압축 1건이며 API의 eligibleSettlementCount(새 원본 수)와 구분한다. 정책을 적용한 canCompress 자체는 여전히 캐시하지 않는다. 기존 3필드 Counts와 혼용하지 않도록 Redis namespace를 v2로 변경했다. TTL/Lua fencing/커밋 후 무효화는 유지한다. 상세는 [최소 범위 계획](group-netting-minimum-scope.md)을 따른다.
