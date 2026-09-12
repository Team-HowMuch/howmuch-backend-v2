# Feature Plan: OTel 기반 관측성 구조 전환

- Status: Implemented
- Last updated: 2026-09-13
- Related issue: #358
- Related ADR: -

## 1. 목적

### 문제

기존 요청 추적은 서버가 UUID를 만들어 `X-Request-ID`와 `requestId` MDC에 넣는 방식이다. HTTP
요청, 외부 client, JDBC/Redis, Outbox scheduler를 하나의 trace로 조회할 수 없고, 운영 로그·메트릭·
트레이스 수집 구조도 저장소에 재현 가능한 템플릿으로 정리되어 있지 않다.

### 성공 기준

- [x] 활성 server span의 trace ID가 `X-Trace-ID`와 JSON 로그에 연결된다.
- [x] 외부 입력 trace context와 외부 호출 전파 헤더를 신뢰하지 않는다.
- [x] HTTP/RestClient/JDBC/Redis/Outbox 관측과 민감정보 제거 정책이 동작한다.
- [x] local plain console, dev/prod/test JSON console, 모든 profile의 JSON file 저장이 유지된다.
- [x] 기존 custom meter와 `http.server.requests` 비활성 정책을 유지한다.
- [x] 복사 가능한 Alloy/Loki/Tempo/Prometheus/Grafana Compose가 같은 private network에서 동작한다.
- [x] 관리 listener가 local loopback 또는 허용된 private CIDR에서만 접근된다.

### 제외

Flyway migration, DB schema 변경, alert rule, Kafka/outbox cross-boundary
trace link, 실제 EC2 server-owned Compose·방화벽 변경은 제외한다.

## 2. 현재 코드 기준 분석

- `RequestTraceFilter`: UUID/X-Request-ID/requestId MDC 제거 대상
- `GlobalExceptionHandler`/`SafeExceptionLogger`: `request_exception` 의미와 민감정보 비노출 유지 대상
- `SettlementNotificationOutboxScheduler`: 기존 meter·로그를 유지하면서 scheduler observation 추가
- `ManagementSecurityConfig`: 실제 management port와 `MANAGEMENT_ALLOWED_CIDR` 검증 추가
- `application*.yaml`: profile별 logging, OTLP, sampling, propagation, file rotation 설정
- `compose.yml`/`Dockerfile`: stdout 수집과 JSON file backup을 위한 `/app/logs`만 제공
- `ops/observability`: 애플리케이션과 분리된 운영 stack 템플릿 추가

기존 완료 문서 [request-observability](../completed/request-observability.md)는 당시 UUID 기반 구현의
역사적 기록으로 보존한다. 현재 운영 계약은 [observability.md](../../observability.md)를 따른다.

## 3. 계약

### Trace

| 항목 | 계약 |
|---|---|
| 응답 header | active span의 32자리 lowercase `traceId`를 `X-Trace-ID`에 설정 |
| no active span | UUID fallback과 header를 만들지 않음 |
| 입력 header | `X-Request-ID`, `X-Trace-ID`, `traceparent`, `tracestate`, `baggage`를 신뢰하지 않음 |
| propagation | 현재 내부 서비스가 없어 `consume=[]`, `produce=[]`; 외부 요청은 local client span만 기록 |
| root trace | public boundary에서는 수신한 W3C context를 사용하지 않고 새 root로 시작 |

### Log and metric

- 구조화 로그에는 `traceId`, `spanId`, 고정 `event/status/area`만 허용한다.
- payload, credential, token, raw URL/query, SQL/bind, Redis key, exception message는 기록하지 않는다.
- Loki label은 `service`, `environment`만 사용한다.
- 기존 `howmuch.http.requests`, Outbox custom meter와 `http.server.requests` 비활성 정책은 유지한다.

### Management and retention

- local listener: `127.0.0.1:9091`
- dev/prod listener: 컨테이너 내부 `0.0.0.0:9091`, host publish 없음
- 허용 범위: `MANAGEMENT_ALLOWED_CIDR`와 실제 management port 동시 검증
- Loki/Tempo: 15일, Prometheus: 30일
- Grafana anonymous access: disabled

## 4. 영향 범위

| 영역 | 변경 |
|---|---|
| Dependency | Spring Boot OpenTelemetry starter, JDBC observation adapter |
| Infra | trace header filter, observation redaction, management CIDR security |
| Scheduler | Outbox polling observation 추가 |
| Config | profile별 logging/OTLP/sampling/rotation/management 설정 |
| Runtime template | app log volume/network override, Alloy/Loki/Tempo/Prometheus/Grafana stack |
| Persistence | 변경 없음; Flyway migration 없음 |
| API response body | 변경 없음; 실패 status와 기존 event 의미 유지 |

## 5. 구현 순서

1. [x] 최신 `origin/develop`에서 `feat/observability-otel` 브랜치 생성
2. [x] `docs(observability): 관측성 전환 계획 수립` 경계의 계획 문서 작성
3. [x] OTel dependency, sampling/export/propagation 및 profile logging 설정
4. [x] `X-Trace-ID`, observation redaction, management CIDR security 구현
5. [x] HTTP/Outbox 관측 회귀 테스트 보강
6. [x] 운영 템플릿과 현재 운영 문서 추가
7. [x] compile/test/static configuration 검증 및 결과 기록
8. [x] push 후 `develop` 대상 PR #359 생성

## 6. 검증 계획

| 구분 | 검증 |
|---|---|
| 단위 | active trace header, no-span no-header, redispatch 중복 집계, Outbox observation, 민감 key/error 제거 |
| 통합 | 401/403, validation 400, 500, management port, JSON console/file, custom meter |
| 외부 경계 | W3C header 미전파, client span redaction, exporter 장애 fail-open |
| 정적 | YAML 중복 키, Compose config, Alloy/Loki/Tempo/Prometheus/Grafana 파일 |
| 로컬 | PostgreSQL·Redis 준비 후 관련 Spring HTTP/Actuator 테스트 |
| CI | `./gradlew compileJava` 및 관련 테스트 결과 |
| EC2 | 직접 변경하지 않으며 운영자가 적용한 결과만 별도 기록 |

실행 명령:

```bash
./gradlew compileJava
./gradlew test --tests '*RequestObservabilityTest' \
  --tests '*ObservabilityHttpIntegrationTest' \
  --tests '*SettlementOutboxMetricsTest'
git diff --check
git status --short --untracked-files=all
```

## 7. 예상 커밋 경계

```text
1. docs(observability): 관측성 전환 계획 수립
2. feat(observability): OTel 공통 계측 설정 추가
3. feat(observability): trace ID 응답 상관관계 적용
4. fix(observability): 관리 포트 private CIDR 제한
5. feat(observability): 관측 데이터 민감정보 제거
6. feat(observability): Outbox polling trace 계측
7. test(observability): HTTP trace 전파와 실패 경계 검증
8. feat(observability): JSON 로그 백업 저장 연결
9. feat(observability): 관측성 운영 스택 템플릿 추가
10. docs(observability): 운영 계약과 검증 결과 갱신
```

## 8. 진행 기록

- 최신 `origin/develop` 확인 및 `feat/observability-otel` 브랜치 생성 완료
- DB schema 변경이 없어 Flyway migration은 생성하지 않음
- 관련 단위·보안·HTTP 통합 테스트와 전체 Gradle 테스트 통과
- Compose, YAML, Alloy fmt 정적 검증 통과
- 실제 EC2 server-owned Compose 적용은 수행하지 않음
