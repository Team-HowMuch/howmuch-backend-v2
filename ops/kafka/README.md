# Kafka transport operations

This directory supplies a local broker, an explicit topic catalog, and private dev/production references. It does not activate Kafka in an existing deployment. Application defaults remain `howmuch.messaging.kafka.enabled=false`, `howmuch.notification.delivery-mode=DIRECT`, and both consumer flags `false`.

## Image and deployment shape

The JVM image is pinned to `apache/kafka:4.2.2`. The [official Kafka 4.2 Docker guide](https://kafka.apache.org/42/getting-started/docker/) listed this patch on 2026-10-07. Application dependencies continue to use the Spring Boot 4.1 managed Kafka client; they are not force-upgraded to the broker version. The CODEF Connector's Kafka 3.9.2 client is a separate dependency.

| Environment | Nodes | Durability and network |
|---|---|---|
| Local | One combined broker/controller | Named volume; host loopback and Docker network listeners; replication 1, minimum ISR 1 |
| Dev | One combined private TLS node | Persistent per-node volume; private DNS/IP; mutual TLS and ACLs; replication 1, minimum ISR 1; no HA claim |
| Production reference | Three controllers and three brokers in separate failure domains | Persistent independent volumes; private TLS; replication 3, minimum ISR 2; broker rack placement |

Combined nodes are limited to local/dev here. Kafka recommends separate roles for critical deployments in its [KRaft operations guide](https://kafka.apache.org/42/operations/kraft/). Running six processes on one host does not provide six failure domains. No reference private cluster, certificate rotation, ACL enforcement, failover, or production sizing has been exercised by the local tests.

## Local start and topic provisioning

Run from the repository root with Docker running:

```bash
docker compose -f ops/kafka/compose-local.yml up -d --wait
docker compose -f ops/kafka/compose-local.yml exec -T kafka /bin/bash /workspace/provision-topics.sh kafka:19092 1 1
```

Host applications use `KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092`. Docker applications join the `howmuch-kafka-local` network and use `KAFKA_BOOTSTRAP_SERVERS=kafka:19092`. Both bootstrap and advertised addresses must be reachable from the client. This local plaintext listener is bound to host loopback; it is not the dev/prod security configuration.

Topics are explicitly created from [topics.tsv](topics.tsv); broker and application auto-creation are disabled. The provisioning script creates missing topics and describes all topics. Existing partitions/configuration are not silently changed: compare the description with the catalog and handle drift before enabling producers. Running the script again does not delete data or reset offsets.

| Topic | Key | Contract/owner | Retention |
|---|---|---|---|
| `notification.delivery.v1` | Decimal `recipientUserId` | Backend delivery reference; schema v1 | 7 days |
| `notification.delivery.v1.dlt` | `topic:partition:offset` | Sanitized quarantine metadata | 7 days |
| `recap.published.v1` | Decimal `userId` | Actual recap producer; `RecapPublished` schema v1 | 7 days |
| `recap.published.v1.dlt` | `topic:partition:offset` | Sanitized quarantine metadata | 7 days |
| `finance.sync.command.v1` | UUID `accountRef` | Existing finance RC2 command | 7 days |
| `finance.sync.event.v1` | UUID `accountRef` | Existing finance RC2 results/observations | 1 day |
| Finance `.dlt` topics | Reserved | No backend finance consumer/recoverer is implemented by this change | 7 days |

Initial partition count is 3. Notification and recap broker record limits are 8 KiB; their inbound application contracts reject payloads above 4 KiB. Finance topics retain a 1 MiB record limit for the existing contract. The backend notification producer's 256 KiB `max.request.size` is not a complete finance publisher implementation. Finance retention and payload/chunk limits require confirmation with the Connector integration; observations can include financial facts even though certificate/private key/password/`connectedId` must not enter the broker.

## Application roles and rollout

Apply the feature's SQL migration before using Kafka mode. Verify PostgreSQL/Flyway and Redis prerequisites independently. The existing local profile supplies a loopback bootstrap default; dev/prod require an explicit `KAFKA_BOOTSTRAP_SERVERS`. An enabled transport with an empty bootstrap fails startup. Delivery mode accepts exactly `DIRECT` or `KAFKA`; unknown values fail startup. KAFKA mode requires enabled transport, and the notification consumer requires KAFKA mode. A recap-only DIRECT process may enable Kafka, but any recap consumer still requires enabled transport.

One-process local evaluation can use:

```bash
KAFKA_ENABLED=true NOTIFICATION_DELIVERY_MODE=KAFKA NOTIFICATION_KAFKA_CONSUMER_ENABLED=true KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 ./gradlew bootRun --args='--spring.profiles.active=local'
```

Provider credentials and `FIREBASE_ENABLED` remain under the existing push configuration. Starting this command does not establish actual device delivery. Synthetic integration tests mock only the provider boundary and do not call CODEF, Firebase, APNs, or a real bank.

Separate processes can run the same artifact with these flags:

| Role | Kafka enabled | Delivery mode | Notification consumer | Recap consumer | Outbox scheduler enabled |
|---|---|---|---|---|---|
| API + DB Outbox relay | `true` | `KAFKA` | `false` | As approved for recap integration | `true` |
| Notification delivery worker | `true` | `KAFKA` | `true` | `false` | `false` |
| Existing direct delivery | `false` | `DIRECT` | `false` | `false` | `true` |

Flags correspond to `KAFKA_ENABLED`, `NOTIFICATION_DELIVERY_MODE`, `NOTIFICATION_KAFKA_CONSUMER_ENABLED`, `RECAP_KAFKA_CONSUMER_ENABLED`, and `SETTLEMENT_NOTIFICATION_OUTBOX_ENABLED`. The worker flag selects its consumers; it does not create a distinct deployable service or remove HTTP endpoints. Its ingress and identity need a separate deployment policy.

Before a mode change, pause delivery schedulers/consumers, let in-flight leases finish or recover, and switch the participating processes consistently. Avoid leaving an old DIRECT scheduler active during a Kafka rollout. For rollback, stop the Kafka relay/consumers, inspect PROCESSING leases and outstanding generations, recover them under the established delivery policy, then enable DIRECT delivery. Do not reset a group to latest or mark rows successful to hide a backlog.

## Private TLS and ACL references

[compose-private-node.reference.yml](compose-private-node.reference.yml) starts one configured node per host. It requires an explicit node label, private interface address, persistent cluster ID, config directory, and secrets directory. The reviewed `server.properties`, keystores, truststore, and password files stay outside Git. Fresh storage is formatted once; existing metadata is reused. Never change the cluster ID or delete a volume to repair a running cluster.

For dev, render [server-dev.reference.properties](server-dev.reference.properties) as `server.properties`. For production, render the appropriate [controller](server-controller.reference.properties) or [broker](server-broker.reference.properties) reference for each node. Replace all `REPLACE_*` fields, use stable private DNS, and set certificate SANs to the advertised names. Brokers use IDs 101–103 and controllers 1–3. The Compose ports must bind a private interface, and network policy must admit only required service/node traffic. Separate nodes require reachable private DNS; the local Docker service name is not a production address.

References use mutual TLS (`SSL`, `ssl.client.auth=required`) plus `StandardAuthorizer` with default deny. Set the application's `KAFKA_SECURITY_PROTOCOL=SSL` for this mTLS option and inject its client certificate/truststore settings via private Spring configuration. Dev/prod application YAML defaults to `SASL_SSL`, so this choice must be explicit. If using SASL/SCRAM over TLS instead, render the broker/client SASL configuration from your secret manager; do not substitute plaintext SASL or commit JAAS secrets. Follow Kafka's [TLS/SASL configuration](https://kafka.apache.org/42/security/authentication-using-sasl/) and [authorization/ACL guide](https://kafka.apache.org/42/security/authorization-and-acls/).

TLS certificate DN values must match actual principals. The reference `CN=` principals assume certificates with those exact subjects; certificates with additional DN components require the full principal or a reviewed mapping rule. Only node and operations identities are superusers. Application principals must not be superusers.

Minimum service ACL plan (grant Describe with the specified topic operations; grant required idempotent-write cluster permission separately):

| Identity | Write topics | Read topics / groups |
|---|---|---|
| Backend API/relay | `notification.delivery.v1`, `finance.sync.command.v1` when finance integration exists | `recap.published.v1` / `howmuch-recap-notification-v1`; finance event/group only when its consumer exists |
| Notification worker | `notification.delivery.v1.dlt` | `notification.delivery.v1` / `howmuch-notification-delivery-v1` |
| Recap producer | `recap.published.v1` only | None |
| Recap consumer process | `recap.published.v1.dlt` | `recap.published.v1` / `howmuch-recap-notification-v1` |
| Finance Connector | `finance.sync.event.v1`; its quarantine topic when its sanitized recoverer exists | `finance.sync.command.v1` / existing Connector group |
| Operations | Provisioning and approved metadata/lag/quarantine inspection | Only approved groups/topics |

The recap listener is a service integration boundary, not a public mobile publishing API. A mobile user cannot establish producer authenticity by putting `userId` in JSON. Enable `RECAP_KAFKA_CONSUMER_ENABLED` only after the real recap producer, its private authenticated principal, publication semantics, and ACL tests exist. This change includes the inbound listener and notification handling; it does not implement the upstream recap generator/producer.

Private provisioning uses the same script, for example from a broker's tools directory with `KAFKA_COMMAND_CONFIG` pointing to a private authenticated client-properties file:

```bash
KAFKA_COMMAND_CONFIG=/run/secrets/kafka/admin-client.properties /bin/bash /workspace/provision-topics.sh kafka-1.private:9092,kafka-2.private:9092,kafka-3.private:9092 3 2
```

Controllers are not client bootstrap endpoints. Review ACLs, topic configuration, minimum ISR and advertised-address reachability before enabling application flags. These are deployment prerequisites; no live deployment is performed by this repository change.

## ACKs, retries, offsets and quarantine

The publisher waits for broker ACK before allowing its caller to mark a publication successful. `acks=all` and idempotent production reduce broker duplicates; the DB ledger still supplies cross-process deduplication. A send can spend up to `max.block.ms` obtaining metadata/buffer space and then wait up to the configured publish timeout. Defaults are 3 seconds + 5 seconds; the broker delivery deadline is 8 seconds. A local ACK timeout can still produce a record later. Retry the same durable message/generation under the DB publication lease rather than claiming exactly-once delivery.

Notification generation starts at **0**. Provider retry/recovery advances the generation and changes its durable message ID. Broker failures do not spend provider attempt budget. Payloads contain IDs, recipient, generation and UTC timestamp only; never include push tokens, title/body, raw links, certificate bytes, passwords, `connectedId`, or arbitrary HTTP/provider response bodies.

Each record invokes its Application UseCase synchronously. Its message ID, delivery ID, generation, recipient and timestamp are compared with the canonical ready DB delivery message; a mismatched envelope is rejected without provider side effects. Consumer `enable.auto.commit=false`, RECORD acknowledgments and synchronous commits mean the source offset moves only after the local UseCase returns. DB/processing errors retry without a finite transport retry count; they do not become ordinary successful/DLT deliveries. Such an outage deliberately stalls that partition and must alert operators. Provider attempts have a separate bounded DB policy.

Malformed JSON, duplicate keys, trailing JSON, unknown fields, unsupported schema/type, invalid ID/key/time, and invalid recap publication arguments are quarantined. The DLT contains only deterministic quarantine ID, UTC time, source topic/partition/offset, SHA-256 payload digest and fixed failure code. It never copies source key/value/headers or exception text/stack. ACK failure while storing the DLT leaves the original source offset uncommitted. Re-delivery can create duplicate DLT records with the same deterministic ID; operators deduplicate by that ID.

A sanitized DLT is evidence, not a source payload archive or automatic replay service. Repair notification publications through the authoritative DB Outbox and recap publications through the authenticated upstream producer. Retention expiry is not successful processing. Do not reset offsets, bulk mark outboxes successful, or copy malformed raw records into a DLT. Finance intake/reconciliation, complete chunk tracking, version fencing and CODEF timeout recovery remain separate implementation work.

## Observation and verification

Observe `howmuch.messaging.kafka.publish` timers with bounded `destination`/`result` labels, Spring Kafka template/listener observations, native consumer lag/fetch metrics, DB publication/provider backlog age, processing-lease age, retry counts and DLT rate. Publication polling uses `howmuch.notification.publication.events` and `.poll.failures`. Do not add user/account/message IDs, keys, bodies, credentials or exception text as metric/span labels. The default producer error logger is replaced so it cannot copy record keys/values. Lag measurements and DB ledger states are different evidence and should be inspected together. Spring Kafka documents its [Micrometer observations](https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html).

Focused checks (run one Gradle test process per worktree to avoid report-output races):

```bash
./gradlew test --tests '*KafkaMessagingConfigurationTest' --tests '*KafkaMessageContractTest' --tests '*KafkaPublicationTest' --tests '*KafkaTransportIntegrationTest'
```

The full `KafkaNotificationFlowIntegrationTest` additionally requires the isolated migrated PostgreSQL/Redis environment used by the project's integration suite. It checks recap broker input → notification/Outbox DB state → relay ACK → delivery consumer → provider boundary, initial generation 0, committed offsets and deduplication. The provider is mocked; device-display acceptance is separate verification.

Finance fixtures and [finance-sync-message-v1.schema.json](../../src/main/resources/schemas/finance-sync-message-v1.schema.json) are copied unchanged from Connector `finance-contracts` RC2: `SyncAccountTransactions` and an empty `TransactionsCollectionCompleted` result, with existing topic names, `accountRef` key, versions, causation and data fields. No notification envelope is imposed on finance messages. Testcontainers round-trip tests validate these fixtures with the backend's client. A local host smoke check also verified the Connector-version Kafka 3.9.2 client against broker 4.2.2 with ACK and exact command/event payload round trips. This proves sample wire transport; it does not prove the Connector's actual process, result completeness, nonempty financial reconciliation, external CODEF call or production TLS/ACL behavior.

The reproducible host check is [FinanceContractTransportSmoke.java](FinanceContractTransportSmoke.java). Supply the chosen Kafka client and SLF4J jars as an explicit classpath; the helper uses the caller's client version and prints no payloads. With the local broker provisioned, run from the repository root (replace the classpath placeholder with actual local jar paths):

```bash
java --class-path '<kafka-clients-3.9.2.jar>:<slf4j-api.jar>' ops/kafka/FinanceContractTransportSmoke.java 127.0.0.1:9092 src/main/resources/messaging/examples/finance-sync-account-transactions-v1.json src/main/resources/messaging/examples/finance-transactions-empty-v1.json
```

Use a local/test broker only: this sends the two synthetic fixtures to the existing finance topics. It assigns and reads the exact acknowledged offsets without changing application consumer-group offsets.

Stop local services without removing their durable volume:

```bash
docker compose -f ops/kafka/compose-local.yml down
```
