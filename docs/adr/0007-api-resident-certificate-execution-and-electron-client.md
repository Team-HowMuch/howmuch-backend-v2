# ADR-0007: API-resident certificate execution and an Electron client

- Status: Accepted
- Date: 2026-10-10
- Decision authority: explicit user direction
- Related: [feature plan](../features/active/codef-certificate-registration.md), [Kafka transport](0006-kafka-transport-and-notification-delivery-ledger.md)

## Context

V44 introduced PC registration metadata and QR approval facts. The initial plan placed certificate uploads, CODEF execution, provider command Inbox/jobs, and result Outbox in the finance connector, with a WPF client. The user selected Electron and requested implementing the pending certificate connector responsibilities inside the Backend API before extracting them later.

V44 does not store pending encrypted certificate material or provider execution state. Existing ExternalConnection requires a user and cannot represent an anonymous pending registration. Losing temporary references at a cache TTL would also lose recovery and orphan-cleanup information after a CODEF request.

## Decision

1. Implement the Windows client with Electron and a small local HTML/CSS/JavaScript renderer. On 2026-10-11 the user selected `apps/windows-certificate` in the finance-connector repository as its home. Keep its Node build, installer artifact and Windows CI separate from the Java server artifact.
2. Implement certificate provider responsibilities as dedicated Application services and Output Ports in the API certificate feature. Keep provider jobs, secrets and result publication in purpose-specific storage capabilities, separate from session/claim and result-Inbox capabilities. Reuse the current CODEF HTTP/OAuth/RSA client and acknowledged Kafka transport. Extraction will require replacing the current shared session-lock checks with a reviewed remote approval contract.
3. Preserve command Outbox -> Kafka -> provider command Inbox/job -> CODEF -> provider result Outbox -> Kafka -> result Inbox. Co-location does not remove durable receipt, publication or duplicate-handling boundaries.
4. Add SQL-only provider storage before Java/client implementation. Combine durable command receipt and job state in one row. Keep upload envelope ciphertext separate from durable connectedId/account-query credentials. Store no secrets in QR, Kafka or ordinary session metadata.
5. Commit a provider-request-start marker before a mutation; an ambiguous completion must not trigger another automatic mutation. Approved registration currently holds a user row lock while performing create/add/update to serialize it with existing ID/PW registration and commit the connection atomically. This deliberate exception increases lock duration and is bounded by the provider timeout. Later extraction needs a reviewed distributed serialization contract.
6. Integrate approved connections with the existing account model using real provider identifiers and existing encryption formats. Check immutable approval and user ownership; never insert a placeholder connectedId.
7. Expose only narrowly scoped preload methods. Use isolated, sandboxed renderers without Node integration, validate IPC senders, and allow only explicit file-picker selections and configured API origins.

The initial React/TypeScript renderer proposal is reduced to a packaged local form for this first client; no renderer framework or bundler is needed for its narrow flow. The main/preload security boundary and explicit IPC validation are retained. Authenticated mobile Yes/No consent is the current approval contract. Successful provider registration binds the connection to that user. Consent does not prove matching financial identity; the user explicitly permits connecting credentials belonging to another person. Account selection remains a separate existing API flow.

## Consequences

The API initially owns more operational work and encrypted storage. Existing team web skills can be used for the client, while Electron increases package/runtime maintenance. The provider boundary must remain explicit to make later extraction manageable. Extraction will require moving provider tables/keys and replacing local ownership/account integration capabilities with reviewed remote contracts; it is not a deployment toggle.

The existing transaction-collection finance connector remains under its current contract. This decision relocates the newly planned certificate responsibilities, not every historical finance feature. Woori registration and account query were verified against the real local API on 2026-10-11. Other institutions, real-bank existing-ID add/update, installer signing and actual Windows execution still require evidence. The mobile consent screen still needs implementation in the separate mobile repository.

## References

- [Electron security](https://www.electronjs.org/docs/latest/tutorial/security)
- [Electron IPC](https://www.electronjs.org/docs/latest/tutorial/ipc)
