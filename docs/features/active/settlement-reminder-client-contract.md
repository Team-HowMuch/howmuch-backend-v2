# Feature Plan: 정산 독촉 클라이언트 계약 보강

- Status: Implemented
- Last updated: 2026-08-30
- Related issue:
- Related ADR: N/A

## 1. Purpose

### Problem

정산 완료 화면은 미납 회원 전체에게 한 번에 독촉하고 실제 성공 인원을 표시해야 하지만 현재 API는 참여자 한 명만 처리한다. 정산 상세 응답에는 회원 연결 여부가 없고, 앱 푸시 대상 등록 시점도 클라이언트 계약에 명확히 드러나지 않는다. 또한 정산 요청 시 한 번 반환한 공유 링크의 원본 토큰을 저장하지 않아 진행 중 정산에서 같은 링크를 다시 조회할 수 없다.

### Why now

앱의 독촉 완료 화면과 공유 동작을 실제 API에 연결하려면 서버가 일괄 성공 건수, 회원 여부, 푸시 등록 수명주기, 재조회 가능한 공유 링크를 제공해야 한다.

### Success criteria

- [x] 생성자가 한 요청으로 현재 독촉 가능한 미납 회원 전체에게 푸시를 보내고 실제 성공 수를 받는다.
- [x] 정산 상세의 각 `participants[]`가 `linked`를 반환한다.
- [x] 앱은 로그인 또는 세션 복원 직후 FID를 등록하고, 변경 시 재등록하며, 로그아웃 전에 해제한다.
- [x] 생성자가 진행 중 정산의 공유 URL과 만료 시각을 다시 조회한다.
- [x] DB에는 공유 링크 원본 토큰을 평문으로 저장하지 않는다.

### Out of scope

- 비회원 SMS 독촉
- 독촉 작업 큐와 영구 발송 이력
- 다중 기기 푸시 대상

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `settlement`, `user`
- Reason a new domain is required, if applicable: N/A
- Relationships to other domains: user는 FID를, settlement는 참여자 연결·납부·공유 링크 상태를 제공한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 일괄 독촉 | 현재 정산에서 독촉 가능한 모든 미납 회원에게 순차 푸시 | `RemindAllSettlementParticipantsUseCase` |
| 연결 회원 | `linkedUserId`가 있는 참여자 | `linked` |
| 재조회 링크 | 생성자 인증으로 다시 얻는 기존 공유 URL | `SettlementShareLinkResult` |
| 링크 암호문 | AES-GCM으로 보호한 원본 링크 토큰 | `settlement_links.token_ciphertext` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 정산 생성자 | 미납 회원 전체에게 독촉하고 성공 수를 확인한다. | `RemindAllSettlementParticipantsUseCase` |
| 정산 생성자 | 진행 중 정산의 공유 링크를 다시 얻는다. | `GetSettlementShareLinkUseCase` |
| 로그인 회원 | 현재 앱 설치 FID를 등록·해제한다. | 기존 `ManageMyPushTargetUseCase` |

### State and invariants

- State transitions: 일괄 독촉은 정산 상태를 바꾸지 않는다. 공유 링크 조회도 링크가 유효하면 상태를 바꾸지 않는다.
- Rules that must always hold: 생성자만 일괄 독촉과 링크 조회가 가능하다. 공유 링크 원본 토큰은 암호문으로만 저장한다.
- Invalid states: `AWAITING_DEPOSIT`이 아닌 독촉, 연결·Share·FID가 없는 대상, 만료되거나 비활성인 링크.

## 3. Scenarios

### Normal flow

1. 앱은 인증 세션과 FID가 준비되면 `PUT /api/v2/user/me/push-target`을 호출한다.
2. 생성자가 일괄 독촉을 요청하면 서버가 미납·연결·FID 등록·cooldown 가능 대상을 고른다.
3. 서버는 기존 단건 규칙을 재사용해 순차 전송하고 `sentCount`를 반환한다.
4. 생성자가 공유 링크를 요청하면 서버는 암호문을 복호화해 기존 URL을 반환한다.

### Failure flow

- 일괄 독촉 대상이 없음: `sentCount = 0`.
- 개별 대상이 미연결·FID 없음·cooldown 중: 해당 대상만 건너뛰고 다른 대상을 계속 처리한다.
- Firebase 전체 사용 불가: `SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE`.
- 링크가 없거나 사용할 수 없음: 기존 링크 오류 계약을 재사용한다.
- 기존 행에 암호문이 없음: 원본을 복구할 수 없으므로 새 링크를 발급하고 이전 링크는 무효화한다.

### Boundary conditions

- 일괄 전송은 별도 큐 없이 순차 처리한다. 실제 지연이 API 제한을 넘을 때만 비동기 작업으로 전환한다.
- `PARTIALLY_PAID`는 현재 단건 독촉 계약과 동일하게 제외한다.
- 기존 `settlement_links` 행은 `token_ciphertext = NULL`을 허용한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: RemindAllSettlementParticipantsUseCase.remindAll(settlementId)
Result: SettlementBulkReminderResult(sentCount)
Result -> Web Response DTO: SettlementBulkReminderWebResponse.from(result)

UseCase: GetSettlementShareLinkUseCase.get(settlementId)
Result: SettlementShareLinkResult(url, expiresAt)
Result -> Web Response DTO: SettlementShareLinkWebResponse.from(result)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| 기존 settlement/user Load Ports | 대상과 납부·FID 조회 | 일괄 독촉 Service | 기존 Persistence Adapters |
| 기존 `SettlementReminderCooldownPort` | 참여자별 30분 예약 | 일괄 독촉 Service | 기존 Redis Adapter |
| 기존 `SettlementReminderPushSenderPort` | FCM 발송 | 일괄 독촉 Service | 기존 Firebase Adapter |
| 공유 링크 조회·갱신 Port | 링크 암호문 조회와 legacy 재발급 저장 | 공유 링크 Service | Settlement Persistence Adapter |

### Application Policy

- Decision owned by the Policy: N/A. 기존 단건 독촉 조건을 공통 private flow로 재사용한다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: FID, Redis, FCM, 링크 암호화는 외부 경계다.
- Future inputs deliberately not implemented yet: 비회원 전달 채널, 비동기 작업 큐.

### Web API

```text
POST /api/v2/settlements/{settlementId}/reminders
Response: { "sentCount": 3 }
Authentication: bearerAuth, creator only

GET /api/v2/settlements/{settlementId}/share-link
Response: { "url": "https://...", "expiresAt": "UTC LocalDateTime" }
Authentication: bearerAuth, creator only
```

`SettlementDetailWebResponse.participants[]`에 required boolean `linked`를 추가한다.

푸시 대상 계약:

- 로그인 성공 또는 인증 세션 복원 뒤 FID가 준비되면 `PUT /api/v2/user/me/push-target`.
- FID 변경 콜백에서도 같은 PUT 재호출.
- 로그아웃 시 인증 토큰 삭제 전에 `DELETE /api/v2/user/me/push-target`.
- 독촉 `422`는 응답 코드로 `SETTLEMENT_REMINDER_TARGET_NOT_LINKED`와 `SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND`를 구분한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: `settlement_links.token_ciphertext VARCHAR(1024) NULL` 추가.
- Flyway migration required (`Yes` / `No`): Yes
- Planned migration: `V24__add_settlement_link_token_ciphertext.sql`
- Migration pull request (`Pending` / URL): https://github.com/Team-HowMuch/howmuch-backend-v2/pull/199 (Merged)
- Redis key, TTL, or serialization changes: 없음, 기존 참여자별 cooldown 재사용.
- External API or message changes: 없음, 기존 Firebase Adapter 재사용.
- Environment variable or profile changes: 없음. 정산 요청 시 이미 필요한 기존 AES-GCM 키를 별도 AAD로 재사용한다.

## 5. Design decisions

### Business logic

- Rules and their locations: 단건·일괄 독촉 대상 판정과 전송 흐름을 같은 Application Service에서 재사용한다.
- Behavior owned by Domain: 기존 정산·참여자·링크 상태 규칙.
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 대상 일괄 조회, 순차 발송, 성공 건수 조립, 링크 복호화와 legacy 재발급.

### Technology and patterns

- Constraint to solve: 해시만 저장된 원본 토큰은 역산할 수 없다.
- Selected approach: 신규 링크 토큰을 AES-GCM 암호문으로 추가 저장한다.
- Reason for selection: 기존 hash 검증을 유지하면서 생성자에게 같은 링크를 다시 반환할 수 있다.
- Alternatives considered: 클라이언트 로컬 저장은 재설치·다른 기기에서 사라진다. 평문 저장은 bearer token 노출 위험이 있다. 매 조회 재발급은 이전 공유 링크를 매번 깨뜨린다.
- Trade-offs and limits: 기존 행은 원본이 없어 최초 조회 시 한 번 재발급해야 한다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 일괄 독촉, 공유 링크 조회, `linked` | settlement Controllers/DTOs |
| Application | 두 UseCase와 Result, 기존 단건 흐름 재사용 | settlement services |
| Domain | 기존 규칙 재사용 | settlement domain |
| Persistence | nullable 링크 토큰 암호문 | Flyway V24, link entity/adapter |
| Redis | 기존 cooldown 재사용 | reminder Redis adapter |
| Security | 생성자 권한, 토큰 암호화 | CurrentActor, AES-GCM adapter |
| Configuration | 링크 토큰 암호화 키 | technical properties |
| Documentation | 앱 FID 수명주기와 오류 코드 | User/Settlement Swagger |

Existing behavior that must remain unchanged:

- 기존 단건 독촉 API와 30분 cooldown.
- 원본 토큰 hash 기반 공개 링크 검증.
- 기존에 공유된 링크는 legacy 최초 재조회 전까지 계속 유효하다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`Required`: V24, PR #199 merged)
- [x] UseCase and Application Command/Result
- [x] Required Output Ports
- [x] Domain models and rules (`SettlementLink.reissue`, 나머지 기존 규칙 재사용)
- [x] Application Policies (`N/A`: 기존 단순 조건 재사용)
- [x] Application Service
- [x] Output Adapters
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (`N/A`: 기존 Bean wiring 재사용)
- [x] Documentation and verification results

### Expected commit boundaries

```text
build(database): 공유 링크 토큰 암호문 컬럼 추가
feat(settlement): 독촉 클라이언트 계약 보강
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
DB_URL=jdbc:postgresql://localhost:55432/howmuch \
  DB_USERNAME=howmuch DB_PASSWORD=<local-test-value> REDIS_PORT=56379 \
  DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused ./gradlew test
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
| Migration version | Pass | PR #199 병합, V24 적용 후 Hibernate schema validation 통과 |
| Focused contract tests | Pass | 독촉·공유 링크·상세·Controller·OpenAPI 테스트 통과 |
| Persistence integration | Pass | 링크 토큰 암호화 저장·복원·legacy 교체 통과 |
| Full test suite | Pass | PostgreSQL 16, Redis 7에서 505 tests 통과 |
| Static checks | Pass | `compileJava`, `compileTestJava`, `git diff --check`, status 확인 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-30 | 일괄 독촉·linked·푸시 등록 문서·공유 링크 조회 구현, V24 기반 전체 505 tests 통과 | 최종 정적 검사 | legacy 링크 최초 조회 시 기존 URL 교체 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-30 | 일괄 독촉은 서버 순차 전송과 `sentCount`만 제공 | 앱 요청 폭주와 과도한 비동기 인프라를 모두 피함 | 지연이 측정될 때 큐 도입 |
| 2026-08-30 | 상세에 명시적 `linked` boolean 추가 | `source=CONTACT`만으로 회원 여부를 판단할 수 없음 | 별도 participants 조회 불필요 |
| 2026-08-30 | 링크 원본은 AES-GCM 암호문으로 저장 | 같은 URL 재조회와 bearer token 보호를 함께 충족 | legacy는 최초 조회 시 재발급 |
| 2026-08-30 | 기존 정산 계좌 AES-GCM 키를 별도 AAD로 재사용 | 링크 발급 시 이미 필요한 키이며 새 설정과 암호화 중복을 피함 | 키 회전은 기존 정산 암호문과 함께 다룸 |

## 11. Completion record

- Completed on: 2026-08-30
- Final behavior: 일괄 독촉 `sentCount`, 상세 `linked`, FID 등록 수명주기, 진행 중 공유 링크 조회와 암호화 저장을 제공한다.
- Unverified items: 실제 Firebase 운영 프로젝트를 통한 푸시 전달은 외부 자격 증명 없이 실행하지 않았다.
- Remaining risks: legacy 링크 최초 재조회 시 기존 링크 무효화
- Follow-up work: 운영에서 일괄 전송 지연이 API 시간 제한에 근접할 때만 비동기 작업 또는 Firebase batch 전송을 검토한다.
