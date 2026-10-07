# Kafka·알림 구현 검증 기록

- 날짜: 2026-10-07
- 구현 계획: [공통 Kafka·알림](kafka-platform-notifications.md)
- 운영 절차: [Kafka 운영 문서](../../../ops/kafka/README.md)
- 기준: `origin/develop` `0a098776`

## 검증 환경

기존 사용자 checkout의 CI·배포·관측 변경을 보존하고 managed worktree 두 개에서 구현했다. SQL-only 브랜치는 V43 파일 하나만 포함한다. 앱 브랜치는 같은 SQL commit을 선행 이력으로 가지므로 두 PR은 SQL → 앱 순서로 병합한다. SQL PR이 develop에 병합되면 앱 PR의 SQL diff도 사라진다.

테스트에는 새 PostgreSQL 18·Redis 8 컨테이너와 별도 DB를 사용했다. 애플리케이션 `.env`를 읽거나 실제 CODEF·Firebase·APNs 자격 증명을 사용하지 않았다. Kafka 4.2.2 Testcontainers는 테스트별 topic/group을 사용하고 실제 발송 provider 경계만 mock했다. 동일 worktree에서는 Gradle test를 하나씩 실행했다.

## SQL 및 이전 버전 호환

- 빈 DB Flyway V1 → V43 43개 적용·validate, V42의 기존 알림·Outbox 상태를 보존한 upgrade 1개, 재실행 0개 적용.
- SQL 검사 115개: 제약·기존 데이터·쓰기 호환 49개, 두 JDBC 연결의 선점·pending 초대·late ACK 동시성 66개.
- SQL-only 브랜치의 기존 `NotificationInboxIntegrationTest` 24개와 기존 `SettlementNotificationOutboxWorkerTest` 7개 통과. 기존 Java/JPA에 V43만 적용한 Flyway/Hibernate 검증을 포함한다.
- `BEFORE INSERT` 호환 trigger는 target을 명시하지 않은 기존 요청·리마인드만 처리한다. 명시한 target은 덮어쓰지 않으며 새 generic 타입의 누락 target은 거부한다.
- 새 유형의 레코드가 생긴 뒤에는 과거 앱/worker를 혼재하거나 바로 rollback하지 않는다. 운영 문서의 중단·lease 회복·호환 앱 복구 절차를 따른다.

## 앱 검증

최종 Java **1506개**(202 classes), JavaScript **35개** 통과. failures/errors/skipped는 모두 0이다. 관련 필터 검증도 136개 통과했으며 SQL·OpenAPI 최종 변경 후 전체 검증을 다시 수행했다.

관련 검증은 다음 실제 경로와 실패 조건을 포함한다.

| 경로 | 확인한 조건 |
|---|---|
| 리캡 → broker → DB → relay → broker → provider | 초기 generation 0, 실제 listener/UseCase/DB, commit된 offset, user/month dedup, current envelope 변조 거부, 완료 generation replay 무효, provider 호출 때 DB TX 없음 |
| Kafka transport | broker ACK 실패, DLT topic 부재 시 원본 offset 보류, DLT ACK 이후 진행, 일시적 처리 오류 재전달 후 RECORD commit, 엄격한 계약·잘못된 설정 거부 |
| Delivery ledger | 발행 실패는 provider budget 0, same message/gen 재발행, concurrent claim 1개, provider retry 새 gen/msg, stale 회복과 늦은 ACK/완료 fencing |
| 알림·발송 | inbox/read/cursor/legacy 회귀, generic target/payload, 삭제·멤버십·초대 만료 시 비활성 target, 준비 DB 장애는 provider attempt 0, automatic quiet-hour defer와 manual 예외 |
| 그룹 초대 HTTP·DB | owner 생성, 수신자 권한, 7일 만료, 거절/반복 수락/동시 수락, 과거 초대로 강퇴 우회 금지, 알림 기록 실패 시 초대·Outbox 원자 rollback, OpenAPI 응답 |
| 정산·탈퇴 업무 | 실제 완납/전체 완료 전이에 알림 연결, 수신자·dedup·target, linked active 참여자만 fanout, 탈퇴자 제외 |
| 금융 계약 | RC2 fixture roundtrip, Connector 버전 Kafka 3.9.2 클라이언트의 broker 4.2.2 ACK·exact payload 왕복 |

`scripts/test.sh`의 환경 guard를 따라 전용 DB·Redis 값을 지정하고 `bash scripts/test.sh test`를 실행했다. 개별 검증은 `--tests '*Kafka*' --tests '*Notification*' --tests '*GroupInvitationHttpIntegrationTest'` 및 관련 정산·그룹 서비스 필터로 실행했다. SQL·Java compile·shell syntax·Compose rendering·document link·whitespace 검증은 서로 다른 증거다.

## 직접 실행 및 남은 연계

패키징한 앱을 local KAFKA 모드로 loopback HTTP에 실행해 health `UP`(200), 비인증 보호 API 401, 초대 API 4개 OpenAPI 노출, queue metric scrape 200을 확인했다. 정상/실패 업무 API 동작은 실제 Spring Boot/Flyway/Redis 환경의 MockMvc 통합 검증이며 이 HTTP smoke와 구분한다.

운영 배포·TLS/ACL 실제 집행·broker failover·금융 Connector 실 프로세스 Inbox/result 적용·실은행 호출·단말 표시/클릭은 수행하지 않았다. 리캡 upstream 생성/공개와 모바일 이동은 #474 및 frontend #358 의존 사항이다. provider 성공 직후 결과 저장 실패의 push 중복과 발송 준비 후 좁은 권한/quiet 경계 race는 외부 시스템의 제한으로 남는다.

## 증거 위치

앱의 JUnit/XML/HTML은 `build/test-results/test`, `build/reports/tests/test`에 있다. 본 세션의 전체/관련 실행 로그, runtime HTTP 결과는 `/tmp/howmuch-kafka-development/`에 보존했다. SQL fresh/upgrade/no-op·115 검사·기존 Java 31개 호환 증거는 `/tmp/howmuch-architecture-20261006/migration-v43-*`에 보존했다. 이 임시 경로는 CI 아티팩트나 운영 검증 증거를 대체하지 않는다.
