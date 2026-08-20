# HowMuch Backend V2 Coding Guidelines

## Purpose

This is the practical starting point for adding or changing a component. [Architecture](architecture.md) owns dependency direction, [Development Workflow](development-workflow.md) owns delivery steps, and ADRs own durable decisions. This guide answers where a component belongs, what it may know, and what its smallest useful shape is.

Apply these shapes to new or modified components. Existing Application `request`/`response` records, broad Repository Ports, Domain `HttpStatus` imports, and flat persistence technical packages are explicit transition points; move them only in an approved feature or refactor scope.

## Default flow

```text
HTTP
  Web Request.toCommand()
    -> UseCase
      -> Application Service
        -> Domain / Policy
        -> Output Port
          <- Output Adapter / external technology
      <- Application Result.from(domain source)
  Web Response.from(result)
```

Dependencies point inward:

```text
adapter.in -> application -> domain
adapter.out -> application -> domain
infra      -> framework wiring and technical components
```

Do not skip a boundary because two records currently have identical fields. Do not add a boundary, interface, Mapper, or factory when it has no real responsibility.

## Component guide

### Domain Entity

**Location:** `domain.<domain>`

Use a Domain Entity when identity, mutable state, or business transitions matter.

- Own business state, Entity-local invariants, and state transitions.
- Expose intention-revealing methods such as `close`, `transferOwnership`, `leave`, or `verify`, not public setters.
- Validate states that must be true regardless of the caller.
- Accept facts as parameters when the Entity cannot know them itself.
- Depend only on Domain and JDK types.
- Do not import Spring, JPA, Web DTOs, Application contracts, Redis, or external SDKs.
- Do not add an Aggregate, Domain Service, Factory, or Value Object unless a real invariant needs it.

```java
public Group close(LocalDateTime now) {
    requireActive();
    return new Group(id, name, description, imageUrl, ownerId, inviteCode,
            GroupStatus.CLOSED, creatorId, createdAt, now);
}
```

### JPA Entity

**Location:** `adapter.out.persistence.<domain>.entity`

A JPA Entity is a persistence model, not the Domain model.

- Own table, column, relationship, index, and persistence lifecycle mapping.
- Extend `BaseTimeJpaEntity` when creation and modification timestamps are persisted.
- Keep business decisions in Domain or Application Policy.
- Do not return JPA Entities through Output Ports.
- Prefer explicit Domain/JPA mapping over leaking persistence annotations into Domain.

### Application Service

**Location:** `application.<domain>.service`

- Implement one or more cohesive UseCases.
- Own transaction boundaries, current-actor lookup, orchestration order, external fact loading, Policy invocation, Domain calls, persistence calls, and Result assembly.
- Use `@Transactional(readOnly = true)` for read-only database flows and `@Transactional` for writes.
- Depend on Input/Output Ports, Domain types, and Application Policies.
- Do not depend on Controllers, Web DTOs, `SecurityContextHolder`, `JwtPrincipal`, JPA Repositories/Entities, Redis clients, or Infra Config.
- Keep reusable Entity-local rules in Domain; do not duplicate them across Services.
- A private helper is appropriate for local orchestration. Move it only when another real caller appears or it represents a Domain/Policy rule.

```java
@Service
@RequiredArgsConstructor
public class GetMyProfileService implements GetMyProfileUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;

    @Transactional(readOnly = true)
    public MyProfileResult getMyProfile() {
        User user = loadUserPort.findById(currentActorPort.getCurrentActor().userId())
                .orElseThrow(/* application error */);
        return MyProfileResult.from(user);
    }
}
```

### Application Policy

**Location:** `application.<domain>.policy`

Use a Policy when a decision combines facts that one Entity cannot own.

- Keep it concrete unless interchangeable implementations are real.
- Receive plain Domain/Application values from a Service.
- Return a decision or throw the shared Application exception for a rejected action.
- Do not query Ports, Adapters, JPA, Redis, HTTP, or Infra Properties directly.
- A stateless Policy may be an Application `@Component`.
- Do not create Infra Config only to construct a fixed Policy.

If only one Entity's state is needed, put the invariant on that Entity instead of creating a Policy.

### Web Request DTO

**Location:** `adapter.in.web.<domain>.dto.request`

- Represent the external HTTP request contract.
- Own Jakarta Validation and Swagger field annotations.
- Convert to an Application Command with `toCommand()`.
- Perform only transport normalization such as trimming or shape conversion; do not decide business policy.
- Do not pass the Request into Application code.

```java
public record CreateGroupRequest(
        @NotBlank String name,
        String description
) {
    public CreateGroupCommand toCommand() {
        return new CreateGroupCommand(name, description);
    }
}
```

### Application Command

**Location:** `application.port.in.<domain>.command`

- Use a plain record for structured UseCase input.
- Keep a simple identifier as a UseCase parameter instead of wrapping it automatically.
- Use Domain/Application/JDK types only.
- Do not import a Web Request or provide `from(Request)`: that reverses the dependency direction.
- Do not repeat Jakarta Validation or Swagger annotations from the Web boundary.
- Add a compact constructor or named factory only for an Application-level invariant or meaningful normalization, not to wrap the canonical constructor.

```java
public record CreateGroupCommand(String name, String description) {}
```

### Application Result

**Location:** `application.port.in.<domain>.result`

- Use a plain record for structured UseCase output.
- Keep HTTP status, headers, Swagger, and Web DTOs out.
- Use `from(Source)` when the Result is a simple projection of one Domain/Application source.
- Use `of(...)` only when it names a meaningful assembly from multiple values.
- Use the canonical constructor when a factory would only rename `new` without centralizing mapping or meaning.
- Never return a JPA Entity or external SDK type.

```java
public record GroupSummaryResult(Long id, String name) {
    public static GroupSummaryResult from(Group group) {
        return new GroupSummaryResult(group.id(), group.name());
    }
}
```

### Web Response DTO

**Location:** `adapter.in.web.<domain>.dto.response`

- Represent only the public HTTP response contract.
- Convert an Application Result with `from(Result)`.
- Keep this DTO even when its fields match the Result; the HTTP contract and Application contract change for different reasons.
- Add serialization or Swagger annotations here, not to the Result.
- Never expose JPA Entities, Domain Entities, or Application Results directly from a Controller.

```java
public record GroupSummaryResponse(Long id, String name) {
    public static GroupSummaryResponse from(GroupSummaryResult result) {
        return new GroupSummaryResponse(result.id(), result.name());
    }
}
```

### UseCase / Input Port

**Location:** `application.port.in.<domain>`

- Name one user goal `<Verb><Target>UseCase`.
- Accept simple identifiers or an Application Command.
- Return `void`, a simple Application value, or an Application Result.
- Keep HTTP concepts and Adapter DTOs out.
- One Service may implement several UseCases when they form a cohesive orchestration unit.
- Do not combine all endpoints for a domain into one broad UseCase.

```java
public interface GetMyGroupsUseCase {
    List<GroupSummaryResult> getMyGroups();
}
```

### Output Port

**Location:** `application.port.out.<domain>`

An Output Port describes a cohesive capability the Application requires from an external system.

- Create it only when there is a real caller and external boundary.
- Name it after Application intent, not the implementation technology.
- Exchange Domain/Application types; do not expose JPA, Redis, HTTP-client, or SDK types.
- Put methods together when they form one cohesive conversation for the same callers.
- Split when callers need materially different capabilities or the operations change for different reasons.
- Do not create an interface only because one implementation might someday be replaced.
- Do not mirror every Spring Data Repository method mechanically.

Choose names by capability:

| Situation | Suitable shape | Example |
|---|---|---|
| General aggregate retrieval is the actual need | `Load<Aggregate>Port` | `LoadGroupPort` |
| General aggregate persistence is the actual need | `Save<Aggregate>Port` | `SaveUserPort` |
| Temporary state is read, written, and expired as one capability | `<Subject>StorePort` | `AuthChallengeStorePort` |
| An external message is sent | `<Channel>SenderPort` | `SmsSenderPort` |
| An event is published | `<Subject>PublisherPort` | `SettlementPublisherPort` |
| An external decision is requested | `<Subject>AuthorizerPort` or domain term | `PaymentAuthorizerPort` |
| A token capability includes issue and parse operations | `<Subject>TokenPort` | `AuthTokenPort` |
| The authenticated principal is requested | `CurrentActorPort` | `CurrentActorPort` |
| Deletion is a distinct Application conversation | `Delete<Subject>Port` | `DeleteStoredFilePort` |

`Load` and `Save` are valid options, not mandatory pairs. Do not bulk-rename an existing clear Port merely to use a more novel verb.

### Controller / Inbound Adapter

**Location:** `adapter.in.web.<domain>`

- Accept and validate Web Request DTOs.
- Call `request.toCommand()`, invoke a UseCase, map with `Response.from(result)`, and wrap with `ApiResponse`.
- Keep the Controller thin: routing, transport mapping, and response status only.
- Depend on Input Ports, not concrete Services.
- Do not access Repositories, Output Adapters, JPA Entities, Redis, or `SecurityContextHolder`.
- Let authenticated Services obtain the actor through `CurrentActorPort`.

```java
@PostMapping
public ApiResponse<GroupResponse> create(@Valid @RequestBody CreateGroupRequest request) {
    return ApiResponse.success(GroupResponse.from(createGroupUseCase.create(request.toCommand())));
}
```

### Swagger Docs interface

**Location:** `adapter.in.web.<domain>.docs`

- Follow the existing `<Domain>Docs` interface pattern.
- Keep endpoint summary, description, security requirements, parameter details, and response documentation here.
- Match the Controller method signature using Web Request/Response DTOs.
- Document observable behavior and errors, not internal Service or database mechanics.
- Update Docs when a path, parameter, payload, status, authentication requirement, or error contract changes.

### Output Adapter

**Location:** `adapter.out.<technology-or-purpose>`

- Implement one or more cohesive Output Ports for the same external boundary.
- Translate Domain/Application types to technology types and back.
- Own technology mechanics: JPA queries, Redis keys and TTL, JWT libraries, SMS clients, HTTP clients, and serialization.
- Translate technology failures into the shared Application exception contract while preserving the cause.
- Consume technical Properties where required.
- Do not decide business Policy or orchestrate a UseCase.

One Adapter may implement multiple Ports. One Port may also have multiple Adapters when real runtime alternatives exist.

### Persistence Mapper

**Location:** `adapter.out.persistence.<domain>.mapper`

- Convert only between Domain and persistence models.
- Use explicit `toDomain` and `toEntity` methods.
- Keep business decisions, Port calls, and repository access out.
- Reuse an existing Mapper before adding another.
- Use a simple static utility when mapping is stateless; do not create a Mapper interface or dependency-injection component without a real reason.
- Preserve IDs, status, ownership, and timestamps explicitly.

### Infra

**Location:** `infra.<concern>`

Infra owns framework and technical setup:

- Spring Security configuration and filters
- Swagger configuration
- JPA auditing
- profiles and `@ConfigurationProperties`
- external-client and technology Bean wiring
- transaction/AOP configuration that applies technical context

Infra must not own business rules, construct or interpret Application Policies, or expose its Properties to Domain. Add Config only when framework wiring is actually required; annotations and component scanning are enough for ordinary Services, Policies, and Adapters.

## Mapping ownership summary

| Source | Target | Owner | Default method |
|---|---|---|---|
| Web Request | Application Command | Web Request | `toCommand()` |
| Domain/Application source | Application Result | Application Result | `from(source)` when useful |
| Multiple meaningful values | Application Result | Application Result | `of(...)` only when it adds meaning |
| Application Result | Web Response | Web Response | `from(result)` |
| JPA Entity | Domain Entity | Persistence Mapper | `toDomain(entity)` |
| Domain Entity | JPA Entity | Persistence Mapper | `toEntity(domain)` |

There is intentionally no `Command.from(Request)` and no default factory that only wraps a record constructor.

## Review checklist

- [ ] The package matches the component responsibility.
- [ ] Dependencies point inward and no Web/JPA/Redis/SDK type crossed into Application or Domain.
- [ ] Request, Command, Result, and Response mappings follow the ownership table.
- [ ] Business invariants are in Domain; multi-fact decisions are in Application Policy.
- [ ] Services orchestrate; Controllers and Adapters translate.
- [ ] Every Output Port has a real caller and external boundary.
- [ ] Port names describe cohesive capabilities; Load/Save was chosen only when it is natural.
- [ ] No speculative interface, Config, Mapper, Factory, or future Port was added.
- [ ] Public API and Swagger contracts remain aligned.
- [ ] The Feature Plan, compilation, and repository verification required by the workflow are complete.

## References

- [Architecture](architecture.md)
- [Development Workflow](development-workflow.md)
- [ADR-0001: Purpose-oriented Ports and Application-owned Policies](adr/0001-purpose-oriented-ports.md)
- [ADR-0003: Capability-oriented Output Port naming](adr/0003-capability-oriented-output-ports.md)
- [Alistair Cockburn, Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture)
- [AWS Prescriptive Guidance, Hexagonal Architecture Pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/hexagonal-architecture.html)
- [AWS Prescriptive Guidance, Building Hexagonal Architectures](https://docs.aws.amazon.com/prescriptive-guidance/latest/hexagonal-architectures/overview.html)
