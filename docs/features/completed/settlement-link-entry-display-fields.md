# Feature Plan: 정산 참여 링크 표시 필드 확장

- Status: Completed
- Last updated: 2026-09-01
- Related issue:
- Related ADR:

## 1. Purpose

### Problem

프론트엔드는 `GET /api/v2/settlement-links/{token}` 응답만으로 요청자를 비활성화하거나, 더치페이 참여자 금액과 총액·전체 인원을 표시할 수 없다. `APP_DIRECT`에서는 후보 배열도 비어 있어 전체 인원 계산이 불가능하다.

### Success criteria

- [x] 링크 응답에 `totalAmount`, `participantCount`가 포함된다.
- [x] `participantCandidates`와 `selectedParticipant`에 `isRequester`, `amount`가 포함된다.
- [x] `amount`는 `DUTCH_PAY`의 확정 Share 금액이며 `ITEM_ALLOCATION`에서는 `null`이다.

### Out of scope

- 참여자 선택 API에서 요청자 선택을 서버가 차단하는 규칙 추가
- 기존 링크·참여 문맥·송금 안내 동작 변경

## 2. Domain analysis

- Existing domain to reuse: `settlement`
- Actor: 공개 링크로 진입한 앱 또는 브라우저 사용자
- UseCase: 기존 `ResolveSettlementLinkUseCase`
- Invariants: 요청자는 `participant.isLinkedTo(settlement.creatorId())`로 판별한다. 유효한 더치페이 링크의 참여자 금액은 요청 확정 시 생성된 `SettlementShare`를 기준으로 한다.

## 3. Scenarios

### Normal flow

1. 유효한 링크에서 정산과 전체 참여자를 조회한다.
2. 더치페이면 확정 Share를 참여자 ID로 매핑한다.
3. 총액·전체 인원과 참여자별 요청자 여부·금액을 반환한다.
4. `APP_DIRECT`는 동일 필드가 채워진 `selectedParticipant`를 반환한다.

### Boundary conditions

- `ITEM_ALLOCATION`: 모든 참여자 `amount=null`
- `APP_DIRECT`: `participantCandidates=[]`이어도 `participantCount`는 전체 참여자 수
- 유효한 더치페이 링크에 참여자 Share가 없으면 불완전한 공개 응답 대신 링크 없음으로 처리

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: ResolveSettlementLinkUseCase (existing)
Result: SettlementLinkEntryResult, SettlementLinkParticipantResult
Result -> Web Response DTO: SettlementLinkEntryWebResponse.from(result)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `LoadSettlementSharePort` | 확정 참여자 금액 조회 | `SettlementLinkEntryService` | 기존 `SettlementTransferInstructionPersistenceAdapter` |

### Web API

```text
GET /api/v2/settlement-links/{token}
Response additions:
  totalAmount: long
  participantCount: int
  selectedParticipant/participantCandidates[]:
    isRequester: boolean
    amount: Long (ITEM_ALLOCATION이면 null)
Authentication: optional (existing)
```

### Persistence and external integrations

- Database change: 없음, 기존 `settlements`, `settlement_participants`, `settlement_shares` 사용
- Flyway migration required: No
- Redis/config/external integration change: 없음

## 5. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 응답 필드 추가 | `SettlementLinkEntryWebResponse`, `SettlementLinkParticipantWebResponse` |
| Application | 기존 결과 조립 확장 | `SettlementLinkEntryService`, 관련 Result |
| Persistence | 기존 Share 조회 재사용 | `LoadSettlementSharePort` |
| Domain/Redis/Security/Configuration | 변경 없음 | 기존 계약 유지 |
| Documentation | Swagger 설명과 Feature Plan | `SettlementLinkEntryDocs` |

## 6. Implementation plan

- [x] Migration-only pull request to `develop` (`N/A`)
- [x] Application Result 확장
- [x] Application Service에서 기존 Share 조회 및 응답 조립
- [x] Web Response DTO와 Swagger 갱신
- [x] 기존 테스트 갱신

### Expected commit boundaries

```text
feat(settlement): 정산 참여 링크 표시 정보를 확장한다
```

실제 커밋은 사용자가 별도로 요청할 때만 생성한다.

## 7. Verification

```bash
./gradlew test --tests '*SettlementLinkEntryServiceTest' --tests '*SettlementLinkEntryControllerTest'
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

## 8. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-09-01 | 최신 `origin/develop` 흐름과 기존 데이터 확인, 마이그레이션 불필요 결정 | 구현과 검증 | 더치페이 Share 누락 시 nullable 의미가 모호해질 수 있어 링크 없음으로 처리 |
| 2026-09-01 | 응답 필드·Swagger·기존 테스트 갱신, 대상 테스트와 컴파일 성공 | 완료 | 없음 |

### Verification results

| Item | Result | Evidence |
|---|---|---|
| 링크 서비스·Web 계약 테스트 | Passed | `./gradlew test --tests '*SettlementLinkEntryServiceTest' --tests '*SettlementLinkEntryControllerTest'` |
| Java compile | Passed | `./gradlew compileJava` |
| Diff whitespace | Passed | `git diff --check` |

## 9. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-09-01 | 더치페이 금액은 `SettlementShare`를 사용 | 링크가 공개되는 시점의 확정 금액이며 기존 조회 포트를 재사용 가능 | DRAFT용 allocation 조회는 확정 계약과 맞지 않음 |
| 2026-09-01 | 요청자 선택 서버 차단은 제외 | 이번 요청은 표시용 응답 필드 확장 범위 | 별도 정책 변경 시 선택 API 오류 계약이 필요 |
