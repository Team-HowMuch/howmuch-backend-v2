# Digest-pinned deployment

The [workflow](../.github/workflows/backend-ci-cd.yml) publishes branch tags for compatibility, but deploys the immutable image reference returned by that build. The build summary and SSH deployment logs record the commit, run ID, attempt, and full image reference. A successful SSH log also includes the verified local image ID.

The EC2 directory (`APP_DIR`, default `/opt/howmuch-infra`) must contain `compose.yml` with an `app` service and its existing runtime configuration. Deployment overlays only that service's image, pulls it by digest, starts it with `--no-build --pull never`, and compares the running container's image ID with the pulled image. Existing dependency images must already be available on the host. The override is temporary; the server-owned Compose file is not rewritten.

For manual deployment with the repository [Compose file](../compose.yml), export `APP_IMAGE=ghcr.io/team-howmuch/howmuch-backend-v2@sha256:<64 hex characters>` using a previously recorded build reference, then run `docker compose pull app` and `docker compose up -d --no-build --pull never app`. Runtime `.env` and storage configuration must be prepared first.

For a server-owned Compose file that still names a branch tag, use an explicit override with that recorded digest for **both** pull and up. Plain `docker compose up` after the workflow finishes can revert to the server file's tag. Do not use a mutable tag for rollback.

Re-running only deploy reuses the successful build's output. Re-running all jobs can produce a new digest; deploy uses that new build output. Concurrency still serializes deployments per branch, but does not guarantee commit ordering or automatic rollback.

## iOS APNs Sandbox key

The iOS native token sender uses `apns-topic: com.teamhowmuch.app` and `api.sandbox.push.apple.com`. Firebase Console's uploaded APNs key is not available to this server. Keep the original `.p8` file outside the repository and mount it read-only into the `app` container. The container runs as UID `10001`, which must be able to read the file.

Add these names to the server's runtime `.env` with the values from Apple Developer and the **container** path to the mounted file:

```text
APNS_KEY_ID=<Key ID>
APNS_TEAM_ID=<Team ID>
APNS_PRIVATE_KEY_PATH=/app/secrets/apns.p8
```

The server-owned Compose `app` service needs a read-only file mount, for example `/opt/howmuch-infra/secrets/apns.p8:/app/secrets/apns.p8:ro`. Do not print the file, key, device token, or provider JWT in logs. A rejected request logs only the APNs HTTP status, `reason`, and `apns-id`. A successful APNs HTTP 200 means APNs accepted the request; confirm device display separately.

## Readiness gate

After image identity verification, the [deployment script](../scripts/deploy-readiness.sh) waits up to 180 seconds for HTTP 200 from `/actuator/health/readiness` inside the same container. The loopback management port defaults to 9091 (`MANAGEMENT_SERVER_PORT`); readiness includes application availability, PostgreSQL, and Redis. EC2 requires Bash and GNU `timeout`, and the application image includes curl. The management port remains private.

Exited or restarting containers fail immediately; persistent unready responses time out. Failure diagnostics report container status, exit code, OOM, restart count, and the last HTTP code, without printing environment variables or raw application logs. Diagnosis may take up to 10 additional seconds. Both success and failure remove the temporary image override; image pruning and the final verification log occur only after both checks pass. Manual Compose commands above do not run this gate.

## Verification

Run `python3 -m pip install PyYAML==6.0.2`, then `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v`. The tests execute workflow shell scripts with a mock Docker command; they verify image selection across tag changes, retries, invalid input, pull failure, and image mismatch.

After merging, compare the build summary and successful SSH verification log for the same run. To verify actual overlapping runs, publish B before A pulls, and confirm A still deploys A's digest. Repeat deploy and then the entire workflow to check each output's identity. These live checks require GHCR and EC2 access and are not performed by the local regression tests.
