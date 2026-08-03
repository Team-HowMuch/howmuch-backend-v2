# Feature Development Workflow

## Purpose

This workflow prevents implementation from starting with a guessed solution. It first establishes the problem and domain, then delivers the feature through verifiable contracts and small change units. AI agents must not attempt an entire feature in one speculative pass. They must leave the reasoning and results in the repository.

## Changes that require documentation

When a change meets any of the following conditions, copy the [Feature Plan template](templates/feature-plan.md) to `docs/features/active/<feature>.md`:

- A new Endpoint or UseCase
- A new domain or a change to an existing domain boundary
- A database or Redis storage change
- An external system integration
- A change to authentication, authorization, or sensitive data handling
- Work spanning multiple packages or more than one session
- A large behavior-preserving refactor

A typo, wording change, clear one-file bug fix, or small cleanup with no behavior or contract change may proceed without a Feature Plan.

## 0. Inspect the current state

Run these commands before implementation:

```bash
pwd
git status --short --untracked-files=all
git log -10 --oneline
rg --files
```

Read the relevant Controller, UseCase, Service, Domain, Port, Adapter, and Config from the inbound request to every external interaction. Find same-role implementations and reuse existing types and rules. Do not overwrite existing changes.

## 1. Define intent

Answer these questions in the Feature Plan first:

- Whose problem does this feature solve?
- Why is the current behavior insufficient?
- What can the user do after the change?
- Which observable outcomes prove success?
- What is intentionally excluded from this change?

A technology, pattern, or package name is not an answer at this stage. Record any time or change-size limit as a constraint.

## 2. Analyze the domain

First decide whether the feature belongs to an existing domain. Do not introduce a new domain only because a new package is convenient.

- Domain and subdomain
- Terms used consistently in code and documentation
- Actor who starts the feature
- UseCases and state transitions
- Business invariants that must always hold
- Relationships to other domains
- External system boundaries

Define business capabilities and relationships before choosing technologies. When a boundary remains unclear, refine the domain analysis instead of implementing around the uncertainty.

## 3. Define contracts and design

Define Port contracts before their implementations:

1. A feature-specific Input Port, or UseCase
2. Plain Application Request and Response records
3. Output Ports required for real external conversations
4. Domain models and business rules
5. API, persistence, security, and configuration contracts

Name an Output Port after its purpose, not its technology. Do not create an interface without a real caller or external boundary.

Record the following whenever the feature chooses a technology or pattern:

- The concrete constraint being solved
- The selected approach and reason
- Alternatives considered
- Trade-offs and known limits
- A linked ADR when the decision has durable impact

## 4. Revalidate before implementation

Walk through normal, failure, and boundary scenarios from beginning to end before editing code.

### Impact checklist

- API path, Request, Response, and status codes
- Calling UseCases and called Services
- Domain state and invariants
- Database Entities, constraints, and queries
- Redis keys, TTL, and serialization
- Authentication, authorization, and Security Filters
- Settings, profiles, and environment variables
- Swagger and repository documentation
- Existing callers and same-role implementations

Use search results to record affected files and behavior that must remain unchanged in the Feature Plan.

## 5. Implement in order

Use this sequence by default:

1. UseCase and Application Request/Response
2. Required Output Ports
3. Domain models and rules
4. Application Service
5. Persistence, Redis, JWT, and external integration Adapters
6. Web Request DTOs, Controllers, and Swagger
7. Properties, Config, Security Filters, and Bean wiring
8. Documentation and Feature Plan updates

Define Port contracts first, but do not stop with unused Ports and a disconnected feature. Reuse existing implementations at every step. Do not add abstractions or configuration that the feature does not need yet.

## 6. Verify and complete

Select verification based on the change type.

| Change | Minimum verification |
|---|---|
| Java code | `./gradlew compileJava` |
| Configuration and Bean wiring | Compile, then run the Application in the required environment |
| API behavior | Run the Application and manually verify normal and failure requests |
| Documentation | Validate relative links, local paths, and code examples |
| Every change | `git diff --check` and `git status --short --untracked-files=all` |

Check PostgreSQL, Redis, and environment prerequisites before running `./gradlew test` or `bootRun`. If an environment dependency prevents verification, record the cause and unverified scope. Do not create new test cases under the current project instruction.

When the feature is complete:

- Record each completion criterion and its verification result.
- Mark completed progress items.
- Correct initial assumptions that differ from the actual implementation.
- Record remaining risks and follow-up work.
- Move the document to `docs/features/completed/`.

## When to use an ADR

An ADR records a decision with durable impact across multiple features, not an implementation note for one feature.

- Package structure and dependency direction
- Authentication, security, and sensitive-data policy
- Public API and compatibility
- Data storage strategy and consistency
- Framework, library, or external service selection
- Structure that affects operational reliability

Record local feature decisions in the Feature Plan decision log. ADRs use `Proposed`, `Accepted`, `Rejected`, and `Superseded` states. Do not edit an Accepted or Rejected ADR. Create a new ADR and link it as a replacement when a decision changes.

## Commit plan and boundaries

Record expected commit boundaries in the Feature Plan before implementation. Create actual commits only when the user explicitly requests them.

```text
<type>(<domain>): <description in Korean>
```

Allowed types:

- `feat`: add user-facing or system behavior
- `fix`: correct faulty behavior
- `refactor`: change structure without changing behavior
- `docs`: change documentation
- `test`: change tests
- `build`: change the build or dependencies
- `ci`: change continuous integration
- `chore`: maintenance outside the categories above

Split commits by intent, not by file count.

- Keep large refactors separate from feature behavior.
- Commit a new public contract with its actual usage.
- Do not create an unused, Port-only commit.
- Keep every commit compilable at minimum.
- Do not stage unrelated existing changes.

Illustrative semantic boundaries:

```text
docs(auth): document the SMS verification design
feat(auth): implement authentication UseCases and domain rules
feat(auth): connect authentication persistence and external adapters
feat(auth): expose authentication API endpoints
```

The examples above are English translations for readability. Actual commit descriptions follow the repository rule and are written in Korean.

## References and rationale

- [Harness engineering, OpenAI](https://openai.com/index/harness-engineering/): use `AGENTS.md` as a short map and keep repository documentation and execution plans as sources of truth.
- [Using PLANS.md, OpenAI](https://developers.openai.com/cookbook/articles/codex_exec_plans): keep long-running work in a resumable living document containing intent, progress, decisions, and verification.
- [Effective harnesses for long-running agents, Anthropic](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents): make incremental progress on one feature and use Git history and progress documents to reduce guesswork in later sessions.
- [Principles of Shaping, Shape Up](https://basecamp.com/shapeup/1.1-chapter-02): define the problem, boundary, solution direction, risks, and exclusions before implementation.
- [Domain analysis, Microsoft](https://learn.microsoft.com/en-nz/azure/architecture/microservices/model/domain-analysis): analyze business capabilities, shared language, and bounded contexts before choosing technology.
- [Hexagonal Architecture, Alistair Cockburn](https://alistair.cockburn.us/hexagonal-architecture): keep UseCases at a technology-independent Application boundary and connect external systems through Ports and Adapters.
- [Architectural decision records, AWS](https://docs.aws.amazon.com/prescriptive-guidance/latest/architectural-decision-records/adr-process.html): preserve the context, decision, consequences, and history of important choices.
- [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): express commit intent in a consistent format.
- [Small CLs, Google Engineering Practices](https://google.github.io/eng-practices/review/developer/small-cls.html): keep each change focused and independently understandable.
