# Feature Plan: 활성 그룹 멤버의 압축·재압축 실행

- Status: Completed (local verification) — SQL PR #571 분리 유지, Java 게시·배포 대기
- Date: 2026-10-17
- Related issue: #506
- SQL-only branch: codex/feat-506-netting-member-schema


게시·전체 회귀 후속 기록은 [직접 실행 계획의 게시 검증 결과](group-netting-direct-execution.md#게시-검증-결과)를 따른다. 아래 로컬 단계의 “미게시” 설명과 테스트 수는 당시 수행 기록이다.

## 변경 정책

OWNER뿐 아니라 활성 그룹 멤버 모두가 직접 압축·재압축을 접수할 수 있다. 비멤버/LEFT/REMOVED 및 실행 중 자격을 잃은 요청자는 확정하지 못한다. 수취 계좌는 각 계좌 소유자의 CODEF 연결로 조회하고 송금 안내는 해당 송금자만 조회한다. 실행 상태는 기존처럼 요청자에게만 제공한다.

## migration gate 판단

Migration required: **Yes**. V53의 assert_group_netting_generation_consistency는 execution.actor_id=snapshot.owner_id 및 현재 OWNER 실행을 요구한다. API 검사만 바꾸면 MEMBER의 Batch 확정이 거절된다.

최종 게시 전 최신 origin/develop a30875b1 및 열린 migration PR을 재확인했다. 마지막 version은 V57이다. 작업 시작 때 V58을 사용하던 #570이 게시 전 병합 없이 CLOSED되어 최종 migration은 V58을 사용한다. 최신 develop에서 별도 SQL-only branch/worktree를 만들고 SQL 한 파일만 검증·commit/push/PR한 뒤 현재 작업을 즉시 중단한다. Java/API/테스트/이 문서는 migration PR에 포함하지 않는다.

## SQL 설계

- snapshot.owner_id는 캡처 당시 그룹 OWNER 메타데이터로 유지하고 requester는 기존 execution.actor_id로 구분한다. 컬럼 재해석/rename/FK 제거를 하지 않는다.
- 성공 실행 및 immutable snapshot 요구는 유지하고 actor=owner 조건을 제거한다.
- generation 최초 INSERT/BUILDING cutover 시 현재 ACTIVE 그룹과 활성 사용자·ACTIVE actor 멤버십을 검사한다. role은 요구하지 않는다. 전액 상계 COMPLETED 생성도 같은 자격 검사에 포함한다.
- 생성 후 요청자가 탈퇴하더라도 다른 멤버의 완료·재압축/이력 처리를 소급 차단하지 않는다.
- 입금·순잔액·단일 successor·rollover·OPEN review·완료/원본 상태의 기존 제약은 그대로 유지한다.

## 검증·commit 경계

- 격리 PostgreSQL 18.6에서 V57 당시 MEMBER 실행 거절을 재현하고 최종 V57→V58을 Flyway migrate/validate한다.
- OWNER/MEMBER ACTIVE 및 zero-net COMPLETED, NONE/LEFT/REMOVED의 ACTIVE/zero-net 거절, cutover 전 탈퇴/비활성 사용자/비활성 그룹/잘못된 owner snapshot 거절, 비성공 실행/순잔액 변조 거절, 최초 requester 탈퇴 후 다른 일반 멤버의 재압축·SUPERSEDED lineage 보존 등 실제 SQL 18개 시나리오를 검증한다.
- 순잔액/rollover/review/완료 검증 부분은 V53 함수와 byte 단위 동일함을 확인한다. 최신 develop Java 21 compileJava 및 verifyMigrationVersions를 수행한다.
- migration-only commit: feat(settlement): 활성 그룹 멤버 압축 실행 DB 제약 허용

## Java 후속 작업 — 2026-10-17 사용자 승인

GroupNettingExecutionService, NettingBankQueryGuard의 원본/기존 계좌 조회, GroupNettingCommitWriter의 OWNER 검사를 requireActiveMember로 변경한다. GroupNettingEstimateService의 canCompress에서 isOwner 제한을 제거한다. Swagger·팀 문서·권한/실제 Batch 회귀를 업데이트한다. 기존 은행 계좌 소유권, 송금자 전용 안내, 요청자 전용 실행 조회는 유지한다. 선행 SQL-only [PR #571](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/571)은 그대로 유지한다. 사용자가 후속 작업 진행을 명시적으로 요청하여 별도 Java 작업 폴더에서 구현한다. 추가 migration은 필요 없으며 SQL을 Java 변경에 복사하지 않는다.

## Java 영향 범위와 검증 계획

- 접수 Service, 은행 조회 Guard 2개 경로, 원본 입금 반영 NettingOriginalDepositWriter, 최종 Commit Writer를 기존 requireActiveMember로 통일한다. estimate 캐시에는 여전히 counts만 저장하고 canCompress는 활성 멤버와 pending 여부를 매번 확인한다.
- Swagger와 현재 기능 설명을 활성 멤버 정책으로 수정한다. snapshot.owner_id의 그룹 OWNER 의미와 execution.actor_id의 요청자 의미는 유지한다.
- 실제 PostgreSQL/Redis/Batch 테스트에서 MEMBER HTTP 접수→최종 저장, 일반 멤버 재압축, zero-net, 비멤버/LEFT/REMOVED 거절, 실행 중 탈퇴 거절, 이전 비당사자 요청자 탈퇴 후 다른 멤버 재압축을 검증한다. 요청자 전용 실행 조회와 송금자 전용 계좌번호 조회도 유지한다.
- 테스트 DB에는 PR #571의 정확한 V58 SQL을 저장소 외부 Flyway location으로 적용한다. 운영 코드에는 SQL을 중복하지 않는다.
- Java 21 compileJava/bootJar, 관련 netting·캐시·계산기 테스트 및 diff/link 검증을 수행한다. 전체 프로젝트 회귀나 실제 CODEF 호출은 범위 밖이다.
- 예정 Java commit 경계: feat(settlement): 활성 그룹 멤버 압축·재압축 허용 (구현·계약 문서·회귀 테스트를 하나의 의도로 묶음). 이번 요청에서는 commit/push/PR/배포하지 않는다.

## 선행 SQL 단계 검증 결과 (Java 변경 전)

- 최종 V57→V58 Flyway migrate 및 validate 성공.
- 실제 PostgreSQL 권한/zero-net/순잔액/재압축 18개 SQL 시나리오 성공.
- Java 21 compileJava 및 verifyMigrationVersions 성공.
- migration branch는 V58 SQL 한 파일만 포함하며 주 작업 폴더는 시작 상태와 동일하다. Java/HTTP/Batch 권한 변경은 수행하지 않았다.

## Java 검증 중 발견 사항

첫 실제 Batch 회귀 79개 중 MEMBER 관련 5개가 GROUP_OWNER_REQUIRED로 실패했다. 원인은 기존 정산 패키지의 NettingOriginalDepositWriter에 남은 OWNER 검사였다. 해당 Writer도 기존 활성 멤버 검사로 변경하며 실제 입금 반영은 계속 source.creatorId 소유권으로 수행한다.

## Java 후속 작업 완료 결과

- 접수, 은행 조회 2개 경로, 원본 입금 반영, 최종 저장을 기존 requireActiveMember로 통일했다. canCompress도 활성 멤버에게 동일하게 제공하며 그룹 공통 counts 캐시는 유지한다.
- Swagger 및 직접 실행/목록/캐시 문서에 활성 멤버 정책과 V58 선행 조건을 반영했다. Command/Result/HTTP 경로·본문·응답 필드 및 계산기 선택 정책은 변경하지 않았다.
- Java 21 compileJava, verifyMigrationVersions, bootJar 성공.
- 관련 Java 테스트 **82개 통과** (failure/error/skip 0). 실제 PostgreSQL/Redis/MockMvc/Spring Batch 테스트 **30개 포함**: MEMBER HTTP 202→Batch COMPLETED, 실제 requester와 snapshot OWNER 구분, 부분 입금 재압축·입금 claim 보존, OWNER/MEMBER zero-net, NONE/LEFT/REMOVED 접수·캐시 조회 거절, sync 전/후 탈퇴 차단, 비당사자 이전 요청자 탈퇴 후 다른 MEMBER 재압축, 본인 계좌 소유자의 CODEF 연결 사용, 실행 상태/송금 안내 개인 권한 보존.
- scripts/test.sh의 기존 JavaScript 테스트 **35개 통과**.
- 최종 검증 로그: /tmp/howmuch-netting-member-java-tests-final.log. JUnit 결과: build/test-results/test. 실제 CODEF 요청은 mock이며 순잔액·저장·Batch 실행은 실제 DB를 사용했다.
- 상대 문서 링크 및 git diff --check 통과. 원래 사용자 작업 폴더와 SQL PR은 변경하지 않았다. 테스트용 PostgreSQL/Redis 프로세스는 검증 후 종료했다.

## 재현 및 배포 전제

PR #571의 V58 SQL을 저장소 외부 디렉터리에 그대로 두고, 그 SQL이 적용된 disposable DB 및 Redis를 지정한다. SQL은 Java 브랜치에 포함하지 않는다. DB_URL/DB_USERNAME/DB_PASSWORD/REDIS_HOST/REDIS_PORT는 테스트 자원으로만 지정한다.

```bash
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/tmp/howmuch-netting-member-java-migrations \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
bash scripts/test.sh compileJava bootJar test \
  --tests 'com.amateur.howmuchbackend.application.settlement.netting.*' \
  --tests '*NettingEstimateCacheRedisAdapter*Test' \
  --tests '*NettingCalculatorConfigurationTest'
```

이번 Java 변경은 commit/push/PR 및 dev/prod 배포하지 않았다. **운영 적용 시 V58을 먼저 적용해야 한다.** V57에서 Java 권한만 배포하면 MEMBER 확정이 DB 제약에 의해 거절된다. 전체 프로젝트 회귀·JMH·실 CODEF·실 송금은 실행하지 않았다. SQL PR의 검토/머지는 별도로 남겨둔다.
