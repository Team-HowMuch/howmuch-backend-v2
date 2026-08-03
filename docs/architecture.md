# HowMuch Backend V2 Architecture

## Purpose

HowMuch Backend V2 uses a domain-oriented hexagonal architecture. Its primary goal is to separate business rules from external technologies such as HTTP, JPA, Redis, and JWT while keeping every feature contract and implementation location predictable.

This document is the structural source of truth for the project. Record feature-specific design and progress in a Feature Plan. Record decisions with durable impact across multiple features in an ADR.

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

The current dependency from `AuthStatusCode` to Spring `HttpStatus` is an explicitly accepted exception. Adding any other Spring dependency to Domain requires a separate architecture decision.

### `application`

- Express one user goal as an Input Port, or UseCase.
- Implement each UseCase in an Application Service that coordinates Domain behavior and Output Ports.
- Own transaction boundaries, current-user lookup, and external persistence or notification order without duplicating business invariants.
- Do not depend on `adapter` or `infra`.
- Spring `@Service`, `@Transactional`, and direct `PasswordEncoder` injection are accepted in this project.

Application Request and Response types are plain Java records. Do not add Jakarta Bean Validation, Servlet, HTTP status, or Swagger annotations to them.

Split UseCases by feature.

```java
public interface CompleteAuthUseCase {
    TokenResponse complete(CompleteAuthRequest request);
}
```

When a business Policy does not need interchangeable implementations, use a concrete type under `application.<domain>.policy` instead of an interface. An `infra` Config creates the Policy Bean from Properties.

### `application.port.out`

- Describe conversations outside the Application, such as database access, caching, message delivery, token creation, and current-user access.
- Name methods after Application intent, not JPA, Redis, or JWT implementation details.
- Create a Port only when there is a real caller and an external boundary.
- Do not create an empty interface for a future implementation or to wrap one internal calculation.

### `adapter.in`

- Validate HTTP requests and convert them into Application Requests.
- Let Controllers call UseCases and wrap Application Responses in the shared `ApiResponse`.
- Place Web Request DTOs under `adapter.in.web.<domain>.dto.request` and apply Jakarta Bean Validation there.
- Allow only simple mapping such as `toRequest()` in Web DTOs. Do not put business rules there.
- Do not access Repositories, JPA Entities, Redis, or `SecurityContext` directly from Controllers.
- Keep Swagger descriptions in the current `*Docs` interface pattern.

The current response contract lives under `application.port.in.<domain>.response`. Add a Web Response DTO only when the HTTP-specific shape differs from the Application Response.

### `adapter.out`

- Implement Output Ports with external technologies.
- Let `adapter.out.persistence` own JPA Entities, Spring Data Repositories, Mappers, and Persistence Adapters.
- Let `adapter.out.redis` store and restore Domain state and apply TTL. Application or Domain code decides attempt limits and expiration policy.
- Keep the JWT implementation of `AuthTokenPort` at `adapter.out.security.jwt.JwtTokenProvider`.
- Keep external SMS delivery implementations under `adapter.out.sms`.
- Adapters may receive technology settings from `infra` Properties, but they must not decide configuration policy.

Keep JPA Entities separate from Domain models and convert them through Mappers. Entities with creation and modification timestamps extend `BaseTimeJpaEntity`.

### `infra`

- Own Spring Configuration, `@ConfigurationProperties`, Security Filters, Swagger, JPA Auditing, and other runtime setup.
- Let Properties read external settings and Config classes assemble Application Policies and technology Beans.
- Do not implement business rules or UseCase flows.
- Development-only Beans and endpoints must not be created outside the `dev` profile.

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

Keep exactly these two external fields in `TokenResponse`:

```java
public record TokenResponse(
        String accessToken,
        String requestToken
) {}
```

## Configuration and Policy

- Keep all Properties and Config classes under `infra`.
- Properties only transport values. Do not create a Policy interface that only exposes Properties through getters.
- Keep UseCase-level policies such as format validation, normalization, and attempt limits under `application.<domain>.policy`.
- Keep Entity invariants and state transitions under `domain`.
- Do not create new Properties or Config when a value is fixed and has no real need to vary.

## Time handling

- Use UTC `LocalDateTime` for storage and internal server calculations.
- Create the current time with `LocalDateTime.now(ZoneOffset.UTC)`.
- Convert library-specific types such as JWT `Date` at the Adapter boundary.
- If a request contains timezone-aware input, normalize it to UTC `LocalDateTime` in the Web Adapter.

## Data and sensitive information

- Never store the raw first seven digits of a resident registration number.
- Generate identity keys and phone identifiers with the configured HMAC secret.
- Hash PINs with Spring `PasswordEncoder`. Never store or log the raw value.
- Never include `.env`, database passwords, JWT secrets, Discord webhooks, or tokens in documentation, logs, or commits.
- Keep `ddl-auto=update` for local development, `validate` as the default, and Flyway disabled for now.

## Canonical implementations

- Web DTO conversion: `adapter/in/web/auth/dto/request/CompleteAuthRequest.java`
- Input Port: `application/port/in/auth/CompleteAuthUseCase.java`
- Application orchestration: `application/auth/service/CompleteAuthService.java`
- Current user access: `application/user/service/GetMyProfileService.java`
- Persistence Adapter: `adapter/out/persistence/user/UserPersistenceAdapter.java`
- JWT Adapter: `adapter/out/security/jwt/JwtTokenProvider.java`
- Configuration wiring: `infra/auth/config/AuthPolicyConfig.java`

When a canonical implementation differs from this document, first identify the intended change and its impact. If the new behavior is a durable decision, create an ADR and update the documentation and code together.

## References

- [Hexagonal Architecture, Alistair Cockburn](https://alistair.cockburn.us/hexagonal-architecture)
- [Domain analysis, Microsoft Azure Architecture Center](https://learn.microsoft.com/en-nz/azure/architecture/microservices/model/domain-analysis)
- [Harness engineering, OpenAI](https://openai.com/index/harness-engineering/)
