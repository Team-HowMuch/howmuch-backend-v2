# Feature Plan: 토스 송금 딥링크

- Status: Complete
- Last updated: 2026-08-26
- Related issue: #176, #179, #180, #181
- Related ADR: N/A

## 1. Purpose

### Problem

정산 참여자는 확정 금액과 수취 계좌를 조회할 수 있지만 직접 송금 앱 딥링크를 조립해야 한다. 클라이언트별 조립 차이를 없애고, 이후 다른 송금 앱 링크를 함께 제공할 수 있는 서버 계약이 필요하다.

### Why now

정산 참여 완료 흐름에서 토스 송금 화면 진입만 남아 있다. 현재 송금 안내 API가 이미 검증된 Share 금액과 복호화된 수취 계좌를 제공하므로 이 경계에서 링크를 생성하는 것이 가장 작다.

### Success criteria

- [x] 지원 은행의 확정 Share에 `supertoss://send?bank=...&accountNo=...&amount=...` 링크를 반환한다.
- [x] 더치페이와 품목별 할당이 같은 송금 안내 API와 링크 계약을 사용한다.
- [x] 지원하지 않는 은행 또는 유효하지 않은 송금 입력은 링크를 반환하지 않는다.
- [x] 링크 조회만으로 Share 또는 Settlement 납부 상태가 변경되지 않는다.
- [x] 응답은 여러 송금 앱 링크를 수용하는 배열 계약을 사용한다.

### Out of scope

- Toss 송금 성공 콜백 또는 송금 완료 판정
- CODEF 입금 대조 변경
- Toss 이외 송금 앱 딥링크 구현
- 딥링크 성능 최적화와 평문 계좌번호 캐시
- 앱인토스 미니앱 내부 `openURL` 지원

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: settlement
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: 정산 수취계좌 스냅샷의 은행 기관코드와 계좌번호, `BANK.attr2`의 Toss 은행명을 사용한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 송금 안내 | 참여자의 확정 Share와 수취 계좌 | `SettlementTransferInstructionResult` |
| 송금 앱 링크 | 특정 송금 앱 화면을 여는 URI | `TransferLink` |
| 링크 제공자 | 송금 앱별 링크 생성 구현 | `SettlementTransferLinkPort` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 참여자 | 확정 금액을 수취 계좌로 송금하기 위해 Toss를 연다. | `GetSettlementTransferInstructionUseCase` |

### State and invariants

- State transitions: 없음. 조회 전후 Settlement와 Share 상태는 유지된다.
- Rules that must always hold: 유효한 링크·참여 문맥, `AWAITING_DEPOSIT`, 확정 Share, 수취계좌 스냅샷이 모두 있어야 한다.
- Invalid states: 지원하지 않는 은행, 0원 이하 금액, 숫자로 정규화할 수 없는 계좌번호는 송금 링크를 만들 수 없다.

## 3. Scenarios

### Normal flow

1. 참여자가 유효한 링크 토큰과 참여 문맥으로 송금 안내를 조회한다.
2. Application Service가 Share와 수취계좌 스냅샷을 조회한다.
3. 등록된 `SettlementTransferLinkPort` 구현체가 지원 가능한 링크를 생성한다.
4. 응답은 기존 송금 안내와 `transferLinks` 배열을 반환한다.

### Failure flow

- Condition: 어떤 링크 제공자도 은행·계좌·금액 조합을 지원하지 않는다.
- Returned error or state transition: `SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED`(422), 상태 변경 없음.

### Boundary conditions

- URL query 값은 UTF-8 percent encoding한다.
- 계좌번호는 `-`를 제거한 숫자만 링크에 포함하고 금액은 양수 정수 원 단위로 제한한다.
- 같은 요청은 저장 없이 같은 링크를 계산하므로 재시도 가능하다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: GetSettlementTransferInstructionUseCase
Command: N/A
Result: SettlementTransferInstructionResult + transferLinks
Web Request DTO -> Command: N/A
Result -> Web Response DTO: SettlementTransferInstructionWebResponse.from(result)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SettlementTransferLinkPort` | 지원 가능한 송금 앱 딥링크 생성 | `SettlementTransferInstructionService` | `TossSettlementTransferLinkAdapter` |

### Application Policy

- Decision owned by the Policy: N/A
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: 외부 앱별 URL 규격은 Domain 규칙이 아니다.
- Future inputs deliberately not implemented yet: Toss 이외 앱별 은행명·URL 스키마.

### Web API

```text
GET /api/v2/settlement-links/{token}/transfer-instruction
Request: Link-Participant-Context header
Response: 기존 송금 안내 + transferLinks[{provider, url}]
Errors: 기존 404 오류, SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED(422)
Authentication: 공개 링크 token과 단기 참여 문맥 결합 검증
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: `BANK.attr2`에 Toss 딥링크 은행명 관리
- Flyway migration required (`Yes` / `No`): Yes
- Planned migration: `V22__add_toss_deep_link_bank_common_code.sql`
- Migration pull request (`Pending` / URL): #186 (Merged)
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: Toss 비공식 `supertoss://send` URI 생성
- Environment variable or profile changes: 없음

Toss 링크 지원 은행:

| CODEF 기관코드 | Toss `bank` 값 | CODEF 기관코드 | Toss `bank` 값 |
|---|---|---|---|
| `0002` | `KDB산업은행` | `0003` | `IBK기업은행` |
| `0004` | `KB국민은행` | `0007` | `수협은행` |
| `0011` | `NH농협은행` | `0020` | `우리은행` |
| `0023` | `SC은행` | `0027` | `씨티은행` |
| `0031` | `대구은행` | `0032` | `부산은행` |
| `0034` | `광주은행` | `0035` | `제주은행` |
| `0037` | `전북은행` | `0039` | `경남은행` |
| `0045` | `MG새마을금고` | `0048` | `신협` |
| `0071` | `우체국` | `0081` | `하나은행` |
| `0088` | `신한은행` | `0089` | `케이뱅크` |

## 5. Design decisions

### Business logic

- Rules and their locations: 송금 가능 상태와 참여자 범위는 기존 Service, Toss 은행명은 `BankCatalog`, URL 규격은 Output Adapter.
- Behavior owned by Domain: 기존 Settlement·Share·Snapshot 불변식 유지.
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 검증된 금액·계좌를 모든 링크 제공자에 전달하고 하나 이상 생성되지 않으면 422를 반환한다.

### Technology and patterns

- Constraint to solve: 현재 Toss 하나를 구현하면서 이후 여러 앱 링크를 한 응답에서 제공해야 한다.
- Selected approach: 앱별 `SettlementTransferLinkPort` 구현체 목록과 `transferLinks` 배열.
- Reason for selection: 신규 앱은 기존 Service 수정 없이 구현체 추가만으로 연결할 수 있다.
- Alternatives considered: Service 내부 Toss 문자열 조립은 가장 짧지만 이후 여러 앱 지원 시 외부 규격 분기가 누적되어 제외했다.
- Trade-offs and limits: `supertoss://send` 파라미터는 공식 안정 계약이 확인되지 않아 앱 업데이트에 따라 변경될 수 있다. 은행 목록 응답은 기존 `organization`, `name`, `iconUrl`만 유지한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `transferLinks` 배열과 422 문서 추가 | `SettlementTransferInstructionWebResponse`, `SettlementTransferInstructionDocs` |
| Application | 링크 제공자 호출과 결과 조립 | `SettlementTransferInstructionService` |
| Domain | 오류 코드 추가 외 상태 변경 없음 | `SettlementStatusCode` |
| Persistence | `BANK.attr2` 조회 | `BankCatalog`, V22 Migration |
| Redis | 없음 | 기존 참여 문맥 조회 재사용 |
| Security | 검증된 문맥에서만 민감한 URL 반환, 로그 저장 없음 | 기존 Controller/Service 경계 |
| Configuration | 없음 | Spring의 동일 Port 구현체 목록 주입 |
| Documentation | 계약·지원 은행·제약 기록 | 이 Feature Plan |

Existing behavior that must remain unchanged:

- 기존 `recipientBankCode`, `recipientAccountNumber`, `amount` 응답 필드는 유지한다.
- 링크 조회는 실제 입금 완료로 처리하지 않는다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules (`N/A`, 기존 모델 재사용)
- [x] Application Policies (`N/A`)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`, Spring 목록 주입 사용)
- [x] Documentation and verification results

### Expected commit boundaries

```text
docs(settlement): 토스 송금 딥링크 계약 문서화
feat(settlement): 다중 송금 앱 링크 생성 계약 구현
test(settlement): 토스 송금 딥링크 계약 검증
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

### Completion criteria

- [x] Verified the user outcome defined under Purpose.
- [x] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| 구현 전 계약 검토 | Pass | 기존 송금 안내·Snapshot 복호화·은행 코드 목록 확인 |
| Flyway migration | N/A | 저장 구조 변경 없음 |
| 관련 테스트 | Pass | Toss Adapter, Application Service, Web Controller 테스트 통과 |
| 전체 테스트 | Pass | 격리 PostgreSQL 18.3·Redis에서 411개 테스트 통과, V22 Flyway 적용 |
| OpenAPI 런타임 | Pass | 200·404·422 응답과 `transferLinks[]` schema 확인 |
| 실제 Toss 앱 실행 | Not run | 모바일 기기와 Toss 앱 버전별 확인이 필요한 외부 상태 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-26 | 기존 송금 안내 흐름과 외부 스킴 계약 검토 | Port, Toss Adapter, API 계약 구현 | `supertoss://send`는 공식 안정 계약이 확인되지 않음 |
| 2026-08-26 | Toss 링크 Adapter, 다중 제공자 Port, API 배열, 테스트 구현 | PR 생성 | 실제 Toss 앱 실행은 모바일 검증 필요 |
| 2026-08-26 | V22 병합 후 하드코딩 맵을 제거하고 `BANK.attr2` 조회로 변경 | 검증 및 PR 업데이트 | 은행 목록 외부 응답에는 attr2를 노출하지 않음 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-26 | 서버가 앱별 링크 배열을 생성한다. | 클라이언트 중복과 앱별 규격 누수를 막는다. | 기존 원시 송금 필드는 호환성을 위해 유지한다. |
| 2026-08-26 | 현재는 Toss만 구현한다. | 사용자가 지정한 현재 범위다. | 다른 앱은 같은 Port Adapter로 추가한다. |
| 2026-08-26 | Toss 은행명은 `BANK.attr2`에서 `BankCatalog`로 조회한다. | 관리 값의 단일 소스를 공통 코드로 유지한다. | 공개 은행 목록 응답에는 attr2를 추가하지 않는다. |

## 11. Completion record

- Completed on: 2026-08-26
- Final behavior: 지원 은행 20개의 확정 송금 안내에 Toss 딥링크를 `transferLinks` 배열로 반환한다. 지원 제공자가 없으면 422를 반환하며 정산 상태는 변경하지 않는다.
- Unverified items: 실제 Toss 앱 버전별 딥링크 실행
- Remaining risks: 비공식 스킴 변경, 앱인토스 내부 호출 금지 정책 및 모바일 OS별 외부 앱 호출 정책
- Follow-up work: 다른 송금 앱 제공자가 필요할 때 Port 구현체 추가
