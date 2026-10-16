# Feature Plan: 압축 정산 목록·상세와 건수 안내

- Status: Completed (local verification)
- Date: 2026-10-17
- Related issue: #506


2026-10-17 권한 정책 변경: 활성 그룹 멤버 모두 압축·재압축할 수 있다. 이 정책의 DB 전제는 별도 [SQL PR #571](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/571)의 V58이다. [멤버 실행 후속 계획](group-netting-member-execution.md)에 구현·검증 범위를 기록한다. 아래 이전 날짜의 검증 기록은 당시 수행 범위다.


게시·전체 회귀 후속 기록은 [직접 실행 계획의 게시 검증 결과](group-netting-direct-execution.md#게시-검증-결과)를 따른다. 아래 로컬 단계의 “미게시” 설명과 테스트 수는 당시 수행 기록이다.

## 요청과 완료 기준

압축 전에는 “정산을 정리하면 N건으로 줄어들어요 / 압축하기” 안내를 제공한다. 압축 정산에는 카테고리를 추가하지 않는다. 포함 원본은 일반 정산 목록에서 숨기고 압축 상세의 `sources`에 내역을 제공한다. 원본 데이터/상세 접근 권한/입금 이력은 보존한다.

## 설계·영향

- Migration required: No. V53의 `group_netting_sources` 존재 여부로 원본 목록에서 제외한다. `NETTING_IN_PROGRESS`뿐 아니라 완료된 원본도 계속 제외하며, 작업 접수만 된 미확정 원본은 숨기지 않는다. RECEIVE/PAY와 총무 목록의 동일 정산 조회 경계를 수정한다.
- 기존 압축 상세 `sources`에 title/status/requesterName/requestedAt/allocationType을 추가한다. 재압축 lineage의 원본도 같은 단일 조회로 반환하며 원본 전문 상세의 권한은 확대하지 않는다. 카테고리나 원본의 계좌번호는 이 응답에 포함하지 않는다.
- `GET /api/v2/groups/{groupId}/settlement-nettings/estimate`: 활성 그룹 멤버가 현재 DB 미납으로 기존 계산기를 실행해 전/후 송금 건수를 읽는다. 저장·은행 호출·Batch 접수·동의·미리보기 생성은 하지 않는다. 권한 및 진행 중 실행을 반영한 `canCompress`로 버튼을 제어한다.
- estimate는 안내용이다. 실제 POST는 종전처럼 body 없이 직접 실행하며 은행 동기화와 입력 재검증 결과에 따라 건수가 달라질 수 있다. 압축할 부채가 없으면 0건과 canCompress=false를 반환한다.
- 기존 작업 `feat/506-group-netting-direct` worktree에서 이어서 수정하고 주 작업 폴더의 별도 변경은 보존한다. UI 코드·SQL·게시 작업은 제외한다.

## 예상 commit 경계 (게시 요청 시만)

1. feat(settlement): 압축 원본 목록 제외 및 상세 내역 제공
2. feat(settlement): 압축 전 예상 송금 건수 조회
3. test(settlement): 압축 목록과 안내 건수 회귀 검증

## 검증 계획

- Java 21 compileJava 및 diff/link 검증.
- 기존 격리 PostgreSQL/Redis의 실제 Batch 통합에서 압축 전 원본 노출, 압축 후 목록 제외, 완료/재압축 후 제외 유지, 상세 sources 표시를 확인한다.
- estimate의 2→1 / 2→0, 조회 무부작용/은행 호출 없음, 멤버/OWNER/외부인 권한, 진행 중 버튼 차단 및 부채 없는 그룹 응답을 확인한다.
- 기존 정산 목록/참여자/총무 조회 회귀를 실행한다. 전체 프로젝트 테스트 및 실 CODEF 호출은 제외한다.

## 구현·검증 결과

- [일반 목록 SQL](../../../src/main/java/com/amateur/howmuchbackend/adapter/out/persistence/settlement/repository/SettlementJpaRepository.java)의 RECEIVE/PAY/총무 목록에서 `group_netting_sources`에 포함된 원본을 제외했다. 상태만으로 필터하지 않으므로 완료된 압축 원본도 다시 나타나지 않는다. 실행 접수만 된 원본은 확정까지 계속 표시한다.
- [압축 응답](../../../src/main/java/com/amateur/howmuchbackend/adapter/in/web/settlement/netting/dto/response/GroupNettingWebResponse.java)의 `sources`에 `title`, `status`, `requesterName`, `requestedAt`, `allocationType`을 추가했다. 기존 `settlementId`, `originalAmount`, `actualDepositAmount`, `replacedAmount`는 유지한다. requestedAt은 기존 목록처럼 링크 발급 시각, 없으면 생성 시각을 사용한다. 원본 금액 세 필드는 압축 당시 미납/입금 snapshot이며 전체 정산 총액이나 개별 사용자의 금액으로 임의 해석하지 않는다.
- 압축 결과에는 `category`/`myCategory`를 추가하지 않았다. 원본의 기존 카테고리 데이터를 삭제하거나 변경하지 않는다. 원본 전문 상세의 접근 권한은 기존 그대로다.
- [안내 건수 조회](../../../src/main/java/com/amateur/howmuchbackend/application/settlement/netting/service/GroupNettingEstimateService.java)를 추가했다. 입력은 저장 없이 기존 계산기·Validator로 검증한다. 같은 사람이 같은 수취인에게 여러 원본의 송금을 해야 한다면 beforeTransferCount는 각 미납 건을 따로 센다. afterTransferCount는 현재 미납을 압축한 실제 송금 계획의 건수다.
- `canCompress`는 현재 합칠 대상이 2건 이상이고 활성 그룹 멤버이며 송금 건수가 감소하고 CREATE 작업이 대기·실행·재시도 중이 아닐 때 true다. 일반 멤버도 동일하게 실행할 수 있으며 외부 사용자는 403이다. 미해결 검토/유효하지 않은 계좌 등의 기존 입력 제한은 공통 오류를 유지한다.

### 프런트엔드 연동

```http
GET /api/v2/groups/{groupId}/settlement-nettings/estimate
Authorization: Bearer <access-token>
```

예시 data (표시용 예시):

```json
{
  "groupId": 1,
  "eligibleSettlementCount": 4,
  "beforeTransferCount": 6,
  "afterTransferCount": 3,
  "reducedTransferCount": 3,
  "canCompress": true
}
```

- eligibleSettlementCount는 이번에 새로 포함할 원본 정산 수이며, 기존 압축의 원본 수를 중복해서 세지 않는다. beforeTransferCount에는 기존 압축의 미납 송금도 포함한다.
- 안내: “정산을 정리하면 {afterTransferCount}건으로 줄어들어요”. canCompress=true일 때 안내와 “압축하기” 버튼을 표시한다. pending 중 대기 상태는 실행 조회로 구분한다.
- “압축하기”는 기존 `POST /api/v2/groups/{groupId}/settlement-nettings`로 새 Idempotency-Key를 보내 직접 접수한다. 별도 preview 생성·저장·승인 단계가 없다. estimate 결과는 확정 입력이나 가격 보장이 아니며 실행 시 은행 동기화 후 다시 계산한다.
- 일반 원본 목록은 기존 `GET /api/v2/settlements`를 사용하면 자동 제외된다. 압축 상세는 기존 current/상세 조회의 `sources`로 구성한다. 해당 원본을 추가로 목록 API에서 가져올 필요가 없다.
- 원본 `status`는 압축 상세 sources 및 기존 원본 상세에서 계속 조회할 수 있다. 진행 시 `NETTING_IN_PROGRESS`, 최종 완료 시 `COMPLETED`다.

### 수행한 검증

- Java 21 `compileJava`: 성공.
- 실제 PostgreSQL/Redis/Spring Batch 기반 `GroupNettingDirectIntegrationTest` 18개를 포함해 **Java 57개 성공, 실패/오류/skip 0**. 기존 정산 목록/참여자/총무 목록 통합 회귀를 포함했다.
- 2→1, 2→0, 미납 없음, OWNER/멤버/외부인, 접수 중 버튼 차단, 조회 시 은행 호출 없음/실행 저장 없음, 압축 전후 목록, 완료·재압축 후 원본 숨김 유지, 상세 sources 및 카테고리 없음 검증.
- 기존 `scripts/test.sh`의 JavaScript 35개 성공. 추가 UI 제작, 전체 회귀, 실 금융기관 호출, 배포, SQL, commit/push/PR은 수행하지 않았다.

```bash
# 격리 DB_URL/DB_USERNAME/DB_PASSWORD/REDIS_HOST/REDIS_PORT를 설정한 뒤 실행
JAVA_HOME=$(/usr/libexec/java_home -v 21) bash scripts/test.sh compileJava test \
  --tests '*GroupNettingDirectIntegrationTest' \
  --tests '*SettlementListPersistenceIntegrationTest' \
  --tests '*SettlementParticipantPersistenceIntegrationTest' \
  --tests '*TreasurerSettlementPersistenceIntegrationTest'
```

## 2026-10-17 예상 건수 캐시 보완

[Redis 예상 건수 캐시](group-netting-estimate-cache.md)를 적용했다. API/응답 계약은 유지하고 그룹 공통 건수만 기본 30초 캐시한다. 변경 커밋 후 무효화하며 권한·미해결 검토·작업 진행 여부는 캐시하지 않는다. 위 저장 없는 estimate라는 설명은 DB에 미리보기/실행 입력을 저장하지 않는다는 의미이며 Redis에는 안내용 Counts를 저장한다. 최신 검증과 한계는 연결 문서를 따른다.

## 화면 최종 연동 계약 (2026-10-17)

그룹 경로 prefix는 `/api/v2/groups/{groupId}`다. 아래 API에는 인증된 사용자가 필요하다.

| 화면/동작 | API | 표시·처리 기준 |
|---|---|---|
| “정산을 정리하면 N건으로 줄어들어요 / 압축하기” | GET `{prefix}/settlement-nettings/estimate` | N=afterTransferCount, canCompress로 표시 제어. Connection failed 문구로 사용하지 않음 |
| 압축·재압축 버튼 | POST `{prefix}/settlement-nettings` | body 없음, UUID Idempotency-Key, 활성 그룹 멤버. 현재 합칠 대상 최소 2건 |
| 압축 처리 중 대기 | GET `{prefix}/settlement-netting-executions/{executionId}` | 요청자만 조회, SUCCEEDED 후 결과 갱신. STALE/FAILED는 errorCode 안내 |
| 상단 압축 카드 | GET `{prefix}/settlement-nettings/current` | transfers의 payerName/receiverName, amount, confirmedAmount, 최상위 status 사용. 이력 없으면 404 |
| 압축 이력·선택 세대 | GET `{prefix}/settlement-nettings`, GET `{prefix}/settlement-nettings/{nettingId}` | ACTIVE/COMPLETED/SUPERSEDED 구분. 재압축된 이전 세대는 송금 불가 |
| 압축 상세의 기존 내역 | 위 상세 응답의 sources | title/requesterName/requestedAt/status 및 originalAmount/actualDepositAmount/replacedAmount. 별도 category 없음 |
| 일반 정산 목록 | GET `/api/v2/settlements` (기존 tab/status/groupId 조건) | 압축에 포함된 원본은 자동 제외. 완료 후에도 숨김 유지 |
| 카드의 사용자 이름·프로필 | GET `{prefix}/members` | transfers의 payerPublicId/receiverPublicId를 members.publicId와 연결 |
| “바로 보내기” | GET `{prefix}/settlement-nettings/{nettingId}/transfers/{transferId}/instruction` | 본인이 payer이고 ACTIVE이며 미납이 있을 때. 전체 계좌번호는 이 전용 응답만 사용 |
| 입금 새로고침 | POST `{prefix}/settlement-nettings/{nettingId}/deposits/reconcile` | 본인 수취 계좌 대상, UUID Idempotency-Key, 202 후 실행 조회 |
| 기존 정산에서 최신 압축으로 이동 | GET `/api/v2/settlements/{settlementId}/netting` | 재압축 후 최신 successor 반환 |
| 받을 돈 미납 요약 | GET `/api/v2/settlements/unpaid-summary` | 기존 미납 + ACTIVE 압축 잔액 합계. NETTING 원본/이전 SUPERSEDED는 중복 합산 안 함 |

압축 카드의 원래 금액은 transfer.amount, 실제 입금은 confirmedAmount, 남은 송금액은 `amount-confirmedAmount`다. 완료 카드에도 amount를 유지할 수 있고 미납 합계에는 잔액만 포함한다. 원본의 참가자별 납부 수를 압축 송금 진행률로 재사용하지 않는다. sources 금액은 원본 보존 snapshot이므로 다시 현재 미납에 더하지 않는다. 실입금은 압축 입금 이력에만 인정하고 원본에 가상 입금을 생성하지 않는다.

단일 일반 정산만 있거나 ACTIVE 압축만 있으면 새 실행은 409 `NETTING_INSUFFICIENT_SETTLEMENTS`다. 기존 압축 + 새 원본 1건은 2건으로 처리한다. 공개 eligibleSettlementCount는 새 원본 수로 유지하므로 재압축에서는 1이어도 canCompress=true일 수 있다. 최소 대상의 판단과 최종 저장 검증은 [최소 범위 계획](group-netting-minimum-scope.md)을 따른다.
