# Feature Plan: Flyway schema baseline

- Status: Completed
- Last updated: 2026-08-28
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

Hibernate가 로컬에서 스키마를 직접 갱신하고 그 외 환경에서는 검증만 하므로, CODEF 외부 연결과 계좌 테이블을 포함한 현재 스키마를 새 환경과 기존 환경에 동일하게 배포할 수 없다.

### Why now

외부 은행 연결과 계좌 연동 기능이 신규 테이블을 요구한다. 애플리케이션 배포 전에 수동 DDL을 별도로 관리하면 코드와 운영 스키마가 어긋날 수 있다.

### Success criteria

- [x] 새 PostgreSQL 데이터베이스에서 V1부터 현재 버전까지 순서대로 스키마를 생성한다.
- [x] 기존 데이터베이스는 명시적으로 한 번 baseline한 뒤 후속 migration을 적용한다.
- [x] 모든 프로필에서 Hibernate는 Flyway가 적용한 스키마를 `validate`만 한다.

### Out of scope

- 기존 운영 데이터 정제 또는 rollback 자동화
- 새 테스트 케이스

## 2. Domain analysis

- Existing domain to reuse: 사용자, 인증 감사, 외부 연결, 계좌 영속성
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: Flyway는 도메인 규칙을 변경하지 않고 현재 JPA 스키마를 배포한다.

## 3. Scenarios

### Normal flow

1. 새 데이터베이스로 애플리케이션을 시작한다.
2. Flyway가 V1 사용자·인증 스키마와 V2 외부 연결·계좌 스키마를 적용한다.
3. Hibernate가 같은 스키마를 검증하고 애플리케이션이 시작한다.

### Failure flow

- 기존 비어 있지 않은 데이터베이스에 Flyway 이력이 없으면 기본 설정으로 시작을 중단한다.
- 운영자가 대상 DB를 확인하고 최초 1회 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`로 시작하면 version 1로 baseline한 뒤 V2를 적용한다.

### Boundary conditions

- baseline 자동 실행은 잘못된 데이터베이스를 정상 기준선으로 오인할 수 있어 기본값을 `false`로 둔다.

## 4. Contracts

- Input Port / Output Port / Web API: N/A; 데이터베이스 초기화 설정 변경이다.
- Database changes: `users`, `auth_audit_logs` 기준선과 `external_connections`, `connected_institutions`, `accounts` 신규 마이그레이션
- Environment variable: 기존 DB 최초 전환 시에만 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`

## 5. Design decisions

- Spring Boot가 이미 제공하는 Flyway 자동 실행을 사용하고 별도 Config 클래스를 만들지 않는다.
- V1은 기존 사용자·인증 스키마 기준선, V2는 이번 외부 연결·계좌 스키마로 분리한다.
- Hibernate `ddl-auto`는 모든 프로필에서 `validate`로 통일한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| Persistence | 현재 JPA 테이블을 SQL로 관리 | `db/migration/V1`, `V2` |
| Configuration | Flyway 활성화, Hibernate 자동 변경 제거 | `application*.yaml` |
| Documentation | 스키마 관리 원칙과 배포 조건 갱신 | `AGENTS.md`, `docs/architecture.md` |

Existing behavior that must remain unchanged:

- 엔티티 매핑과 API 계약은 변경하지 않는다.
- 기존 DB의 V1 대상 테이블은 다시 생성하거나 수정하지 않는다.

## 7. Implementation plan

- [x] UseCase, Ports, Domain, Application, Web: N/A; 애플리케이션 계약 변경 없음
- [x] Flyway 설정과 V1/V2 SQL
- [x] 문서와 검증 결과

### Expected commit boundaries

```text
build(database): Flyway 기준선과 외부 계좌 스키마를 설정
```

## 8. Verification

```bash
./gradlew clean compileJava --console=plain
./gradlew test --console=plain
git diff --check
git status --short --untracked-files=all
```

PostgreSQL과 Redis가 실행 중일 때 애플리케이션 Context 테스트로 실제 마이그레이션을 확인한다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Java compile | Pass | `./gradlew clean compileJava --console=plain` |
| Package migrations | Pass | `./gradlew bootJar --console=plain`, JAR에 V1/V2 포함 |
| PostgreSQL migration | Pass | 임시 PostgreSQL에서 V1부터 현재 버전까지 적용하고 전체 412개 테스트 통과 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-20 | 현재 엔티티와 Flyway 의존성 확인, 전환 계약 확정 | 설정·SQL 적용과 검증 | 기존 DB 최초 실행은 명시적 baseline 필요 |
| 2026-08-20 | V1/V2, 전 프로필 `validate`, 관련 문서와 패키징 검증 완료 | PostgreSQL 가동 후 Context 테스트 | 로컬 PostgreSQL·Redis·Docker 중지 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-20 | baseline 자동 실행 기본값은 false | 잘못된 대상 DB를 정상 기준선으로 등록하는 위험 방지 | 기존 DB는 최초 1회 환경변수 필요 |

## 11. Completion record

- Completed on: 2026-08-20
- Final behavior: Spring Boot 시작 시 Flyway가 마이그레이션을 적용하고 Hibernate는 결과 스키마만 검증한다.
- Unverified items: 기존 운영 DB의 일회성 baseline은 대상 스키마를 확인한 뒤 운영 절차로 수행해야 한다.
- Remaining risks: 기존 DB가 V1 기준선과 다르면 baseline 전에 스키마를 먼저 정합화해야 한다.
- Follow-up work: 새 migration 추가 시 신규 PostgreSQL 적용과 기존 스키마 upgrade 경로를 계속 검증한다.
