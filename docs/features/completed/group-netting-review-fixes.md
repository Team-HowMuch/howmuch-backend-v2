# Feature Plan: PR #572 재시도 및 건수 안내 리뷰 반영

- Status: Completed (local verification; PR update)
- Date: 2026-10-17
- Related issue: #506
- PR: #572
- Branch: feat/506-group-netting-direct

## 요청과 확인 결과

사용자가 PR 리뷰 반영을 요청했다. SQL PR #571에는 inline 리뷰가 없고 현재 변경하지 않는다.

- P1: GroupNettingWorkerService.failed가 NETTING_WORKER_FAILED만 RETRY로 분류하여 CODEF_UPSTREAM_UNAVAILABLE/CODEF_RATE_LIMITED 등의 공통 ApplicationException이 FAILED로 확정된다. Tasklet의 finish가 lease를 지운 후 launcher의 후속 예외는 이를 복구하지 못한다. 실제 장애다.
- P2: canCompress가 전액 상계를 막는다는 지적은 현재 코드와 다르다. Counts.reducedTransferCount는 before-after이므로 2→0이면 2이며 기존 실제 DB 테스트도 true를 검증한다. 의미를 명확히 하도록 before>after로 직접 비교하고 동일 건수(2→2) false·전액 상계 true 회귀를 보완한다. public reducedTransferCount 의미는 바꾸지 않는다.

## 설계와 영향 범위

- Flyway migration required: **No**. 기존 RETRY/attempt_count/max_attempts/next_attempt_at/deadline/lease fencing을 그대로 사용한다.
- Application 소유의 구체 NettingExecutionFailurePolicy를 도입하여 worker/dispatcher의 오류 분류를 통일한다. 일시적인 CODEF 통신/한도/응답 오류, 일시적 가용성·경합/호출 예산 오류 및 기존 generic worker 오류는 RETRY, 입력 변경/검토/대상 부족은 기존 STALE, 인증·권한·계좌 재연결·추가 인증·영구 설정 오류는 FAILED다. 5초 지연·최대 3회·10분 deadline은 기존 Adapter의 처리다.
- code는 기존 Domain/Common StatusCode의 getCode 값을 사용한다. HTTP 숫자만으로 모든 5xx/4xx를 재시도하지 않는다. Policy가 외부 I/O나 Infra를 참조하지 않는다.
- Tasklet/launcher의 후속 예외가 이미 저장된 오류 코드/재시도 상태를 덮어쓰지 못하는 기존 lease fencing을 실제 Batch로 확인한다.
- 캐시/계산/선택 정책/API 필드/SQL/보안 권한/배포 설정은 변경하지 않는다. estimate 조건 표현만 동등하게 바꾼다.

## 검증과 게시 경계

1. commit 경계: fix(settlement): 일시적 금융 조회 오류 재시도 복구 (Policy/worker/dispatcher·오류 분류·실제 Batch 재시도 회귀), fix(settlement): 압축 안내 건수 비교 명확화와 리뷰 검증 정리 (estimate·건수 회귀·검증 문서).
2. Java 21 compileJava/bootJar, 오류 분류 단위 테스트, 실제 PostgreSQL/Redis/Batch에서 오류→RETRY→같은 요청 성공/최대 횟수 소진/영구 오류 종료/old lease 덮어쓰기 거절을 검증한다. 전액 상계 및 건수 비감소 안내도 확인한다.
3. Docker가 응답하지 않으므로 기존 Testcontainers 6개 클래스를 외부 local init script로 제외한 일반 전체 회귀를 실행한다. 저장소 테스트/CI는 제외하지 않는다.
4. GitHub CI 38946799766은 billing/spending limit으로 job 시작 전 실패했음을 check-run annotation으로 확인했다. 성공했다고 보고하거나 workflow를 우회 수정하지 않는다.
5. git diff/status·문서 상대 링크 검증 후 같은 PR 브랜치에 commit/push하고 PR 본문에 수정 내용과 근거를 기록하고 해당 리뷰 스레드를 해결 표시한다. 다른 사용자 작업 폴더 및 SQL PR은 수정하지 않는다.

## 집중 검증 결과

Java 21 compileJava/bootJar/verifyMigrationVersions 성공. 관련 Java 144개 및 기존 JavaScript 35개 성공, failure/error/skip 0. 실제 DB/Batch 테스트는 44개이며 오류 분류 44개·launcher 직접 오류 4개를 포함한다. 실제 CODEF 호출은 기존 mock 경계를 사용했다.

- CODEF_UPSTREAM_UNAVAILABLE/CODEF_RATE_LIMITED/TEMPORARILY_UNAVAILABLE의 첫 실패가 RETRY이고 같은 Idempotency-Key로 두 번째 실행이 성공한다. 원래 공통 오류 코드는 launcher 후속 예외에 덮어쓰이지 않는다.
- 반복 장애는 정확히 3회에서 FAILED로 끝나며 이후 실행·세대를 더 만들지 않는다.
- 은행 인증정보 오류/추가 인증/설정 오류는 첫 시도에서 종료한다. 이전 lease의 늦은 실패는 이미 성공한 결과를 변경하지 못한다.
- 실제 독립 송금 2→2는 canCompress=false, 전액 상계 2→0은 reducedTransferCount=2 및 canCompress=true를 확인했다. 후자는 기존 동작을 보존한 표현 명확화다.

## 최종 검증과 범위

- Java 21 compileJava/bootJar/verifyMigrationVersions 성공.
- 기존 Docker 의존 Testcontainers 6개 클래스만 외부 Gradle init script로 제외한 Java 전체 일반 회귀 **2,205개 성공**, failure/error/skip 0. 기존 JavaScript **35개 성공**.
- 실제 PostgreSQL/Redis/MockMvc/Spring Batch 압축 테스트 **44개** 포함. 집중 회귀 **144개**도 성공했다.
- 로그: /tmp/howmuch-netting-review-focused-tests.log, /tmp/howmuch-netting-review-full-tests.log. 실제 은행/CODEF는 mock이며 DB/Batch 상태 전이와 lease fencing은 실제 자원을 사용했다.
- git diff --check 및 문서 상대 링크 검증을 수행했다. DB migration/API 응답/계산 전략 선택/기존 권한/CI workflow·테스트 제외 설정은 변경하지 않았다.
- PR #572의 같은 브랜치에 두 fix commit을 추가한다. SQL PR #571에는 변경이 없으며 이번에도 배포·머지하지 않는다. 원격 CI는 계정 결제/사용 한도 때문에 job을 시작하지 못했으므로 성공으로 보고하지 않는다.

## 주요 구현

- [오류 분류 Policy](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/policy/NettingExecutionFailurePolicy.java)는 기존 getCode 계약으로 구체적 retry/stale/terminal을 분류하며 worker와 dispatcher가 함께 사용한다. 모든 500/503을 일괄 재시도하지 않는다.
- [실제 Batch 회귀](../../../src/test/java/com/amateur/howmuchbackend/application/settlement/netting/GroupNettingDirectIntegrationTest.java)의 makeRetryDue는 테스트 시간 대기를 줄이기 위해 기존 RETRY 행의 next_attempt_at만 앞당긴다. attempt_count/max_attempts/deadline/lease 상태나 DB 제약을 우회하지 않는다.
- [예상 건수 Service](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/GroupNettingEstimateService.java)는 beforeTransferCount>afterTransferCount를 직접 비교한다. 기존 reducedTransferCount=before-after 및 최소 대상 2건/pending/멤버 검사는 유지한다.
