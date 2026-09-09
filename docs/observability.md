# Operational observability

## Request correlation and safe logs

Every servlet request receives a server-generated UUID in `X-Request-ID`. Search console JSON logs for the matching `requestId`. Incoming IDs are ignored: a client must not be able to inject a credential into this field. `request_failed` contains the HTTP status and a fixed area (`auth`, `settlement`, `other`). Handled exceptions also emit `request_exception` with their type, status and up to 12 application code locations.

The application does not record request bodies, query strings, raw paths, headers, exception messages, causes or suppressed exceptions in these events. This includes PIN, OTP, JWT, participant-context and share-link tokens. Avoid enabling request/body or SQL bind-value debug logging in production. Existing auth audit records and Outbox processing logs remain available. Stack traces intentionally omit exception messages; reproduce with synthetic input when more diagnosis is needed.

The filter runs before Spring Security, so rejected authentication also has a response ID and failure event. Request ID is restored/removed in `finally`. Async redispatch reuses the ID; propagation into arbitrary executor tasks and async work without a servlet redispatch is outside this feature. No current controller uses that async contract.

## Metrics access

The management listener binds to `127.0.0.1:9091` by default (`MANAGEMENT_SERVER_PORT` changes its port). It exposes only `/actuator/health`, `/actuator/metrics` and `/actuator/prometheus`. Its security chain accepts requests only on the actual, separate management port and loopback local address. Setting the management port equal to the application port denies access. Existing application authentication remains in place; ordinary API users cannot read management endpoints through the application port.

Run the collector in the same host/container network namespace, or use an SSH tunnel to the host. Do not publish/proxy this listener publicly. Docker host port mapping alone cannot reach a listener bound to container loopback. Remote collector deployment and durable metric/log storage are not configured by this change.

```bash
curl --fail http://127.0.0.1:9091/actuator/health
curl --fail http://127.0.0.1:9091/actuator/metrics/howmuch.http.requests
curl --fail http://127.0.0.1:9091/actuator/prometheus
```

| Meter | Tags | Meaning |
|---|---|---|
| `howmuch.http.requests` | `area`, `status` | Completed servlet requests, including Security 401/403, validation 400, application errors and unexpected 500 |
| `howmuch.settlement.outbox.events` | `outcome` | Existing BatchResult totals: recovered, claimed, succeeded, retried, failed, ownership_lost |
| `howmuch.settlement.outbox.poll.failures` | none | Outbox polling exceptions |

Authentication failures can be inspected with `area=auth` and 4xx status; protected API authentication refusals are status 401 across all areas. Settlement request failures use `area=settlement` and 4xx/5xx status. A 4xx is a rejected request, not necessarily a server defect. HTTP and Outbox counters represent separate stages and must not be added together as unique failures.

Outbox `claimed` and `recovered` overlap processing outcomes. Counters reflect completed batches; an interrupted/throwing batch may have partially processed rows, so use existing durable Outbox state and logs for reconciliation. Individual automatic-reminder failures retain their existing logs and are not included in the Outbox counters. Counters reset on process restart and require external scraping for history. The first HTTP request or Outbox batch registers its meters.

Prometheus examples:

```promql
sum by (area, status) (rate(howmuch_http_requests_total{status=~"4..|5.."}[5m]))
sum(rate(howmuch_http_requests_total{status="401"}[5m]))
sum by (outcome) (rate(howmuch_settlement_outbox_events_total{outcome=~"retried|failed|ownership_lost"}[5m]))
sum(rate(howmuch_settlement_outbox_poll_failures_total[5m]))
```

User IDs, request IDs, settlement IDs, URLs, token values and exception messages are never metric labels. Default `http.server.requests` instrumentation is disabled to keep raw/fallback URI labels out; JVM/process meters remain provided by Actuator.

## Verification

See the [feature record](features/active/request-observability.md) for execution results and [test setup](testing.md) for database/Redis prerequisites. Integration tests use a random management port to prevent clashes between cached test contexts.

## Framework references

- [Spring Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [Spring Boot management HTTP configuration](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html)
