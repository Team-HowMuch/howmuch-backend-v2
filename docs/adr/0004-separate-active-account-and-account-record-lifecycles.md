# ADR-0004: Separate active Account and Account Record lifecycles

- Status: Proposed
- Date: 2026-08-24
- Decision owners: HowMuch Backend
- Related Feature Plan: [Account deletion and reconnection](../features/active/account-deletion-and-reconnection.md)
- Supersedes:
- Superseded by:

## Context

`accounts` currently represents both a usable CODEF-linked payment method and the parent of durable transaction and settlement records. Transactions and monthly summaries reference it with `ON DELETE RESTRICT`, settlements reference it as a receiving account, and relinking the same candidate updates and reactivates the same row.

The product decision is different: deleting a payment method must remove its current CODEF-derived state, while transactions, expense imports, and finalized settlement records remain. Relinking the same external bank account must start a new local lifecycle rather than reconnect old history.

Current state:

- `accounts` stores the encrypted external account key, candidate key, live balance, active/default state, and display order.
- `account_transactions` and `account_monthly_summaries` require the Account row to remain.
- a DRAFT settlement selects an active Account, while an issued settlement already stores an encrypted receiving-account snapshot.
- deleting an Account selected by a DRAFT settlement would silently change an in-progress user decision.
- CODEF `/v1/account/delete` removes registered institution credentials from a `connectedId`; it does not remove one selected bank account.

Problems caused by leaving the decision unresolved:

- physical deletion fails because of foreign keys;
- soft deletion leaves sensitive integration state and makes accidental row reactivation possible;
- hard deletion without a durable owner reference creates orphaned transaction history;
- calling CODEF account deletion for one local Account removes a broader institution credential boundary;
- reconnecting can fetch the same historical external transactions again, so the old and new lifecycle records must remain separate.

## Decision Drivers

- Remove CODEF-derived secrets and mutable payment-method state when the user deletes an Account.
- Preserve transaction ownership, monthly summaries, imported settlement expenses, and finalized receiving-account snapshots.
- Give a reconnected account a new identity while allowing the requested historical transaction period to be synchronized.
- Keep referential integrity in PostgreSQL.
- Reject deletion while any DRAFT settlement uses the Account as its receiving account.
- Allow the migration-only pull request to be deployed before the Java implementation without breaking account creation.
- Do not broaden a local payment-method deletion into an institution credential deletion.

## Options

### Option 1: Soft-delete and reactivate the same Account

- Benefits: smallest schema change and all current foreign keys remain valid.
- Drawbacks: retains external ciphertext and mutable state unless many columns become nullable; current upsert naturally revives the old ID.
- Risks: deleted and reconnected histories become indistinguishable and future callers may expose inactive Accounts.

### Option 2: Hard-delete Account and keep unvalidated numeric references

- Benefits: removes the active row and needs little replacement schema.
- Drawbacks: transaction, summary, and settlement source IDs no longer have database referential integrity or a durable owner.
- Risks: direct or buggy writes can create orphaned financial history that cannot be attributed safely.

### Option 3: Hard-delete Account and retain a minimal Account Record

- Benefits: removes current integration state, keeps durable ownership and foreign keys, and gives each reconnection a new lifecycle ID.
- Drawbacks: adds one persistence table and requires migration-safe synchronization between `accounts` and `account_records`.
- Risks: the record can become a second mutable Account model if future code adds live integration fields to it.

## Decision

Use option 3. `accounts` represents only currently usable payment methods and is physically deleted. `account_records` retains the same lifecycle ID plus only the minimum non-sensitive provenance needed by transaction and settlement history: owner, institution, display label, masked number, type, currency, linked time, and deleted time. It never stores the external connection ID, candidate key, encrypted external account key, balance, default state, or display order.

Every active Account has one Account Record. PostgreSQL triggers maintain that record across migration-first deployment and mark it deleted in the same database transaction as Account deletion. Transactions, monthly summaries, and finalized settlement snapshots reference the Account Record. A reconnected external account creates a new Account and Account Record. Its transaction synchronization uses the full period requested by the user and does not use the Account creation or linked time as a lower bound.

Fetching an earlier period after reconnection may store the same external transaction under both the deleted and reconnected Account lifecycle IDs. This is intentional: no existing transaction row is moved, re-parented, or merged, and each Account keeps its own deduplication boundary.

Transaction and monthly-summary JPA entities store `account_id` as a scalar lifecycle ID rather than an `AccountJpaEntity` association. PostgreSQL still enforces `NOT NULL` foreign keys from those columns to `account_records(id)`, so removing the JPA association does not remove referential integrity.

Account deletion is rejected with a conflict while any DRAFT settlement selects it as the receiving account. The user must select another receiving account or end the DRAFT first. Issued settlements do not block deletion: their active `receiving_account_id` reference is detached explicitly while their immutable receiving-account snapshot and Shares remain.

Local Account deletion does not call CODEF `/v1/account/delete`. Institution disconnect is a separate user goal because the CODEF operation deletes registered institution credentials and can delete the `connectedId` when its last registered institution is removed.

## Consequences

### Positive

- Current CODEF-derived secrets and mutable payment-method state are removed with the Account row.
- Historical financial and settlement data keeps a database-enforced owner and source identity.
- Relinking cannot reactivate the deleted row because its active Account no longer exists.
- In-progress DRAFT settlements never lose a receiving-account selection implicitly.
- Existing account creation remains compatible while the migration-only change is deployed before Java code.
- A local deletion is not coupled to CODEF availability.

### Negative

- PostgreSQL triggers become part of the persistence contract and require migration tests.
- Account transaction JPA mappings must reference the lifecycle ID or Account Record rather than require an active `AccountJpaEntity`.
- Account deletion adds a settlement-usage check and an explicit detach operation for issued settlements.
- Issued settlements have `receiving_account_id=null` after Account deletion; their immutable snapshot becomes the only valid transfer source.
- A dedicated API is required if the product later wants users to browse deleted-account history.
- A historical external transaction may exist once per Account lifecycle after reconnection.

### Follow-up

- Implement the migration-only pull request before Java changes.
- Add the local Account deletion UseCase and concurrency checks in a separate task.
- Design institution disconnect, user confirmation, and CODEF partial failure handling separately.
- Define retention and erasure rules before deleting Account Records.
- Reconsider the trigger only if a proven expand/contract deployment process replaces its compatibility role.

## Impact

| Area | Impact |
|---|---|
| Architecture | Separates current Account behavior from durable financial-record provenance |
| API | Adds local `DELETE /api/v2/accounts/{accountId}` with a DRAFT-use conflict; no deleted-history API |
| Data | Adds `account_records`, changes transaction/summary/snapshot references, preserves DRAFT references, and detaches issued-settlement active references |
| Security | Deletes encrypted external account keys and candidate linkage while preserving only masked provenance |
| Operations | Requires migration-first deployment and PostgreSQL trigger verification |
| Developer workflow | Account deletion implementation starts with the repository Flyway migration gate |

## Verification

- [ ] Verify every active Account creates or updates exactly one Account Record on the pre-implementation application version.
- [ ] Verify Account deletion removes `accounts` state while preserving transaction, summary, settlement-expense, snapshot, and Share rows.
- [ ] Verify a DRAFT receiving-account reference returns a conflict and preserves both the Account and Settlement.
- [ ] Verify all historical references retain PostgreSQL foreign-key integrity.
- [ ] Verify relinking creates a new ID and synchronizes the full requested transaction period without moving old rows.
- [ ] Verify local deletion makes no CODEF delete request.
- [x] Link the related Feature Plan and documentation.
- [ ] Complete compilation, PostgreSQL migration, concurrency, CODEF Mock, and manual HTTP verification.

## References

- [Issue #97: Account deletion and CODEF reconnection policy](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/97)
- [CODEF account deletion](https://developer.codef.io/common-guide/connected-id/delete)
- [Account connection Feature Plan](../features/active/codef-bank-account-connection.md)
- [Account query and transaction Feature Plan](../features/active/account-query-and-transactions.md)
- [Settlement receiving account Feature Plan](../features/completed/settlement-receiving-account.md)
