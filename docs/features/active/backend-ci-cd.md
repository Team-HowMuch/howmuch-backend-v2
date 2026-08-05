# Feature Plan: Backend CI/CD

- Status: Implemented, pending EC2 verification
- Last updated: 2026-08-05
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

The backend has development and production branches but no repeatable build, image publication, or EC2 deployment path. Manual JAR deployment would duplicate server setup and can deploy different artifacts to each environment.

### Why now

Both environments need automatic deployment before backend delivery can proceed reliably.

### Success criteria

- [x] Pull requests to `develop` and `main` compile and run the existing test suite.
- [x] Pushes to `develop` and `main` publish a commit-addressable GHCR image.
- [x] A successful `develop` push targets the GitHub `development` environment.
- [x] A successful `main` push targets the GitHub `production` environment.
- [x] EC2 deployment waits for an internal TCP health check and restores the previous image when replacement fails.
- [ ] Both environment workflows have completed successfully against their EC2 targets.

### Out of scope

- Provisioning EC2, PostgreSQL, Redis, DNS, TLS, or a reverse proxy
- Managing PostgreSQL or Redis with Compose
- Adding an external HTTP health endpoint
- Adding new test cases
- Database migrations while Flyway remains disabled

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: deployment infrastructure; application domain behavior is unchanged.
- Reason a new domain is required, if applicable: N/A.
- Relationships to other domains: the deployed application keeps its existing JPA, Redis, authentication, and SMS contracts.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| Development deployment | Image built from a successful `develop` push | GitHub `development` environment |
| Production deployment | Image built from a successful `main` push | GitHub `production` environment |
| Runtime environment | Secrets and connection settings owned by one EC2 host | `${APP_DIR}/app.env` |
| Deployment image | Immutable image selected by the build output | GHCR digest reference |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| Developer | Reject a change that does not build or start its Spring context | Pull-request verification |
| GitHub Actions | Publish the verified commit as one image | GHCR publication |
| EC2 deploy user | Replace the running application without owning build tools | Compose deployment |

### State and invariants

- State transitions: verified commit -> published digest -> deployed environment.
- Rules that must always hold: pull requests never publish or deploy; only `develop` targets development; only `main` targets production; application secrets never enter the image or repository.
- Invalid states: deployment without `app.env`, a mutable tag as the deployed reference, or concurrent deployments to the same branch environment.

## 3. Scenarios

### Normal flow

1. A pull request or branch push runs the Gradle build against CI PostgreSQL and Redis services.
2. A successful branch push builds and publishes the Docker image to GHCR.
3. The deploy job copies the shared Compose file to the selected EC2 host.
4. Compose pulls the exact digest and waits for the application's internal port to become available.

### Failure flow

- Gradle or Docker build failure: no image is published and no deployment starts.
- Missing EC2 configuration: deployment fails before changing the running container.
- Pull or startup failure: the previous Compose and image-reference files are restored and restarted when available.

### Boundary conditions

- The deployment concurrency group permits only one deployment per branch environment at a time.
- A first deployment has no previous image to restore.
- GHCR authentication for private image pulls is bootstrapped once on each EC2 host and is not transported by Actions.

## 4. Contracts

### Input Port

```text
N/A: no application UseCase is introduced.
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| N/A | Deployment is outside the application architecture | N/A | N/A |

### Web API

```text
N/A: no endpoint changes.
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: none; each server supplies an existing PostgreSQL schema.
- Redis key, TTL, or serialization changes: none; each server supplies its Redis connection.
- External API or message changes: GHCR image publishing and SSH-based EC2 deployment.
- Environment variable or profile changes: production requires `REDIS_HOST`, `AUTH_IDENTITY_HMAC_SECRET`, and `JWT_SECRET`; production SMS defaults to `solapi` and therefore requires existing SOLAPI settings.

## 5. Design decisions

### Business logic

- Rules and their locations: N/A.
- Behavior owned by Domain: unchanged.
- Flow orchestrated by Application: unchanged.

### Technology and patterns

- Constraint to solve: deploy the same Java 21 application automatically to two EC2 environments with minimal server tooling.
- Selected approach: one GitHub Actions workflow, one multi-stage Dockerfile, one app-only Compose file, GHCR digest deployment, and GitHub Environments for target-specific SSH settings.
- Reason for selection: this avoids duplicated workflows, server-side builds, and runtime-secret transport through Actions.
- Alternatives considered: JAR plus systemd was rejected because it requires Java and release wiring on each host; separate environment workflows were rejected because their build and deployment logic would drift.
- Trade-offs and limits: internal TCP health proves that the application port opened, not that every dependency or business operation is healthy.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | None | Existing controllers and security paths |
| Application | None | Existing UseCases and services |
| Domain | None | Existing domain models |
| Persistence | External PostgreSQL remains required | `application.yaml` |
| Redis | External Redis remains required | `application-prod.yaml`, `application-dev.yaml` |
| Security | Runtime secrets remain EC2-owned; SSH host keys are pinned | `app.env`, GitHub Environment secrets |
| Configuration | Adds production profile and container deployment | `application-prod.yaml`, `compose.yml` |
| Documentation | Records setup, exclusions, and verification | This Feature Plan |

Existing behavior that must remain unchanged:

- Local development continues to use the `local` profile.
- Development continues to use the `dev` profile and Discord SMS unless overridden.
- The application continues to validate schemas outside local development, with Flyway disabled.

## 7. Implementation plan

- [x] Input Port and Application Request/Response: N/A; no application behavior.
- [x] Required Output Ports: N/A; deployment is outside the application boundary.
- [x] Domain models and rules: N/A.
- [x] Application Service: N/A.
- [x] Output Adapters: N/A.
- [x] Web DTOs, Controllers, and Swagger: N/A; no endpoint added.
- [x] Infra configuration and Bean wiring: production profile, Dockerfile, Compose, and workflow added.
- [x] Documentation and local verification results recorded.

### Expected commit boundaries

```text
docs(deploy): CI/CD 배포 계약 문서화
ci(deploy): EC2 자동 배포 파이프라인 구성
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew clean build --no-daemon
docker build --tag howmuch-backend-v2:local .
docker compose --env-file <deployment-env-file> config --quiet
git diff --check
git status --short --untracked-files=all
```

The Gradle build requires PostgreSQL and Redis. CI supplies both service containers and overrides JPA schema handling with `create-drop`. Runtime verification requires Docker Compose with `--wait`, an EC2-owned `app.env`, an existing database schema, external Redis, and an authenticated GHCR client when the package is private.

### Completion criteria

- [x] Verified the repository-side user outcome defined under Purpose.
- [ ] Verified normal and rollback flows against development EC2.
- [ ] Verified normal and rollback flows against production EC2.
- [x] Confirmed no API or Swagger contract changed.
- [x] Recorded EC2 verification as not performed because host access and environment configuration are external prerequisites.

### Results

| Item | Result | Evidence |
|---|---|---|
| Repository files | Passed | YAML syntax, Compose config, and `git diff --check` passed locally |
| Existing test | Passed | `./gradlew clean build --no-daemon` passed with disposable PostgreSQL and Redis services |
| Docker image | Passed | Multi-stage image built and ran as the non-root `appuser` |
| Compose readiness | Passed | Application became `healthy` through the internal TCP check and `docker compose up --wait` completed |
| EC2 deployment | Not run | GitHub Environments and EC2 bootstrap are not configured in this worktree |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-05 | Added and locally verified the minimal GHCR and Compose deployment path | Configure both EC2 targets | Same-host PostgreSQL and Redis must be reached through `host.docker.internal`, not container localhost |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-05 | Use one workflow and GitHub Environment-scoped settings | Removes duplicate development and production deployment logic | Both environments must use the same secret and variable names |
| 2026-08-05 | Deploy a GHCR digest | A branch tag is mutable and cannot identify the exact running artifact | Branch and SHA tags remain available for humans |
| 2026-08-05 | Use a Compose TCP health check | No external health URL will be added | Dependency-level readiness is not covered |
| 2026-08-05 | Keep PostgreSQL and Redis outside Compose | They are provisioned separately on each server | Database schema and backups remain operational prerequisites |
| 2026-08-05 | Increase the Gradle Wrapper network timeout to 60 seconds | The isolated Docker build exceeded the previous 10-second distribution download timeout | No retry script or additional build dependency was added |

## 11. Completion record

- Completed on:
- Final behavior: Repository-side implementation is complete; EC2 validation remains.
- Unverified items: Both real EC2 deployments and rollback behavior.
- Remaining risks: EC2 CPU architecture and Compose version must match the published image and workflow commands.
- Follow-up work: Configure GitHub Environments and bootstrap each EC2 host before merging.
