# Certificate registration operations

The implementation is an API-resident provider plus a separate [Electron project](https://github.com/Team-HowMuch/howmuch-finance-connector/tree/feat/certificate-electron-app/apps/windows-certificate). The client source and Windows workflow live in the finance-connector repository; CODEF execution remains in this API. It does not replace the transaction-collection finance connector. See the current [mobile consent plan](../../docs/features/active/certificate-mobile-consent.md).

## Activation boundary

`CERTIFICATE_ENABLED` defaults to `false`. Keep it disabled in dev/prod until V45 has been merged/applied, institution requirements have been reviewed, and independent financial ownership verification is integrated. The current implementation supports upload, provider registration, QR reservation and rejection; **approval/BIND and account selection are not exposed yet**. PC confirmation and self-entered birth dates do not establish ownership.

The SQL-only [PR #498](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/498) is now merged on GitHub. Apply and validate V45 in each target environment before enabling the feature. No server deployment or production activation is performed by the client build workflow.

## Configuration

| Setting | Requirement |
|---|---|
| `CERTIFICATE_ENABLED` | `false` until rollout acceptance; enables PC endpoints, listeners and schedulers together |
| `CERTIFICATE_DETAILED_ERRORS_ENABLED` | `false` by default; emits safe CF codes for definite registration rejections only after V46 and tolerant consumers are deployed |
| `KAFKA_ENABLED` | `true` when certificates are enabled; existing notification mode remains independent |
| `KAFKA_BOOTSTRAP_SERVERS` | Reachable private advertised broker addresses |
| `CERTIFICATE_KMS_KEY_ID` | Optional KMS key/alias; when configured, new envelopes use KMS in every profile. Scope Encrypt to this key and retain Decrypt access to stored key IDs while their envelopes remain |
| `CERTIFICATE_CONFIRMATION_KEY` | Independently generated 32-byte Base64 HMAC key from a secret manager; identical across API replicas |
| `CERTIFICATE_LOCAL_WRAPPING_KEY` | Independently generated 32-byte Base64 key; required when no KMS key ID is configured, including dev/prod. Keep identical across API replicas and retain it while existing `local:v1` envelopes remain, even after enabling KMS |
| `CODEF_CLIENT_ID`, `CODEF_CLIENT_SECRET`, `CODEF_PUBLIC_KEY` | Existing CODEF configuration; no OAuth/client secret in Electron |
| `CODEF_CONNECT_TIMEOUT`, `CODEF_READ_TIMEOUT` | Combined duration below nine minutes; provider execution lease is ten minutes |

Set at least one of the two encryption keys before enabling certificates; no key is generated automatically. With both configured, new envelopes use KMS and existing `local:v1` envelopes remain readable through the local wrapping key. KMS failures return a storage error and do not fall back to local encryption. Keep the certificate wrapping key separate from `ACCOUNT_CREDENTIAL_LOCAL_KEY` and `CERTIFICATE_CONFIRMATION_KEY`; persist and back up all keys outside the image with restricted file access. See [the deployment guide](../../docs/deployment.md#certificate-secret-encryption).

KMS and local AES-GCM authenticate the context containing purpose, session UUID and upload/connection UUID. KMS decryption uses the key ID stored in each envelope, rather than the currently configured encryption key. Automatic KMS rotation retains the key ID. Repointing an alias or changing the configured key affects new envelopes; retain Decrypt permission and availability for previous keys until their envelopes have expired or been migrated through a reviewed procedure. Do not remove or replace the local wrapping key while `local:v1` envelopes remain: the stored version marker does not identify different local keys. Do not rotate the HMAC key while reservations exist: confirmation codes must remain stable across their 60-second lifetime. Stop new reservations and let them expire before rotation.

All active `BANK` common codes support certificate registration by default. No per-institution allowlist or `reviewed` setting is needed. Unknown/inactive bank codes remain unavailable. Configure bank-specific additional inputs through private Spring configuration (`howmuch.certificate.institutions`): `code`, `registration-account-required`, `account-list-account-required`, and `birth-date-required`. Unconfigured banks require no additional inputs. The legacy `reviewed` field is accepted for compatibility and ignored; `reference` is optional documentation/acceptance metadata. Do not copy ID/PW `BANK.attr3`. Verify actual CODEF input requirements before setting these flags; synthetic tests do not establish Woori/iM requirements. Account credentials needed only for account listing are retained encrypted and are not sent to certificate registration. Restart the API and reopen the client after changing these properties.

PC sessions last ten minutes. DER/KEY are each limited to 128 KiB and the upload JSON body to 768 KiB. The API accepts at most four concurrent uploads per process and 200 active unapproved sessions across the DB. Session creation is limited to ten per remote address per ten minutes; QR reservations are limited to ten per authenticated user per ten minutes. Redis failure closes admission. Only session creation uses the serialized global capacity check; provider calls never hold that lock or a database transaction.

Serve the API through HTTPS. Bound ingress body size, request-header/body timeouts and concurrent connections. The rate limiter uses the servlet remote address, not an arbitrary `X-Forwarded-For` header. Behind a proxy, configure the container's native forwarding support with an explicit trusted-proxy allowlist and make the proxy replace untrusted forwarded headers. Verify distinct clients remain distinct; otherwise the proxy shares one admission bucket. Do not expose the backend port around that trust boundary. Never log request bodies or capability headers at the proxy/APM.

The shared default configuration requires account credentials for Woori (`0020`) certificate registration, based on the user's confirmed requirement. This enables the existing client fields and server-side missing-input checks. The provider sends `withdrawAccountNo` and RSA-encrypted `withdrawAccountPassword`. If external configuration replaces the `institutions` list, include this rule as well:

```yaml
howmuch:
  certificate:
    institutions:
      - code: "0020"
        registration-account-required: true
```

Other banks remain selectable. Their additional input flags are not inferred from Woori's rule.

## Registration diagnostics

The certificate provider adapter logs only allowlisted diagnostic metadata: `organization`, numeric `httpStatus`, internal `statusCode`, CODEF `resultCode`/`accountCode`/`providerCode` matching `CF-[0-9]{5}`, and exception class names. `httpStatus=0` means no HTTP status was available; `UNAVAILABLE` means no safe code could be extracted. Response bodies, provider messages, exception messages and stack traces are omitted because they can contain certificate/account credentials or connection identifiers.

An uncertain registration remains UNKNOWN and is not automatically repeated. A diagnostic entry does not establish whether CODEF created a connection. Historical attempts made before this logging existed cannot recover their discarded detailed errors. Correlate new entries by time and institution with existing job metadata; investigate before attempting another create.

## Kafka

Provision the four `certificate.registration.{command,result}.v1` and matching `.dlt` topics from [the shared catalog](../kafka/topics.tsv) using [the normal procedure](../kafka/README.md). All use session UUID keys, three partitions, seven-day transport retention and 8192-byte maximum messages. Topic auto-creation stays off. The schemas are [command v1](../../src/main/resources/schemas/certificate-command-v1.schema.json) and [result v1](../../src/main/resources/schemas/certificate-result-v1.schema.json).

The co-located API identity needs Write/Describe on command, result and both sanitized DLT topics; Read/Describe on command/result; Read on groups `howmuch-certificate-provider-v1` and `howmuch-certificate-registration-v1`; and the normal idempotent producer permission. Isolate dev/prod principals and private TLS credentials. No certificate material, passwords, account number, connectedId, capabilities or ownership assertions belong in these messages. Do not grant a mobile/client identity producer permissions.

Only malformed/conflicting contracts go to a sanitized DLT. Database outages retry without advancing the record. DLT publication must be acknowledged before skipping the malformed record. DLT records contain position, payload hash and a fixed error code, not the source key/body/headers/exception. The shared transport's existing notification/recap mapping remains intact.

## Recovery and inspection

Publication leases last 30 seconds. Recovery puts expired leases back into RETRY with the same immutable message ID. Completion requires current token/generation and an unexpired lease. A lost broker ACK may duplicate a record; both Inboxes make its effect idempotent.

Provider execution is separate: only a failure before `provider_request_started_at` permits another create. The marker commits before the HTTP call and the certificate path disables the HTTP client's automatic 401 retry. After a timeout/crash, the job becomes UNKNOWN and is not automatically claimed again. A late confirmed response from that same immutable execution generation can reconcile UNKNOWN. A later UNKNOWN result cannot replace a confirmed READY session.

Cancellation and session expiry enqueue CANCEL. Cleanup shares the session lock and excludes approved sessions. Known successful deletion clears the encrypted connection secret. Uncertain deletion retains DELETE_UNKNOWN evidence and never blindly repeats deletion. A CANCEL blocked by an unresolved REGISTER is excluded from runnable jobs until a confirmed connection or definitive registration failure exists; it does not spin through retry attempts. Unknown outcomes require trusted CODEF reconciliation. There is no administrative reconciliation endpoint or completed retention cleanup in this change; preserve UNKNOWN evidence and obtain the actual provider outcome before changing state. In particular, KMS/DB failure after a successful provider response can leave a created connection whose identifier was not durably recorded. Do not fabricate success or issue another create to hide that uncertainty.

Useful metadata-only inspection:

```sql
SELECT command_type, status, count(*) FROM certificate_provider_command_inbox GROUP BY 1,2;
SELECT status, count(*) FROM certificate_command_outbox GROUP BY 1;
SELECT status, count(*) FROM certificate_provider_result_outbox GROUP BY 1;
SELECT status, count(*) FROM certificate_provider_connections GROUP BY 1;
SELECT status, min(next_attempt_at), count(*) FROM certificate_provider_command_inbox GROUP BY 1;
```

Do not dump encrypted tables or delete receipts to repair lag. Retention must preserve replay deduplication and approval/ownership evidence; add a separately reviewed retention/reconciliation procedure before production activation.


For a rejected certificate response, `derBytes`/`keyBytes` report the decoded file lengths and `derAllZero`/`keyAllZero` report whether each file consists entirely of zero bytes immediately before Base64 serialization. These describe the outgoing material, not the dashboard display. No file contents, fingerprints, passwords, or paths are logged. A dashboard value containing repeated `A` characters alone cannot establish corruption because its display/redaction behavior is not verified. `keyAllZero=false` rules out an entirely zeroed outgoing key, but does not prove that the key matches the certificate or that the password is correct. DER/KEY registration explicitly sends `certType="1"`, which is also the documented CODEF default.


## User-facing CODEF errors

The CODEF HTTP adapters share `CodefFailureMapper`. Certificate rejections retain a non-success
`CF-[0-9]{5}` value through the command inbox, result outbox, Kafka result and session row.
The session read adapter normalizes this stored provider value into a server common code.
Status responses provide `data.failureCode` and `data.failureMessage`; synchronous bank APIs
use the existing `error.code` and `error.message` contract. Raw provider messages/details are
never copied. Certificate-password, account-password, lockout, expired certificate, account-number,
birth-date, additional-authentication, rate-limit and service errors have separate static messages.
Unknown safe CF codes use `CODEF_UNMAPPED_ERROR`.

For certificate creation, a matching institution error takes precedence over a generic outer
`CF-04000`. A connected ID, any successful account, partial success, registration in progress or
an ambiguous transport failure remains `UNKNOWN`; no automatic create retry is permitted.
The detailed-error flag does not change that safety rule or retry limits.

Roll out in this order:

1. Apply `V46__allow_codef_certificate_failure_codes.sql` after V45. It expands four CHECK
   constraints without rewriting rows or adding columns. Schedule the ALTER TABLE locks.
2. Deploy every API result consumer, the shared common-code mappings, API response and client
   changes with `CERTIFICATE_DETAILED_ERRORS_ENABLED=false`. Legacy messages remain accepted.
3. After confirming no old result consumers remain, set `CERTIFICATE_DETAILED_ERRORS_ENABLED=true`
   on certificate execution instances. Verify a synthetic rejection from result receipt to UI.
4. Keep tolerant consumers during rollback until all queued/replayable detailed-code messages
   have drained. Turning the producer flag off alone does not make old consumers compatible
   with existing detailed-code events.

Electron uses a packaged common-code message allowlist and ignores upstream message/details
fields. A Java contract test checks these local messages against server enums. The main-process
IPC guard forwards only known codes or a numeric HTTP status. Unknown provider values and
non-JSON HTTP errors fall back to static text. Password fields and QR content are cleared on
terminal failure. The Windows installer must be rebuilt to include these client changes.

The mapping and local verification record are in the
[feature plan](../../docs/features/active/codef-user-facing-errors.md).
