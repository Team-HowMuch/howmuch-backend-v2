# Feature Plan: 정산 요청 링크 및 Share 확정

- Status: Draft
- Last updated: 2026-08-23
- Related issue: [#26 정산 요청 링크 및 수금 계좌 설정](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/26)
- Related ADR: N/A
- Product requirement: [일회성 정산 PRD](../../product/settlement-prd.md)
- Prerequisites: [정산 초안 및 기본 정보 Spec](../completed/settlement-core-spec.md), [정산 참여자 선택 및 관리 Spec](settlement-participants-spec.md), [정산 지출 및 금액 배분 Spec](settlement-expense-allocation-spec.md)
- Completed increment: [정산 수령 계좌 조회와 선택](../completed/settlement-receiving-account.md)

## 1. Purpose

### Problem

생성자가 선택한 수금 계좌와 참여자별 송금 금액을 하나의 공통 URL로 안전하게 전달하고, 링크를 연 앱 회원을 이름 선택 없이 자기 참여자로 연결하는 기능이 없다. 품목별 배분은 모든 선택이 끝날 때까지 송금액을 확정할 수 없는 문제도 있다.

### Why now

이 기능이 있어야 생성자는 휴대폰 기본 공유 기능으로 한 링크를 전달하고, 참여자는 앱 또는 브라우저에서 자기 몫을 확인한 뒤 Toss 송금 화면으로 이동할 수 있다. 이후 입금 현황 관리가 확정된 Share를 기준으로 거래 내역을 대조한다.

### Success criteria

- [ ] 생성자는 DRAFT 정산에서 하나의 수금 계좌를 선택하고 공통 HTTPS 정산 링크를 발급한다.
- [ ] 검증된 App/Universal Link가 앱을 열고 유효 로그인 세션의 userId가 참여자와 일치하면, 이름 선택 없이 해당 참여자의 송금 또는 품목 선택 화면 정보를 반환한다.
- [ ] 앱 미설치·링크 미검증·세션 없음·정산 참여자 불일치의 경우에는 브라우저의 표시 이름 선택 흐름으로 처리한다.
- [ ] 더치페이는 링크 발급 시 Share와 수금 계좌 스냅샷을 확정하고, 품목별 배분은 모든 참여자 선택이 검증된 뒤에만 Share를 확정한다.
- [ ] 확정된 Share는 바꿀 수 없고, 클라이언트에는 Toss로 이동하는 데 필요한 송금 안내만 제공한다.

### Out of scope

- SMS, 푸시, 카카오톡 등 서버 발송과 특정 공유 앱 선택
- Toss 송금 실행·결제 콜백·입금 완료 판정
- 비회원의 이름 선택을 OTP·신분 확인으로 강화하는 기능
- 모바일 운영체제의 App/Universal Link 도메인 검증 파일 배포

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: Settlement, SettlementParticipant, SettlementExpense와 AllocationResult를 사용한다.
- Reason a new domain is required, if applicable: 공통 링크, 수금 계좌 스냅샷, 불변 Share, 공개 링크 참여 문맥이 필요하다.
- Relationships to other domains: user/auth는 앱의 현재 userId를 제공한다. account는 생성자의 CODEF 연결 계좌와 수금 계좌 후보를 제공한다. allocation은 더치페이 또는 품목별 계산 결과를 제공한다. deposit-status는 Share와 계좌 스냅샷을 기준으로 CODEF 거래를 대조한다.

### Ubiquitous language

| Term | Meaning | Code representation |
| --- | --- | --- |
| 공통 정산 링크 | 한 정산의 모든 참여자가 공유하는 불투명 HTTPS URL | SettlementLink |
| 링크 토큰 | URL에 담기는 고엔트로피 값. DB에는 hash만 저장한다. | tokenHash |
| 수금 계좌 스냅샷 | 요청 시점에 고정한 은행·계좌·예금주 정보 | ReceivingAccountSnapshot |
| Share | 한 참여자가 송금해야 하는 확정 금액과 수금 대상을 가진 불변 채무 | SettlementShare |
| 참여 문맥 | 공통 링크에서 한 참여자를 고른 뒤 발급되는 짧은 수명의 요청 문맥 | LinkParticipantContext |
| 앱 직접 진입 | 로그인한 앱 userId로 참여자를 자동 매칭해 이름 선택을 건너뛰는 경로 | APP_DIRECT |
| 브라우저 이름 선택 | 공통 링크에서 표시 이름으로 참여자를 고르는 MVP 경로 | BROWSER_SELECTION |

### Actors and UseCases

| Actor | Goal | UseCase |
| --- | --- | --- |
| 생성자 | 수금 계좌를 선택하고 공통 링크를 발급한다. | ConfigureReceivingAccountUseCase, RequestSettlementUseCase |
| 로그인 앱 회원 | 이름 선택 없이 자기 송금 또는 품목 선택 단계로 간다. | ResolveSettlementLinkUseCase |
| 브라우저 참여자 | 표시 이름을 고르고 자기 송금 또는 품목 선택 단계로 간다. | SelectLinkParticipantUseCase |
| 품목 배분 기능 | 검증된 배분 결과로 Share를 고정한다. | FinalizeSettlementSharesUseCase |
| 클라이언트 | 확정 Share의 금액·수금 정보로 Toss를 연다. | LoadTransferInstructionUseCase |

### State and invariants

- State transitions: DRAFT에서 요청 확정 시 DUTCH_PAY는 AWAITING_DEPOSIT으로, ITEM_ALLOCATION은 REQUESTED로 전환한다. ITEM_ALLOCATION의 전체 선택 검증 성공 시 AWAITING_DEPOSIT, 실패 시 SUSPENDED로 전환한다.
- Rules that must always hold: 활성 공통 링크는 정산당 하나다. 원본 링크 토큰은 생성 응답에서 한 번만 반환하고 tokenHash만 저장한다. Share의 payerParticipantId, amount, 수금 계좌 스냅샷은 생성 뒤 수정할 수 없다. Share 합은 총액과 같아야 한다.
- Invalid states: 수금 계좌·참여자·지출·배분 방식이 없는 요청 확정, DRAFT가 아닌 상태에서 수금 계좌 변경, 아이템 배분 검증 전 Share 생성, 만료·비활성 링크 해석, 다른 참여자로 위조한 참여 문맥 사용.

## 3. Scenarios

### Normal flow: 링크 발급과 공유

1. 생성자는 DRAFT 정산에서 자신의 결제수단 중 수금 계좌 하나를 선택한다.
2. 생성자는 요청하기를 누른다. 서버는 참여자 수·지출 총액·수금 계좌·배분 방식의 사전조건을 검사한다.
3. 서버는 고엔트로피 토큰으로 공통 HTTPS 링크를 만들고 원본 토큰은 응답에만 반환하며 hash를 저장한다.
4. 클라이언트는 이 URL을 모바일 OS 기본 공유 시트에 전달한다. 공유 대상 앱·수신자 선택은 OS와 사용자가 담당한다.

### Normal flow: 앱 회원 직접 진입

1. 참여자가 공통 HTTPS 링크를 누른다.
2. 기기에 앱이 설치되어 있고 iOS Universal Link 또는 Android App Link 도메인 연결이 검증돼 있으면 OS가 앱으로 전달한다. 그렇지 않으면 같은 URL은 브라우저로 열린다.
3. 앱은 유효 로그인 세션의 현재 userId를 링크 해석 요청에 포함한다.
4. 서버가 같은 정산의 linkedUserId와 일치하는 참여자를 찾으면 APP_DIRECT와 participantId를 반환한다. 이름 선택은 보이지 않는다.
5. DUTCH_PAY 또는 Share가 이미 확정된 ITEM_ALLOCATION이면 즉시 송금 안내를 반환한다. 아직 선택 중인 ITEM_ALLOCATION이면 해당 참여자의 품목 선택 화면 데이터를 반환한다.

### Normal flow: 브라우저 이름 선택

1. 앱 미설치·링크 미검증·세션 없음·참여자 불일치 사용자는 브라우저에서 공통 URL을 연다.
2. 서버는 표시 이름이 포함된 참여자 목록과 BROWSER_SELECTION 상태를 반환한다.
3. 사용자가 자신을 선택하면 서버는 link token과 participantId에 묶인 짧은 수명의 LinkParticipantContext를 발급한다.
4. 이후 송금 안내 또는 품목 선택 요청은 이 문맥을 함께 보내며, 선택된 참여자 범위 안에서만 처리한다.

### Normal flow: Share 확정과 송금 안내

1. DUTCH_PAY는 요청 확정 시 AllocationResult를 받아 모든 참여자 Share를 생성하고 Settlement를 AWAITING_DEPOSIT으로 전환한다.
2. ITEM_ALLOCATION은 공통 링크를 먼저 열어 참여자의 품목 선택을 받는다. 참여자·지출·배분 방식·수금 계좌는 이 시점에 잠근다.
3. 마지막 제출 후 배분 기능이 검증된 AllocationResult를 전달하면 Share와 수금 계좌 스냅샷을 한 트랜잭션에서 저장하고 AWAITING_DEPOSIT으로 전환한다.
4. LoadTransferInstructionUseCase는 payer의 확정 Share에서 금액·은행 코드·계좌번호·예금주·shareId를 반환한다.
5. 앱 또는 브라우저 클라이언트가 Toss 송금 화면을 연다. 서버는 송금을 직접 실행하거나 성공을 완료 처리하지 않는다.

### Failure flow

- 링크가 만료·비활성·존재하지 않는다: 링크 없음 또는 만료 응답을 반환한다.
- 앱 userId가 참여자와 일치하지 않는다: 앱 직접 진입을 허용하지 않고 브라우저 이름 선택으로 전환한다.
- 브라우저에서 선택한 참여자가 존재하지 않거나 LinkParticipantContext가 만료됐다: 다시 링크 해석·이름 선택을 요구한다.
- ITEM_ALLOCATION의 모든 제출 뒤 검증이 실패한다: Share를 만들지 않고 Settlement를 SUSPENDED로 바꾼다.
- 완료·중지된 정산 링크에서 재송금 안내를 요청한다: 정산 상태에 맞는 조회 전용 또는 요청 불가 응답을 반환한다.

### Boundary conditions

- 설치 여부만으로 앱 직접 진입이나 회원 식별을 보장하지 않는다. OS의 링크 연결 검증, 앱 설치, 유효 로그인 세션, 해당 정산 participant 매칭이 모두 필요하다.
- 공통 링크 자체는 사용자별로 달라지지 않는다. LinkParticipantContext만 선택 뒤의 단기 요청 문맥이다.
- 비회원 브라우저 이름 선택은 신뢰 기반 MVP다. 링크를 가진 사람이 다른 표시 이름을 고르는 것을 강하게 방지하지 않는다.
- 공통 링크가 생성된 뒤 DUTCH_PAY의 참여자·지출·계좌·Share는 모두 잠근다. ITEM_ALLOCATION은 참여자·지출·계좌는 잠그되, 전체 검증 전까지 품목 선택만 허용한다.
- Toss로의 이동 실패·취소는 Share와 Settlement 상태를 바꾸지 않는다. 실제 입금은 별도 CODEF 거래 검증이 판단한다.

## 4. Contracts

### Input Port

~~~
ConfigureReceivingAccountUseCase.configure(
  settlementId, receivingAccountId
) -> ReceivingAccountResponse

RequestSettlementUseCase.request(settlementId) -> SettlementLinkResponse

ResolveSettlementLinkUseCase.resolve(
  rawToken, optionalCurrentUserId
) -> SettlementLinkEntryResponse

SelectLinkParticipantUseCase.select(rawToken, participantId)
  -> LinkParticipantContextResponse

FinalizeSettlementSharesUseCase.finalize(
  settlementId, AllocationResult
) -> List<SettlementShareResponse>

LoadTransferInstructionUseCase.load(
  rawToken, LinkParticipantContext
) -> TransferInstructionResponse
~~~

SettlementLinkEntryResponse는 entryMode, settlementTitle, allocationPhase, participantId optional, participant 후보 optional, payment instruction optional, item selection data optional을 반환한다. APP_DIRECT는 등록 회원 자동 매칭이 성공한 경우만 participantId를 포함한다.

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
| --- | --- | --- | --- |
| LoadSettlementLinkPort | tokenHash·상태·정산을 조회한다. | 링크 해석·송금 안내 UseCase | JPA Persistence Adapter |
| SaveSettlementLinkPort | 링크와 활성·만료 상태를 저장한다. | 요청 확정 UseCase | JPA Persistence Adapter |
| LoadAccountPort | 생성자가 선택할 수금 계좌 후보와 소유권을 조회한다. | 계좌 설정 UseCase | 기존 AccountPersistenceAdapter |
| SaveSettlementSharePort | 불변 Share와 계좌 스냅샷을 저장한다. | Share 확정 UseCase | JPA Persistence Adapter |
| LoadSettlementSharePort | 참여자의 확정 Share를 조회한다. | 송금 안내 UseCase, 입금 현황 | JPA Persistence Adapter |
| LoadSettlementParticipantByUserPort | 앱 userId의 등록 참여자를 조회한다. | 링크 해석 UseCase | participant Persistence Adapter |
| SaveLinkParticipantContextPort | 단기 참여 문맥을 저장·검증한다. | 브라우저 선택·공개 요청 UseCase | Redis Adapter |
| LoadAllocationResultPort | 더치페이 또는 전체 검증된 품목 배분 결과를 조회한다. | 요청·Share 확정 UseCase | allocation Application Adapter |

### Web API

~~~
GET /api/v2/settlements/{settlementId}/receiving-accounts
PUT /api/v2/settlements/{settlementId}/receiving-account
Request: { receivingAccountId }
Authentication: required, creator only, DRAFT only

POST /api/v2/settlements/{settlementId}/request
Response: { url, expiresAt, allocationPhase }
Authentication: required, creator only

GET /api/v2/settlement-links/{token}
Authentication: optional
Header: Authorization is sent only when the app has a valid login session
Response: SettlementLinkEntryResponse

POST /api/v2/settlement-links/{token}/participants/{participantId}/select
Response: { linkParticipantContext, expiresAt, nextPhase }
Authentication: not required

GET /api/v2/settlement-links/{token}/transfer-instruction
Header: Link-Participant-Context
Response: { shareId, amount, recipientBankCode, recipientAccountNumber, recipientName }
Authentication: public link and LinkParticipantContext required
~~~

공개 품목 선택 API는 [정산 지출 및 금액 배분 Spec](settlement-expense-allocation-spec.md)의 endpoint를 사용하며, 동일한 Link-Participant-Context를 요구한다. 앱 자동 매칭 성공 시에도 서버가 해당 참여자용 문맥을 발급해 공개 후속 API 권한을 일관되게 처리한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: settlement_links에 settlement_id, token_hash unique, status, expires_at을 저장한다. settlement_receiving_account_snapshots에 settlement_id unique, source_account_id, bank_code, account_number, account_holder를 저장한다. settlement_shares에 settlement_id, payer_participant_id, amount, 계좌 스냅샷 참조, 확정 시각을 저장하고 정산과 payer 범위 Unique 제약을 둔다.
- Redis key, TTL, or serialization changes: settlement-link-participant:{contextId}에 tokenHash와 participantId를 저장한다. TTL은 보안·UX 정책 확정 전 구성값으로 두며, 만료 후 다시 링크를 해석한다.
- External API or message changes: 계좌 후보는 기존 CODEF 연결 계좌의 Account 저장 데이터를 사용한다. 단일 HTTPS 링크의 앱 라우팅은 iOS Associated Domains와 Android Digital Asset Links 설정을 배포 단계에서 연결한다.
- Environment variable or profile changes: link base URL과 참여 문맥 TTL을 Properties로 관리한다. 수금 계좌 후보용 개발 Stub은 추가하지 않는다.

## 5. Design decisions

### Business logic

- Rules and their locations: token 원본 비보관·Share 불변성·계좌 스냅샷 고정은 SettlementLink와 SettlementShare Domain 규칙으로 둔다. 상태 전환과 링크·Share 저장 순서는 Application Service가 트랜잭션으로 조정한다.
- Behavior owned by Domain: 링크 활성·만료 판정, Share 금액 양수·합계·중복 payer 검증, 확정 뒤 변경 금지.
- Flow orchestrated by Application: 생성자 권한 확인, 수금 계좌 조회·스냅샷화, 앱 current user 매칭, 브라우저 참여 문맥 발급, AllocationResult 소비, Toss 안내 DTO 조립.

### Technology and patterns

- Constraint to solve: 하나의 링크로 앱 회원은 빠르게, 비회원은 브라우저에서 접근해야 하며 서버는 수신 기기 설치 상태를 알 수 없다.
- Selected approach: 단일 HTTPS 링크를 만들고 OS의 검증된 App/Universal Link 라우팅에 맡긴다. 앱은 optional Authorization으로 userId를 보내 자동 매칭하고, 나머지는 브라우저 이름 선택을 한다.
- Reason for selection: URL을 사용자별로 만들지 않아도 되고 OS 표준 공유 시트와 앱·웹 진입 경로를 모두 지원한다.
- Alternatives considered: 링크 발급 때 수신자별 링크를 만드는 방식은 한 링크 공유 요구와 충돌해 제외한다. 브라우저에도 로그인·OTP를 강제하는 방식은 MVP 범위를 넘어서 제외한다.
- Trade-offs and limits: 브라우저 이름 선택은 신뢰 기반이고, App/Universal Link 설정이 빠지면 설치 앱도 브라우저로 갈 수 있다. 참여 문맥은 위조 방지 보조 수단이지 비회원의 본인 확인 수단이 아니다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
| --- | --- | --- |
| API | 수금 계좌 설정·요청 발급·공개 링크 해석·송금 안내 API | SettlementRequestController |
| Application | 링크, Share, 계좌 스냅샷, 참여 문맥 UseCase·Port | application.settlement |
| Domain | SettlementLink, ReceivingAccountSnapshot, SettlementShare 불변식 | domain.settlement |
| Persistence | 링크·계좌 스냅샷·Share JPA 저장 구조 | adapter.out.persistence.settlement |
| Redis | LinkParticipantContext와 TTL | adapter.out.redis.settlement |
| Security | 생성자 권한, 앱 optional auth, public link context | Security Adapter |
| Configuration | link base URL, context TTL | infra.settlement |
| Documentation | 앱 직접 진입·브라우저 fallback·Toss 역할 분리 | PRD, allocation Spec |

Existing behavior that must remain unchanged:

- 기존 JWT 로그인·user 프로필 계약을 바꾸지 않는다.
- 서버가 SMS·공유 대상 선택·Toss 송금을 실행하지 않는다.
- 입금 완료 상태는 이 기능에서 정하지 않고 CODEF 기반 입금 현황 관리가 처리한다.

## 7. Implementation plan

- [ ] 공통 링크·수금 계좌 스냅샷·불변 Share·참여 문맥 Domain 모델과 상태 규칙을 정의한다.
- [ ] 링크·Share·계좌·참여자·배분 결과·Redis 문맥 Output Port를 정의한다.
- [x] 수금 계좌 조회·선택 UseCase와 API를 구현한다.
- [ ] 링크 발급, 앱 자동 매칭, 브라우저 참여 선택, Share 확정, Toss 안내 UseCase를 구현한다.
- [ ] JPA Entity·Repository·Mapper·Persistence Adapter와 Redis 참여 문맥 Adapter를 구현한다.
- [ ] 생성자 API와 public link API, Swagger, 선택적 인증 처리, OS 링크 설정 배포 문서를 구현한다.
- [ ] 더치페이와 품목별 배분 각각의 Share 확정 시점·잠금·입금 현황 연계를 통합 검증한다.

### Expected commit boundaries

~~~
docs: 정산 요청 링크 구현 명세 작성
feat: 정산 요청 링크와 Share 저장 구조 추가
feat: 정산 수금 계좌와 요청 발급 API 추가
feat: 정산 링크 자동 매칭과 송금 안내 API 추가
~~~

## 8. Verification

### Commands

~~~
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
~~~

수동 검증 전제조건: PostgreSQL·Redis, 생성자·자동 매칭 가능한 앱 회원·브라우저 참여자, CODEF 연결 계좌, iOS/Android 링크 도메인 검증 배포 환경이 필요하다.

### Completion criteria

- [ ] 생성자는 DRAFT에서만 수금 계좌를 고르고 하나의 공통 URL을 발급한다.
- [ ] 유효 로그인 앱 회원이 참여자에 매칭되면 이름 선택 없이 송금 또는 품목 선택 단계로 간다.
- [ ] 앱 조건이 충족되지 않으면 브라우저에서 표시 이름 선택과 단기 참여 문맥 발급으로 진행한다.
- [ ] 더치페이는 요청 시, 품목별 배분은 전체 선택 검증 후에만 Share와 계좌 스냅샷을 불변으로 저장한다.
- [ ] 송금 안내는 Toss 이동 정보만 반환하며 Toss 성공만으로 Settlement가 COMPLETED가 되지 않는다.

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| 문서 작성 | Complete | 이 Spec |
| 구현·컴파일 | Pending | 구현 전 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
| --- | --- | --- | --- |
| 2026-08-21 | 공통 링크·앱 자동 매칭·브라우저 fallback·Share 고정 설계 기록 | 수금 계좌와 public link 실제 구현 | App/Universal Link 배포 검증과 payment-method 계약 미정 |
| 2026-08-23 | V15 수령 계좌 참조 스키마와 실제 Account 조회 경계 확정 | 수령 계좌 조회·선택 후 public link 구현 | App/Universal Link 배포 검증 미정 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
| --- | --- | --- | --- |
| 2026-08-21 | 한 정산에는 하나의 공통 HTTPS 링크를 발급한다. | 휴대폰 기본 공유 기능으로 같은 URL을 전달하기 위해서다. | 수신자별 URL은 만들지 않는다. |
| 2026-08-21 | 앱 회원은 userId 매칭 성공 시 이름 선택을 건너뛴다. | 공통 링크에서도 로그인한 참여자를 바로 개인 단계로 보내기 위해서다. | 세션·매칭이 없으면 브라우저 선택으로 내린다. |
| 2026-08-21 | 품목별 Share는 전체 선택 검증 뒤에만 고정한다. | 선택 중 송금액 변경을 막는다. | 중간 Share·부분 송금은 제공하지 않는다. |
| 2026-08-21 | Toss 이동과 입금 완료 판정을 분리한다. | 앱 호출은 송금 성공 증명이 아니기 때문이다. | CODEF 거래 검증이 완료 상태를 결정한다. |
| 2026-08-23 | 수금 계좌 후보는 기존 `LoadAccountPort`를 재사용한다. | CODEF 연결 계좌가 이미 실제 Account 도메인과 Adapter로 제공된다. | 별도 payment-method Port와 개발 Stub을 만들지 않는다. |

## 11. Completion record

- Completed on: Pending
- Final behavior: Pending
- Unverified items: 전체 구현 전
- Remaining risks: App/Universal Link 운영 배포와 LinkParticipantContext TTL·폐기 정책을 구현 전에 확정해야 한다.
- Follow-up work: 입금 현황과 CODEF 거래 대조 기능을 별도 Feature Plan으로 정의한다.
