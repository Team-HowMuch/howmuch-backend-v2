# Feature Plan: <Feature name>

> Copy this file to `docs/features/active/<feature>.md`. Update it whenever implementation reveals new information or decisions, then move it to `docs/features/completed/` when complete.

- Status: Draft
- Last updated: YYYY-MM-DD
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

Describe whose problem this feature solves and why the current behavior is insufficient. Use a concrete situation.

### Why now

Record why the problem should be solved now and what happens if it remains unresolved.

### Success criteria

- [ ] An observable outcome available to the user
- [ ] Guaranteed behavior for relevant failure or boundary conditions

### Out of scope

- Work intentionally excluded from this feature

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse:
- Reason a new domain is required, if applicable:
- Relationships to other domains:

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
|  |  |  |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
|  |  |  |

### State and invariants

- State transitions:
- Rules that must always hold:
- Invalid states:

## 3. Scenarios

### Normal flow

1. Starting condition
2. User or external-system action
3. Application processing
4. Observable result

### Failure flow

- Condition:
- Returned error or state transition:

### Boundary conditions

- Record expiration, duplication, retry, concurrency, empty values, or other cases only when they apply to this feature.

## 4. Contracts

### Input Port

```text
UseCase:
Request:
Response:
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
|  |  |  |  |

Do not create a Port when no real external conversation exists.

### Web API

```text
Method /path
Request:
Response:
Errors:
Authentication:
```

### Persistence and external integrations

- Database Entity, constraint, or query changes:
- Redis key, TTL, or serialization changes:
- External API or message changes:
- Environment variable or profile changes:

## 5. Design decisions

### Business logic

- Rules and their locations:
- Behavior owned by Domain:
- Flow orchestrated by Application:

### Technology and patterns

- Constraint to solve:
- Selected approach:
- Reason for selection:
- Alternatives considered:
- Trade-offs and limits:

Create an ADR for a decision with durable impact across multiple features and link it under `Related ADR` above.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API |  |  |
| Application |  |  |
| Domain |  |  |
| Persistence |  |  |
| Redis |  |  |
| Security |  |  |
| Configuration |  |  |
| Documentation |  |  |

Existing behavior that must remain unchanged:

- <Behavior that must not be affected>

## 7. Implementation plan

- [ ] UseCase and Application Request/Response
- [ ] Required Output Ports
- [ ] Domain models and rules
- [ ] Application Service
- [ ] Output Adapters
- [ ] Web DTOs, Controllers, and Swagger
- [ ] Infra configuration and Bean wiring
- [ ] Documentation and verification results

Do not delete an unnecessary step. Mark it `N/A` and record the reason.

### Expected commit boundaries

Create actual commits only when the user explicitly requests them. Commit descriptions are written in Korean.

```text
docs(<domain>): <Korean description of the design documentation>
feat(<domain>): <Korean description of an independently understandable change>
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

Add the manual execution procedure and expected results required by the feature. State prerequisites such as database, Redis, and environment variables.

### Completion criteria

- [ ] Verified the user outcome defined under Purpose.
- [ ] Verified normal, failure, and boundary flows.
- [ ] Confirmed documentation and Swagger match the actual contract.
- [ ] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
|  | Pending |  |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| YYYY-MM-DD | Initial document | Review contracts |  |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| YYYY-MM-DD |  |  |  |

## 11. Completion record

- Completed on:
- Final behavior:
- Unverified items:
- Remaining risks:
- Follow-up work:
