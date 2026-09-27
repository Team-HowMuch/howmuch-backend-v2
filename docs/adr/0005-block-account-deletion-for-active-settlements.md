# ADR-0005: Block Account deletion while a settlement is in progress

- Status: Accepted
- Date: 2026-09-10
- Decision owners: HowMuch Backend
- Related Feature Plan: [Account deletion active settlement](../features/active/account-deletion-active-settlement.md)
- Supersedes: ADR-0004 account deletion rule for issued settlements
- Superseded by:

## Context

Account deletion currently rejects only `DRAFT` settlements that use the Account as their receiving account. `REQUESTED`, `AWAITING_DEPOSIT`, and `SUSPENDED` settlements detach the active reference and continue without the selected receiving Account, although those states still require future settlement work.

State at the time of the original decision:

- All non-terminal settlement states are protected by `ACCOUNT_IN_USE_BY_SETTLEMENT`.
- Only `COMPLETED` settlement references are detached by `detachCompletedByReceivingAccountId`.
- `COMPLETED` settlements retain an immutable receiving-account snapshot and no longer need the active Account row.

Problems caused by leaving the decision unresolved:

- A settlement waiting for deposits can lose the account that must be queried for reconciliation.
- A suspended settlement can be resumed without its original receiving Account.
- The API allows a destructive account action while an unfinished settlement still depends on it.

## Decision Drivers

- Do not break any settlement that can still be resumed, paid, or reconciled.
- Preserve the existing Account Record and finalized settlement snapshot lifecycle.
- Keep account deletion transactional and compatible with the current API boundary.
- Avoid blocking deletion forever because of completed historical settlements.
- Return an error code whose name matches the full protected state range.

## Options

### Option 1: Block only `DRAFT`

- Benefits: preserves the current API and allows cleanup of issued settlements.
- Drawbacks: unfinished issued settlements lose their active receiving-account reference.
- Risks: deposit reconciliation and suspension recovery may no longer have the expected account.

### Option 2: Block all settlement states

- Benefits: never removes an Account referenced by any settlement.
- Drawbacks: a completed historical settlement can permanently prevent account deletion.
- Risks: account cleanup becomes dependent on historical retention and manual data changes.

### Option 3: Block non-terminal states and allow `COMPLETED`

- Benefits: protects all unfinished flows while keeping completed history deletable through its snapshot.
- Drawbacks: the API error contract and deletion query must distinguish terminal state.
- Risks: a newly introduced terminal state must be added to the deletion policy review.

## Decision

Use option 3. Account deletion is rejected when `DRAFT`, `REQUESTED`, `AWAITING_DEPOSIT`, or `SUSPENDED` uses the Account as its receiving account. The API returns `409 ACCOUNT_IN_USE_BY_SETTLEMENT`, and the transaction changes neither the Account nor the Settlement. Deletion is allowed when only `COMPLETED` settlements reference the Account; their active reference is detached while the immutable receiving-account snapshot and Shares remain. This decision remains valid while `COMPLETED` is the only terminal settlement state.

Issue #430 adds global settlement soft deletion. A settlement with `deleted_at IS NOT NULL` is no longer active and does not block Account deletion, regardless of its preserved status. Account deletion detaches its active receiving-account reference, just as it does for `COMPLETED`; the settlement status and financial records remain stored.

## Consequences

### Positive

- All unfinished settlement flows retain a valid receiving-account selection.
- Account deletion remains possible after settlement completion.
- Historical settlement transfer information continues to use the preserved snapshot.
- The error code describes the protected relationship without referring only to `DRAFT`.

### Negative

- Users must select another receiving account or finish the settlement before deleting an account.
- A new terminal settlement state requires an explicit policy review.
- Existing clients that special-case `ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT` must support `ACCOUNT_IN_USE_BY_SETTLEMENT`.

### Follow-up

- Update account deletion Feature Plan, Swagger, and tests.
- Coordinate the new 409 code with clients before release.
- Revisit the policy if settlement retention or account erasure requirements change.

## Impact

| Area | Impact |
|---|---|
| Architecture | Existing Account–Settlement usage Port changes from DRAFT-only to in-progress usage |
| API | 409 contract becomes `ACCOUNT_IN_USE_BY_SETTLEMENT` |
| Data | No schema change for this ADR; completed or soft-deleted settlement active references are detached on Account deletion |
| Security | No change |
| Operations | No migration or deployment ordering change |
| Developer workflow | New status transitions must be checked against account deletion |

## Verification

- [ ] Verify all four non-terminal statuses return 409.
- [ ] Verify `COMPLETED` deletion preserves snapshot and Share data.
- [ ] Verify rejected deletion leaves Account and Settlement unchanged.
- [ ] Link this ADR from the active and completed Feature Plans.

## References

- [Issue #356: 진행 중인 정산의 수령 계좌 삭제 차단](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/356)
- [Issue #430: SUSPENDED·COMPLETED 정산 전역 논리 삭제 지원](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/430)
- [ADR-0004: Separate active Account and Account Record lifecycles](0004-separate-active-account-and-account-record-lifecycles.md)
