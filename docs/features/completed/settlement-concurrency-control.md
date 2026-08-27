# Feature Plan: 정산 동시 수정 제어

- Status: Completed
- Last updated: 2026-08-21
- Related issue: [#28 정산 초안 및 기본 정보 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/28)
- Related review: [PR #60 Codex review](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/60#discussion_r3827191570)
- Related ADR: N/A
- Parent specification: [정산 초안 및 기본 정보](settlement-core-spec.md)

## 1. Purpose

### Problem

동일한 DRAFT 정산을 수정하는 두 PATCH 요청이 겹치면 각 요청이 같은 원본 Aggregate를 읽을 수 있다. 현재 영속 Adapter는 제목, 총액, 상태를 읽은 시점의 값으로 모두 복사하므로 마지막에 커밋한 요청이 앞선 요청의 변경을 조용히 되돌릴 수 있다.

### Why now

현재도 제목만 변경하는 요청과 총액만 변경하는 요청 사이에서 lost update가 발생할 수 있다. 이후 요청 링크와 상태 전이 기능이 추가되면 오래된 PATCH가 REQUESTED 상태를 DRAFT로 되돌리는 더 큰 정합성 문제로 이어질 수 있으므로 정산 영속 구조를 도입하는 PR에서 수정한다.

### Success criteria

- [x] 동일한 정산 버전을 수정하는 동시 요청 중 하나만 반영된다.
- [x] 뒤늦은 요청은 기존 변경을 덮어쓰지 않고 HTTP 409 `CONCURRENT_UPDATE`를 반환한다.
- [x] 충돌 후 최신 정산을 조회하여 재시도하면 정상 반영된다.
- [x] 순차 PATCH와 기존 인증·권한·상태 검증은 그대로 동작한다.

### Out of scope

- API Request 또는 Response에 version 노출
- 충돌 요청의 서버 자동 재시도
- 비관적 락과 전체 정산 수정 흐름의 직렬화
- 정산 외 Entity에 대한 낙관적 락 일괄 적용

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: 기존 settlement Domain을 그대로 사용한다.
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 사용자 인증·권한 흐름은 변경하지 않는다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 동시 수정 | 같은 정산 버전을 기반으로 겹쳐 실행되는 둘 이상의 변경 | concurrent update |
| 낙관적 락 | 저장 시 읽었던 version이 현재 DB version과 같은지 검사하는 방식 | JPA `@Version` |
| 충돌 | 먼저 완료된 변경 때문에 후속 요청의 version 조건이 더 이상 맞지 않는 상태 | `CONCURRENT_UPDATE` |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 정산 생성자 | 다른 요청의 변경을 잃지 않고 초안을 수정한다. | 기존 `UpdateSettlementDraftUseCase` |

### State and invariants

- State transitions: 기존 정산 상태 전이 규칙을 변경하지 않는다.
- Rules that must always hold: 오래된 Aggregate 스냅샷은 최신 정산 상태를 덮어쓸 수 없다.
- Invalid states: 앞선 변경을 감지하지 못하고 오래된 title, totalAmount 또는 status가 저장된 상태.

## 3. Scenarios

### Normal flow

1. 생성자가 DRAFT 정산을 조회하고 PATCH를 요청한다.
2. JPA가 정산과 현재 version을 함께 로드한다.
3. Domain 규칙을 검증하고 변경을 저장한다.
4. UPDATE가 기존 version과 일치하는 행을 변경하고 version을 증가시킨다.
5. 변경된 정산을 HTTP 200으로 반환한다.

### Failure flow

- 두 요청이 같은 version을 읽고 겹쳐 저장한다: 먼저 저장한 요청은 성공하고 후속 요청은 HTTP 409 `CONCURRENT_UPDATE`를 반환한다.
- 충돌 요청이 최신 상태를 조회한 후 다시 PATCH한다: 새 version을 기준으로 정상 저장한다.

### Boundary conditions

- 서로 다른 필드를 변경해도 같은 정산 Aggregate를 수정하므로 충돌로 처리한다.
- 같은 필드를 변경하는 동시 요청도 동일하게 충돌로 처리한다.
- 낙관적 락 예외는 기술 예외 원문을 노출하지 않고 공통 오류 응답으로 변환한다.
- 클라이언트가 오래 보관한 화면 상태까지 검출하는 API version 계약은 이번 범위에 포함하지 않는다. 서버 트랜잭션이 실제로 겹쳐 같은 DB version을 읽은 경우를 보호한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: UpdateSettlementDraftUseCase.update(settlementId, command)
Command: 기존 UpdateSettlementDraftCommand(title?, totalAmount?)
Result: 기존 SettlementResult
Web Request DTO -> Command: 변경 없음
Result -> Web Response DTO: 변경 없음
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
| --- | --- | --- | --- |
| `LoadSettlementPort` | 현재 version이 포함된 영속 Entity를 트랜잭션 안에서 로드한다. | `SettlementService` | 기존 `SettlementPersistenceAdapter` |
| `SaveSettlementPort` | JPA version 조건으로 정산 변경을 저장한다. | `SettlementService` | 기존 `SettlementPersistenceAdapter` |

새 Port는 추가하지 않는다. version은 JPA Persistence Adapter가 소유하는 기술적 동시성 정보이며 기존 Application 계약은 변경하지 않는다.

### Application Policy

- N/A: 정산 단일 행의 저장 충돌은 DB/JPA 동시성 제어이며 여러 Domain 사실을 결합하는 비즈니스 Policy가 아니다.

### Web API

```text
PATCH /api/v2/settlements/{settlementId}
Request: 기존 { title?, totalAmount? }
Response: 기존 SettlementWebResponse
Errors: 기존 오류 + HTTP 409 CONCURRENT_UPDATE
Authentication: required
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: `settlements.version BIGINT NOT NULL DEFAULT 0`을 Flyway V4로 추가하고 `SettlementJpaEntity.version`에 `@Version`을 적용한다.
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 없음.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 기존 DRAFT 수정 규칙은 Domain에 유지하고 동시 저장 충돌 감지는 JPA Entity가 담당한다.
- Behavior owned by Domain: 제목·금액 유효성과 DRAFT 수정 가능 여부.
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 기존 조회 → 권한 검증 → Domain 변경 → 저장 흐름을 유지한다.

### Technology and patterns

- Constraint to solve: 오래된 Aggregate 전체 저장이 앞선 변경을 조용히 덮어쓰지 않아야 한다.
- Selected approach: JPA `@Version` 낙관적 락과 공통 HTTP 409 예외 변환.
- Reason for selection: 모든 정산 변경 필드를 일관되게 보호하고 충돌이 드문 수정 흐름에서 DB 락 대기 없이 stale write를 거부한다.
- Alternatives considered: 비관적 write lock은 모든 변경 경로가 같은 잠금 조회를 사용해야 하고 요청을 대기시킨다. 필드별 조건부 UPDATE는 Domain 전체 저장 흐름을 우회한다. `@DynamicUpdate`만으로는 DRAFT 검증 이후 발생한 상태 전이 경쟁 조건을 막지 못한다.
- Trade-offs and limits: 충돌한 요청은 자동 병합되지 않으며 클라이언트가 최신 상태를 조회하고 명시적으로 재시도해야 한다.

이 결정은 settlement 영속 구현에 한정되므로 별도 ADR을 만들지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | PATCH에 409 `CONCURRENT_UPDATE` 추가 | `SettlementDocs` |
| Application | 기존 트랜잭션 경계 유지 | `SettlementService.update` |
| Domain | 변경 없음 | `Settlement` |
| Persistence | version 컬럼·JPA 낙관적 락 추가 | Flyway V4, `SettlementJpaEntity` |
| Redis | 없음 | N/A |
| Security | 변경 없음 | 기존 `CurrentActorPort` 흐름 |
| Configuration | 변경 없음 | N/A |
| Documentation | 동시성 계약과 검증 결과 기록 | 이 문서, 부모 Spec |

Existing behavior that must remain unchanged:

- 정상적인 순차 PATCH는 HTTP 200을 반환한다.
- DRAFT가 아닌 정산은 `SETTLEMENT_NOT_EDITABLE`로 거부한다.
- 다른 사용자의 수정은 `SETTLEMENT_ACCESS_DENIED`로 거부한다.
- 기존 API Request와 성공 Response 스키마를 변경하지 않는다.

## 7. Implementation plan

- [x] 기존 UseCase와 Command/Result를 재사용한다.
- [x] 새 Output Port가 필요하지 않음을 확인한다.
- [x] Domain 규칙 변경이 필요하지 않음을 확인한다.
- [x] Application Policy가 필요하지 않음을 확인한다.
- [x] 기존 Application 트랜잭션 경계를 재검증한다.
- [x] Flyway V4와 JPA `@Version`을 추가한다.
- [x] 낙관적 락 예외를 HTTP 409 공통 오류로 변환한다.
- [x] Swagger 오류 계약을 갱신한다.
- [x] Infra configuration과 Bean wiring은 N/A다.
- [x] 부모 Spec과 이 문서에 실제 검증 결과를 반영한다.

### Expected commit boundaries

```text
docs: 정산 동시 수정 제어 계획 추가
fix: 정산에 낙관적 락 적용
fix: 동시 수정 충돌을 HTTP 409로 변환
docs: 정산 동시 수정 오류 응답 문서화
docs: 정산 동시 수정 검증 결과 반영
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
DB_URL=jdbc:postgresql://127.0.0.1:<port>/howmuch \
  DB_USERNAME=howmuch \
  DB_PASSWORD=<test-password> \
  REDIS_HOST=127.0.0.1 \
  REDIS_PORT=<port> \
  DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused \
  ./gradlew test
git diff --check
git status --short --untracked-files=all
```

실제 HTTP 검증에서는 PostgreSQL row lock으로 두 PATCH가 같은 version을 읽은 뒤 UPDATE에서 대기하도록 만들고 lock을 해제한다.

### Completion criteria

- [x] 동일 version 기반 동시 PATCH 결과가 HTTP 200 하나와 HTTP 409 하나인지 확인한다.
- [x] 409 응답 code가 `CONCURRENT_UPDATE`인지 확인한다.
- [x] 실패 요청이 성공 요청의 값을 되돌리지 않았는지 PostgreSQL에서 확인한다.
- [x] 충돌 후 조회·재시도가 성공하고 두 변경이 최종 반영되는지 확인한다.
- [x] Flyway V1~V4와 Hibernate validation을 확인한다.
- [x] 기존 테스트와 Swagger 계약을 확인한다.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| 구현 | Complete | Flyway V4, `SettlementJpaEntity.version`, 공통 409 예외 처리 |
| 컴파일 | Complete | 각 코드·Swagger 커밋 전 `./gradlew compileJava` 성공 |
| Flyway·Hibernate | Complete | PostgreSQL 18.3에 Flyway V1~V4 검증, Hibernate `ddl-auto=validate` 통과 |
| 기존 테스트 | Complete | 격리 PostgreSQL·Redis에서 `./gradlew test` 성공 |
| 동시 HTTP PATCH | Complete | 같은 version 0을 읽은 제목·금액 PATCH에서 HTTP 200과 409 `CONCURRENT_UPDATE`, DB version 1 확인 |
| 충돌 후 재시도 | Complete | 최신 상태 GET 후 PATCH HTTP 200, 제목·20,000원·version 2 확인 |
| 회귀 HTTP | Complete | 다른 사용자 PATCH 403, REQUESTED PATCH 409 `SETTLEMENT_NOT_EDITABLE`, DB 값 유지 |
| Swagger | Complete | Swagger UI PATCH 설명과 409 행에 `CONCURRENT_UPDATE` 표시 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-21 | Codex 리뷰와 현재 저장 흐름 분석, 낙관적 락 설계 | Flyway V4와 JPA version 구현 | 예외가 트랜잭션 커밋 시 발생하므로 공통 예외 처리에서 409로 변환해야 함 |
| 2026-08-21 | Flyway V4, JPA `@Version`, 공통 409, Swagger 구현과 실제 병렬 HTTP 검증 완료 | PR 리뷰 답변·resolve와 CI 확인 | 서버 트랜잭션이 겹치지 않은 장시간 클라이언트 편집 충돌은 이번 계약으로 감지하지 않음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
| --- | --- | --- | --- |
| 2026-08-21 | 정산 Entity에 JPA 낙관적 락을 적용한다. | 오래된 전체 Aggregate 저장을 감지하면서 평상시 DB 락 대기를 피한다. | 충돌한 요청은 409를 받고 최신 상태 조회 후 재시도한다. |
| 2026-08-21 | version을 Domain과 Web API에 노출하지 않는다. | 현재 문제는 서버 트랜잭션 간 충돌이며 JPA 영속 경계 안에서 해결할 수 있다. | 장시간 열린 클라이언트 편집 충돌 검출은 별도 API 계약이 필요하다. |
| 2026-08-21 | 낙관적 락 예외를 공통 `CONCURRENT_UPDATE`로 변환한다. | 커밋 시점 기술 예외를 안정적인 API 오류로 제공한다. | 이후 다른 Entity가 낙관적 락을 사용해도 같은 공통 계약을 재사용할 수 있다. |

## 11. Completion record

- Completed on: 2026-08-21
- Final behavior: 같은 version의 정산 변경은 하나만 반영하고 뒤늦은 요청은 HTTP 409 `CONCURRENT_UPDATE`를 반환한다. 최신 정산 조회 후 재시도하면 정상 반영된다.
- Unverified items: 없음. 컴파일, 기존 테스트, Flyway·Hibernate, 실제 병렬 HTTP, 회귀 HTTP, Swagger UI를 확인했다.
- Remaining risks: API version을 전달하지 않으므로 서버 트랜잭션이 겹치지 않은 장시간 클라이언트 편집 충돌은 감지하지 않는다.
- Follow-up work: 참여자·지출·상태 전이 저장도 동일한 Settlement Entity와 트랜잭션 경계를 사용하도록 구현 시 재검증한다.
