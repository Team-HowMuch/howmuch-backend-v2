# Feature Plan: Management Readiness Loopback Access

- Status: Ready for review
- Last updated: 2026-10-14
- Related issue: 배포 readiness check가 HTTP 403으로 실패
- Related ADR: N/A

## 1. Purpose

### Problem

운영 관측성 설정에서 `MANAGEMENT_ALLOWED_CIDR`을 Prometheus 전용 Docker network로 제한하면, 같은 컨테이너 안에서 `127.0.0.1`로 readiness를 확인하는 배포 스크립트까지 거부된다. 현재 CD 로그는 이 응답을 180초 동안 재시도한 뒤 readiness timeout으로 종료한다.

### Why now

애플리케이션 배포가 헬스체크에서 중단되어 배포 성공 여부를 확인할 수 없다.

### Success criteria

- [x] 관리 포트의 loopback 요청은 `MANAGEMENT_ALLOWED_CIDR`이 loopback을 포함하지 않아도 허용된다.
- [x] 관리 포트가 아닌 애플리케이션 포트의 Actuator 요청은 계속 거부된다.
- [x] 비-loopback 요청은 계속 설정된 `MANAGEMENT_ALLOWED_CIDR` 범위 안에서만 허용된다.
- [ ] CI와 PR이 성공한다.

### Out of scope

- 관리 포트를 호스트에 게시하거나 `MANAGEMENT_ALLOWED_CIDR`을 넓히는 작업
- Actuator endpoint 노출 범위 변경
- 운영 EC2 설정 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: N/A. Infra security configuration only.
- Reason a new domain is required: N/A.
- Relationships to other domains: 배포 readiness check와 관측성 관리 포트의 접근 제어.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| Management listener | Actuator를 제공하는 분리된 서버 포트 | `management.server.port` |
| Loopback request | 컨테이너 내부에서 관리 listener로 보내는 자기 자신 요청 | `InetAddress.isLoopbackAddress()` |
| Allowed CIDR | Prometheus 등 사설 네트워크 scrape 요청의 허용 범위 | `MANAGEMENT_ALLOWED_CIDR` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 배포 스크립트 | 컨테이너 내부 loopback으로 readiness 확인 | HTTP GET `/actuator/health/readiness` |
| Prometheus | 허용된 사설 네트워크에서 metrics scrape | HTTP GET `/actuator/prometheus` |

### State and invariants

- State transitions: N/A.
- Rules that must always hold: 관리 포트는 애플리케이션 포트와 분리되어야 하며, loopback 또는 설정된 CIDR에서 온 요청만 허용한다.
- Invalid states: 애플리케이션 포트에서 Actuator 요청을 허용하거나 모든 원격 CIDR을 허용하는 상태.

## 3. Scenarios

### Normal flow

1. 배포 스크립트가 컨테이너 내부에서 `127.0.0.1:9091/actuator/health/readiness`를 요청한다.
2. 보안 설정이 분리된 management port와 loopback source를 확인한다.
3. readiness 상태를 확인하고 정상 시 HTTP 200을 반환한다.

### Failure flow

- Condition: 비-loopback source가 `MANAGEMENT_ALLOWED_CIDR` 밖에서 management endpoint를 요청한다.
- Returned error or state transition: HTTP 403.

### Boundary conditions

- IPv4 및 IPv6 loopback 주소를 허용한다.
- 관리 포트와 애플리케이션 포트가 같거나 요청의 local port가 관리 포트와 다르면 거부한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: N/A
Command: N/A
Result: N/A
Web Request DTO -> Command: N/A
Result -> Web Response DTO: N/A
```

### Output Ports

N/A. 외부 Application Port를 추가하지 않는다.

### Application Policy

N/A. HTTP/네트워크 접근은 Infra Security 책임이다.

### Web API

```text
GET /actuator/health/readiness
Request: 없음
Response: Spring Boot readiness health response
Errors: HTTP 403 for disallowed access; HTTP 503 when readiness is refusing traffic
Authentication: 내부 management listener, loopback 또는 허용 CIDR
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: 없음
- Flyway migration required (`Yes` / `No`): No
- Planned migration: N/A
- Migration pull request (`Pending` / URL): N/A
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: Infra security 설정에서 management port, local port, remote address를 검증한다.
- Behavior owned by Domain: N/A.
- Decisions owned by Application Policy: N/A.
- Flow orchestrated by Application: N/A.

### Technology and patterns

- Constraint to solve: loopback 배포 probe와 Prometheus 사설 CIDR을 동시에 허용해야 한다.
- Selected approach: 현재 management port 및 local port 검증을 유지하고, 원격 주소 조건을 `loopback || within configured CIDR`로 판정한다.
- Reason for selection: 컨테이너 내부 준비 상태 검사를 허용하면서 원격 접근은 기존 allowlist로 제한한다.
- Alternatives considered: `MANAGEMENT_ALLOWED_CIDR`을 넓히거나 배포 probe를 사설 네트워크 source로 변경하는 방식은 접근 범위 또는 운영 결합도를 키운다.
- Trade-offs and limits: 컨테이너 내부 loopback에 접근할 수 있는 프로세스는 관리 endpoint에 접근할 수 있다. management port는 별도 포트이고 호스트에 게시하지 않는 기존 경계를 유지한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | Actuator 응답 정책만 수정 | `/actuator/health/readiness` |
| Application | 없음 | N/A |
| Domain | 없음 | N/A |
| Persistence | 없음 | N/A |
| Redis | 없음 | N/A |
| Security | loopback과 CIDR allowlist를 함께 허용 | `ManagementSecurityConfig` |
| Configuration | 환경 변수와 profile 변경 없음 | `MANAGEMENT_ALLOWED_CIDR` |
| Documentation | 관리 포트 접근 정책 설명 갱신 | `docs/observability.md` |

Existing behavior that must remain unchanged:

- 애플리케이션 포트에서 Actuator를 접근할 수 없다.
- 비-loopback 요청은 기존 `MANAGEMENT_ALLOWED_CIDR` 검사를 통과해야 한다.
- readiness가 준비되지 않은 경우 기존 HTTP 503 동작을 유지한다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A` — persistence 변경 없음)
- [x] UseCase and Application Command/Result (`N/A` — Application contract 변경 없음)
- [x] Required Output Ports (`N/A` — 외부 Application 경계 변경 없음)
- [x] Domain models and rules (`N/A` — Domain 변경 없음)
- [x] Application Policies (`N/A` — business policy 변경 없음)
- [x] Application Service (`N/A` — Application 변경 없음)
- [x] Output Adapters (`N/A` — Adapter 변경 없음)
- [x] Web Request/Response DTOs, Controllers, and Swagger (`N/A` — Web API 계약 변경 없음)
- [x] Infra configuration and Bean wiring
- [x] Documentation and verification results

### Expected commit boundaries

```text
fix(security): readiness loopback 헬스체크 허용
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests 'com.amateur.howmuchbackend.infra.observability.ObservabilityHttpIntegrationTest'
git diff --check
git status --short --untracked-files=all
```

The observability integration test requires the disposable PostgreSQL and Redis services configured by CI.

### Completion criteria

- [x] Loopback health check succeeds when `MANAGEMENT_ALLOWED_CIDR` contains only the private scrape network.
- [x] Application port remains closed to Actuator requests.
- [ ] CI and PR checks pass.

### Results

| Item | Result | Evidence |
|---|---|---|
| Compile | Passed | `./gradlew compileJava --no-daemon` |
| Integration test | Passed | `scripts/test.sh test --tests 'com.amateur.howmuchbackend.infra.observability.ObservabilityHttpIntegrationTest'` with disposable PostgreSQL/Redis; Node tests 35/35 passed |
| CI | Pending | GitHub Actions PR checks |
| PR | Pending | GitHub pull request |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-10-14 | 최신 `origin/develop`에서 격리 브랜치를 만들고 보안 규칙, 회귀 테스트, 배포/관측성 문서를 수정함. 컴파일과 관련 통합 테스트 통과 | 변경 커밋, PR CI 확인 | 실제 EC2 환경 변수는 이 작업에서 변경하지 않음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-10-14 | management port loopback은 allowlist CIDR과 별도로 허용 | 배포 probe의 source는 컨테이너 내부 loopback이며 Prometheus CIDR은 별도 네트워크다 | management listener 및 호스트 비게시 조건은 유지 |

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: Pending
- Remaining risks: Pending
- Follow-up work: Pending
