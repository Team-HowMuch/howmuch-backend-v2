# HowMuch Backend V2 Architecture

## Purpose

HowMuch Backend V2 uses a domain-oriented hexagonal architecture. Its primary goal is to separate business rules from external technologies such as HTTP, JPA, Redis, and JWT while keeping every feature contract and implementation location predictable.

This document is the structural source of truth for the project. Record feature-specific design and progress in a Feature Plan. Record decisions with durable impact across multiple features in an ADR.

Related decision:

- [Purpose-oriented Ports and Application-owned Policies](adr/0001-purpose-oriented-ports.md)
- [Framework-neutral StatusCode contract](adr/0002-framework-neutral-status-code.md)
- [Capability-oriented Output Port naming](adr/0003-capability-oriented-output-ports.md)

Use [Coding Guidelines](coding-guidelines.md) for practical component shapes and mapping conventions.

## Structure

```text
                           +--------------------+
HTTP request ------------>| adapter.in.web     |
                           +---------+----------+
                                     |
                                     v
                           +--------------------+
                           | application        |
                           | UseCase / Service  |
                           | Input/Output Port  |
                           +----+----------+----+
                                |          |
                                v          v
                           +---------+  +----------------+
                           | domain  |  | output port    |
                           +---------+  +-------+--------+
                                                ^
                                                |
                           +--------------------+---------+
                           | adapter.out                  |
                           | JPA / Redis / JWT / SMS      |
                           +------------------------------+

infra: Properties, Config, Security Filters, and Bean wiring
common: shared response and exception contracts
```

Dependencies point from the outside toward the inside. Application code does not know external technologies directly. It describes each purposeful external conversation through a Port.

## Package responsibilities

### `domain`

- Own business concepts, state, values, invariants, and state transitions.
- Do not depend on `application`, `adapter`, or `infra`.
- Do not use JPA Entities, HTTP DTOs, or Redis serialization models as Domain models.
- Put domain-meaningful behavior in Domain model methods instead of treating models as passive data containers.
- Do not add unnecessary Aggregates, Domain Services, or Factories to a simple CRUD domain.

The `StatusCode` contract is framework-neutral and stores numeric HTTP status values. Domain status enums must not import Spring `HttpStatus`.

### `application`

- Express one user goal as an Input Port, or UseCase.
- Place Input Ports under `application.port.in.<domain>` and name them `<Verb><Target>UseCase`.
- Place structured input contracts under `application.port.in.<domain>.command` as `<Action>Command` records; keep simple identifiers as UseCase method parameters.
- Place structured output contracts under `application.port.in.<domain>.result` as `<Subject>Result` records.
- Keep Commands and Results free of Jakarta Validation, Servlet, HTTP status, Swagger, JPA, and Redis types.
- Do not name new or modified Application contracts `Request`, `Response`, or `DTO`; those names belong to external Adapter contracts.
- Existing `application.port.in.*.request` and `.response` records are transition points. Do not bulk-rename them outside a scoped contract refactor.
- A cohesive Application Service may implement several related UseCases; do not put every endpoint in one broad domain UseCase.
- Place Services under `application.<domain>.service`.
- Services own transaction boundaries, current-actor lookup, Output Port call order, Policy input loading, Domain invocation, and response assembly.
- Services do not depend on Web DTOs, `SecurityContextHolder`, `JwtPrincipal`, JPA Repositories/Entities, or Redis clients.
- Spring `@Service`, `@Transactional`, and direct `PasswordEncoder` injection are accepted.

Example Input Port:

```java
public interface GetMyGroupsUseCase {
    List<GroupSummaryResult> getMyGroups();
}
```

#### Application Policy

- Place Policies under `application.<domain>.policy`.
- A Policy is Application-owned and used only by Application Services.
- Use a concrete class or record unless interchangeable implementations are real.
- Use a Policy when a decision combines facts from multiple Entities or domains.
- The Service loads external facts through Output Ports and passes plain Domain/Application values to the Policy.
- Policies do not access Adapters, JPA, Redis, HTTP, Infra Properties, or Infra Config.
- Stateless Policies may be Application `@Component` classes. Do not create an Infra Config solely to construct one.
- Entity-local invariants and state transitions remain in Domain.

Group-member exit flow:

```text
GroupMemberService
  1. load the member through LoadGroupMemberPort
  2. call GroupMemberExitPolicy.validate(member)
  3. call GroupMember.leave/remove
  4. persist through SaveGroupMemberPort

After settlement exists:
  2. load settlement state through LoadGroupMemberSettlementStatePort
  3. call GroupMemberExitPolicy.validate(member, settlementState)
```

The same Policy applies to self-leave and manager removal so outstanding obligations cannot be bypassed by removal. Do not create the future settlement Port or a no-op Adapter before a real settlement caller and query exist.

Current transitional exception: `infra/auth/config/AuthPolicyConfig.java` constructs Application Policies from Infra Properties. It describes current code, but it is not the target pattern for new Policies and must be refactored separately when authentication architecture is changed.

### `application.port.out`

Output Ports describe purposeful Application conversations with external systems.

- Place them under `application.port.out.<domain>`.
- Name each Port after the cohesive capability the Application requires.
- Use `Load<Aggregate>Port` and `Save<Aggregate>Port` when general aggregate retrieval and persistence are the actual capabilities; they are options, not mandatory pairs.
- Use names such as `Store`, `Sender`, `Publisher`, `Authorizer`, `Token`, `CurrentActor`, or `Delete` when they describe the conversation more accurately.
- Do not combine reads and writes into a broad `<Aggregate>RepositoryPort`.
- Split a Port when callers need materially different capabilities or operations change for different reasons; keep cohesive operations together.
- Name methods after Application intent, not JPA, Redis, or JWT operations.
- Exchange Domain or Application types, not JPA Entities.
- Create a Port only when it has a real caller and external boundary.
- Do not create empty future Ports.

Target user-port shape:

```java
public interface LoadUserPort {
    Optional<User> findById(Long id);
    Optional<User> findByIdentityKey(String identityKey);
}

public interface SaveUserPort {
    User save(User user);
    User update(User user);
}
```

A single Persistence Adapter may implement both interfaces. Each Service injects only the capability it needs. `Load` and `Save` are examples rather than a required pair. CODEF integration uses `BankConnectionPort`, `ExternalConnectionStorePort`, and `AccountStorePort` to express its actual external conversations.

### `adapter.in`

Inbound Adapters translate external input into Application contracts.

- Place HTTP Controllers, request DTOs, and Swagger interfaces under `adapter.in.web.<domain>`.
- Validate HTTP requests with Jakarta Bean Validation.
- Keep HTTP request DTOs under `dto.request` and HTTP response DTOs under `dto.response`.
- Limit request DTO behavior to normalization and simple `toCommand()` mapping.
- Map every Application Result to a Web Response DTO with `Response.from(result)`, even when their fields currently match.
- Invoke only Input Ports and wrap responses in the shared `ApiResponse`.
- Keep Swagger descriptions in the current `*Docs` interface pattern.
- Do not implement business rules or access Repositories, JPA Entities, Redis, or `SecurityContextHolder`.
- Authenticated Application Services obtain the actor through `CurrentActorPort`; new Controllers do not interpret `JwtPrincipal`.
- Use the explicit boundary flow `Web Request DTO -> Command -> UseCase/Service -> Result -> Web Response DTO`.
- Do not pass Adapter DTOs into Application or return Application Results directly from Controllers.
- Existing Controllers that return Application `response` records directly are transition points; use the explicit mapping for new or modified HTTP contracts.

### `adapter.out`

Outbound Adapters implement Output Ports with external technology.

- Let `adapter.out.persistence` own JPA Entities, Spring Data Repositories, Mappers, and Persistence Adapters.
- One Adapter may implement several Ports for the same external boundary, for example `UserPersistenceAdapter implements LoadUserPort, SaveUserPort`.
- Keep each Persistence Adapter directly under `adapter.out.persistence.<domain>`.
- Place JPA Entities under `adapter.out.persistence.<domain>.entity`.
- Place Domain/JPA Mappers under `adapter.out.persistence.<domain>.mapper`.
- Place Spring Data Repositories under `adapter.out.persistence.<domain>.repository`.
- Apply these subpackages to new or structurally modified persistence domains. Existing direct package placement is a transition point and is not a reason for unrelated file movement.
- Keep JPA Entities separate from Domain models and convert through Mappers.
- Entities with creation and modification timestamps extend `BaseTimeJpaEntity`.
- Redis Adapters own serialization, keys, and TTL mechanics.
- JWT Adapters own token creation, parsing, signature validation, and library-type conversion.
- SMS Adapters own message delivery.
- Security Adapters translate framework authentication state into Application types such as `CurrentActor`.
- Adapters may consume technical settings, but they do not decide business Policies.
- Translate technology failures into the shared Application exception contract while preserving the original cause.

### `infra`

Infra owns framework and technical setup only.

- Spring Security configuration and filters
- Swagger configuration
- JPA auditing
- Profiles
- External-client configuration
- Technical `@ConfigurationProperties`
- Technology Bean wiring

Infra Properties carry technical configuration to Infra components or Outbound Adapters. Infra does not own, construct, or interpret Application Policies or business rules. Do not copy the current `AuthPolicyConfig` pattern into new features.

### `common`

- Own API responses and exception contracts shared across domains.
- Express expected application failures with `ApplicationException` and `StatusCode`, then convert them through `GlobalExceptionHandler`.
- Keep domain-specific status codes in their domain.
- Do not move a domain concept to `common` only because two callers use it.

## Authentication and Security boundaries

`JwtPayload` and `CurrentActorPort` serve different purposes.

- `JwtPayload`: use for JWT creation and parsing, access/request token validation, and rotation logic that operates on tokens.
- `CurrentActorPort`: use in authenticated UseCases such as `/user/me` and logout to obtain the current user ID.
- `JwtAuthenticationFilter`: validate the access token and Redis Session, then place a `JwtPrincipal` in the `SecurityContext`.
- `SpringSecurityAdapter`: read the `SecurityContext` and convert it into `CurrentActor`.

Application Services and Controllers must not depend directly on `SecurityContextHolder` or `JwtPrincipal`. Use `CurrentActorPort` when a UseCase needs the authenticated user.

JWT is a signed authentication proof. Redis Session state is the source of truth for an active login. Logout, single-session enforcement, and request token rotation operate through Redis Session state.

The target auth boundary keeps exactly these two external fields in a Web `TokenResponse`, mapped from an Application `TokenResult`:

```java
public record TokenResult(
        String accessToken,
        String requestToken
) {}

public record TokenResponse(
        String accessToken,
        String requestToken
) {}
```

The current auth Controller still exposes the Application `TokenResponse` directly. Preserve its public fields until a scoped Command/Result boundary refactor replaces the internal contract.

## Configuration and Policy ownership

- Keep framework and adapter Properties/Config under `infra`.
- Properties transport technical values; they do not define or interpret business rules.
- Keep all business Policies under `application.<domain>.policy`.
- Application Services are the only callers of Application Policies.
- Policies receive plain Domain/Application values, never Infra Properties.
- Use Application-owned constants or inputs for current business rules.
- If a future Policy truly requires runtime configuration, record the cross-layer contract in an ADR before adding it.
- Do not add Properties or Config for fixed values.
- Treat `AuthPolicyConfig` and the configurable Auth Policy records as transitional current code, not canonical structure.

## Time handling

- Use UTC `LocalDateTime` for storage and internal server calculations.
- Create the current time with `LocalDateTime.now(ZoneOffset.UTC)`.
- Convert library-specific types such as JWT `Date` at the Adapter boundary.
- If a request contains timezone-aware input, normalize it to UTC `LocalDateTime` in the Web Adapter.

## Data and sensitive information

- SMS OTP는 휴대폰 소유만 확인하며 실명 또는 CI 본인확인으로 취급하지 않는다.
- 신규 인증 요청에서 주민등록번호를 수집하거나 저장하지 않는다.
- Redis 제한 Key에 사용하는 휴대폰 식별자는 설정된 HMAC Secret으로 생성한다.
- Hash PINs with Spring `PasswordEncoder`. Never store or log the raw value.
- Never include `.env`, database passwords, JWT secrets, Discord webhooks, or tokens in documentation, logs, or commits.
- Use Flyway as the only schema-change mechanism and keep Hibernate `ddl-auto=validate` in every profile.
- For an existing database without Flyway history, verify the target first and set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` for the first migration only.

## Canonical implementations

- Current Web DTO conversion: `adapter/in/web/auth/dto/request/LoginRequest.java`
- Current Input Port: `application/port/in/auth/LoginUseCase.java`
- Current Application orchestration: `application/auth/service/LoginService.java`
- Current user access: `application/user/service/GetMyProfileService.java`
- Current Persistence Adapter: `adapter/out/persistence/user/UserPersistenceAdapter.java`
- Current JWT Adapter: `adapter/out/security/jwt/JwtTokenProvider.java`
- Transitional Auth Policy wiring: `infra/auth/config/AuthPolicyConfig.java` (do not copy into new features)

Use [Coding Guidelines](coding-guidelines.md) for target Command/Result and Web Response shapes. When current code differs from the target architecture, record the intended refactor in the active Feature Plan instead of hiding the difference or expanding an unrelated change. Create an ADR for durable cross-feature decisions.

## References

- [Hexagonal Architecture, Alistair Cockburn](https://alistair.cockburn.us/hexagonal-architecture)
- [Domain analysis, Microsoft Azure Architecture Center](https://learn.microsoft.com/en-nz/azure/architecture/microservices/model/domain-analysis)
- [Harness engineering, OpenAI](https://openai.com/index/harness-engineering/)
