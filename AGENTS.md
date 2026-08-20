# HowMuch Backend V2 Agent Guide

## Purpose and precedence

This file is the entry point for AI coding agents working in this repository. It is a map to the relevant sources of truth, not an encyclopedia of every project rule.

Apply instructions in this order:

1. The user's current explicit request
2. The nearest `AGENTS.md` to the files being changed
3. This root `AGENTS.md`
4. Detailed repository documentation and the current code

When documentation and code disagree, do not guess. Inspect the actual code and configuration. If the documentation is stale, update it within the requested scope.

## Documentation map

- [Architecture](docs/architecture.md): package responsibilities, dependency direction, and layer-specific rules
- [Coding guidelines](docs/coding-guidelines.md): default component shapes, mapping ownership, and review checklist
- [Development workflow](docs/development-workflow.md): feature analysis, implementation, verification, and commit boundaries
- [Feature Plan template](docs/templates/feature-plan.md): integrated design and execution plan for non-trivial work
- [ADR template](docs/templates/adr.md): decisions that have durable impact across multiple features
- [Purpose-oriented Ports ADR](docs/adr/0001-purpose-oriented-ports.md): historical Load/Save Port separation and Application-owned Policy rules
- [Framework-neutral StatusCode ADR](docs/adr/0002-framework-neutral-status-code.md): numeric HTTP status contract without Domain framework dependencies
- [Capability-oriented Output Ports ADR](docs/adr/0003-capability-oriented-output-ports.md): current Output Port naming and cohesion rules

Create a Feature Plan before adding an endpoint, UseCase, domain, storage structure, external integration, security behavior, or change spanning multiple packages. Skip it for typos, isolated one-file changes, and small cleanups that do not change behavior.

## Documentation language

- Write system documents in English: `AGENTS.md`, architecture, development workflow, templates, ADRs, and repository-wide operating rules.
- Write development review documents in Korean: active Feature Plans, implementation review notes, QA notes, and handoff documents intended for the team to review.
- Keep code identifiers, API paths, JSON fields, commands, and error codes verbatim.

## Project baseline

- Java 21
- Spring Boot 4.1
- Gradle Wrapper
- PostgreSQL and Spring Data JPA
- Redis
- Spring Security and JWT
- Springdoc OpenAPI
- Default profile: `local`
- Flyway: enabled; Hibernate validates the schema after migrations
- Internal storage and calculation time: UTC `LocalDateTime`

Run commands from the repository root.

```bash
./gradlew compileJava
./gradlew test
./gradlew bootRun
```

Check the database, Redis, and environment prerequisites before running `test` or `bootRun`. Never report an environment-dependent command as successful when it failed or was not run.

## Starting a task

1. Run `pwd` and `git status --short --untracked-files=all` to confirm the location and existing changes.
2. Read this file and the detailed documents relevant to the task.
3. Use `rg` and `rg --files` to find targets, callers, and same-role implementations.
4. Trace the relevant flow from the inbound request to every external interaction.
5. For non-trivial work, create or update `docs/features/active/<feature>.md`.
6. Define the impact surface and verification method before implementation.

Treat existing changes as user-owned work. Do not modify or clean unrelated files.

## Core architecture

```text
external -> adapter.in -> application -> domain
                         -> output port <- adapter.out <- external

infra: framework configuration, Properties, and Bean wiring
common: shared response and exception contracts
```

- `domain` owns business state and invariants. It does not depend on `application`, `adapter`, or `infra`.
- `application` orchestrates UseCases and expresses required external conversations through Output Ports. It does not depend on `adapter` or `infra`.
- `adapter.in` converts HTTP and other external input into Application contracts.
- `adapter.out` implements Output Ports with JPA, Redis, JWT, or external APIs.
- `infra` owns Properties, Config, Security Filters, and Bean wiring.
- Do not create an interface without an external boundary or a real need for interchangeable implementations.

Follow [Architecture](docs/architecture.md) for detailed rules and documented exceptions.

## Implementation rules by component

### Input Port and UseCase

- Place them under `application.port.in.<domain>`.
- Name each user goal `<Verb><Target>UseCase`.
- When a UseCase needs a structured input record, place it under `application.port.in.<domain>.command` and name it `<Action>Command`; keep simple identifiers as method parameters.
- Place structured output values under `application.port.in.<domain>.result` and name them `<Subject>Result`.
- Apply Command/Result naming to new or modified contracts. Existing `request`/`response` contracts are transition points, not examples to copy.
- Keep Commands and Results as plain Java records.
- Do not include Jakarta Validation, Servlet, HTTP status, Swagger, JPA, or Redis types.
- One Application Service may implement several cohesive UseCases; do not combine every endpoint in a domain into one broad UseCase.

### Application Service

- Place it under `application.<domain>.service`.
- Implement UseCases and coordinate current-actor lookup, transaction boundaries, Domain behavior, Policy decisions, Output Port calls, and response assembly.
- Do not depend on Web DTOs, `SecurityContextHolder`, `JwtPrincipal`, JPA Repositories/Entities, or Redis clients.
- Keep Entity-only invariants in Domain. Pass facts spanning multiple entities or domains to an Application Policy.
- `@Service`, `@Transactional`, and direct Spring `PasswordEncoder` injection are accepted.

### Application Policy

- Place it under `application.<domain>.policy`.
- A Policy is Application-owned and is used only by Application Services.
- Use a concrete class or record unless interchangeable implementations are real.
- Do not place Policy creation, Policy rules, or Policy interpretation in `infra`.
- A Policy must not access Adapters, JPA, Redis, HTTP, or `infra` Properties.
- The Service loads external facts through Output Ports and passes plain Domain/Application values to the Policy.
- A stateless Policy may be an Application `@Component`; do not create an Infra Config solely to construct it.
- This repository currently contains `infra/auth/config/AuthPolicyConfig.java`; treat it as transitional code, not the pattern for new Policies.

Group-member exit example:

```text
GroupMemberService
  -> LoadGroupMemberPort
  -> GroupMemberExitPolicy.validate(member)
  -> GroupMember.leave/remove
  -> SaveGroupMemberPort

When settlement is implemented:
  -> LoadGroupMemberSettlementStatePort
  -> GroupMemberExitPolicy.validate(member, settlementState)
```

Do not create the future settlement Port or a no-op Adapter until a real settlement caller and query exist. Self-leave and manager removal must use the same exit Policy so removal cannot bypass outstanding obligations.

### Output Port

- Place it under `application.port.out.<domain>`.
- Name it after the cohesive capability the Application requires from an external system.
- Use `Load<Aggregate>Port` and `Save<Aggregate>Port` when general aggregate retrieval and persistence are the actual capabilities; they are options, not mandatory pairs.
- Use capability names such as `Store`, `Sender`, `Publisher`, `Authorizer`, `Token`, `CurrentActor`, or `Delete` when they express the conversation more accurately.
- Do not combine reads and writes into a broad `<Aggregate>RepositoryPort`.
- Use Domain or Application types and do not expose JPA-specific operations.
- Create a Port only for a real external boundary with a caller.
- Split a Port when callers need materially different capabilities; keep cohesive operations together instead of creating one interface per method.

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

`UserPersistenceAdapter` may implement both Ports while each Service injects only the capability it uses. The current `UserRepositoryPort` and `PaymentConnectionRepositoryPort` are transition points; do not introduce another broad Repository Port or refactor these outside an approved feature scope.

### Inbound Adapter

- Place HTTP adapters under `adapter.in.web.<domain>`.
- Keep HTTP request and response DTOs under `adapter.in.web.<domain>.dto.request` and `.response`.
- Use the explicit flow `Web Request DTO -> Command -> UseCase/Service -> Result -> Web Response DTO`.
- Validate Web Request DTOs, map them with `toCommand()`, invoke UseCases, map Results with `Response.from(result)`, wrap responses in `ApiResponse`, and document them through the current `*Docs` pattern.
- Do not implement business rules or access Repositories, JPA Entities, Redis, or `SecurityContextHolder`.
- Authenticated Application Services obtain the actor through `CurrentActorPort`; new Controllers do not interpret `JwtPrincipal`.

### Outbound Adapter

- Implement Output Ports with external technology under `adapter.out`.
- One Adapter may implement multiple Ports for the same technology boundary, for example `UserPersistenceAdapter implements LoadUserPort, SaveUserPort`.
- Persistence Adapters own JPA Entities, Spring Data Repositories, and Domain/Entity mapping.
- Keep each Persistence Adapter directly under `adapter.out.persistence.<domain>`.
- Place JPA Entities under `adapter.out.persistence.<domain>.entity`, Mappers under `.mapper`, and Spring Data Repositories under `.repository` for new or structurally modified persistence domains.
- Existing `user`, `auth`, and `payment` technical types that remain directly under the domain persistence package are transition points; move them only in a dedicated refactor.
- Adapters translate technology interactions and errors but do not decide business Policy.
- Redis Adapters own serialization/key/TTL mechanics, JWT Adapters own token mechanics, and SMS Adapters own message delivery.

### Domain

- Own Entity state, value invariants, and state transitions.
- Do not depend on Application, Adapters, Infra, JPA, HTTP, or Redis models.
- Do not add Aggregates, Domain Services, or Factories to simple CRUD without a real invariant requiring them.

### Infra

- Own framework and technical setup only: Security configuration and filters, Swagger, JPA auditing, profiles, external-client configuration, and technology Bean wiring.
- Properties transport technical configuration to Infra or Outbound Adapters.
- Infra must not own, construct, or interpret Application Policies or business rules.
- Do not copy the current `AuthPolicyConfig` pattern into new features; refactor it separately when authentication architecture is addressed.

## Feature implementation order

1. Define the user problem, reason, success criteria, and exclusions.
2. Decide whether to reuse an existing domain or introduce a new one.
3. Define user-goal UseCases and Application Command/Result contracts first.
4. Define only the purpose-oriented Output Ports required for real external interactions.
5. Implement Domain state and Entity-local invariants.
6. Implement Application Policies for decisions combining multiple facts.
7. Orchestrate Domain, Policies, and Ports in Application Services.
8. Implement Output Adapters.
9. Implement Web Request/Response DTOs, Controllers, and Swagger documentation.
10. Add Infra configuration only for framework or technical setup.
11. Revalidate the impact surface and completion criteria.

Do not finish with an unused Port. Keep unrelated refactoring separate from feature behavior.

## Safety and sensitive data

- Never print or commit `.env` contents, secrets, tokens, passwords, or resident registration numbers.
- Use the first seven digits of the resident registration number only for validation and identity generation. Never store the raw value.
- Never store a raw PIN. Use Spring `PasswordEncoder`.
- Do not weaken authentication or authorization boundaries, or expose development-only endpoints outside the development profile.
- Do not delete, force checkout/reset, or move large file sets without an explicit request.

## Verification

- Java change: at minimum, run `./gradlew compileJava`.
- Configuration or runtime change: verify required services and environment variables, then run the relevant profile.
- Documentation change: validate every relative link and referenced local path.
- Every change: run `git diff --check` and `git status --short --untracked-files=all`.

Do not create new test cases under the current project instruction. Run existing tests only when they are relevant and their prerequisites are available. Report why a test was not run.

## Commits

Do not commit, push, or open a pull request unless the user explicitly requests it.

Use this commit title format:

```text
<type>(<domain>): <description in Korean>
```

Allowed types are `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `ci`, and `chore`. Use a domain or change area such as `auth`, `user`, `security`, or `docs` as the scope.

Each commit must express one intent, be independently understandable, and compile at minimum. Record expected commit boundaries in the Feature Plan before implementation. Do not use an unused, Port-only state as a commit boundary.

## Completion report

Report only what is relevant and actually performed:

- Changed behavior and documentation
- Verification commands and results
- Verification not performed and why
- Remaining risks or follow-up decisions

Never claim an unsuccessful command passed, and do not perform unrequested follow-up work.
