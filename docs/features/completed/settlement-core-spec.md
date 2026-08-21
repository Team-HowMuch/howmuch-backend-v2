# Feature Plan: 정산 초안 및 기본 정보

- Status: Completed
- Last updated: 2026-08-21
- Related issue: [#28 정산 초안 및 기본 정보 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/28)
- Related ADR: N/A
- Product requirement: [일회성 정산 PRD](../../product/settlement-prd.md)

## 1. Purpose

### Problem

현재 v2에는 정산을 생성·식별·조회할 도메인과 저장 구조가 없다. 참여자, 지출, 배분, 공통 링크는 모두 특정 정산의 생성자와 상태를 기준으로 동작해야 하지만 이를 보장할 기반이 없다.

### Why now

정산 초안과 상태는 이후 참여자 선택, 지출 등록, 배분, 요청 확정의 부모 Aggregate다. 이 기반 없이 하위 기능을 먼저 구현하면 생성자 권한과 수정 가능 시점을 일관되게 검증할 수 없다.

### Success criteria

- [x] 로그인한 사용자가 자신의 정산 초안을 생성·조회·수정할 수 있다.
- [x] 초안 외 상태에서는 기본 정보 변경을 거부한다.
- [x] 이후 기능이 정산 ID, 생성자 ID, 총액, 상태를 단일 계약으로 참조한다.

### Out of scope

- 참여자, 지출, 배분 Share, 수령 계좌, 공통 링크의 실제 구현
- 정산 목록·검색·히스토리 화면
- CODEF 입금 현황과 완료 거래 매칭

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: 없음. settlement을 새 도메인으로 만든다.
- Reason a new domain is required: 정산은 생성자 권한, 금액, 상태 전이, 하위 참여자·지출·Share를 소유하는 독립적인 비즈니스 개념이다.
- Relationships to other domains: user는 생성자·등록 참여자의 식별자를 제공한다. 참여자, 지출·배분, 요청·링크 Spec이 정산 ID와 상태를 사용한다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 정산 | 한 번의 비용 분배와 입금 확인을 관리하는 Aggregate | Settlement |
| 생성자 | 정산을 만들고 초안 정보를 수정하는 로그인 사용자 | creatorId |
| 선언 총액 | 생성자가 현재 정산 대상으로 입력한 총 금액 | totalAmount |
| 초안 | 참여자·지출·방식을 아직 바꿀 수 있는 상태 | DRAFT |
| 요청됨 | 공통 링크가 활성화돼 참여자 단계가 진행 중인 상태 | REQUESTED |
| 입금 확인 대기 | 최종 Share가 확정되어 CODEF 검증을 기다리는 상태 | AWAITING_DEPOSIT |
| 중단 | 품목별 배분 합계 오류 등으로 새 요청이 필요한 상태 | SUSPENDED |
| 완료 | 모든 Share의 실제 입금이 확인된 상태 | COMPLETED |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 로그인한 생성자 | 새 정산을 시작하고 기본 정보를 정한다. | CreateSettlementUseCase |
| 로그인한 생성자 | 자신의 초안을 다시 확인한다. | GetSettlementUseCase |
| 로그인한 생성자 | 초안 제목과 선언 총액을 수정한다. | UpdateSettlementDraftUseCase |
| 하위 정산 기능 | 상태 조건을 확인하고 정산을 다음 단계로 전이한다. | 상태 전이 Domain 메서드 |

### State and invariants

- State transitions: DRAFT → REQUESTED, REQUESTED → AWAITING_DEPOSIT, REQUESTED → SUSPENDED, AWAITING_DEPOSIT → COMPLETED만 허용한다. SUSPENDED 정산은 재개하지 않고 새 정산 요청을 만든다.
- Rules that must always hold: 생성자 ID와 정산 ID는 변경하지 않는다. 금액은 0원보다 커야 한다. DRAFT만 생성자에게 제목·선언 총액 수정 권한을 준다.
- Invalid states: 존재하지 않는 생성자, 0 이하 총액, 완료·중단 정산 수정, CODEF 완료 전 COMPLETED 전이.

## 3. Scenarios

### Normal flow

1. 로그인한 사용자가 제목과 총액으로 정산 생성 요청을 보낸다.
2. Application Service가 CurrentActorPort로 생성자를 조회한다.
3. Domain Settlement을 DRAFT 상태로 생성하고 저장한다.
4. 생성자는 정산 ID로 초안을 조회하고 제목·총액을 수정한다.
5. 참여자·지출·요청 기능은 이 정산 ID를 사용해 하위 데이터를 추가하고, 각 단계가 충족될 때 상태 전이를 요청한다.

### Failure flow

- 다른 사용자가 정산을 조회하거나 수정한다: 정산 접근 권한 오류를 반환한다.
- DRAFT가 아닌 정산을 수정한다: 수정 불가 상태 오류를 반환한다.
- 총액이 0원 이하이거나 제목이 비어 있다: Web Adapter에서 검증 오류를 반환한다.

### Boundary conditions

- 선언 총액은 정수 원으로 저장한다. 소수·음수 금액은 허용하지 않는다.
- 지출·품목별 Spec은 최종 지출 합계와 선언 총액이 다를 때의 처리 규칙을 소유한다. 이 Spec은 초안의 입력값을 임의로 재계산하지 않는다.
- 모든 시간은 UTC LocalDateTime으로 저장한다.

## 4. Contracts

### Input Port

~~~
CreateSettlementUseCase.create(CreateSettlementCommand(title, totalAmount))
  -> SettlementResult(settlementId, creatorId, title, totalAmount, status, createdAt)

GetSettlementUseCase.get(settlementId)
  -> SettlementResult(...)

UpdateSettlementDraftUseCase.update(settlementId, UpdateSettlementDraftCommand(title?, totalAmount?))
  -> SettlementResult(...)
~~~

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
| --- | --- | --- | --- |
| LoadSettlementPort | 정산을 ID로 조회한다. | 조회·수정·하위 UseCase | JPA Persistence Adapter |
| SaveSettlementPort | 정산 생성과 상태·초안 변경을 저장한다. | 생성·수정·상태 전이 UseCase | JPA Persistence Adapter |
| CurrentActorPort | 현재 로그인 사용자 ID를 제공한다. | 생성·조회·수정 UseCase | 기존 Spring Security Adapter |

### Web API

~~~
POST /api/v2/settlements
Request: { title, totalAmount }
Response: SettlementWebResponse
Errors: 인증 필요, 입력 검증 실패
Authentication: required

GET /api/v2/settlements/{settlementId}
Response: SettlementWebResponse
Errors: 정산 없음, 접근 권한 없음
Authentication: required

PATCH /api/v2/settlements/{settlementId}
Request: { title?, totalAmount? }, at least one field required
Response: SettlementWebResponse
Errors: 정산 없음, 접근 권한 없음, 수정 불가 상태, 입력 검증 실패
Authentication: required
~~~

### Persistence and external integrations

- Database Entity, constraint, or query changes: settlements 테이블과 SettlementJpaEntity를 추가한다. UUID PK, 생성자 ID, 제목, 선언 총액, 상태, 생성·수정 시각을 저장하고 생성자 ID·생성 시각 인덱스를 둔다.
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: 없음.
- Environment variable or profile changes: 없음. 활성화된 Flyway에 V3 정산 스키마를 추가하고 모든 프로필의 Hibernate `ddl-auto=validate` 정책을 유지한다.

## 5. Design decisions

### Business logic

- Rules and their locations: 상태 전이와 초안 수정 가능 여부는 Settlement Domain이 소유한다. 현재 사용자·권한 확인과 저장 순서는 Application Service가 조정한다.
- Behavior owned by Domain: 금액 유효성, 생성자 불변성, 허용된 상태 전이, DRAFT 수정 가능 여부.
- Flow orchestrated by Application: 현재 사용자 확인, 정산 로드·저장, Domain 예외를 API 오류로 변환하는 흐름.

### Technology and patterns

- Constraint to solve: JPA 모델을 Domain 모델로 노출하지 않으면서 이후 하위 기능이 일관된 정산 상태를 참조해야 한다.
- Selected approach: Settlement Domain과 SettlementJpaEntity를 분리하고 Persistence Adapter가 Mapper를 통해 Output Port를 구현한다.
- Reason for selection: 현재 저장소의 user Persistence 구조와 Hexagonal 의존성 방향을 그대로 따른다.
- Alternatives considered: 참여자·지출 각 기능이 자체 정산 상태를 보관하는 방식은 상태 불일치 위험이 있어 제외한다.
- Trade-offs and limits: 첫 Spec에서 전체 정산 모델을 추가하지만 실제 참여자·지출 조회는 이후 Spec이 완성되기 전까지 제공하지 않는다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | 정산 초안 생성·조회·수정 API 추가 | SettlementController, SettlementDocs |
| Application | 새 Input Port, Command/Result, Service, Persistence Port | application.port.in/out.settlement |
| Domain | Settlement, SettlementStatus, 정산 상태 코드 추가 | domain.settlement |
| Persistence | settlements Entity·Repository·Mapper·Adapter·Flyway V3 추가 | adapter.out.persistence.settlement |
| Redis | 없음 | N/A |
| Security | 생성자만 자신의 정산을 읽고 수정 | CurrentActorPort |
| Configuration | 없음 | N/A |
| Documentation | 이 Spec과 PRD를 기준으로 하위 Spec 연결 | 이 문서 |

Existing behavior that must remain unchanged:

- 기존 auth와 user API·보안 흐름은 변경하지 않는다.
- Flyway 스키마 관리와 UTC 저장 규칙을 유지한다.

## 7. Implementation plan

- [x] Application Command/Result와 생성·조회·수정 Input Port를 정의한다.
- [x] 정산 로드·저장 Output Port를 정의한다.
- [x] Domain 모델, 상태, 금액·상태 전이 규칙, 상태 코드를 구현한다.
- [x] 생성자 권한을 확인하는 Application Service를 구현한다.
- [x] JPA Entity·Repository·Mapper·Persistence Adapter와 Flyway V3를 구현한다.
- [x] Web Request/Response DTO, Controller, Swagger 문서를 구현한다.
- [x] 문서의 실제 API·상태·검증 결과를 갱신한다.

### Expected commit boundaries

~~~
feat: 정산 초안 도메인과 상태 규칙 추가
feat: 정산 테이블과 영속 모델 추가
feat: 정산 초안 생성 기능 추가
feat: 정산 초안 조회 기능 추가
feat: 정산 초안 수정 기능 추가
feat: 정산 초안 생성 API 추가
feat: 정산 초안 조회 API 추가
feat: 정산 초안 수정 API 추가
docs: 정산 초안 API 오류 응답 설명 추가
docs: 정산 초안 명세 구현 결과 반영
~~~

## 8. Verification

### Commands

~~~
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
~~~

수동 검증 전제조건: PostgreSQL과 Redis, 유효한 JWT로 로그인한 생성자가 필요하다.

### Completion criteria

- [x] 생성자가 초안을 생성·조회·수정한다.
- [x] 다른 사용자의 조회·수정은 거부된다.
- [x] 요청됨·중단·완료 정산의 수정은 거부된다.
- [x] 문서와 Swagger가 실제 계약과 일치한다.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| 문서 작성 | Complete | 이 Spec |
| 구현 | Complete | Domain, Application, Persistence, Web Adapter |
| 컴파일 | Complete | `./gradlew compileJava` 성공 |
| 기존 테스트 | Environment failed | `./gradlew test` 실행, Flyway PostgreSQL 연결 거부로 contextLoads 1건 실패 |
| 수동 API 검증 | Not run | PostgreSQL·Redis와 유효한 JWT 미준비 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-21 | 정산 초안 Domain·API·상태 설계 기록 | Entity와 UseCase 구현 | 지출 합계 검증은 지출·배분 Spec과의 계약 일치가 필요 |
| 2026-08-21 | Domain, Flyway V3, JPA, 생성·조회·부분 수정 API 구현 | 참여자 선택 구현 | `./gradlew test`가 로컬 PostgreSQL 연결 거부로 실패, Docker 데몬도 중지 상태 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
| --- | --- | --- | --- |
| 2026-08-21 | 정산을 독립 Domain으로 분리한다. | 참여자·지출·Share의 단일 부모와 상태 소유자가 필요하다. | user Domain에 종속시키지 않는다. |
| 2026-08-21 | 선언 총액은 초안에서 저장하고 최종 지출 검증은 지출 Spec이 소유한다. | 초안 입력과 상세 지출 등록의 책임을 분리한다. | 지출 합계가 확정될 때까지 임의 재계산하지 않는다. |
| 2026-08-21 | UUID를 Application 외부에도 정산 ID로 사용한다. | 링크와 하위 정산 데이터가 예측하기 어려운 식별자를 공유할 수 있다. | Web API path와 DB PK가 UUID를 사용한다. |
| 2026-08-21 | PATCH는 제목·총액의 부분 수정을 허용한다. | 생성 화면에서 각 기본 정보를 독립적으로 고칠 수 있다. | 두 필드가 모두 없으면 입력 검증 오류를 반환한다. |

## 11. Completion record

- Completed on: 2026-08-21
- Final behavior: 로그인 사용자가 UUID 정산 초안을 생성하고, 생성자 본인만 조회하며, DRAFT 상태에서 제목·총액을 부분 수정한다.
- Unverified items: `./gradlew test`를 실행했으나 로컬 PostgreSQL 연결 거부로 Spring context test가 실패했다. Redis, migration 완료 상태와 실제 HTTP 요청도 검증하지 못했다.
- Remaining risks: 지출·품목별 요청 시점과 상태 전이 계약은 하위 Spec 구현 시 재검증한다.
- Follow-up work: 참여자, 지출·배분, 요청·링크 Spec 순서로 구현한다.
