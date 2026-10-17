# Feature Plan: 압축 대상 최소 2건 및 화면 API 최종 검증

- Status: Completed (local verification)
- Date: 2026-10-17
- Related issue: #506


게시·전체 회귀 후속 기록은 [직접 실행 계획의 게시 검증 결과](group-netting-direct-execution.md#게시-검증-결과)를 따른다. 아래 로컬 단계의 “미게시” 설명과 테스트 수는 당시 수행 기록이다.

## 사용자 문제와 완료 기준

정산 1개를 압축 정산으로 바꾸는 불필요한 실행을 막는다. 활성 그룹 멤버의 최초 압축·재압축 권한, 입금 반영·기존 금액 중복 제외 및 현재 UI에 필요한 API를 유지한다.

- 현재 합칠 대상은 새 미납 원본 각각 1건 + ACTIVE 압축 정산 1건이다. 과거 원본 수나 송금 수와 구분한다.
- 일반 원본 1건만 또는 기존 압축 정산만 있을 때 canCompress=false, POST는 409 NETTING_INSUFFICIENT_SETTLEMENTS로 거절하며 실행을 생성하지 않는다.
- 기존 압축 정산 + 새 원본 1건은 2건이므로 재압축 가능하다. completed/superseded 이력은 대상을 늘리지 않는다.
- 은행 동기화 후/최종 확정에서도 최소 건수를 확인하여 유효 대상이 감소했으면 저장하지 않는다. 이전 접수의 동일 idempotency key 재조회는 새 입력 검사에 막히지 않는다.
- 카테고리는 압축에 없고, Connection failed 위치는 예상 송금 건수/압축하기 안내 API를 사용한다. 압축 포함 원본은 일반 목록에서 제외하고 상세 sources에 표시한다.

## 설계와 영향 범위

- Flyway migration required: **No additional migration**. 별도 PR #571 V58은 활성 멤버 실행의 선행 조건으로 유지하며 변경하지 않는다. 저장 구조/API 경로/기존 공개 응답 필드는 그대로 유지한다. UI 이름·프로필 연결에 필요한 공개 정보만 응답에 추가한다.
- Application의 구체 NettingCompressionPolicy가 최소 범위 규칙을 한 곳에서 판단한다.
- InputReader의 기존 원본 자격 필터를 재사용한다. 접수 시 계산/은행 I/O 없이 범위를 확인하고, worker read/recalculate는 정책을 검증한다. estimate 전용 읽기는 단일 대상도 건수를 안내한다.
- Redis Counts에 내부 compressibleSettlementCount를 추가하고 v2 namespace로 이전 Counts와 혼용을 피한다. 공개 eligibleSettlementCount는 새 원본 수 의미를 유지한다. canCompress/권한 자체는 캐시하지 않는다.
- QueryService에서 기존 LoadUserPort.findAllByIds로 송금 당사자를 한 번에 읽어 Transfer의 payerName/payerPublicId/receiverName/receiverPublicId를 추가한다. 기존 그룹 members 응답은 내부 userId를 제공하지 않으므로 publicId로 프로필을 연결한다. 전체 계좌번호/인증정보를 추가하지 않는다.
- 기존 입금·목록·미납 합계·완료·재압축의 경로를 검토하고 실제 DB/Batch 회귀로 검증한다. 신규 알고리즘, SQL, UI 구현, commit/push/PR/배포는 제외한다.

## 검증 및 예정 commit 경계

1. feat(settlement): 압축 대상 최소 두 건 정책 및 안내·접수·Batch 일관성 (구현/문서/회귀를 같은 의도로 묶음; 이번에는 게시하지 않음).
2. Java 21 compileJava/bootJar, 관련 계산기·캐시·실제 PostgreSQL/Redis/Batch, 기존 정산 미납/목록/입금 테스트를 실행한다. 단일 정산, 기존 압축만, 기존 압축+새 원본, 처리 중 감소 및 같은 키 재조회 검증을 포함한다.
3. SQL PR의 정확한 V58은 외부 Flyway location으로 테스트 DB에서만 사용한다. 실 CODEF/실 송금/전체 프로젝트 테스트는 제외한다.
4. diff/status 및 문서 링크를 검증하고 원래 작업 폴더의 변경을 보존한다.

## 검증 중 확인 사항

최초 회귀 157개 중 계좌 identity 경합 테스트 1개가 기존 STALE 대신 BANK_ACCOUNT_RECONNECT_REQUIRED/FAILED를 반환했다. 새 최소 대상 규칙에 맞춰 신규 원본 fixture를 추가하면서 은행 mock의 최초 호출 대상이 달라져, 변경이 대상 계좌 조회 전에 발생한 것이 원인이다. 원래 목적(조회 도중 identity 교체 후 snapshot 재사용 거절)에 맞춰 해당 계좌의 실제 fetch 중에만 교체하도록 fixture를 수정했다. 운영 안전성 검사나 오류를 완화하지 않았다. 추가 UI 검토에서 그룹 members가 userId 대신 publicId를 제공하는 점을 확인하여 압축 Transfer에 이름/publicId를 보완했다.

## 완료 결과

- [압축 정책](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/policy/NettingCompressionPolicy.java): 실제 현재 합칠 대상 최소 2건을 공통 검사한다.
- [실행 접수](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/GroupNettingExecutionService.java): 동일 키의 이전 실행을 먼저 반환하고 새 접수에만 최신 DB 범위 검사를 적용한다. 단일 대상이면 409이며 job/실행/세대를 만들지 않는다.
- [입력 Reader](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/GroupNettingInputReader.java): 은행 동기화 전후 및 최종 recalculate에서 범위를 재검증한다. 대상 감소는 STALE/NETTING_INSUFFICIENT_SETTLEMENTS로 종료한다. 실제 원본 입금은 이미 반영된 상태로 보존한다.
- estimate는 단일 대상도 정확한 건수를 안내하되 canCompress=false다. 공개 eligibleSettlementCount는 새 원본 수이며 기존 압축을 포함한 내부 compressionTargetCount와 구분한다. Redis v2 Counts/fencing/무효화/권한 검사를 검증했다.
- [압축 응답 DTO](../../../src/main/java/com/amateur/howmuchbackend/adapter/in/web/settlement/netting/dto/response/GroupNettingWebResponse.java)에 payerName/payerPublicId/receiverName/receiverPublicId를 추가했다. 기존 LoadUserPort의 일괄 조회를 재사용한다. publicId로 기존 그룹 멤버의 프로필과 연결하고 송금자 여부는 기존 payerId와 로그인 userId로 확인할 수 있다.
- [UI별 API·금액 계약](group-netting-list-and-estimate.md#화면-최종-연동-계약-2026-10-17)에 안내/카드/원본 내역/사용자 이름/송금/입금/미납 요약 경로를 정리했다. 카테고리는 압축에 없고 Connection failed 위치는 예상 건수 안내로 사용한다.

## 최종 검증

- Java 21 compileJava, verifyMigrationVersions, bootJar 성공.
- Java **157개 통과**, failure/error/skip 0. 실제 PostgreSQL/Redis/MockMvc/Spring Batch **36개 포함**.
- 단일 원본 HTTP 409·실행 미생성, 기존 압축만 거절, 기존 압축+새 원본 1건의 재압축, 동일 키 replay, 입금으로 2→1 대상 감소 시 원본 입금 보존·압축 저장 차단, sync 이후 범위 감소 시 snapshot/세대 저장 차단을 검증했다.
- 실제 미납 합계 15,000 → 압축 후 5,000 → 부분 입금 후 3,000 → 완납 후 0을 검증했다. 완납 시 원본 전부 COMPLETED, 일반 목록에서 계속 숨김, 원본 가상 입금 0건, 실제 입금 claim 1회 보존을 확인했다.
- 실제 HTTP current 응답의 송금자·수취인 이름/publicId 및 category/myCategory 부재를 검증했다. 기존 재압축·review·lease·identity·권한·계산기·캐시·미납 요약·일반 목록·입금·총무 회귀를 포함했다.
- scripts/test.sh의 기존 JavaScript **35개 통과**. 최종 로그: /tmp/howmuch-netting-single-tests-final.log. JUnit XML: build/test-results/test.
- git diff --check 및 변경 문서의 상대 링크 검증 통과. 실제 CODEF/실 송금은 호출하지 않았으며 통합 테스트의 외부 은행 경계는 mock이다. 전체 프로젝트 회귀·성능/JMH·UI 구현·commit/push/PR·배포는 수행하지 않았다.
- SQL PR #571은 변경하지 않았다. 활성 멤버 실행 배포에는 해당 V58의 선행 적용이 필요하다. Java 변경은 feat/506-group-netting-direct의 로컬 상태다.

## 재현 명령

격리 DB/Redis 환경 변수를 지정하고 PR #571의 V58 파일을 외부 Flyway location에 그대로 둔다.

```bash
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/tmp/howmuch-netting-member-java-migrations \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
bash scripts/test.sh compileJava bootJar test \
  --tests 'com.amateur.howmuchbackend.application.settlement.netting.*' \
  --tests '*NettingEstimateCacheRedisAdapter*Test' \
  --tests '*NettingCalculatorConfigurationTest' \
  --tests '*SettlementUnpaidSummary*Test' \
  --tests '*SettlementListPersistenceIntegrationTest' \
  --tests '*SettlementDepositPersistenceIntegrationTest' \
  --tests '*TreasurerSettlementPersistenceIntegrationTest'
```
