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
- [Development workflow](docs/development-workflow.md): feature analysis, implementation, verification, and commit boundaries
- [Feature Plan template](docs/templates/feature-plan.md): integrated design and execution plan for non-trivial work
- [ADR template](docs/templates/adr.md): decisions that have durable impact across multiple features

Create a Feature Plan before adding an endpoint, UseCase, domain, storage structure, external integration, security behavior, or change spanning multiple packages. Skip it for typos, isolated one-file changes, and small cleanups that do not change behavior.

## Project baseline

- Java 21
- Spring Boot 4.1
- Gradle Wrapper
- PostgreSQL and Spring Data JPA
- Redis
- Spring Security and JWT
- Springdoc OpenAPI
- Default profile: `local`
- Flyway: currently disabled
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

## Feature implementation order

1. Define the user problem, reason, success criteria, and exclusions.
2. Decide whether to reuse an existing domain or introduce a new one.
3. Define the UseCase and Application Request/Response contracts first.
4. Define only the Output Ports required for real external interactions.
5. Implement the Domain model and business rules.
6. Orchestrate the flow in an Application Service.
7. Implement Output Adapters.
8. Implement Web Request DTOs, Controllers, and Swagger documentation.
9. Wire Infra configuration and Beans.
10. Revalidate the impact surface and completion criteria.

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
