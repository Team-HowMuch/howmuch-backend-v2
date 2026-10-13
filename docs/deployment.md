# Digest-pinned deployment

The [workflow](../.github/workflows/backend-ci-cd.yml) publishes branch tags for compatibility and stable Release tags for production promotion, but deploys the immutable image reference returned by that build. The build summary and SSH deployment logs record the commit, run ID, attempt, and full image reference. A successful SSH log also includes the verified local image ID.

A published or promoted-to-stable GitHub Release deploys to the production environment only when its tag commit is reachable from `main`. Prereleases and tags outside `main` do not deploy; `workflow_dispatch` remains verification-only. Release deployments share production concurrency with pushes to `main`.

The EC2 directory (`APP_DIR`, default `/opt/howmuch-infra`) must contain `compose.yml` with an `app` service and its existing runtime configuration. Deployment overlays only that service's image, pulls it by digest, starts it with `--no-build --pull never`, and compares the running container's image ID with the pulled image. Existing dependency images must already be available on the host. The override is temporary; the server-owned Compose file is not rewritten.

For manual deployment with the repository [Compose file](../compose.yml), export `APP_IMAGE=ghcr.io/team-howmuch/howmuch-backend-v2@sha256:<64 hex characters>` using a previously recorded build reference, then run `docker compose pull app` and `docker compose up -d --no-build --pull never app`. Runtime `.env` and storage configuration must be prepared first.

For a server-owned Compose file that still names a branch tag, use an explicit override with that recorded digest for **both** pull and up. Plain `docker compose up` after the workflow finishes can revert to the server file's tag. Do not use a mutable tag for rollback.

Re-running only deploy reuses the successful build's output. Re-running all jobs can produce a new digest; deploy uses that new build output. Concurrency still serializes deployments per branch, but does not guarantee commit ordering or automatic rollback.

## Bank account login credential encryption

Before deploying the bank-credential feature, provide `ACCOUNT_CREDENTIAL_LOCAL_KEY` as a dedicated, randomly generated 32-byte key encoded in Base64. Do not reuse `CODEF_CONNECTED_ID_ENCRYPTION_KEY`. Store this key outside the repository and retain it for as long as any bank-credential ciphertext exists; changing it without re-encrypting existing rows makes those rows unreadable.

Optionally set `ACCOUNT_CREDENTIAL_KMS_KEY_ID` to a symmetric AWS KMS key ARN/ID/alias and grant the app identity `kms:Encrypt` and `kms:Decrypt`. The AWS SDK uses its normal region and credential provider chain. When configured, each credential has both a LOCAL AES-256-GCM ciphertext and a KMS ciphertext. A transient KMS outage falls back to LOCAL; a permission denial or invalid KMS ciphertext does not. A successful LOCAL read fills a missing KMS ciphertext after service recovery. An unset KMS key ID means LOCAL-only mode. KMS-only key deletion does not make the dual-protected data unreadable; protect and rotate the LOCAL key separately. Never log either key or the plaintext credentials.

## iOS APNs key and environment

The iOS native token sender uses `apns-topic: com.teamhowmuch.app`. Set `APNS_ENVIRONMENT=dev` for a development build and the Sandbox endpoint, or `APNS_ENVIRONMENT=prod` for a production-signed build and `api.push.apple.com`. The `prod` Spring profile defaults to `prod`; other profiles default to `dev`. Each server selects one APNs environment for all its tokens. Firebase Console's uploaded APNs key is not available to this server. Keep the original `.p8` file outside the repository and mount it read-only into the `app` container. The container runs as UID `10001`, which must be able to read the file. The key must be authorized for the selected APNs environment.

Add these names to the server's runtime `.env` with the values from Apple Developer and the **container** path to the mounted file:

```text
APNS_KEY_ID=<Key ID>
APNS_TEAM_ID=<Team ID>
APNS_PRIVATE_KEY_PATH=/app/secrets/apns.p8
APNS_ENVIRONMENT=<dev or prod>
```

Set `APNS_ENVIRONMENT=prod` on the production server. The server-owned Compose `app` service needs a read-only file mount, for example `/opt/howmuch-infra/secrets/apns.p8:/app/secrets/apns.p8:ro`. Do not print the file, key, device token, or provider JWT in logs. A rejected request logs only the APNs HTTP status, `reason`, and `apns-id`. A successful APNs HTTP 200 means APNs accepted the request; confirm device display separately.

## Monthly recap storage and activation

Keep `RECAP_GENERATION_ENABLED=false` (the default) until Flyway V48 is applied and persistent image storage is mounted and writable. Enabling it starts both generation and image cleanup. Application startup verifies directory writes and atomic moves; an unavailable storage path prevents activation.

The repository [Compose file](../compose.yml) mounts `${RECAP_IMAGE_HOST_DIR:-./uploads/recap-images}` at `/app/uploads/recap-images` and sets `RECAP_IMAGE_STORAGE_DIR` to that container path. For a server-owned Compose file, add the same persistent mount and container environment setting explicitly: the deployment workflow only replaces the image and does not install these storage changes. Grant container UID `10001` write access before enabling generation. Without Compose, `RECAP_IMAGE_STORAGE_DIR` defaults to `./uploads/recap-images`.

All app instances must use the same persistent storage. Keep recap images separate from original profile images, and back up the recap directory together with PostgreSQL. Copied images must survive container replacement and deletion of the original profile. Existing recap copies owned by other users remain after a companion withdraws.

Set `RECAP_GENERATION_ENABLED=true` after these checks. The first activation stores only the preceding KST month as the permanent first target. Startup and every hour at minute 10 register missing work from that target through the preceding month. Generation for a newly completed month becomes available at 00:10 KST on day 1, including after an earlier restart. Older months before the first target are not backfilled. Work polling defaults to 30 seconds.

Registration resumes after the stored month cursor. New users without jobs receive the range from the first target; existing users do not repeatedly attempt inserts for completed registration months. Cursor advancement and job insertion share a transaction, so interrupted registration remains recoverable.

Generation failures retry after 5–60 minutes and hold later months for that owner. Expired processing leases can be recovered after 10 minutes. Image deletion failures retry after one hour; unreferenced final and temporary files left by a crash are eligible for cleanup after 24 hours. Directory scans continue across polls in batches of up to 1,000 entries and restart after reaching the end, so referenced files cannot block later orphans. Referenced snapshot files and live reservations are protected. If generation is disabled again, queued image cleanup also waits until it is re-enabled.

The [completed Feature Plan](features/completed/monthly-recap.md) records API contracts, verification, and the remaining production storage checks. Local regression and activation checks do not verify EC2 volume permissions, shared storage across hosts, or backup restoration.

## Readiness gate

After image identity verification, the [deployment script](../scripts/deploy-readiness.sh) waits up to 180 seconds for HTTP 200 from `/actuator/health/readiness` inside the same container. The loopback management port defaults to 9091 (`MANAGEMENT_SERVER_PORT`); readiness includes application availability, PostgreSQL, and Redis. EC2 requires Bash and GNU `timeout`, and the application image includes curl. The management port remains private.

Exited or restarting containers fail immediately; persistent unready responses time out. Failure diagnostics report container status, exit code, OOM, restart count, and the last HTTP code, without printing environment variables or raw application logs. Diagnosis may take up to 10 additional seconds. Both success and failure remove the temporary image override; image pruning and the final verification log occur only after both checks pass. Manual Compose commands above do not run this gate.

## Verification

Run `python3 -m pip install PyYAML==6.0.2`, then `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v`. The tests execute workflow shell scripts with a mock Docker command; they verify image selection across tag changes, retries, invalid input, pull failure, and image mismatch.

After merging, compare the build summary and successful SSH verification log for the same run. To verify actual overlapping runs, publish B before A pulls, and confirm A still deploys A's digest. Repeat deploy and then the entire workflow to check each output's identity. These live checks require GHCR and EC2 access and are not performed by the local regression tests.
