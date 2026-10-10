# Kafka 알림 dev·prod 전환 준비

- 기준: 구현 PR [#476](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/476), SQL-only PR [#475](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/475)
- 대상: 공통 Kafka transport와 알림. 리캡 생성 기능·금융 result consumer는 이번 구현에 없다.
- 운영 참고 구성은 준비했지만 실제 dev/prod 설치·TLS/ACL 집행·장애 복구·단말 발송은 아직 검증하지 않았다.
- 실제 hostname/IP, 인증서 principal, 서버의 `APP_DIR`·Compose·배포 환경은 운영자가 확인한다. 아래 주소는 예시이며 현재 서버를 조회한 결과가 아니다. 원래 checkout의 별도 CI/PVE 변경은 이 PR에 포함하지 않는다.

현재 workflow는 `develop`을 GitHub `development` 환경으로, `main`과 stable Release를 `production` 환경으로 연결하고 각 환경의 `EC2_HOST`·`EC2_SSH_KEY`로 서버에 접속한다. PVE를 dev 대상으로 사용하더라도 secret 이름은 동일하며 prod 값과 분리해야 한다. 로컬 checkout의 별도 배포 문서는 dev PVE CT102·prod EC2를 목표로 기록한다. 이 문서만으로 해당 서버의 설치 완료를 판단하지 않는다. 서버의 기본 `APP_DIR`은 `/opt/howmuch-infra`이며 GitHub 환경 변수 `APP_DIR`로 변경할 수 있다.

## 1. 환경별 Kafka

| 항목 | dev | prod 기준안 |
|---|---|---|
| cluster | 독립 cluster, broker/controller 겸용 1개 | dev와 독립 cluster, controller 3개 + broker 3개 |
| 장애 영역 | 단일 노드, HA 없음 | 노드를 독립 장애 영역에 배치. 동일 물리 호스트의 여러 VM/컨테이너는 호스트 HA가 아님 |
| topic | 3 partitions, replication 1, min ISR 1 | 3 partitions, replication 3, min ISR 2 |
| 내부 상태 topic | replication 1 | offsets/transaction topic replication 3 |
| client endpoint 예시 | `kafka-dev.private:9092` | `kafka-prod-1.private:9092,kafka-prod-2.private:9092,kafka-prod-3.private:9092` |
| 보안 | 사설 네트워크 + mTLS + ACL | 사설 네트워크 + mTLS + ACL, 역할별 client 인증서 |

이 구성의 근거와 역할 분리는 [Kafka KRaft 운영 지침](https://kafka.apache.org/42/operations/kraft/)을 따른다. 노드 수·디스크/CPU/메모리는 예산과 처리량을 확인해 확정하며 현재 문서는 성능 측정 결과가 아니다.

[private node Compose](../../../ops/kafka/compose-private-node.reference.yml)에 노드별 private IP, label, config/secret directory와 cluster ID를 지정한다. dev는 [dev properties](../../../ops/kafka/server-dev.reference.properties), prod는 [controller](../../../ops/kafka/server-controller.reference.properties)·[broker](../../../ops/kafka/server-broker.reference.properties)를 외부 `server.properties`로 복사하고 모든 `REPLACE_*`를 실제 DNS/ID로 바꾼다. 참고 properties는 static quorum `controller.quorum.voters`를 사용한다. 이미 dynamic quorum으로 설치한 cluster에 이 설정을 덮어쓰지 않는다.

노드별 persistent volume과 데이터 디스크를 준비하고 최초 format 때만 cluster ID를 사용한다. 기존 volume을 새 cluster ID로 재생성하지 않는다. `9092`는 Backend/worker/Connector 등 필요한 서비스만 접근하고, `9093`은 broker/controller 통신에만 허용한다. client는 controller를 bootstrap endpoint로 사용하지 않는다. `advertised.listeners`의 모든 broker DNS가 각 서비스의 컨테이너 안에서도 resolve·접속되어야 한다.

## 2. 인증서·앱 설정 전달

현재 dev/prod profile의 기본 `KAFKA_SECURITY_PROTOCOL`은 `SASL_SSL`이다. 준비된 private 참고 구성은 **SSL/mTLS**이므로 이번 구성에서는 반드시 `KAFKA_SECURITY_PROTOCOL=SSL`을 지정한다. SASL/SCRAM을 선택하면 broker와 client를 모두 별도 설정하고 본 mTLS 예시와 혼용하지 않는다.

Broker 인증서의 SAN은 advertised DNS와 일치해야 한다. node 인증서는 broker/controller 간 client 인증에도 사용한다. 서비스 인증서는 Backend/worker/Connector별 principal을 구분하고 superuser를 부여하지 않는다. 실제 전체 certificate DN을 기준으로 ACL을 설정한다. [Kafka SSL 설정](https://kafka.apache.org/42/security/encryption-and-authentication-using-ssl/)

[client mTLS YAML](../../../ops/kafka/client-mtls.reference.yml)을 Git 밖의 파일로 복사한다. 서비스마다 다음 디렉터리를 외부에서 준비하고 read-only로 mount한다.

```text
<service-specific secret directory>/
  client.keystore.p12
  cluster.truststore.p12
  kafka.keystore.password
  kafka.key.password
  kafka.truststore.password
```

비밀번호 파일은 해당 값만 담으며 Git·로그·명령 출력에 넣지 않는다. `client.keystore.p12`에는 해당 서비스의 개인키와 인증서 chain이, truststore에는 신뢰 CA가 있어야 한다. 앱 UID `10001`이 파일과 상위 디렉터리를 읽을 수 있게 설정한다. Broker 쪽 파일은 해당 Kafka 이미지의 실행 사용자도 읽을 수 있어야 한다.

[앱 mount 참고 Compose](../../../ops/kafka/compose-app-mtls.reference.yml)의 내용을 서버 소유 `app` service에 반영한다. `KAFKA_CLIENT_CONFIG_FILE`·`KAFKA_CLIENT_SECRET_DIR`는 Docker host에서 Compose interpolation에 사용하는 실제 외부 경로이고, Kafka YAML의 경로는 컨테이너 경로다. `.env`를 `env_file`로 전달하는 것만으로 파일이 컨테이너에 mount되지는 않는다.

`SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/kafka-client.yml`로 외부 설정을 읽고 `configtree:/run/secrets/kafka-client/`로 비밀번호를 읽는다. 두 경로는 optional이 아니므로 누락된 mount는 시작 실패로 드러난다. 기존 additional location이 있다면 필요한 기존 location을 포함해 명시적으로 합친다. mount 예시는 [Spring Boot 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html)을 따른다.

현재 배포 workflow는 서버 소유 Compose의 **이미지만** digest로 교체한다. 이 PR을 병합해도 Kafka 노드·DNS·방화벽·ACL·위 mount·서버 runtime 변수는 자동 설치되지 않는다.

## 3. Topic·ACL

[topic catalog](../../../ops/kafka/topics.tsv)의 topic을 앱 시작 전에 명시적으로 만든다. 알림 delivery와 DLT, 리캡 공개와 DLT, 금융 command/event와 DLT의 총 8개다. 리캡·금융 topic을 생성해도 해당 기능을 활성화한 것은 아니다.

인증된 운영 client properties를 외부 파일로 만들고 각 환경에서 [provision script](../../../ops/kafka/provision-topics.sh)를 실행한다. secret 값은 명령 인수로 전달하지 않는다.

```bash
KAFKA_COMMAND_CONFIG=/run/secrets/kafka/admin-client.properties /bin/bash /workspace/provision-topics.sh kafka-dev.private:9092 1 1
KAFKA_COMMAND_CONFIG=/run/secrets/kafka/admin-client.properties /bin/bash /workspace/provision-topics.sh kafka-prod-1.private:9092,kafka-prod-2.private:9092,kafka-prod-3.private:9092 3 2
```

위 경로는 private node 참고 컨테이너 기준이다. script는 기존 topic의 설정을 자동 변경하지 않으므로 기존 partition/replication/retention/min ISR의 drift도 확인한다.

| 서비스 principal | Topic 권한 | Group 권한 |
|---|---|---|
| API/relay | `notification.delivery.v1` Write/Describe, idempotent producer 권한 | 없음 |
| 알림 worker | `notification.delivery.v1` Read/Describe, `notification.delivery.v1.dlt` Write/Describe, DLT producer 권한 | `howmuch-notification-delivery-v1` Read |
| dev 단일 앱 | 위 두 역할의 합집합 | 동일 알림 group Read |
| Connector | 금융 command Read/Describe, 금융 event Write/Describe, producer 권한 | `howmuch-finance-connector` Read (현재 기본값) |

idempotent producer의 cluster `IdempotentWrite` 및 client가 요구하는 권한을 운영자가 확인한다. 서비스별 최소 권한으로 배포하고 `allow.everyone.if.no.acl.found=false`를 유지한다. 리캡 producer/consumer와 Backend 금융 consumer ACL은 실제 연계 구현 후 부여한다. [Kafka ACL 지침](https://kafka.apache.org/42/security/authorization-and-acls/)

## 4. 앱 환경변수·역할

아래는 기존 DB·Redis·JWT·외부 서비스 설정에 추가하는 Kafka 값의 예시다. dev/prod 각각 `SPRING_PROFILES_ACTIVE=dev`/`prod`를 명시하고 서로 다른 bootstrap 주소와 client 인증서를 사용한다.

```text
KAFKA_ENABLED=true
KAFKA_BOOTSTRAP_SERVERS=<환경의 broker DNS:9092 목록>
KAFKA_SECURITY_PROTOCOL=SSL
NOTIFICATION_DELIVERY_MODE=KAFKA
SETTLEMENT_NOTIFICATION_OUTBOX_ENABLED=true
NOTIFICATION_KAFKA_CONSUMER_ENABLED=true
RECAP_KAFKA_CONSUMER_ENABLED=false
KAFKA_CONCURRENCY=1
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/kafka-client.yml
```

위 값은 dev의 단일 앱/API+relay+worker 구성이다. prod를 역할별 프로세스로 나누면 다음을 적용한다. 동일 이미지의 역할 flag이며 worker 전용 별도 binary는 아니다.

| 변수 | API + relay | 알림 worker |
|---|---|---|
| `KAFKA_ENABLED` | `true` | `true` |
| `NOTIFICATION_DELIVERY_MODE` | `KAFKA` | `KAFKA` |
| `SETTLEMENT_NOTIFICATION_OUTBOX_ENABLED` | `true` | `false` |
| `NOTIFICATION_KAFKA_CONSUMER_ENABLED` | `false` | `true` |
| `RECAP_KAFKA_CONSUMER_ENABLED` | `false` | `false` |
| `SETTLEMENT_AUTOMATIC_REMINDER_ENABLED` | 기존 운영 정책 유지 | `false` |

worker도 같은 PostgreSQL·Redis와 필요한 앱 설정을 사용한다. HTTP endpoint는 남으므로 public ingress 대상에 등록하지 않는다. 같은 group의 총 consumer 수와 3개 partition을 비교하여 replica/concurrency를 정한다. API와 worker의 mode는 모두 일치시킨다.

실제 푸시를 보내는 프로세스에는 기존 Firebase ADC 서비스 자격 증명(`FIREBASE_ENABLED=true`)과 APNs key ID/team ID·`.p8` mount가 필요하다. ADC를 파일로 제공한다면 외부 서비스 계정 파일을 read-only mount하고 `GOOGLE_APPLICATION_CREDENTIALS`에 컨테이너 내부 경로를 지정한다. Firebase Console 설정만으로 Backend ADC가 제공되지는 않는다. APNs는 서버 환경 이름 대신 앱의 `aps-environment` entitlement에 맞춰 `APNS_ENVIRONMENT=dev`(development/sandbox) 또는 `prod`(production)를 선택한다. dev 서버에서 production-signed 빌드를 시험한다면 prod endpoint가 필요하다. [Apple의 환경 구분](https://developer.apple.com/library/archive/technotes/tn2265/_index.html)을 따르며 설정은 [기존 배포 문서](../../deployment.md)를 참고한다. 민감 값과 device token은 출력하지 않는다.

리캡 생성·공개 producer가 없으므로 `RECAP_KAFKA_CONSUMER_ENABLED=false`를 유지한다. 금융 RC2 샘플의 transport 왕복 검증을 실제 CODEF 동기화 완료로 해석하지 않는다.

### CODEF Connector 연결 시 별도로 할 설정

현재 로컬 Connector의 `application.yaml`은 `KAFKA_BOOTSTRAP_SERVERS`와 `KAFKA_CONSUMER_GROUP`(기본 `howmuch-finance-connector`)을 읽는다. Backend의 `KAFKA_ENABLED`·`KAFKA_SECURITY_PROTOCOL`만 복사해도 Connector가 활성화되거나 SSL로 연결되는 것은 아니다. Connector에도 자신의 인증서·truststore·비밀번호 파일과 외부 `spring.kafka.properties` 설정을 mount하고 `SPRING_CONFIG_ADDITIONAL_LOCATION`으로 전달해야 한다. 별도 DB와 기존 `CONNECTOR_INTERNAL_SERVICE_TOKEN`·`CONNECTOR_DATA_KEY` 등은 기존 Connector 계약을 따른다.

Connector는 `FINANCE_MESSAGING_ENABLED`(command consumer), `FINANCE_COLLECTION_ENABLED`(수집 worker), `FINANCE_EVENT_RELAY_ENABLED`(result publisher)를 따로 읽는다. 금융 전체 연계 전에는 모두 `false`를 유지하고, 준비 완료 후 해당 담당의 [Kafka 설정 #2](https://github.com/Team-HowMuch/howmuch-finance-connector/issues/2)·[복구 #4](https://github.com/Team-HowMuch/howmuch-finance-connector/issues/4)에서 단계적으로 활성화한다. 이번 Backend PR에는 금융 command 업무 발행과 result 적용 consumer가 없으므로 알림 Kafka 활성화만으로 CODEF 비동기 동기화가 완성되지 않는다. Connector의 실제 mTLS 연결은 별도 검증한다.

## 5. 배포·활성화 순서

1. Broker·volume·DNS·네트워크·TLS·ACL·8개 topic을 준비하고 Backend/worker/Connector 컨테이너에서 실제 접속을 확인한다.
2. SQL #475를 먼저 적용해 V43 Flyway validate를 확인한다. SQL 적용 직후 기존 Java 쓰기 호환 검증은 완료됐지만, 새 타입이 저장된 뒤의 과거 앱/worker 혼재는 허용하지 않는다.
3. 모바일 [#358](https://github.com/Team-HowMuch/howmuch-frontend-v2/issues/358)의 새 enum·nullable allocation·typed target/fallback을 준비한 뒤 앱 #476을 배포한다. **새 알림 종류별 flag는 없다. 앱 배포부터 DIRECT에서도 신규 업무 알림이 만들어지므로 Kafka 비활성화만으로 모바일 호환 문제를 피할 수 없다.**
4. DIRECT scheduler를 모든 instance에서 중단하고 진행 중 발송이 종료하거나 lease가 회복될 때까지 확인한다. 업무 API가 기록한 알림/Outbox는 DB에 보존한다.
5. 모든 관련 process를 KAFKA mode로 맞춰 worker consumer를 준비한 뒤 API/relay scheduler를 켠다. 구형 DIRECT worker를 함께 실행하지 않는다. 리캡 consumer는 계속 끈다.
6. dev 전용 테스트 사용자의 실제 업무 → 알림함 → Outbox ACK → worker 처리 → provider 접수 → 단말 표시/클릭을 확인한 뒤 prod 전환한다. 서로 다른 증거로 기록한다.
7. readiness·queue backlog/oldest age·publication/provider retry·expired lease·consumer lag·DLT·디스크와 인증서 만료를 관측한다. 현재 readiness에는 DB/Redis가 포함되고 Kafka 접속/발행 성공을 보장하지 않으므로 실제 broker metadata/ACK와 consumer 동작을 별도로 확인한다.

실제 private cluster TLS/ACL·failover 검증은 운영 준비 단계에서 수행한다. 실패한 것을 성공으로 기록하거나 group offset을 latest로 바꿔 backlog를 숨기지 않는다. 알림 DLT는 원문 보관소가 아니며 자동 replay API도 없다.

복귀 시 relay/consumer를 중단하고 PROCESSING·lease·generation을 확인한 뒤 **새 알림 타입을 이해하는 호환 앱**에서 DIRECT로 전환한다. 이때 Kafka·consumer flag도 일치시킨다. V43을 제거하거나 새 타입을 모르는 과거 이미지로 바로 rollback하지 않는다. provider 접수 직후 DB 결과 저장 실패의 중복 푸시는 남는 제한이다.
