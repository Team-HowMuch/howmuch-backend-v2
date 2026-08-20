# ADR-0001: Purpose-oriented Ports and Application-owned Policies

- Status: Accepted
- Date: 2026-08-11
- Decision owners: HowMuch Backend
- Related Feature Plan: [Hexagonal coding guidelines](../features/active/hexagonal-coding-guidelines.md)
- Supersedes:
- Superseded by:

## Context

The current `UserRepositoryPort` combines user reads, creation, and updates in one interface.

```java
public interface UserRepositoryPort {
    Optional<User> findById(Long id);
    Optional<User> findByIdentityKey(String identityKey);
    User save(User user);
    User update(User user);
}
```

A read-only Service therefore depends on write capabilities it does not use, and the Port name describes a persistence implementation concept instead of the Application purpose.

The current repository also contains `infra/auth/config/AuthPolicyConfig.java`, which constructs Application Policy records from Infra Properties. This is current code, but it conflicts with the target convention that Policies belong to and are used only by Application.

Group-member exit decisions will combine membership state with future settlement or unpaid-fee facts. Leaving that decision in a Service condition or Persistence Adapter would scatter the rule and allow manager removal to bypass financial exit restrictions.

Leaving these decisions unresolved would cause:

- broad `<Aggregate>RepositoryPort` interfaces;
- Services depending on capabilities they do not use;
- confusion between Application Ports and Spring Data Repositories;
- business Policies being constructed or interpreted in Infra;
- exit restrictions scattered across Services and Adapters.

## Decision drivers

- Port names must reveal the Application purpose.
- Services must depend only on capabilities they use.
- Adapter count must not grow unnecessarily.
- Domain, Service, Policy, Adapter, and Infra responsibilities must remain distinct.
- Policies must remain Application-owned.
- Future Ports and no-op Adapters must not be created before real callers exist.

## Options

### Option 1: Keep aggregate Repository Ports and Infra Policy wiring

Benefits:

- Fewer interfaces.
- No immediate refactor of current authentication code.

Drawbacks:

- Read and write capabilities remain mixed.
- Port names emphasize persistence technology.
- Application Policy ownership remains unclear.
- New features are likely to copy the same structure.

### Option 2: Split purpose-oriented Ports and keep Policies inside Application

Benefits:

- Services depend only on required capabilities.
- Port names express Application intent.
- One Persistence Adapter can still implement multiple Ports.
- Policy inputs and decision ownership become explicit.
- Infra remains limited to technical setup.

Drawbacks:

- More Port interface files.
- Existing callers are migrated to capability-specific Ports.
- Existing Auth Policy wiring needs a separate refactor.

### Option 3: Inject Spring Data Repositories directly into Services

Benefits:

- Less Port and Adapter code.

Drawbacks:

- Application depends directly on JPA.
- Domain and JPA Entity boundaries collapse.
- The external boundary defined by Hexagonal Architecture disappears.

## Decision

Application Output Ports are named by purpose and capability. Persistence reads use `Load<Aggregate>Port`; persistence creation and updates use `Save<Aggregate>Port`; deletion uses `Delete<Aggregate>Port` only when it is a separate Application conversation. A single Persistence Adapter may implement several Ports.

The user persistence boundary uses `LoadUserPort` and `SaveUserPort`; `UserPersistenceAdapter` implements both while each Service injects only the capability it needs.

External conversations where Load/Save is unnatural retain purpose-specific names such as `SmsSenderPort`, `AuthTokenPort`, and `CurrentActorPort`.

Input Ports represent individual user goals as `<Verb><Target>UseCase`. A cohesive Application Service may implement several related UseCases.

Inbound HTTP contracts and Application contracts remain separate. Web Adapters use Request and Response DTOs. Structured Application input records are named `<Action>Command`, and structured Application output records are named `<Subject>Result`; simple identifiers remain UseCase method parameters. The boundary flow is `Web Request DTO -> Command -> UseCase/Service -> Result -> Web Response DTO`. Application code does not depend on Adapter DTOs, and Controllers do not expose Application Results directly.

Business Policies live under `application.<domain>.policy` and are called only by Application Services. Policies do not depend on Infra Properties, Config, Adapters, JPA, Redis, or HTTP. Services load external facts through Output Ports and pass plain Domain/Application values to Policies.

A stateless Policy may be an Application `@Component`. Infra does not construct or interpret Policies. The current `AuthPolicyConfig` is a transitional exception and must not be copied into new features.

## Consequences

### Positive

- Service dependencies become capability-specific.
- Application Ports and Spring Data Repositories have distinct meanings.
- Read and write impact surfaces become explicit.
- Policy ownership and inputs are clear.
- A technology Adapter can still be reused for multiple Ports.
- Infra remains focused on framework and external technology configuration.

### Negative

- The number of Port interfaces increases.
- Existing Auth Policy construction requires a separate architectural refactor.
- UseCase-to-Service relationships need consistent naming.
- Matching Result and Web Response DTO fields require an explicit mapping at the HTTP boundary.

### Follow-up

- Create `LoadUserPort` with `findById` and `findByIdentityKey`.
- Create `SaveUserPort` with `save` and `update`.
- Let `UserPersistenceAdapter` implement both Ports.
- Inject only required Ports into each existing Service.
- Use `LoadGroupPort`, `SaveGroupPort`, `LoadGroupMemberPort`, and `SaveGroupMemberPort` for group management.
- Use Command and Result records for group Application contracts and Request/Response DTOs for its Web contracts.
- Use Command and Result records for authentication Application contracts and Request/Response DTOs for its Web contracts.
- Implement `GroupMemberExitPolicy` inside Application.
- Add a settlement-state Output Port only when the settlement domain has a real caller and query.
- Refactor `AuthPolicyConfig` separately; do not include it in an unrelated domain feature by default.

## Impact

| Area | Impact |
|---|---|
| Architecture | Clarifies Input Port, Output Port, Service, Policy, Adapter, and Infra responsibilities |
| API | No public HTTP contract change |
| Data | No database schema change |
| Security | Current-user access continues through `CurrentActorPort` |
| Operations | No deployment or environment-variable change |
| Developer workflow | Feature Plans must record purpose-oriented Ports and Application Policy inputs |

## Verification

- [x] `UserRepositoryPort` is replaced by `LoadUserPort` and `SaveUserPort` on the current branch.
- [x] Each current Service injects only the capabilities it uses.
- [x] Persistence Adapters preserve Domain/JPA Entity separation.
- [x] Policies have no dependencies on Infra, Adapters, JPA, Redis, or HTTP.
- [x] New Infra Config does not construct Application Policies.
- [x] Feature Plans and architecture documentation use the same naming rules.
- [ ] Group Web DTOs map to Application Commands and Results explicitly after the group feature is merged.
- [ ] Authentication Web DTOs map to Application Commands and Results explicitly on the current branch.
- [x] The architecture documentation matches the implemented user Port boundary.

## References

- [Architecture](../architecture.md)
- [Hexagonal coding guidelines Feature Plan](../features/active/hexagonal-coding-guidelines.md)
