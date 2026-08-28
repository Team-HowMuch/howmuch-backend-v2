# ADR-0002: Framework-neutral StatusCode contract

- Status: Accepted
- Date: 2026-08-11
- Decision owners: HowMuch Backend
- Related Feature Plan: [Hexagonal coding guidelines](../features/completed/hexagonal-coding-guidelines.md)
- Supersedes:
- Superseded by:

## Context

`StatusCode` returned Spring `HttpStatus`, which forced Domain status enums to import a Web framework type. Adding group-specific errors would spread that dependency to every new domain.

## Decision Drivers

- Domain must remain independent of Spring.
- Existing HTTP status values and error responses must remain unchanged.
- The shared exception contract should stay small.

## Options

### Option 1: Keep Spring `HttpStatus` in Domain enums

- Benefits: no shared contract change.
- Drawbacks: every Domain error enum depends on Spring Web.

### Option 2: Store numeric status values

- Benefits: removes framework types from Domain while preserving the current response contract.
- Drawbacks: status values are represented as integers.

## Decision

`StatusCode` exposes `int getStatus()`. Domain and common status enums store the numeric HTTP status, while Web and Infra layers translate that value into an HTTP response.

## Consequences

### Positive

- Domain packages no longer import Spring.
- Existing JSON error status values remain unchanged.

### Negative

- Enum declarations use numeric HTTP status values.

### Follow-up

- Reconsider only if the application must support a non-HTTP error transport with different status semantics.

## Impact

| Area | Impact |
|---|---|
| Architecture | Removes the Domain-to-Spring dependency |
| API | No response status or body change |
| Data | None |
| Security | Existing authentication and authorization statuses remain unchanged |
| Operations | None |
| Developer workflow | New Domain status enums use numeric status values |

## Verification

- [x] Domain sources no longer import Spring on the current branch.
- [x] `GlobalExceptionHandler` and `SecurityConfig` use `StatusCode.getStatus()` on the current branch.

## References

- [Architecture](../architecture.md)
- [Hexagonal coding guidelines Feature Plan](../features/completed/hexagonal-coding-guidelines.md)
