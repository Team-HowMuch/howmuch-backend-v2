# 예외 로그·요청 추적·운영 지표 (#311)

- 상태: 완료
- 작성일: 2026-09-09
- 이슈: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/311

## 목적과 완료 조건

운영자가 응답의 `X-Request-ID`로 실패 로그를 찾고 요청 오류·인증 실패·정산 실패 추이를 확인한다. PIN·OTP·토큰·주민등록번호가 포함된 실패 요청에서도 로그·지표로 원문이 유출되지 않아야 한다.

- 서버 생성 UUID를 응답 헤더와 MDC에 연결하고 요청 종료 시 정리한다. 클라이언트가 보낸 ID는 신뢰하거나 복사하지 않는다.
- 예외 메시지·본문·쿼리·헤더·실제 URI를 기록하지 않는다. 일반 예외는 종류와 제한된 코드 위치를 구조화 로그에 남긴다.
- HTTP 상태와 고정 영역(auth, settlement, other)별 요청 수, Outbox 처리 결과·폴링 실패 수를 제공한다. 사용자·정산·요청 식별자는 지표 태그에 넣지 않는다.
- 기존 인증 감사 기록, Outbox BatchResult 및 재시도 정책을 재사용한다.

## 범위와 계약

기존 common 예외 경계 및 infra 필터·스케줄러에 적용한다. 신규 Domain, UseCase, Command/Result, Output Port, Application Policy, Web DTO는 N/A다. 관측을 위한 Micrometer는 Infra에서만 사용한다. 기존 API 응답 본문·상태·인증 규칙은 유지한다.

Flyway migration: **No**. DB·Redis 저장 구조 변경이 없어 migration PR 단계는 N/A다. CODEF, 외부 수집 서버 구축, 알림 규칙, 모든 비동기 작업의 분산 추적은 제외한다. 자동 독촉 내부 개별 실패는 기존 로그를 유지하며 이번 배치 지표는 Outbox 기준이다.

Actuator와 Prometheus를 추가한다. 관리 서버는 별도 loopback 포트 9091에서 health/metrics/prometheus만 제공하며 관리 포트에서만 접근을 허용한다. 일반 서비스 포트의 인증 경계를 보존한다. 기본 콘솔 로그는 Logstash JSON을 사용한다.

## 시나리오 및 영향

- 정상 요청: 새 ID 응답, 요청 수 증가, MDC 복구.
- 검증 오류·ApplicationException·인증 거절: 기존 4xx, ID가 연결된 실패 로그, HTTP 상태별 지표.
- 일반 오류·필터 오류: 기존 일반 500 응답과 메시지 없는 예외 진단.
- 정산 HTTP 실패: settlement 영역의 4xx/5xx; Outbox: 기존 BatchResult의 succeeded/retried/failed/ownershipLost를 집계.
- 경계: 위조 ID·민감값을 포함한 URI/본문/예외 원인, 연속 요청 MDC 누출, 관리 경로의 일반 포트 접근 차단.

영향 파일: `GlobalExceptionHandler`, 신규 `common/exception/SafeExceptionLogger`, 신규 `infra/observability` 필터·관리 보안 설정, `SettlementNotificationOutboxScheduler`, `build.gradle`, `application.yaml`, 관측 테스트 및 운영 문서.

## 결정

- 정규식 마스킹 대신 로그 입력을 허용된 기술 필드로 제한한다. 원문 Throwable은 넘기지 않아 cause/suppressed에 숨은 민감값도 기록하지 않는다.
- URL 템플릿별 태그 대신 고정 영역만 사용해 인증 이전 경로와 공유 링크의 고카디널리티를 막는다. 기본 HTTP 지표는 비활성화하고 명시적 카운터를 사용한다.
- DB 감사 테이블에 요청 ID를 추가하지 않는다. 기존 감사는 유지하고 HTTP 관측을 별도로 연결한다.

## 구현 및 커밋 경계

1. `feat(observability): 요청 추적과 안전한 예외 로그 추가` — 설계, 요청 필터, 공통 예외 로깅, JSON 로그.
2. `feat(observability): 요청 및 정산 실패 운영 지표 제공` — Actuator, 제한된 관리 접근, 요청·Outbox 지표, 운영 문서.
3. `test(observability): 실패 추적과 민감값 비노출 검증` — 단위·HTTP 회귀 및 완료 기록.

## 검증 계획

`./gradlew compileJava`, 대상 테스트, 실제 HTTP/관리 서버 검증, `git diff --check`, 문서 상대 링크 확인. 테스트 전 PostgreSQL·Redis 준비 상태를 확인하고 격리 스키마를 사용한다. 상위 AGENTS.md의 변경 동작 테스트 추가 지침을 적용한다(하위 workflow의 오래된 테스트 추가 금지 문구보다 우선).

## 진행 및 결과

- 요구사항과 최신 develop 및 최근 PR 형식 확인 완료. 작업 트리의 기존 변경 없음.
- 구현과 검증을 완료했다. 아래 결과 및 운영 문서를 기준으로 검토한다.

- 추가 경계 검증에서 유효한 관리자 JWT로 서비스 포트의 `/actuator/metrics`에 접근하면 기존 MVC 일반 예외 처리 때문에 500이 반환됨을 확인했다. 관리 경로 전체에 보안 체인을 적용해 403으로 차단하도록 보완한다. 이 수정은 `fix(observability): 서비스 포트의 관리 경로 접근 차단` 커밋으로 분리한다.


## 완료 기록

- 완료일: 2026-09-09
- 서버 발급 `X-Request-ID`와 JSON MDC 연계, 원문 Throwable을 제외한 예외 진단, 고정 태그 HTTP/Outbox 카운터, loopback 관리 서버를 구현했다.
- [운영 문서](../../observability.md)에 조회 명령·PromQL·수집 위치·지표 해석과 한계를 기록했다.
- 기존 인증 감사 저장·Outbox 재시도 및 상태 변경 계약을 보존했다. DB/Redis 구조, 외부 API, 신규 UseCase/Port/Policy는 변경하지 않았다.
- 커밋은 요청 추적, 운영 지표, 관리 경로 보완, 회귀 테스트의 4개 의도로 분리했다.

### 검증 결과

| 항목 | 결과 |
|---|---|
| Java 21 `./gradlew compileJava` | 통과 |
| `RequestObservabilityTest` | 13개 통과: ID 위조 방지·고유성·MDC 복구, 4xx/5xx, 메시지/cause/suppressed 비노출, 필터/I/O 실패, ERROR/ASYNC 재디스패치 |
| `SettlementOutboxMetricsTest` | 1개 통과: 기존 BatchResult 및 폴링 실패 계수 |
| `ObservabilityHttpIntegrationTest` | 4개 통과: 실제 Tomcat HTTP 및 JSON 로그, 401·400·500, Prometheus/metrics 노출 제한 |
| `SecurityFilterChainIntegrationTest` | 22개 통과: 기존 실제 JWT·Redis·PostgreSQL 보안 회귀 21개와 관리자 JWT의 서비스 포트 관리 경로 차단 |
| `GlobalExceptionHandlerTest` | 기존 14개 통과 |
| `AuthRecordMaskingTest` | 기존 51개 통과 |
| `SettlementNotificationOutboxWorkerTest` | 기존 6개 통과 |
| 문서 상대 링크 및 `git diff --check` | 통과 |

총 111개의 서로 다른 관련 테스트를 검증했다. 최초 전체 대상 실행의 관리 경로 테스트 2개가 실패했고, 경로 차단 및 오류 재디스패치 방지 보완 후 해당 두 테스트를 포함한 실제 HTTP 26개를 다시 실행해 모두 통과했다. 나머지 85개는 직전 실행에서 통과했다.

PostgreSQL `pg_isready` 및 Redis `PING`을 확인한 뒤 로컬 기본 연결 설정과 격리 스키마 `issue311_observability_test`를 사용했다. 실제 서버 시작에서 Flyway와 Hibernate 검증이 수행됐고 테스트 서버는 종료됐다. 테스트가 만든 사용자·세션은 기존 정리 로직으로 제거하며, 별도 테스트 스키마는 유지한다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DB_URL='jdbc:postgresql://localhost:5432/howmuch?currentSchema=issue311_observability_test' \
SPRING_FLYWAY_SCHEMAS=issue311_observability_test \
./gradlew compileJava test \
  --tests '*RequestObservabilityTest' \
  --tests '*SettlementOutboxMetricsTest' \
  --tests '*ObservabilityHttpIntegrationTest' \
  --tests '*GlobalExceptionHandlerTest' \
  --tests '*AuthRecordMaskingTest' \
  --tests '*SecurityFilterChainIntegrationTest' \
  --tests '*SettlementNotificationOutboxWorkerTest'
```

최종 관리 보안 수정 후에는 위 환경에서 `./gradlew compileJava test --tests '*ObservabilityHttpIntegrationTest' --tests '*SecurityFilterChainIntegrationTest'`를 실행했다.

### 미수행 및 한계

- 전체 프로젝트 테스트·prod 프로필·외부 수집 서버 연동은 로컬에서 실행하지 않았다. 전체 테스트는 PR CI 대상이며 외부 수집 배포는 이슈 범위 밖이다.
- 관리 지표는 같은 호스트/컨테이너 네트워크 영역의 collector가 수집해야 한다. 원격 프록시나 방화벽 공개 설정은 추가하지 않았다.
- 임의 executor 내부 MDC 전파·비동기 재디스패치 없는 완료는 지원 범위 밖이다. 현재 컨트롤러는 이 비동기 계약을 사용하지 않는다.
- 중간에 예외로 종료된 Outbox 배치의 부분 처리와 자동 독촉 내부 개별 실패는 기존 로그·저장 상태로 확인한다. 메트릭은 영속 감사 기록의 대체가 아니다.
