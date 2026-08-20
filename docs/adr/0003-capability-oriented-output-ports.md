# ADR-0003: Capability-oriented Output Port naming

- Status: Accepted
- Date: 2026-08-13
- Decision owners: HowMuch Backend
- Related Feature Plan: [Hexagonal coding guidelines](../features/active/hexagonal-coding-guidelines.md)
- Supersedes: The mandatory Load/Save naming rule in [ADR-0001](0001-purpose-oriented-ports.md)
- Superseded by:

## Context

ADR-0001 correctly moved persistence access away from broad Repository Ports, but its wording made `Load<Aggregate>Port` and `Save<Aggregate>Port` look mandatory for every persistence boundary. That can make naming follow CRUD mechanics instead of the capability the Application actually requires.

Hexagonal architecture requires Ports to express semantically meaningful conversations at the Application boundary. It does not prescribe Load/Save pairs or one Port per CRUD category.

## Decision Drivers

- Output Ports must describe Application needs without exposing technology.
- Port boundaries must remain cohesive and understandable at call sites.
- Existing useful Load/Save Ports must not be renamed without a concrete benefit.
- New interfaces must have a real caller and external boundary.
- Naming rules must not create mechanical interface proliferation.

## Options

### Option 1: Require a Load/Save pair for every persisted aggregate

- Benefits: predictable names and read/write separation.
- Drawbacks: turns an example into a framework, obscures narrow business capabilities, and can create interfaces that are split only by CRUD verb.
- Risks: Services depend on storage-shaped contracts rather than intent-shaped contracts.

### Option 2: Name every Port after the narrowest individual method

- Benefits: very explicit dependencies.
- Drawbacks: produces many single-method interfaces even when operations form one cohesive external conversation.
- Risks: interface churn and fragmented Adapters without architectural value.

### Option 3: Name Ports after cohesive Application capabilities

- Benefits: keeps the Application vocabulary primary and allows Load/Save, Store, Sender, Publisher, Authorizer, CurrentActor, and other suitable shapes.
- Drawbacks: requires judgment instead of one mechanical naming formula.
- Risks: Ports can become broad if cohesion is not reviewed from the caller's perspective.

## Decision

Use option 3. Name an Output Port after the cohesive capability the Application requires from an external system. `Load<Aggregate>Port` and `Save<Aggregate>Port` remain valid when general aggregate retrieval and persistence are the real capabilities, but they are optional rather than mandatory. Prefer names such as `SmsSenderPort`, `AuthChallengeStorePort`, `EventPublisherPort`, `PaymentAuthorizerPort`, or `CurrentActorPort` when those names express the conversation more accurately. Do not expose `JpaRepository`, Redis, HTTP-client, SDK, or vendor terminology through the Port.

Split a Port when its callers need materially different capabilities or the operations change for different reasons. Keep it together when the methods form one cohesive external conversation and the same callers use them together. Implementation count alone neither requires nor forbids a Port.

## Consequences

### Positive

- Application dependencies describe intent instead of storage mechanics.
- Existing Load/Save Ports remain valid without forcing the pattern onto unrelated boundaries.
- Interface count follows real boundaries and callers.
- Technology replacement remains isolated in Adapters.

### Negative

- Reviewers must assess cohesion and naming case by case.
- Similar domains may use different Port shapes when their Application needs differ.

### Follow-up

- Use [Coding Guidelines](../coding-guidelines.md) for the selection checklist and examples.
- Do not bulk-rename existing Ports; rename only as part of a change that improves a real caller contract.
- Reconsider a Port when callers inject methods they do not use or when technology terms leak into Application code.

## Impact

| Area | Impact |
|---|---|
| Architecture | Refines Output Port naming from mandatory CRUD categories to cohesive capabilities |
| API | No public HTTP contract change |
| Data | No schema or persistence behavior change |
| Security | Security capabilities such as current-actor lookup remain behind Ports |
| Operations | No deployment change |
| Developer workflow | Feature Plans explain the capability and caller instead of requiring a Load/Save pair |

## Verification

- [x] Architecture and workflow documents describe Load/Save as optional capability names.
- [x] The coding guide includes capability selection examples and dependency rules.
- [x] Existing useful Load/Save Ports remain unchanged.
- [x] `./gradlew compileJava` passes.

## References

- [Alistair Cockburn, Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture)
- [AWS Prescriptive Guidance, Hexagonal Architecture Pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/hexagonal-architecture.html)
- [AWS Prescriptive Guidance, Building Hexagonal Architectures](https://docs.aws.amazon.com/prescriptive-guidance/latest/hexagonal-architectures/overview.html)
