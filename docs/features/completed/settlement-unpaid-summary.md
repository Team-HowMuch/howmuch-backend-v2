# Feature Plan: 미납 현황 집계 API

- Status: Completed
- Last updated: 2026-10-10
- Related issue: [#477](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/477), [#479](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/479), [#480](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/480), [#481](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/481)
- Related ADR: [Capability-oriented Output Ports](../../adr/0003-capability-oriented-output-ports.md)

## 1. Purpose

정산 생성자가 본인의 진행 중 정산에서 확정된 남은 받을 금액과 미납 참여 건수를 한 번에 조회한다. 기존 총무 요약은 입금액을 차감하지 않아 부분 납부를 반영한 위젯 집계로 사용할 수 없다.

### Success criteria

- [x] 확인 입금액을 Share별로 차감한 잔액과 미납 참여 건수를 반환한다.
- [x] 품목 배분이 끝나지 않은 정산 건수를 별도로 반환한다.
- [x] 본인 생성 정산만 조회하고 중단·완료·초안·삭제 정산을 제외한다.
- [x] 빈 결과, 0원 제출, 비회원, 부분 납부와 초과 납부를 검증한다.

### Out of scope

앱 위젯 화면·이동·로그인 안내, 기존 총무 요약 계약 변경, 알림·입금 대조 동작 변경은 포함하지 않는다.

## 2. Domain analysis

기존 settlement 영역을 재사용한다. account의 거래 금액과 settlement의 입금 연결 데이터를 읽고, 현재 회원은 auth의 `CurrentActorPort`로 확인한다. 상태를 변경하지 않는 조회 기능이다.

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 확정 부담액 | 저장된 송금 의무 금액 | `SettlementShare.amount` |
| 확인 입금액 | Share에 연결된 거래 금액의 합 | `settlement_deposits` + `account_transactions` |
| 남은 받을 금액 | Share별 양수 잔액의 합, 원 단위 | `remainingReceivableAmount` |
| 미납 참여 건수 | 잔액이 양수인 정산별 참여 수 | `unpaidParticipantCount` |
| 미확정 정산 건수 | `REQUESTED`·`ITEM_ALLOCATION` 정산 수 | `unconfirmedSettlementCount` |

인증 회원은 `GetSettlementUnpaidSummaryUseCase`로 본인의 집계 값을 조회한다. 같은 회원이 두 정산에서 미납이면 2건으로 센다. 회원 연결 여부는 집계 조건이 아니다.

## 3. Scenarios

1. 정산 생성자가 API를 호출한다.
2. Service가 인증 회원을 확인하고 전용 조회 Port로 집계에 필요한 사실을 한 번에 가져온다.
3. Application Policy가 생성자 몫을 제외한 확정 Share별 잔액과 인원을 합산한다.
4. Web Adapter가 숫자 필드로 반환한다.

- 10,000원 부담에 3,000원 입금이면 7,000원·1건이다.
- 완납·초과 납부·0원 제출·Share 없는 참여자는 미납 건수에 포함하지 않는다.
- 한 Share의 초과 납부는 다른 Share의 잔액을 상쇄하지 않는다.
- `REQUESTED` 품목 정산에 일부 Share가 있으면 해당 잔액과 미납 건수를 포함하면서 미확정 정산 건수에도 반영한다.
- 대상이 없으면 세 값 모두 0이다. 미인증·유효하지 않은 세션은 기존 401 계약을 따른다.

## 4. Contracts

### Input Port and boundary mapping

```text
GetSettlementUnpaidSummaryUseCase.getSummary()
  -> SettlementUnpaidSummaryResult
  -> SettlementUnpaidSummaryWebResponse.from(result)
```

입력이 없는 조회이므로 Command와 Web Request DTO는 추가하지 않는다.

| Output Port | 목적 | 호출자 | Adapter |
|---|---|---|---|
| `LoadSettlementUnpaidSummaryPort` | 본인 생성 진행 정산의 부담액·확인 입금액 조회 | `SettlementUnpaidSummaryService` | `SettlementUnpaidSummaryPersistenceAdapter` |
| `CurrentActorPort` | 인증 회원 식별 | 같은 Service | 기존 보안 Adapter |

Policy는 정산 상태·배분 방식과 참여자별 Share 금액·확인 입금 합계를 받아 집계한다. 여러 정산과 입금을 조합하는 규칙이므로 Application이 소유한다.

### Web API

```text
GET /api/v2/settlements/unpaid-summary
Authentication: 기존 Access JWT와 활성 Redis 세션
Request: 본문·사용자 식별자 없음
Response: 200 ApiResponse<SettlementUnpaidSummaryWebResponse>
Fields: remainingReceivableAmount(long), unpaidParticipantCount(int),
        unconfirmedSettlementCount(int)
Error: 401 UNAUTHORIZED, 기존 인증 오류 계약
```

대상은 `creator_id = 인증 회원`, `status IN ('REQUESTED', 'AWAITING_DEPOSIT')`, `deleted_at IS NULL`이다. 생성자와 연결된 참여자 몫은 제외한다.

10,000원 부담에 확인 입금이 3,000원인 참여자 한 명만 남아 있다면 다음과 같이 반환한다.

```json
{
  "success": true,
  "data": {
    "remainingReceivableAmount": 7000,
    "unpaidParticipantCount": 1,
    "unconfirmedSettlementCount": 0
  }
}
```

세 필드는 항상 존재하며 JSON 숫자로 반환한다. Swagger에는 금액을 `integer/int64`, 두 건수를 `integer/int32`로 문서화한다.

### Persistence and external integrations

- Flyway migration required: **No**. 기존 테이블을 조회하며 Entity·제약·인덱스를 변경하지 않는다.
- 조회 SQL에서 대상 정산을 먼저 제한하고 입금액을 `(settlement_id, share_id)`별로 집계한다.
- Share 없는 정산도 LEFT JOIN으로 유지하고, 조인으로 참여자나 입금액이 중복되지 않게 한다.
- PostgreSQL 합계는 정확한 정수로 전달한다. 초과 입금 합계가 long 범위를 넘어도 Share별 잔액 계산이 잘못되지 않게 한다.
- Redis 키·TTL, 외부 API·메시지, 환경 설정은 변경하지 않는다.

## 5. Design decisions

`max(확정 Share 금액 - 확인 입금 합계, 0)`을 Share별로 계산한 뒤 합산한다. 전체 합계에서 입금을 한 번에 차감하지 않는다. 확정되지 않은 총액이나 생성자 선택 금액을 추정하지 않는다.

전용 Port와 한 번의 조회로 상세 API의 정산별 반복 호출을 피한다. Service는 읽기 전용 트랜잭션을 열고 Policy를 호출한다. Controller는 UseCase와 Web Response에만 의존한다. 기존 Domain 상태 전이와 총무 요약은 유지한다.

## 6. Impact surface

| 영역 | 영향 |
|---|---|
| API | 인증된 집계 GET API와 Swagger 추가 |
| Application | UseCase·Result·조회 사실·Policy·Service 추가 |
| Persistence | 전용 읽기 Adapter와 조회 SQL 추가 |
| Domain / Redis / Infra | 변경 없음 |
| Documentation | 집계 계약·검증 기록 추가 |

## 7. Implementation plan

- [x] Migration-only PR: N/A — 저장 구조 변경 없음.
- [x] UseCase와 Result 정의.
- [x] 실제 호출자와 함께 조회 Output Port 연결.
- [x] Domain 모델·상태 전이 변경: N/A — 기존 사실만 집계.
- [x] Application Policy와 Service 구현.
- [x] Persistence Adapter 구현.
- [x] Controller·Web Response·Swagger 구현.
- [x] Infra 설정: N/A — 기존 인증과 Bean 탐색 재사용.
- [x] 자동화 검증 및 문서 완성.

### Expected commit boundaries

```text
docs(settlement): 미납 집계 계약과 구현 계획 정리
feat(settlement): Share별 미납 잔액과 참여 건수 계산 추가
feat(settlement): 생성 정산의 미납 집계 조회 구현
test(settlement): 미납 집계 영속 조회 검증
feat(settlement): 미납 현황 집계 API 추가
test(settlement): 미납 현황 본인 조회 권한 검증
docs(settlement): 미납 집계 구현 결과와 검증 기록
```

## 8. Verification

일회용 PostgreSQL 18·Redis 8과 Java 21을 사용한다. 환경 설정과 실행 절차는 [Testing](../../testing.md)을 따른다.

로컬 검증에서는 `compose.test.yml`을 별도 프로젝트로 실행하여 PostgreSQL `15432`, Redis `16379` 포트를 사용했다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`는 해당 일회용 환경을 지정했다. Kafka와 소비자는 비활성화하고 알림 전달 모드는 `DIRECT`로 설정했다. 실제 인증 검증에서는 발급한 JWT와 Redis 세션을 사용했다.

```bash
./gradlew compileJava
bash scripts/test.sh test --tests '*SettlementUnpaid*'
bash scripts/test.sh
git diff --check
git status --short --untracked-files=all
```

- Policy: 부분 납부·완납·초과 납부·다른 Share의 잔액 보존·정수 합계 처리.
- 영속 조회: 상태·삭제·생성자 필터, 여러 입금, 동일 회원의 여러 정산, 비회원·0원 제출·미확정·부분 확정·빈 결과.
- Web: 세 숫자 필드와 응답 envelope, 정적 경로가 UUID 상세 경로로 처리되지 않는지 검증.
- 실제 HTTP: JWT·Redis 인증, 두 회원의 데이터 분리, 세션 무효화, Swagger 응답 계약.

| 항목 | 결과 | 근거 |
|---|---|---|
| 컴파일 | 통과 | `./gradlew compileJava` 및 전체 회귀의 clean build |
| 관련 테스트 | 32건 통과 | Policy 8건, Service 1건, 영속 조회 14건, Web 2건, 실제 HTTP 7건 |
| 전체 회귀 | 통과 | `bash scripts/test.sh`: Java 1,542건·JavaScript 35건, 실패·오류·건너뜀 없음 |
| 문서·diff 검사 | 통과 | 상대 링크·로컬 경로 확인, `git diff --check`; 기존 `.DS_Store`만 미추적 상태로 보존 |

## 9. Progress log

| 날짜 | 완료 작업 | 다음 작업 | 확인 사항 |
|---|---|---|---|
| 2026-10-10 | 최신 develop 확인·설계 기록 | 집계 Policy 구현 | 기존 저장 구조로 구현 가능 |
| 2026-10-10 | Policy·UseCase·조회 Adapter 구현 | 영속 조회 검증 | 입금 선집계와 Share별 잔액 계산 연결 |
| 2026-10-10 | 영속 조회·Web·실제 HTTP 검증 | 전체 회귀 | 회원별 데이터 분리·부분 납부·세션 무효화·Swagger 확인 |
| 2026-10-10 | 전체 회귀·문서 검사 완료 | PR 검토 | 신규 32건 포함 Java 1,542건과 JavaScript 35건 통과 |

## 10. Decision log

| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-10-10 | 인원은 정산별 참여 건수로 합산 | 사용자 선택 반영, 이름만 등록된 참여자도 집계 |
| 2026-10-10 | 확정 Share만 합산하고 미확정 정산 수를 별도 제공 | 선택 대기 금액을 확정 의무로 표시하지 않음 |
| 2026-10-10 | 전용 집계 API 추가 | 기존 총무 요약의 호환성 유지 |

## 11. Completion record

- Completed on: 2026-10-10
- Final behavior: 인증 회원의 진행 중 생성 정산에서 확정 Share별 남은 받을 금액·미납 참여 건수와 미확정 품목 정산 건수를 반환한다. 빈 결과는 모두 0이며 기존 총무 요약 계약은 유지한다.
- Unverified items: 실제 은행 연동 및 앱 위젯은 이번 구현 범위에서 제외했다. API·저장 조회·인증·Swagger·전체 회귀 검증은 수행했다.
- Remaining risks: 실제 은행 입금 반영 시점은 기존 입금 대조 처리 주기를 따른다.
- Follow-up work: 앱에서 집계 API 연동 및 위젯 표시 구현.
