# Feature Plan: 공통 Kafka 기반 구축과 알림 확장

- 상태: 구현·검증 완료, PR 검토 대기
- 갱신일: 2026-10-07, Asia/Seoul
- 관련 요청: 서비스에서 공통으로 사용할 Kafka 설정, 기존 알림 전환, 새 알림 5종
- 기준: Backend 구현 기준 `origin/develop` HEAD `0a098776`, 참고 브랜치 `feat/account-transaction-sync-request` HEAD `4ac73a0e`, Connector HEAD `4cf5824`
- 관련 보고서: [Kafka·CODEF·공동인증서 설계](kafka-codef-certificate-architecture.md)
- 관련 ADR: [Kafka transport와 notification delivery ledger](../../adr/0006-kafka-transport-and-notification-delivery-ledger.md)

## 1. 목적과 범위

### 문제와 결과

현재 Backend 알림은 정산 요청·리마인드 두 종류에 맞춰져 있고, DB polling worker가 외부 push를 호출한다. 조사 기준 develop에는 Kafka client 코드가 없었다. CODEF Connector는 이미 Kafka command listener와 event publisher를 가지고 있어 금융과 알림이 함께 사용할 서비스 공통 기반이 필요하다.

이번 개발 결과는 **공통 Kafka 운영·애플리케이션 기반 + 기존 알림의 Kafka 발송 전환 + 신규 알림 5종**이다. 알림함 저장은 업무 TX 안에 유지하고 외부 push 실행을 분리한다. 사용자 알림함과 consumer 중복 수신 원장은 서로 다른 개념이다.

### 성공 기준

- [x] local/dev/prod에서 Kafka 접속·topic·권한·설정 소유자가 명확하다.
- [x] 기존 금융 command/event 계약 fixture와 Connector 버전 클라이언트의 broker 왕복을 검증한다. 실제 Connector 프로세스·업무 Inbox/result 적용 검증은 해당 금융 이슈에서 수행한다.
- [x] 기존 요청·수동/자동 리마인드·목록/읽음 동작이 유지된다.
- [x] 업무 DB commit 후 Kafka 장애가 나도 알림 기록과 발송 의도가 보존된다.
- [x] 재전달·worker 종료·broker 복구에 대해 DB 효과가 중복되지 않는다.
- [x] 신규 3종과 특정 회원 초대를 실제 업무 트리거부터 목록·provider 경계까지 연결한다. 리캡은 실제 publication 수신 경계를 구현하며 upstream 생성과 모바일 이동 검증은 의존 이슈로 남긴다.

### 이번 범위 밖

Windows 인증서 앱·QR 연결, CODEF 관측 v2/거래 대조 전체 구현, 리캡 계산 알고리즘 전체, 다른 도메인의 Kafka 전환은 별도 기능이다. 금융 연결 기반 검증을 전체 계좌 동기화 기능 완료로 보고하지 않는다. 리캡 본체는 다른 담당 기능과 연계하되, 실제 공개 이벤트가 없으면 리캡 알림 완료로 보고하지 않는다.

2026-10-07 후속 요청으로 이슈 생성·구현·검증·PR 생성을 승인받았다. 사용자 지시가 기존 migration gate보다 우선한다: SQL-only 별도 브랜치에 migration을 준비하고 Java/config/test를 계속 구현하며, 전체 검증 후 SQL-only PR과 구현 PR을 함께 생성한다. 운영 배포·PR merge·실은행 호출은 범위 밖이다.

## 2. 현재 코드와 로컬 브랜치 조사

| 대상 | 확인한 구현 | 재사용과 보완 |
|---|---|---|
| 현재 Backend | `NotificationRecorder`가 `user_notifications`와 delivery Outbox를 같은 TX로 저장 | 이 원자성과 수신자별 business dedup을 유지 |
| 현재 worker | 정산 row lock을 유지하면서 FCM/APNs 호출, 요청이 아니면 리마인드로 mapping | 외부 호출을 TX 밖으로 이동하고 타입별 발송 mapping으로 수정 |
| 현재 알림 모델 | `allocationType` 필수, 목록 action이 정산 중심, V31 type CHECK가 2종 | 그룹·초대·리캡 typed target과 표시 계약 필요 |
| 현재 V38 | 정산 전용 notification creation Outbox SQL만 존재 | 이번 기본 흐름에서 활성화하지 않음. 알림 생성 동기 유지 |
| 참고 브랜치 | `spring-boot-starter-kafka`, String producer, JSON Schema 검증, broker ACK 확인 publisher, command Outbox relay | 금융 전용 publisher/claim 패턴을 참고하되 공통 접속·오류 규칙을 분리 |
| 참고 브랜치 한계 | relay가 publish 예외 뒤 retry 전이를 기록하지 않음; claim된 `PROCESSING` 회수 보완 필요; `KafkaListener` 없음 | 정상 발행만으로 공통 Kafka 기반 완료로 계산하지 않음 |
| Connector | `finance.sync.command.v1` listener, `finance.sync.event.v1` publisher, Inbox/execution/Outbox 있음 | group/ack/error/관측 설정을 Backend와 맞추고 실패·재전달 실험 |
| 금융 호환성 | Connector event topic과 schemaVersion 1이 Java 및 V3 CHECK에 고정 | 환경변수만 바꿔 v2로 전환 불가. 별도 금융 계약/migration 작업 |

참고 브랜치는 최신 코드와 설정·KMS wiring 등도 다르므로 전체 병합을 기본 방법으로 삼지 않는다. 필요한 publisher·relay·통합 테스트 패턴을 선별하여 현재 기준에 맞게 적용한다. 현재 트리에서 없는 파일은 아래처럼 조회한다.

```bash
git show feat/account-transaction-sync-request:src/main/java/com/amateur/howmuchbackend/adapter/out/kafka/AccountTransactionSyncKafkaPublisher.java
git show feat/account-transaction-sync-request:src/main/java/com/amateur/howmuchbackend/application/account/service/AccountTransactionSyncCommandRelayService.java
git show feat/account-transaction-sync-request:src/main/java/com/amateur/howmuchbackend/adapter/out/persistence/account/AccountTransactionSyncJdbcAdapter.java
```

현재 소스: [Recorder](../../../src/main/java/com/amateur/howmuchbackend/application/notification/service/NotificationRecorder.java), [Worker](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/service/SettlementNotificationOutboxWorker.java), [알림 모델](../../../src/main/java/com/amateur/howmuchbackend/domain/notification/UserNotification.java), [목록 mapping](../../../src/main/java/com/amateur/howmuchbackend/application/notification/service/MyNotificationService.java), [V31](../../../src/main/resources/db/migration/V31__create_user_notification_inbox.sql).

## 3. 개발 목록: 공통 Kafka 기반

| 작업 | 개발할 내용 | 완료 증거 |
|---|---|---|
| Broker·환경 구성 | KRaft broker, durable volume, listener/advertised address, 내부 네트워크, health, local/dev/prod 분리 | host 실행 Backend와 Docker Connector가 모두 metadata의 broker 주소에 접속 |
| Topic 관리 | notification delivery, 기존 finance command/event, domain별 DLT/격리 경로의 catalog·provisioning | 자동 topic 생성 의존 없이 topic/partition/replication/retention 확인 |
| Producer 공통 설정 | Boot 관리 Kafka starter, String key/value, ACK 확인, `acks=all`, idempotence, bounded send timeout | ACK 실패 뒤 DB Outbox 완료 표시가 되지 않음 |
| Consumer 공통 설정 | record listener factory, auto commit off, 명시 group, DB 처리 commit 뒤 offset 완료, concurrency/poll 제한 | DB commit 전 실패와 commit 후 종료 모두 안전한 재전달 |
| 메시지 계약 | messageId/type/schemaVersion/occurredAt 의미, key·header·schema validation·호환성 기준 | 기존 finance JSON 계약을 유지하면서 알림 계약 검증 |
| 오류·복구 규칙 | transient DB 오류 재전달/일시 중단, invalid contract 격리, DLT ACK 확인, 안전한 replay 절차 | DLT/격리 저장 실패에도 원본 offset이 먼저 완료되지 않음 |
| 보안·운영 | TLS/service 인증, topic/group ACL, payload 비수집, lag·oldest age·failure/recovery metric, runbook | 금융 consumer와 알림 consumer의 권한·장애를 독립 확인 |

local은 단일 broker로 시작한다. 운영 고가용성 기본안은 broker 3개, replication 3, min ISR 2이지만 서로 다른 장애 영역과 비용을 확인하고 확정한다. 같은 물리 호스트에 broker를 셋 띄운 것은 호스트 장애 대응 증거가 아니다. Broker 버전은 Backend/Connector의 Boot 관리 client와 통합 검증한 버전으로 고정한다.

공통 설정은 `infra.messaging.kafka`에 둔다. Kafka inbound는 `adapter.in.kafka.<domain>`, publisher는 `adapter.out.kafka.<domain>`에 두고 domain별 UseCase/Output Port를 호출·구현한다. 초기에는 별도 공통 SDK를 배포하지 않고 두 서비스가 작은 설정과 catalog·계약 테스트를 공유한다. 공통화할 것은 transport 규칙이며, 금융·알림의 DB 저장소와 업무 retry Policy까지 하나의 범용 프레임워크로 합치지 않는다.

`spring.kafka.*`는 접속/producer/consumer 설정, `howmuch.messaging.*`는 역할별 enabled/topic/group/concurrency 설정으로 분리한다. 전역 consumer group 하나를 모든 listener에 암묵 적용하지 않는다. 금융 producer, 금융 result consumer, 알림 relay, 알림 delivery consumer의 활성화를 각각 제어한다.

기본 consumer는 `enable.auto.commit=false`, `AckMode.RECORD`로 두고 동기 UseCase의 DB commit 이후 listener가 반환한다. listener에서 task만 별도 thread에 넘기고 즉시 반환하지 않는다. DB 장애는 업무 처리가 끝나지 않았으므로 재전달/일시 중단으로 다루고, 잘못된 계약만 durable 격리 후 완료한다. 긴 CODEF 호출은 기존처럼 durable job worker가 수행한다. [Spring listener/offset 규칙](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)

producer ACK와 timeout은 공통 설정이지만 DB Outbox 상태 전이는 domain relay 책임이다. idempotent producer만으로 별도 relay 재발행이나 DB/외부 API 효과의 중복까지 막을 수는 없다. [Kafka producer 규칙](https://kafka.apache.org/41/configuration/producer-configs/)

DLT에는 인증정보·provider 원문·exception body를 복사하지 않는다. topic/partition/offset, 가능한 messageId, hash, 분류된 오류 등 복구용 metadata만 남기는 기본안을 적용한다. 금융 원본이 필요한 재처리는 권한 제한·retention 안에서 원본 topic을 조회하는 운영 절차로 한다. 격리 실패 시 exception을 전파한다. [Spring 오류 복구 규칙](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)

## 4. Topic·공통 계약과 CODEF 연결 범위

| Topic 제안/현재 | Producer → Consumer | Key | Group |
|---|---|---|---|
| `notification.delivery.v1` 제안 | Backend delivery relay → notification worker | `recipientUserId` | `howmuch-notification-delivery-v1` 제안 |
| `finance.sync.command.v1` 현재 | Backend finance relay → Connector intake | `accountRef` | 현재 `howmuch-finance-connector`; 역할별 명시 유지 |
| `finance.sync.event.v1` 현재 | Connector event relay → Backend finance result 처리 | `accountRef` | `howmuch-finance-result-v1` 제안 |
| `finance.sync.event.v2` 후속 | Connector 관측 v2 → Backend staging/대조 | `accountRef` | 별도 금융 계약 계획에서 확정 |

같은 key는 partition을 선택한다. 여러 relay의 업무 발행 순서와 provider 외부 효과까지 자동 보장하지 않는다. 알림은 최초에는 순서보다 dedup·유효 target을 우선하며, 금융은 기존 version/manifest 계약으로 늦은 결과를 판정한다.

메시지는 고정 ID·고정 payload로 재발행한다. 이미 존재하는 finance schema를 새 범용 envelope로 감싸거나 root 필드를 임의 추가하지 않는다. 기존 `messageId/messageType/schemaVersion/occurredAt`, finance job/ref/version을 유지하고 trace/causation 전달 위치는 허용된 계약/headers로 맞춘다. 알림은 별도 작은 schema로 `messageId`, `messageType`, `schemaVersion`, `occurredAt`, `deliveryId`, `generation`을 정의한다. 제목·본문·push token은 DB에서 읽는다.

동일 broker 발행 retry는 같은 메시지다. 실제 provider retry를 새 delivery generation으로 예약할 때는 새 messageId를 저장하며 같은 notificationId/deliveryId는 유지한다. 알림의 current generation은 DB에 저장된 messageId·deliveryId·recipient·occurredAt과 정확히 비교해 다른 envelope를 격리한다. 금융 소비 ledger의 hash/version 비교는 별도 금융 구현에 남긴다.

개발/운영은 별도 cluster 또는 명시적 권한 경계로 격리한다. 기존 finance topic을 `dev.*`처럼 변경하려면 Connector Java/SQL 제약까지 맞춰야 하므로 이름 변경을 단순 환경변수 작업으로 취급하지 않는다.

이번 CODEF 공통 기반 검증은 기존 RC2 fixture의 broker 왕복과 Connector 버전 Kafka 3.9.2 client 호환 확인이다. 실제 Backend producer→Connector Inbox 및 Connector Outbox→Backend 업무 결과 수신은 금융 구현의 통합 검증으로 남긴다. 현재 Backend에 금융 결과 consumer가 없으므로 실제 event를 운영으로 흘리기 전 담당 금융 작업에서 durable Inbox/staging consumer를 구현해야 한다. 임시 로그 consumer가 운영 group의 메시지를 소모하는 방식으로 대체하지 않는다. synthetic 검증은 별도 test group/DB를 사용하며 업무 consumer 미구현 상태를 표시한다.

## 5. 개발 목록: 기존 알림 수정

| 작업 | 수정 방법 |
|---|---|
| 사용자 알림함 유지 | 현재 목록·미읽음 수·본인 읽음·cursor·snapshot·dedup 유지 |
| 범용 target | 정산 참여/상세, 그룹 초대/상세, 월 리캡을 명시적으로 구분. allocation은 정산용 optional 값으로 변경 |
| 범용 delivery | 기존 Outbox row/ID를 보존하며 settlement 필수 의존을 풀고 notification+recipient 기반 관계·제약으로 확장 |
| Kafka relay/consumer | 업무 TX에서 알림함+Outbox commit → relay 발행 → consumer의 원자 선점 → push → 결과 저장 |
| 실행·복구 | 발행 상태/lease와 provider 상태/lease/attempt 분리; 짧은 TX → 외부 호출 → 짧은 TX; stale 회수와 generation/CAS |
| 발송·화면 호환 | 요청/리마인드 이분법 제거, type별 제목/body/action mapping, old client fallback, Android/iOS 클릭 분기 |
| 리마인드·기존 기능 회귀 | 수동 cooldown, 자동 12시간·KST 03~06 제외, 삭제된 target 비활성, token 등록/해제, legacy delivery 처리 |

기존 `settlement_notification_outbox`의 물리 이름은 초기 확장 migration에서 유지해 row 복사·중복 발송 위험을 줄인다. 새 Application/Port는 notification delivery 책임으로 옮기고 물리 이름은 전환 후 별도 정리한다. `notification_id IS NULL`인 legacy 요청은 정산 링크 경로를 유지하고, 새 범용 알림은 notificationId를 필수로 한다. 기존 정산 복합 FK를 그대로 둔 채 settlementId를 NULL로 만드는 것만으로 수신자 일치가 검증되지는 않으므로 notificationId+recipient 관계를 별도로 강제한다.

발행 retry가 현재 max 5 provider attempt를 소진하지 않도록 한다. Kafka 대기 중인 row에는 provider 실행 timeout을 적용하지 않는다. consumer가 relay의 ACK 기록보다 먼저 도착할 수 있으므로 발행/전송 상태는 독립적으로 변경하며, 늦은 relay 결과가 이미 성공한 delivery를 되돌리지 못하게 한다. 새로운 generation의 원자 선점·완료 ledger를 같은 Backend DB에 두므로 별도 consumer Inbox 테이블을 중복해서 만들지 않는다.

업무 retry는 DB `nextAttemptAt`을 기준으로 한다. provider 실패를 DB에 기록한 뒤 offset을 완료하고 다음 generation을 relay가 발행한다. Kafka retry topic과 같은 업무 재시도를 중첩하지 않는다. provider 접수 성공 후 응답 유실에서는 push 중복 가능성이 남으며 사용자 단말 표시를 exactly-once라고 설명하지 않는다.

자동 리마인드는 현재 quiet hours에 생성을 건너뛰고 다음 poll에서 다시 찾는다. 새 worker는 발송 직전에도 quiet hours를 검사해 지연된 메시지를 06:00 KST 이후로 예약한다. 조회 수를 줄이기 위해 긴 외부 push 동안 정산 row lock을 유지하지 않는다. 삭제와 발송 시작의 race를 다루되 이미 provider에 보낸 호출을 회수한다고 주장하지 않는다.

## 6. 개발 목록: 새 알림 5종

2026-10-06 Notion 실제 앱에서 본인 담당·시작 전으로 확인한 항목을 기준으로 한다. 이번 턴에 Notion 상태를 다시 변경하거나 확인하지 않았다.

| 기능 | 실제 트리거·수신자 | 개발 방법 | 선행 조건 |
|---|---|---|---|
| ALM-STL-004 송금 완료 | 해당 몫 최초 완납 → 생성자 | `SettlementDepositWriter`의 입금 반영 TX에서 이전/이후 상태 비교, share별 dedup | 부분 입금·버튼·수집 완료로 발송 금지 |
| ALM-STL-005 정산 완료 | 최초 COMPLETED → 참여자 전원 | 완료 전이 TX에서 수신자별 dedup, 상세 target | 마지막 송금은 004/005 각각 생성하는 기본 정책 |
| ALM-GRO-002 그룹 탈퇴 | 본인 탈퇴 commit → 남은 active 회원 | `GroupMemberExitService.leave`의 동일 TX에서 탈퇴 이력별 fanout | manager removal을 같은 문구로 포함할지 명세 확정; 임의 확장 금지 |
| ALM-GRO-001 그룹 초대 | 특정 회원 invitation 생성 → 대상 회원 | invitationId dedup, 초대 조회·수락 권한과 invitation target | 현재 즉시 ACTIVE 추가 API와 다름. pending/수락/거절/만료 모델 선행 |
| ALM-RCP 리캡 도착 | 전월 리캡 저장·공개 완료 → 생성된 회원 | `RecapPublished` 수신 뒤 user+month dedup, monthly target | 전월 완료 정산 1건 이상·월 기준·publication 계약; 집계 시작만으로 발송 금지 |

초대/리캡이 아직 없으면 알림 종류·mapper만 만든 것은 실제 기능 완료가 아니다. 초대 선행 기능은 본 계획의 구현 계약과 #473에서 함께 구현하고, 리캡 담당자는 저장·공개 성공을 reliable Outbox 이벤트 또는 같은 DB 업무 TX의 호출로 연결한다. 네트워크 호출이나 휘발성 after-commit callback만으로 알림 생성 성공을 보장하지 않는다. 실제 caller 없이 미래 Port나 no-op Adapter를 만들지 않는다.

## 7. Application 계약과 정책

| 계약 제안 | 책임 | 외부 경계 |
|---|---|---|
| `RecordNotificationCommand` / Recorder | 타입별 snapshot·target·dedup과 delivery 의도 저장 | notification save + delivery store |
| `PublishNotificationDeliveriesUseCase` | 발행 lease 확보, publish 성공/실패 기록 | delivery store + `NotificationDeliveryPublisherPort` |
| `DeliverNotificationUseCase` | generation 선점, actor/target/token 확인, provider 호출과 결과 | delivery store + target load + notification push sender |
| domain별 notification factory | 해당 업무의 문구·대상 계약 생성 | 실제 업무 Service에서 Recorder 호출 |

Ports는 실 caller가 있는 능력별로 정의하고 Kafka DTO를 Application에 넣지 않는다. 요청·결과 record는 plain Java이고 HTTP DTO mapping은 Adapter에서 한다. target 유효성·quiet hours·provider retry는 Application Policy가 외부 사실을 받아 판단한다. Domain은 delivery 상태·generation 전이 불변 조건을 갖는다. Infra는 client/Properties/Bean wiring에 집중한다.

## 8. Migration과 구현 순서

**Migration 필요: Yes.** type CHECK, optional allocation/typed target, delivery 관계와 발행/실행 상태·lease/generation을 확장해야 한다. V29/V31/V38 등 기존 versioned SQL은 수정하지 않는다. V38 creation path를 사용하지 않는 이번 단계에서 해당 테이블을 억지로 범용화하지 않는다. pending 초대 스키마도 V43에 포함하며 리캡 본체 스키마는 별도 담당 범위다.

사용자가 이번 개발의 gate 순서를 명시적으로 변경했다. 최신 origin/develop의 V42와 열린 PR #342를 확인했으며 별도 `feat/471-notification-kafka-migration` 브랜치에서 다음 SQL을 준비한다. `feat/470-kafka-notifications`에서 구현·검증을 계속하고 검증 후에만 두 PR을 생성한다. SQL-only PR에는 Java·문서·테스트를 포함하지 않는다.

| 순서 | 작업 단위 | 결과 |
|---|---|---|
| 1 | 계약과 migration 설계 확정 | topic catalog·target·delivery 상태·외부 경계·선행 담당 확정 |
| 2 | migration-only PR | 별도 SQL 브랜치 준비·검증, 전체 구현 후 PR 생성 |
| 3 | 공통 Kafka 설정 | broker/provisioning·producer/listener·보안·metric·통합 테스트 |
| 4 | 기존 요청/리마인드 전환 | 범용 알림 모델·delivery relay/consumer·legacy 회귀 |
| 5 | 즉시 연결 가능한 신규 3종 | 송금 완료·정산 완료·본인 그룹 탈퇴 |
| 6 | 선행 기능 연계 신규 2종 | pending invitation과 recap publication 뒤 초대·리캡 발송 |
| 7 | CODEF transport 호환과 운영 전환 | synthetic 왕복·권한·오류 복구; 실금융 consumer 완료 여부 별도 표시 |

공통 기반과 알림은 한 거대 PR보다 독립적인 검증 단위로 나누되 사용하지 않는 Port-only commit을 만들지 않는다. 예상 의도는 `build(messaging): 공통 Kafka 실행 기반 구성`, `refactor(notification): 범용 알림과 Kafka 발송 전환`, `feat(notification): 송금과 정산 완료 알림 추가`, `feat(notification): 그룹 탈퇴 알림 추가`, `feat(notification): 초대와 리캡 공개 알림 연계`다. 이번 사용자 요청으로 이슈·브랜치·PR 발행이 승인되었다. 완성된 연결과 계약 검증을 하나의 기능 commit으로 발행해 중간 unused Port 상태를 공개하지 않는다. SQL-only commit은 별도 브랜치에 유지한다.

## 9. 검증과 전환

- DB·Redis·Kafka prerequisites를 먼저 확인하고 Java 변경에서 `./gradlew compileJava`와 관련 테스트를 실행한다.
- broker down 중 업무 접수/알림함 보존 → 복구 후 발행을 검증한다.
- broker ACK 직후 relay 종료, DB commit 직후 consumer 종료, stale worker의 늦은 결과를 재현한다.
- 동일 messageId 반복, current generation의 변조된 envelope, 이전 generation, 동시 consumer에서 중복 DB 효과가 없는지 확인한다.
- DLT broker 실패와 DB 격리 실패에서 offset이 먼저 진행하지 않는지 확인한다.
- provider attempt와 publish retry가 분리되고 queue wait가 delivery stale timeout으로 오판되지 않는지 확인한다.
- worker가 발행 ACK DB 기록보다 먼저 실행·성공한 경우 relay 결과가 성공을 덮어쓰지 않는지 확인한다.
- 5종의 receiver·dedup·target·권한과 legacy 목록/read/삭제/리마인드를 검증한다.
- Android/iOS의 표시·클릭·foreground/background/로그아웃/token 교체를 provider 접수와 별도로 확인한다.
- 금융은 별도 test group·fixture·DB로 command Inbox 및 event 수신을 확인하고, 업무 확정 consumer 미구현 상태를 명확히 기록한다.

전환 시 기존 직접 worker와 Kafka worker가 같은 row를 동시에 발송하지 않도록 `DIRECT`/`KAFKA` 모드를 배타적으로 둔다. schema 확장→새 consumer 준비→신규 producer/type 활성화 순서로 배포한다. rollback 시 새 타입을 모르는 old worker를 켜지 않으며 신규 발행/소비를 중단하고 backlog를 보존하는 복구 경로를 둔다. 같은 row에 두 worker가 동시에 접근하는 fallback은 만들지 않는다.

lag·outbox oldest age·generation 재시도·stale 회수·DLT·provider outcome을 관측한다. token·payload·사용자/계좌 ID를 metric label에 넣지 않는다. broker 단일 개발 성공, CI 통과, 운영 복구, provider 접수, 실제 기기 표시를 별개 증거로 보고한다.

## 10. 구현 결과와 남은 결정

- 공통 Kafka: Boot 관리 client, ACK 확인, RECORD offset, transient 재전달, sanitized DLT, mode/flag startup 검사, schema·topic catalog·local broker·private TLS/ACL 참고 구성.
- 기존 알림: 물리 inbox/outbox와 legacy ID를 보존하고 범용 target을 추가했다. broker publication과 provider lifecycle·attempt를 분리하고 generation/lease/CAS로 늦은 완료를 차단한다.
- 신규 알림: 송금 완료, 전체 정산 완료, 본인 그룹 탈퇴, 특정 회원 초대를 실제 업무 TX에 연결했다. 리캡은 인증된 upstream의 RecapPublished 수신 후 user/month dedup으로 기록한다.
- 검증: SQL fresh/upgrade/no-op/validate와 115개 SQL·동시성 검사 통과. Java 컴파일·관련 136개·전체 1506개·JS 35개 통과. 상세 HTTP·DB·broker 증거는 QA 기록에 있다.
- 의존 사항: upstream 리캡 생성·publication, Android/iOS 라우팅·실기기 표시, 실제 금융 Connector Inbox/result 적용, dev/prod TLS·ACL·장애 영역 검증은 별도 이슈/배포 검증이다. Kafka와 두 consumer flag는 기본 비활성화한다.
- 제한: 외부 provider 접수 직후 결과 저장 실패에서는 중복 push 가능성이 남는다. target 권한과 quiet-hour 판단은 발송 준비 TX 시점의 snapshot이며 준비 종료→외부 호출 사이의 작은 변경 race를 원자적으로 제거하지 않는다.

운영 전환은 [Kafka 운영 절차](../../../ops/kafka/README.md)를 따른다. user-owned CI·배포·관측 파일은 원래 checkout에서 보존했다.

## 11. 진행·결정 기록

| 날짜 | 결정 | 근거·다음 작업 |
|---|---|---|
| 2026-10-07 | 범위를 공통 Kafka+기존/새 알림으로 제한 | CODEF 전체 기능·인증서 앱과 개발 경계 분리 |
| 2026-10-07 | 기존 금융 브랜치 패턴 선별 재사용 | 정상 producer 있음, retry/consumer와 최신 설정 보완 필요 |
| 2026-10-07 | 기존 알림 생성 TX 유지, 발송만 Kafka | 기존 inbox/리마인드 계약과 원자성 재사용 |
| 2026-10-07 | transport 공통화, domain별 ledger/Policy 유지 | 금융과 알림의 외부 비용·복구 의미가 다름 |
| 2026-10-07 | 알림 delivery 물리 테이블은 초기 확장 방식 | 기존 row/ID·legacy 요청 보존, 추후 이름 정리 |

완료 기록: Backend 구현·리뷰·전체 회귀 검증을 마쳤으며 PR 검토 단계다. 운영 배포나 upstream 리캡·모바일 완료 상태로 해석하지 않는다.

검토 PR: [SQL-only #475](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/475) → [앱 구현 #476](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/476) 순서로 병합한다. SQL PR의 병합 방식에 따라 앱 브랜치를 최신 develop에 맞추고 최종 diff에 SQL 중복이 없는지 확인한다.

관련 이슈: [공통 기반 #470](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/470), [SQL #471](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/471), [신규 알림 #472](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/472), [그룹 초대 #473](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/473), [리캡 공개 연계 #474](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/474), [모바일 연계 #358](https://github.com/Team-HowMuch/howmuch-frontend-v2/issues/358).

## 구현 계약: 초대와 비정산 알림

- 특정 사용자 초대는 7일 유효한 PENDING 초대를 생성하며 수락 전 멤버십을 만들지 않는다. 소유자만 생성하고 수신자만 조회/수락/거절한다. 동일 pending 재요청은 기존 초대를 반환한다.
- 초대 수락은 사용자 → 그룹 → 초대 행 순서로 잠그고 멤버십과 수락 상태를 같은 트랜잭션에 저장한다. 탈퇴/제거된 회원은 명시적 새 초대를 수락하면 복귀할 수 있다.
- 송금 완료는 개별 share 미납→납부확정 전이 때 생성자에게, 정산 완료는 전체 납부확정 전이 때 연결된 참여자에게 기록한다. 자기 탈퇴만 남아 있는 활성 회원에게 알린다.
- 리캡은 별도 담당 기능의 실제 RecapPublished 메시지를 입력으로 받는다. 완료 정산 0건과 미래 월은 알리지 않는다. 리캡 본문 생성은 이번 범위 밖이다.
- 추가 저장 구조는 V43에 포함하며 SQL 전용 브랜치에서 관리한다. Endpoint, Application, Domain, Persistence, Kafka 연결을 각각 검증한다.

- 추가 권한 불변 조건: 코드 가입·직접 추가·탈퇴·강퇴는 같은 그룹 TX에서 기존 PENDING 초대를 CANCELLED로 만든다. 과거 초대로 강퇴를 우회할 수 없다. 수락은 현재 OWNER가 발급한 초대만 허용하며 종료된 멤버십보다 이후에 발급된 새 초대여야 복귀한다.
- 금융 의존 이슈: [Connector Kafka 설정 #2](https://github.com/Team-HowMuch/howmuch-finance-connector/issues/2), [메시지 복구 #4](https://github.com/Team-HowMuch/howmuch-finance-connector/issues/4).

최종 검증 기록: [QA](kafka-notifications-qa.md). 기존 Java의 알림 쓰기도 유지하도록 V43에 호환 trigger를 포함한다.
