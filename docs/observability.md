# Operational observability

현재 관측성 계약은 Micrometer Observation과 OpenTelemetry trace를 기준으로 한다. 애플리케이션은
trace를 OTLP HTTP로 Alloy에 전송하고, Prometheus는 Actuator를 직접 scrape한다. Alloy는 Docker
stdout을 Loki로 전달하며 애플리케이션의 백업용 로그 파일은 수집하지 않는다.

## Request correlation

활성 server span이 있는 요청은 `traceId`를 `X-Trace-ID` 응답 헤더와 구조화 로그의 `traceId` 필드에
사용한다. 활성 span이 없으면 UUID나 다른 대체 식별자를 만들지 않으며 헤더도 추가하지 않는다.
클라이언트가 보낸 `X-Request-ID`, `X-Trace-ID`, `traceparent`, `tracestate`, `baggage`는 신뢰하거나
응답·로그에 복사하지 않는다. 현재 애플리케이션은 내부 서비스 간 W3C 전파가 없어 consume/produce
목록을 비워 두며, 외부 호출에는 local client span만 기록한다.

`request_failed`와 `request_exception` 이벤트의 상태·영역 의미는 유지한다. 401/403, validation 400,
application 500, error/async redispatch에서도 trace가 연결된다. 로그·span·metric에는 request body,
query string, 원본 URL, header, credential, token, PIN, OTP, SQL, SQL bind 값, Redis key, 예외 메시지를
기록하지 않는다.

## Logs

| Profile | Console | JSON file |
|---|---|---|
| `local` | plain text | 저장 |
| `dev` | JSON | 저장 |
| `prod` | JSON | 저장 |
| `observability-test` | JSON | 저장 |

파일은 백업용이다. 기본 경로는 `LOG_FILE`이며 기본 회전 정책은 일별 회전, 파일당 `100MB`, 전체
`5GB`, 최대 보관 `15`개다. `LOG_FILE_PATTERN`, `LOG_MAX_FILE_SIZE`, `LOG_TOTAL_SIZE_CAP`,
`LOG_MAX_HISTORY`로 조정한다. Alloy는 Docker stdout만 읽으므로 같은 파일을 다시 Loki로 보내지
않는다.

Loki label은 낮은 카디널리티의 `service`, `environment`만 사용한다. `traceId`와 `spanId`는 JSON
필드로 남기며 label로 만들지 않는다.

## Tracing and metrics

- 기본 sampling은 `1.0`(100%)이며 `OTEL_TRACING_SAMPLING_PROBABILITY`로 조정할 수 있다.
- dev/prod의 기본 OTLP trace endpoint는 `http://alloy:4318/v1/traces`이며
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`로 주입한다.
- HTTP server, RestClient, JDBC query, Redis, Outbox scheduler observation을 사용한다.
- JDBC query observation은 유지하되 SQL·bind 값과 query logging은 비활성화한다.
- exporter는 업무 요청과 분리된 bounded queue, timeout, retry 설정을 사용한다. exporter 장애가
  업무 요청의 성공/실패를 바꾸지 않도록 한다.
- 기존 `howmuch.http.requests`, `howmuch.settlement.outbox.events`,
  `howmuch.settlement.outbox.poll.failures` meter를 유지한다.
- `howmuch.http.request.duration`과 `howmuch.codef.requests` histogram으로 API 및 CODEF p95 latency를
  계산한다. `http.server.requests`는 중복 집계를 피하려고 비활성화한다. Alert rule과 Kafka/outbox
  cross-boundary trace link는 현재 범위가 아니다.

Management endpoint는 `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`만 연다.
Prometheus는 애플리케이션의 management listener를 직접 scrape하고 Alloy는 metric을 scrape하지
않는다.

## Management listener

`local`은 기존처럼 `127.0.0.1:9091`에 바인딩한다. `dev`/`prod`는 컨테이너 내부에서만 접근하도록
`MANAGEMENT_SERVER_ADDRESS=0.0.0.0`을 사용하며 compose에는 management 포트를 host에 publish하지
않는다. `MANAGEMENT_ALLOWED_CIDR`에 Prometheus/관측성 private Docker network CIDR을 설정하고,
보안 체인은 실제 management port와 remote address가 모두 일치할 때만 허용한다.

```bash
curl --fail http://127.0.0.1:9091/actuator/health
curl --fail http://127.0.0.1:9091/actuator/metrics/howmuch.http.requests
curl --fail http://127.0.0.1:9091/actuator/prometheus
```

운영 템플릿은 [ops/observability/README.md](../ops/observability/README.md)에 있다. 실제 EC2
server-owned Compose와 방화벽은 이 저장소가 직접 변경하지 않는다.

## Retention and topology

복사 가능한 별도 stack은 [ops/observability/compose.yml](../ops/observability/compose.yml)로
제공한다. 애플리케이션과 stack은 같은 external Docker network에 연결한다.

```text
Application stdout -> Alloy -> Loki (15 days)
Application OTLP    -> Alloy -> Tempo (15 days)
Prometheus          -> Application /actuator/prometheus (30 days)
Grafana             -> Loki / Tempo / Prometheus
```

Grafana anonymous access는 끄고 admin 계정은 환경변수로 주입한다. Tempo↔Loki trace-to-logs는
`service.name`/`service`를 맞추고, Loki JSON의 `traceId`를 Tempo로 연결하도록 양방향 설정한다.

## Existing meters

| Meter | Tags | Meaning |
|---|---|---|
| `howmuch.http.requests` | `area`, `status` | 완료된 servlet 요청 수 |
| `howmuch.http.request.duration` | `area`, `status` | 완료된 servlet 요청 시간 |
| `howmuch.codef.requests` | `api`, `organization`, `outcome` | CODEF API 결과와 요청 시간 |
| `howmuch.settlement.outbox.events` | `outcome` | 기존 BatchResult 처리 결과 |
| `howmuch.settlement.outbox.poll.failures` | 없음 | Outbox polling 예외 수 |

```promql
sum by (area, status) (rate(howmuch_http_requests_total{status=~"4..|5.."}[5m]))
histogram_quantile(0.95, sum by (le, area) (rate(howmuch_http_request_duration_seconds_bucket[5m])))
sum by (api, organization, outcome) (rate(howmuch_codef_requests_seconds_count[5m]))
sum by (outcome) (rate(howmuch_settlement_outbox_events_total{outcome=~"retried|failed|ownership_lost"}[5m]))
```

## Verification

실행 가능한 범위와 결과는 [active Feature Plan](features/active/observability-otel.md)에 기록한다.
정적 설정 검증, 로컬 PostgreSQL/Redis 기반 검증, CI 결과, EC2 적용 결과를 서로 구분한다.

## Framework references

- [Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [Spring Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html)
- [Grafana Alloy OTLP](https://grafana.com/docs/alloy/latest/collect/opentelemetry-data/)
- [Grafana trace-to-logs](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-trace-to-logs/)
