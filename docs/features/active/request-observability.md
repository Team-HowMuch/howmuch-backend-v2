# 예외 로그·요청 추적·운영 지표 (#311)

- 상태: 구현 중
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
- 구현·검증 결과는 완료 시 갱신한다.
