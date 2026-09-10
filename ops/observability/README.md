# HowMuch observability stack

이 디렉터리는 애플리케이션과 분리된 복사 가능한 Alloy/Loki/Tempo/Prometheus/Grafana 템플릿이다.
실제 EC2 server-owned Compose를 자동으로 수정하지 않는다.

## 1. Private network

애플리케이션과 관측성 stack이 같은 external Docker network를 사용해야 한다. 기본 CIDR을 그대로
쓸 경우 최초 한 번만 실행한다.

```bash
docker network create --driver bridge --subnet 172.30.0.0/16 howmuch-observability
```

이미 운영 중인 network가 있으면 `OBSERVABILITY_NETWORK_NAME`과 `MANAGEMENT_ALLOWED_CIDR`을 그
network의 실제 이름/CIDR로 바꾼다. `MANAGEMENT_ALLOWED_CIDR`을 `0.0.0.0/0`으로 설정하지 않는다.

## 2. Application attach

`app-compose.override.yml`은 기존 애플리케이션 Compose를 private network에 붙이고 OTLP endpoint와
management binding을 주입한다. 현재 저장소의 앱 Compose를 직접 바꾸는 대신 운영자가 다음처럼
적용한다.

```bash
docker compose -f compose.yml -f ops/observability/app-compose.override.yml up -d app
```

management port `9091`은 override에서 host에 publish하지 않는다. Prometheus가 같은 network에서
`app:9091`로 접근한다.

## 3. Start the stack

Grafana admin credential은 shell history나 저장소에 남기지 말고 안전한 환경 주입 방식으로 제공한다.

```bash
export GRAFANA_ADMIN_USER=admin
export GRAFANA_ADMIN_PASSWORD='change-me-before-use'
docker compose -f ops/observability/compose.yml up -d
```

Grafana는 기본적으로 host loopback의 `127.0.0.1:3000`에만 publish한다. Alloy OTLP, Loki, Tempo,
Prometheus management port는 host에 publish하지 않는다.

## 4. Components

| Component | Input/output | Retention or policy |
|---|---|---|
| Alloy | Docker stdout → Loki, OTLP HTTP → Tempo | trace/log routing only; metric scrape 없음 |
| Loki | JSON stdout logs | 15 days |
| Tempo | OTLP traces | 15 days |
| Prometheus | `app:9091/actuator/prometheus` direct scrape | 30 days |
| Grafana | Prometheus/Loki/Tempo UI | anonymous access disabled |

Loki stream label은 `service`, `environment`만 사용한다. trace/span ID는 log JSON 안에 있으며,
Grafana provisioning은 Tempo→Loki와 Loki→Tempo 링크를 모두 설정한다.

## 5. Smoke checks

```bash
docker compose -f ops/observability/compose.yml ps
docker compose -f ops/observability/compose.yml config --quiet
curl --fail http://127.0.0.1:3000/api/health
```

애플리케이션 요청 후 Grafana Explore에서 Loki의 JSON `traceId` 링크로 Tempo trace를 열고, Tempo
span의 Logs for this span 링크로 같은 Loki stream을 확인한다. 이 저장소에서는 실제 EC2에 적용하지
않았으므로 이 확인 결과는 운영자가 별도로 기록한다.
