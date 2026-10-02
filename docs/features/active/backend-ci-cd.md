# Feature Plan: Backend CI/CD

- Status: Stable Release deployment configured; live Release verification pending
- Last updated: 2026-10-03
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

The backend already builds and deploys on branch pushes. Stable published GitHub Releases should also promote their tagged code to production; manual workflow dispatch remains verification-only.

### Why now

Both environments need automatic deployment before backend delivery can proceed reliably.

### Success criteria

- [x] Pull requests to `develop` and `main` compile the application and build the image without publishing it.
- [x] Pushes to `develop` and `main` publish branch-tagged GHCR images.
- [x] A successful `develop` push targets the GitHub `development` environment.
- [x] A successful `main` push targets the GitHub `production` environment.
- [x] EC2 deployment pulls the branch image and starts it with Docker Compose.
- [ ] A stable published Release whose tag commit is reachable from `main` publishes its image and deploys to production.
- [ ] Prereleases do not deploy, and stable Release tags outside `main` fail before image publication.
- [x] `workflow_dispatch` remains verification-only and does not publish or deploy.
- [ ] Both environment workflows have completed successfully against their EC2 targets.

### Out of scope

- Provisioning EC2, DNS, TLS, or a reverse proxy
- Committing the server-owned integrated Compose file to this repository
- Deploying prereleases or Release tags whose commit is not reachable from `main`
- Adding an external HTTP health endpoint
- Adding new test cases
- Authoring database migrations beyond the application-owned Flyway scripts

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
| Release deployment | Image built from a stable published Release reachable from `main` | GitHub `production` environment |
| Runtime environment | Secrets and connection settings owned by one EC2 host | `${APP_DIR}/.env` |
| Deployment image | Image selected by the target branch | GHCR branch tag |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| Developer | Reject a change that does not compile or build its image | Pull-request verification |
| GitHub Actions | Publish the branch image and trigger the target deployment | GHCR publication and SSH deployment |
| EC2 deploy user | Replace the running application without owning build tools | Compose deployment |

### State and invariants

- State transitions: compiled commit -> published branch or Release tag -> image digest -> selected environment deployment.
- Rules that must always hold: pull requests never publish or deploy; only `develop` targets development; `main` pushes and stable Releases from `main` target production; prereleases and manual dispatch do not deploy; application secrets never enter the image or repository.
- Invalid states: a stable Release tag outside `main`, deployment without `.env`, a missing image digest, or concurrent deployments to the same environment.

## 3. Scenarios

### Normal flow

1. A pull request or branch push runs the workflow verification and image build.
2. A successful branch push builds and publishes the branch-tagged Docker image to GHCR.
3. The deploy job connects to the preconfigured EC2 directory.
4. Compose pulls the branch image and starts the application.
5. A stable published Release verifies its tag commit is reachable from `main`, publishes the tag image, and deploys the digest to production. A prerelease runs verification/build only.

### Failure flow

- Gradle or Docker build failure: no image is published and no deployment starts.
- A stable Release tag outside `main`: the workflow fails before image publication or deployment.
- Missing EC2 configuration: deployment fails before changing the running container.
- Pull or startup failure: the deploy job fails and the existing container is left for manual inspection.

### Boundary conditions

- The deployment concurrency group permits only one deployment per environment at a time; main pushes and Releases share production concurrency.
- GHCR authentication for private image pulls is refreshed by the deploy job using `GHCR_PAT`.

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
- External API or message changes: GHCR image publishing for branches and Releases, GHCR pull authentication, and SSH-based deployment.
- Environment variable or profile changes: production requires `REDIS_HOST`, `AUTH_IDENTITY_HMAC_SECRET`, and `JWT_SECRET`; production SMS defaults to `solapi` and therefore requires existing SOLAPI settings.

## 5. Design decisions

### Business logic

- Rules and their locations: N/A.
- Behavior owned by Domain: unchanged.
- Flow orchestrated by Application: unchanged.

### Technology and patterns

- Constraint to solve: keep existing branch deployment behavior while making stable Release publication promote the same Java 21 application to production.
- Selected approach: one GitHub Actions workflow builds and publishes a Release tag only for stable Releases whose tagged commit is reachable from `main`, then deploys its immutable image digest using the production Environment.
- Reason for selection: it preserves branch deployment behavior, makes a Release an explicit production promotion, and prevents prereleases and off-main tags from reaching production.
- Alternatives considered: JAR plus systemd was rejected because it requires Java and release wiring on each host; separate environment workflows were rejected because their build and deployment logic would drift.
- Trade-offs and limits: branch tags are mutable, deployment has no automatic rollback, and `up -d` does not wait for application readiness.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | None | Existing controllers and security paths |
| Application | None | Existing UseCases and services |
| Domain | None | Existing domain models |
| Persistence | PostgreSQL is managed by the server-owned Compose file | `application.yaml`, server Compose |
| Redis | Redis is managed by the server-owned Compose file | `application-prod.yaml`, `application-dev.yaml`, server Compose |
| Security | Runtime secrets remain host-owned; GHCR pull credentials are provided by the deploy job | `.env`, GitHub Environment secrets |
| Configuration | Stable Release promotion selects the production Environment | `.github/workflows/backend-ci-cd.yml` |
| Documentation | Records setup, exclusions, and verification | This Feature Plan |

Existing behavior that must remain unchanged:

- Local development continues to use the `local` profile.
- Development continues to use the `dev` profile and Discord SMS unless overridden.
- The application runs pending Flyway migrations before Hibernate validates the schema in every profile.

## 7. Implementation plan

- [x] Input Port and Application Request/Response: N/A; no application behavior.
- [x] Required Output Ports: N/A; deployment is outside the application boundary.
- [x] Domain models and rules: N/A.
- [x] Application Service: N/A.
- [x] Output Adapters: N/A.
- [x] Web DTOs, Controllers, and Swagger: N/A; no endpoint added.
- [x] Infra configuration and Bean wiring: no Application or runtime Infra changes; CI deployment routing only.
- [x] Documentation and local verification results recorded.

### Expected commit boundaries

```text
ci(deploy): 안정 Release를 production에 배포
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew clean build --no-daemon -x test
docker build --tag howmuch-backend-v2:local .
docker compose config --quiet
git diff --check
git status --short --untracked-files=all
```

Runtime verification requires Docker Compose, an EC2-owned integrated Compose file and `.env`, existing database volume data, and a GHCR credential with package pull access. Publishing an actual stable Release triggers a production deployment and is not part of local verification.

### Completion criteria

- [x] Verified the repository-side user outcome defined under Purpose.
- [ ] Verified normal deployment against development EC2.
- [ ] Verified normal deployment against production EC2.
- [x] Confirmed no API or Swagger contract changed.
- [x] Recorded EC2 verification as not performed because host access and environment configuration are external prerequisites.
- [x] Recorded live Release deployment verification as pending the first stable Release.

### Results

| Item | Result | Evidence |
|---|---|---|
| Repository files | Passed | Ruby YAML parse, local documentation link check, and `git diff --check`; `actionlint` unavailable |
| Application tests | Not rerun | Workflow-only change; pull-request CI will run the existing Gradle and JavaScript tests |
| Docker image | Passed | Multi-stage image built and ran as the non-root `appuser` |
| Compose startup | Not run against EC2 | The workflow uses `docker compose up -d` on the target host |
| EC2 deployment | Not run | GitHub Environments and EC2 bootstrap are not configured in this worktree |
| Release deployment | Not run | Requires publishing a stable Release and would deploy to production |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-03 | Added stable Release-to-production workflow routing and documented the contract | Verify the first stable Release deployment | Publishing a stable Release deploys production |
| 2026-08-06 | Simplified the workflow to the supplied GHCR and SSH Compose pattern | Configure both EC2 targets | Branch tags are mutable and failed startup requires manual recovery |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-03 | Deploy only stable Releases whose tag commit is reachable from `main` | A Release is an explicit production promotion; prereleases and off-main tags must not deploy | Manual dispatch remains verification-only |
| 2026-08-05 | Use one workflow and GitHub Environment-scoped settings | Removes duplicate development and production deployment logic | Both environments must use the same secret and variable names |
| 2026-08-06 | Deploy branch tags with `docker compose pull` | Matches the supplied pipeline and keeps server setup small | The running image is not immutable |
| 2026-08-06 | Keep the integrated Compose file server-owned | The server already owns PostgreSQL and Redis storage; the repository only publishes the app image | Keep the server Compose and `.env` synchronized with the image tag |
| 2026-08-05 | Increase the Gradle Wrapper network timeout to 60 seconds | The isolated Docker build exceeded the previous 10-second distribution download timeout | No retry script or additional build dependency was added |
| 2026-08-06 | Authenticate the EC2 pull from the deploy job with `GHCR_PAT` | Matches the supplied pipeline and avoids a separate server login bootstrap | The token must be stored in both GitHub Environments |

## 11. Completion record

- Completed on:
- Final behavior: Stable Releases promote the tagged image digest to production; workflow configuration is locally validated.
- Unverified items: Actual Release-to-production deployment and recovery behavior.
- Remaining risks: Mutable tags, no automatic rollback, SSH reachability from GitHub Actions, and EC2 CPU architecture.
- Follow-up work: Verify production deployment by publishing the first stable Release after merge.
