# Test infrastructure and CI

The [CI workflow](../.github/workflows/backend-ci-cd.yml) runs all JUnit tests and the existing administrator JavaScript tests before building an image:

```text
test (PostgreSQL + Redis, JUnit + Node)
  -> build (bootJar, Docker image; publish only on push)
    -> deploy (only on push to develop/main)
```

`build` requires `test` to succeed. `deploy` requires `build` to succeed. Neither downstream job uses `always()` or `continue-on-error`. A failed test stops the chain before image publication. Pull requests execute tests and build the image without publishing or deploying. Manual workflow runs also do not publish or deploy.

The test job uses disposable PostgreSQL 18 and Redis 8 service containers with health checks. Java 21, the Gradle Wrapper, and Node.js 22 are required. Flyway applies the real migrations and Hibernate validates the resulting schema; no H2 or schema-generation substitute is used. The Gradle test task limits the Spring context cache to four entries so old connection pools are closed before the full suite exhausts PostgreSQL connections.

## Run locally

Run from the repository root. Use disposable services: the integration suite writes database rows and Redis keys. Do not point it at a development/shared/production database.

```bash
docker compose -p howmuch-test -f compose.test.yml up -d --wait

export DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test
export DB_USERNAME=howmuch_test
export DB_PASSWORD=howmuch_test
export REDIS_HOST=127.0.0.1
export REDIS_PORT=16379
bash scripts/test.sh

# Run the real HTTP security regression tests plus the JavaScript tests only:
bash scripts/test.sh test --tests '*SecurityFilterChainIntegrationTest'

# Stop and discard only the test infrastructure:
docker compose -p howmuch-test -f compose.test.yml down -v
```

The [test compose file](../compose.test.yml) binds ports to loopback and keeps PostgreSQL data in temporary storage. If these ports are occupied, change the compose port mapping and the corresponding environment variable. Native PostgreSQL 18 and Redis 8 on dedicated ports work too.

The [test script](../scripts/test.sh) requires explicit database/Redis connection variables and never reads `.env`. It selects the existing `local` profile, overrides JWT/HMAC values with public synthetic test values, directs SMS/CODEF endpoints to loopback, disables Firebase/LLM and notification schedulers and the automatic transfer reconciliation scheduler, and stores test images under `build/test-storage`. These settings are for disposable tests only. No GitHub deployment secrets are needed by the test job.

With no arguments the script runs `./gradlew clean test --no-daemon`, then `node --test src/test/js/*.test.cjs`. A Java or JavaScript failure returns a nonzero exit code. Java failure stops the script before Node. Existing tests that explicitly select `local` still receive the same environment settings.

## Inspect CI results

```bash
gh pr checks <PR_NUMBER>
gh run view <RUN_ID>
gh run download <RUN_ID> -n test-reports -D /tmp/howmuch-test-reports
```

The `test-reports` artifact is uploaded even when tests fail, with a seven-day retention:

- `build/reports/tests/test/`: JUnit HTML report.
- `build/test-results/test/*.xml`: machine-readable test results.
- `build/reports/javascript-tests.txt`: Node output, when the Java phase succeeded and Node ran.

An infrastructure/setup failure may occur before reports exist; inspect the failed step in that case. Reports use synthetic fixtures. Do not supply real credentials or user data to these runs.

## Security coverage

[SecurityFilterChainIntegrationTest](../src/test/java/com/amateur/howmuchbackend/infra/security/SecurityFilterChainIntegrationTest.java) starts the application on a random port and sends real HTTP requests. It does not replace the SecurityFilterChain, JWT implementation, Redis sessions, current-actor lookup, or persistence with mocks.

| Request/state | Expected result |
|---|---|
| Protected profile without Authorization | 401 `UNAUTHORIZED` JSON |
| Valid Access JWT and matching active Redis session | 200, authenticated user's ID |
| Anonymous request after an authenticated request | 401, no leaked authentication |
| Expired JWT with an active Redis session | 401 |
| Incorrect signature or malformed JWT | 401 |
| Refresh JWT used as Access JWT | 401 |
| Same Access JWT after HTTP logout | 401, Redis session absent |
| Old session after replacement / current session | 401 / 200 |
| Admin API without authentication / with USER | 401 / 403 |
| Admin API with DB ADMIN role / after role removal | 200 / 403 |
| Public administrator HTML page | 200 |

Authorization is currently enforced by `CommonCodeAdminService` using the database role, after filter-chain authentication. These tests exercise that actual boundary; they do not introduce a test-only role matcher. Each test deletes only its own user, audit, and session fixtures.

## Java test method names and comments

Name behavior tests with `<subject>_<keyCondition>_<expectedOutcome>`. Name configuration and annotation tests with `<subject>_<property>_<expectedState>`. Keep each part specific enough to explain the assertion without repeating words; there is no fixed character limit, and names around 60 characters or fewer are preferred when clarity is preserved.

Write Javadoc and explanatory test comments as complete Korean declarative sentences using an active verb form such as `~한다.` or `~하지 않는다.`. Short helper notes and section labels may use noun phrases. Add comments only when they explain a contract, regression reason, or detail that the code does not show; do not restate the method name or obvious code. Add Javadoc only when the extra explanation is useful.

Change an existing `@DisplayName` only when it disagrees with what the test verifies. Naming and comment cleanups preserve test bodies, assertions, and annotations; JavaScript tests use their existing naming conventions.

## Verify failure propagation

On a disposable local checkout, temporarily add a JUnit test whose body calls `Assertions.fail("CI gate probe")`, then run it with `bash scripts/test.sh test --tests '*CiFailureProbeTest'`. Expect exit code 1 and no Node phase. Remove the temporary file and run the full suite again before publishing.

For a GitHub negative check on a temporary PR branch, push that temporary test and inspect the run: `test` must fail, `build` and `deploy` must be skipped, and the JUnit artifact must contain the failure. Remove the probe before merging. Never test this by pushing a failing commit to a deployment branch. A successful PR run alone demonstrates the passing path, not an observed push-event deployment failure.
