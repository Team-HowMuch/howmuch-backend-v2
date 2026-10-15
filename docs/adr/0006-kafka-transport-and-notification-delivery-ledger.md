# ADR-0006: Kafka transport and the notification delivery ledger

- Status: Proposed
- Date: 2026-10-07
- Decision owners: Backend maintainers
- Related Feature Plan: [Kafka platform and notifications](../features/active/kafka-platform-notifications.md)

## Context

Notifications already persist an inbox record and an Outbox intention with the business change. The existing polling worker delivers pushes directly. The finance Connector consumes and produces Kafka messages using a separate durable request/result boundary. Both services need consistent transport configuration while preserving their different retry and reconciliation semantics.

## Decision Drivers

- Retain existing notification rows, identifiers, cursor/read behavior, and legacy settlement deliveries.
- A broker outage must not discard business intent or exhaust the push provider retry budget.
- Duplicate delivery and late worker completion must be fenced by durable database state.
- Commit a consumer offset only after durable processing or acknowledged contract quarantine.
- Preserve the existing finance RC2 wire contract and service authorization boundaries.

## Options

### Direct database polling

It needs less infrastructure and remains a controlled fallback mode. Each domain must own polling, scaling, and failure isolation, and it does not provide the shared transport needed by the Connector.

### Kafka with a durable database delivery ledger

It separates publication and execution and permits independent consumers. It requires broker provisioning, ACLs, retention management, and explicit database/broker recovery. Kafka idempotent production alone cannot make external push effects exactly once.

## Decision

Use Kafka for notification execution with the business inbox/Outbox write retained in PostgreSQL. Keep the physical `settlement_notification_outbox` table for this transition. Publication owns an independent lease, retry count, and stable message ID. Provider retries advance a durable generation and message ID; completion uses a compare-and-set fence. The same database ledger provides notification consumer deduplication, so a second notification consumer Inbox table is unnecessary.

Choose mutually exclusive `DIRECT` and `KAFKA` modes, with `DIRECT` as the default until broker, permissions, consumers, and mobile compatibility are verified. CODEF retains its own mandatory request/result Inbox/Outbox design; this change does not implement finance result reconciliation.

Transport configuration belongs to Infra, inbound listeners to `adapter.in.kafka`, and publishers to `adapter.out.kafka`. Business Ports use plain Application records. Use bounded acknowledged publication, disabled auto-commit/topic creation, explicit consumer groups, and synchronous record processing. Malformed contracts go to an acknowledged DLT containing recovery metadata without the original payload; transient processing failures remain retryable.

## Consequences

- Business transactions remain independent of broker availability.
- Old and generic notifications share one preserved delivery ledger.
- Database compare-and-set prevents duplicate successful generation claims and stale completion writes.
- Push acceptance followed by a process or result-persistence failure can still produce duplicate pushes. No device-display exactly-once guarantee is made.
- Single-broker local verification does not prove production availability, SASL/TLS interoperability, or actual finance Connector behavior.
- New notification types require mobile routing support. Deployment must apply the expanded schema and compatible application together before enabling Kafka consumption.

## Verification

The Feature Plan records SQL upgrade/fresh-install checks, real broker transport tests, an actual recap-to-inbox-to-Kafka-to-provider flow, database claim/retry fencing, and existing notification regressions. Deployment and real-device push verification remain separate checks.
