# Feature Plan: 정산 참여자 선택 및 관리

- Status: In Progress
- Last updated: 2026-08-22
- Related issue: [#27 정산 참여자 선택 및 관리](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/27)
- Related ADR: N/A
- Product requirement: [일회성 정산 PRD](../../product/settlement-prd.md)
- Prerequisite: [정산 초안 및 기본 정보 Spec](../completed/settlement-core-spec.md)

## 1. Purpose

### Problem

정산 초안에 누가 참여하는지 등록하고, 링크를 연 로그인 회원을 어떤 참여자로 처리할지 결정하는 데이터와 계약이 없다. 참여자를 확정하지 못하면 더치페이 금액, 품목 선택, Share, 브라우저 이름 선택을 모두 만들 수 없다.

### Why now

참여자는 정산 금액 계산의 분모이자 링크 진입 후 현재 사용자를 자동 식별하는 기준이다. 지출·배분과 요청·링크 기능보다 먼저 일관된 참여자 모델이 필요하다.

### Success criteria

- [ ] 생성자는 DRAFT 정산에서 자신을 포함한 참여자를 추가·제거할 수 있다.
- [ ] 사용자 ID, 연락처, 그룹 구성원이라는 세 경로가 같은 참여자 모델로 저장된다.
- [ ] 로그인 회원이 공통 링크를 앱에서 열면 사용자 ID로 참여자를 자동 식별할 수 있다.
- [ ] 비회원 브라우저 흐름에는 표시 이름을 가진 참여자 목록을 제공할 수 있다.

### Out of scope

- 그룹 생성·수정·가입·탈퇴
- 기기 연락처 동기화와 서버의 SMS·푸시 발송
- 비회원 이름 선택을 강한 본인 인증으로 바꾸는 기능
- 공통 링크 토큰 생성과 실제 링크 라우팅 구현

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: settlement의 하위 Entity로 참여자를 추가한다.
- Reason a new domain is required, if applicable: N/A. 참여자는 독립 Aggregate가 아니라 하나의 Settlement에 귀속된다.
- Relationships to other domains: user는 등록 참여자의 userId와 표시 이름을 제공한다. group은 구성원 조회 Output Port만 제공한다. request-link은 SettlementParticipant의 등록 userId를 사용해 앱 회원을 자동 매칭한다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 정산 참여자 | 하나의 정산에서 비용을 부담하거나 품목을 선택하는 사람 | SettlementParticipant |
| 등록 참여자 | 서비스 userId와 연결된 참여자 | REGISTERED_USER |
| 연락처 참여자 | 생성자가 연락처 정보로 추가한 외부 참여자 | CONTACT |
| 그룹 참여자 | 그룹 구성원 조회 결과에서 추가한 참여자 | GROUP_MEMBER |
| 표시 이름 | 브라우저 이름 선택과 정산 현황에 보여 주는 이름 | displayName |
| 자동 매칭 | 로그인 사용자 ID와 참여자의 userId가 일치하는 경우 | linkedUserId match |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 생성자 | 정산에 자신과 다른 참여자를 등록한다. | AddSettlementParticipantUseCase |
| 생성자 | 초안에서 잘못 선택한 참여자를 제거한다. | RemoveSettlementParticipantUseCase |
| 생성자 | 사용자 ID·연락처·그룹 구성원에서 참여자를 찾는다. | SearchSettlementParticipantUseCase |
| 요청·링크 기능 | 로그인 회원과 해당 정산 참여자를 연결한다. | LoadSettlementParticipantByUserUseCase |

### State and invariants

- State transitions: 참여자 추가·제거는 Settlement가 DRAFT일 때만 허용한다.
- Rules that must always hold: 정산 생성자는 초안 생성 시 첫 참여자로 자동 등록한다. 하나의 정산에 같은 등록 사용자 또는 같은 연락처 식별자를 중복 등록하지 않는다. 요청 확정 전 참여자는 최소 2명이어야 한다.
- Invalid states: 다른 생성자의 정산 변경, 요청됨 이후 참여자 변경, 중복 참여자, 존재하지 않는 사용자·그룹 구성원 추가, 생성자 자신을 제거하는 요청.

## 3. Scenarios

### Normal flow

1. 정산 생성 시 현재 사용자를 등록 참여자로 자동 저장한다.
2. 생성자는 사용자 ID 검색 결과, 전화번호 기반 연락처 검색 결과, 또는 그룹 구성원 조회 결과에서 대상을 고른다.
3. Application Service가 대상 정보를 SettlementParticipant로 정규화하고 DRAFT 정산에 추가한다.
4. 생성자는 요청 확정 전까지 참여자를 제거할 수 있다.
5. 요청·링크 기능은 앱에서 받은 현재 사용자 ID로 같은 정산의 등록 참여자를 조회한다.
6. 일치하는 참여자가 있으면 이름 선택 없이 그 참여자의 더치페이 송금 또는 품목 선택 단계로 이동한다.

### Failure flow

- 생성자가 아닌 사용자가 참여자를 변경한다: 접근 권한 오류를 반환한다.
- DRAFT가 아닌 정산의 참여자를 변경한다: 수정 불가 상태 오류를 반환한다.
- 중복 사용자·연락처를 추가한다: 중복 참여자 오류를 반환한다.
- 요청 확정 시 참여자가 한 명뿐이다: 정산 요청 불가 오류를 반환한다.
- 앱 로그인 사용자와 연결된 참여자가 없다: 앱은 브라우저 이름 선택 흐름으로 전환한다.

### Boundary conditions

- 비회원·앱 미설치 사용자는 common link로 브라우저에서 표시 이름만 선택한다.
- 로그인 세션이 없는 앱도 현재 사용자를 식별할 수 없으므로 브라우저 이름 선택 흐름으로 전환한다.
- 연락처의 원본 전화번호는 영속화하지 않고 IdentityPolicy로 정규화·HMAC 처리한 식별자만 저장한다.
- 그룹 구성원 정보가 없는 경우 그룹 경로는 결과 없음으로 처리하고 다른 참여자 경로를 계속 제공한다.

## 4. Contracts

### Input Port

~~~
AddSettlementParticipantUseCase.add(
  settlementId,
  AddSettlementParticipantRequest(source, userId, displayName, phoneNumber)
) -> SettlementParticipantResponse

RemoveSettlementParticipantUseCase.remove(settlementId, participantId) -> void

SearchSettlementParticipantUseCase.search(
  settlementId,
  ParticipantSearchRequest(type, keyword, groupId)
) -> List<ParticipantCandidateResponse>

LoadSettlementParticipantByUserUseCase.find(settlementId, userId)
  -> Optional<SettlementParticipantResponse>
~~~

source는 REGISTERED_USER, CONTACT, GROUP_MEMBER 중 하나다. CONTACT만 displayName과 phoneNumber를 받을 수 있고, 저장 전 phoneNumber는 hash로 변환한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
| --- | --- | --- | --- |
| LoadSettlementParticipantPort | 정산 참여자와 userId 연결을 조회한다. | 추가·제거·링크 해석 UseCase | JPA Persistence Adapter |
| SaveSettlementParticipantPort | 참여자를 저장·삭제한다. | 추가·제거 UseCase | JPA Persistence Adapter |
| SearchRegisteredUserPort | 사용자 ID 또는 전화번호 식별자로 등록 사용자를 찾는다. | 참여자 검색 UseCase | user Persistence Adapter |
| LoadGroupMembersPort | 특정 그룹의 구성원을 조회한다. | 그룹 기반 후보·추가 UseCase | group Adapter 또는 개발 Stub |
| CurrentActorPort | 생성자 권한과 앱 로그인 사용자를 제공한다. | 모든 UseCase | 기존 Spring Security Adapter |

### Web API

~~~
GET /api/v2/settlements/{settlementId}/participant-candidates
Query: type=userId|contact|group, keyword, groupId
Response: List<ParticipantCandidateResponse>
Authentication: required

POST /api/v2/settlements/{settlementId}/participants
Request: { source, userId?, displayName?, phoneNumber?, groupId? }
Response: SettlementParticipantResponse
Authentication: required, creator only

DELETE /api/v2/settlements/{settlementId}/participants/{participantId}
Response: empty success
Authentication: required, creator only
~~~

앱 회원 자동 매칭은 public endpoint가 아니라 request-link UseCase가 인증된 현재 사용자 ID와 participant userId를 비교하는 내부 계약으로 제공한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: settlement_participants 테이블에 settlement_id, source, linked_user_id nullable, phone_hash nullable, display_name, 생성·수정 시각을 저장한다. 등록 사용자 중복과 연락처 식별자 중복을 방지하는 정산 범위 Unique 제약을 둔다.
- Flyway migration required: Yes
- Planned migration: `V9__create_settlement_participant_schema.sql`
- Migration pull request: [#80 정산 참여자 영속 스키마 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/80) (Merged)
- Redis key, TTL, or serialization changes: 없음.
- External API or message changes: group 구성원 조회는 Output Port와 dev Stub으로만 연결한다.
- Environment variable or profile changes: 없음.

## 5. Design decisions

### Business logic

- Rules and their locations: 참가자 중복·생성자 제거 금지·초안 수정 가능 여부는 SettlementParticipant와 Settlement Domain 규칙으로 둔다.
- Behavior owned by Domain: 참여자 식별자 유효성, source별 필수값, 중복 방지 기준, 생성자 참여자 불변성.
- Flow orchestrated by Application: 현재 생성자 확인, 사용자·그룹 후보 조회, 연락처 hash 처리, 저장과 삭제 순서.

### Technology and patterns

- Constraint to solve: user와 group 기능의 구현 시점이 달라도 정산 참여자 선택이 중단되지 않아야 한다.
- Selected approach: 참여자는 정산 모듈이 소유하고 user 검색과 group 구성원 조회만 Output Port로 분리한다.
- Reason for selection: 정산이 참여자 상태를 직접 소유하면서도 외부 도메인의 CRUD 구현을 기다리지 않는다.
- Alternatives considered: 참여자를 user 또는 group 테이블의 조인 결과로만 표현하는 방식은 연락처 참여자와 링크 자동 매칭을 표현하지 못해 제외한다.
- Trade-offs and limits: 이름만 고르는 브라우저 MVP는 가장을 막지 못한다. 강한 본인 확인은 별도 기능으로 추가한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | 후보 검색, 참여자 추가·제거 API 추가 | SettlementParticipantController, Docs |
| Application | 참여자 UseCase, 검색·그룹·저장 Port 추가 | application.settlement |
| Domain | SettlementParticipant와 source 규칙 추가 | domain.settlement |
| Persistence | settlement_participants Entity·Repository·Adapter 추가 | adapter.out.persistence.settlement |
| Redis | 없음 | N/A |
| Security | 생성자만 DRAFT 참여자 변경, 로그인 회원 자동 매칭 | CurrentActorPort |
| Configuration | 개발용 group Stub 활성화 | dev profile |
| Documentation | PRD와 요청·링크 Spec의 자동 매칭 계약 연결 | 이 문서 |

Existing behavior that must remain unchanged:

- 기존 user 프로필과 auth 세션 계약을 변경하지 않는다.
- 정산 외부에서 group CRUD를 구현하거나 수정하지 않는다.
- 연락처 원본 전화번호를 DB·로그·응답에 저장하지 않는다.

## 7. Implementation plan

- [x] V9 migration-only PR로 참여자 테이블과 DB 제약을 추가한다.
- [ ] 참여자 source와 중복·생성자 규칙을 Domain에 정의한다.
- [ ] 생성자 자동 참여에 필요한 저장 Output Port와 JPA Adapter를 구현한다.
- [ ] 정산 생성 트랜잭션에서 현재 사용자를 첫 등록 참여자로 저장한다.
- [ ] 참여자 저장·조회, 등록 사용자 검색, 그룹 구성원 조회 Output Port를 실제 호출 시점에 정의한다.
- [ ] 추가·제거·후보 검색·로그인 사용자 자동 매칭 UseCase를 구현한다.
- [ ] 추가·제거 구현 시 JPA 조회·삭제 기능과 개발 group Stub을 확장한다.
- [ ] Web DTO, Controller, Swagger와 생성자 권한 검증을 구현한다.
- [ ] 요청·링크 Spec과 자동 매칭 계약을 실제 코드로 재검증한다.

### Expected commit boundaries

~~~
docs: 정산 참여자 구현 계획 갱신
feat: 정산 참여자 도메인 모델 추가
feat: 정산 참여자 영속 어댑터 추가
feat: 정산 생성자 자동 참여 추가
test: 정산 참여자 생성과 영속 흐름 검증
docs: 정산 참여자 구현 현황 반영
~~~

## 8. Verification

### Commands

~~~
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
~~~

수동 검증 전제조건: PostgreSQL·Redis, 생성자와 다른 로그인 사용자, group dev Stub 데이터가 필요하다.

### Completion criteria

- [ ] 생성자는 자신을 포함한 참여자를 추가·제거한다.
- [ ] 다른 사용자는 참여자 변경을 할 수 없다.
- [ ] 중복 참여자·생성자 제거·요청됨 이후 수정이 거부된다.
- [ ] 로그인 회원 링크 진입은 이름 선택 없이 매칭되고, 비회원은 브라우저 이름 선택으로 진행한다.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| 문서 작성 | Complete | 이 Spec |
| V9 참여자 스키마 | Complete | PR #80, 실제 PostgreSQL 제약 검증 |
| 구현·컴파일 | Pending | 구현 전 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-21 | 참여자 source·권한·앱 자동 매칭 설계 기록 | Persistence와 후보 검색 구현 | 비회원 이름 선택 MVP의 가장 위험 |
| 2026-08-22 | V9 참여자 스키마와 식별자·중복·순서 제약 머지 | Domain·JPA와 생성자 자동 참여 구현 | SQL `CHECK`의 NULL 삼값 논리를 명시 조건으로 보완함 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
| --- | --- | --- | --- |
| 2026-08-21 | 생성자를 첫 참여자로 자동 등록한다. | UI와 더치페이 분모에서 생성자도 참여자로 취급한다. | 생성자 제외 정산은 제공하지 않는다. |
| 2026-08-21 | 앱 회원은 userId로 자동 매칭한다. | 공통 링크에서 이름 선택을 건너뛰고 바로 개인 단계로 이동해야 한다. | 세션이 없으면 브라우저 MVP로 내린다. |
| 2026-08-21 | 연락처 원본은 hash 식별자로만 저장한다. | 개인정보 최소화와 기존 IdentityPolicy 규칙을 따른다. | 원본 번호를 조회·로그에 노출하지 않는다. |

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: 전체 구현 전
- Remaining risks: group 실제 구현과 연락처 후보 공급 계약이 확정돼야 운영 Adapter를 연결할 수 있다.
- Follow-up work: 지출·배분 Spec과 요청·링크 Spec을 구현한다.
