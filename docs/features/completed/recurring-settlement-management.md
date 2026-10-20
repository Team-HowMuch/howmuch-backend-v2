# Feature Plan: 정기 정산 폼 생성·수정·삭제 (#599)

- 상태: 완료
- 최종 수정: 2026-10-20
- 관련 이슈: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/599
- 선행 저장 구조: V62, PR #601 병합 확인. 이번 구현에는 추가 migration 없음.
- 관련 문서: [기존 정기 정산](../active/group-recurring-settlement.md), [개발 흐름](../../development-workflow.md)

## 1. 목적과 완료 기준

기존 DRAFT 전환만 가능한 등록을 폼 한 번의 요청으로 제공한다. 생성자가 동일 ID·발행 이력을
유지하면서 다음 회차의 내용을 수정하고, 삭제한 설정은 해지 목록에서도 숨길 수 있어야 한다.

- [x] 기존 sourceSettlementId 전환·replay 유지, 폼 직접 등록 지원.
- [x] 생성자만 전체 수정·삭제, 과거 발행 정산은 변경하지 않음.
- [x] 삭제 후 ACTIVE/CANCELLED 목록·홈·상세·회차 조회에서 제외, 이후 발행 중단.
- [x] 상세·그룹 목록에 이름·프로필·카테고리·마스킹 계좌 제공.
- [x] UTC 시각의 기존 앱 계약을 유지하고 HTTP에서 직렬화 검증.

운영 배포, 실제 은행·SMS·FCM, 프론트 화면, 일시정지·재개·회차 건너뛰기는 범위 밖이다.

## 2. 도메인·저장·불변식

기존 settlement.recurring 도메인을 사용한다. 설정과 발행된 Settlement/회차 이력은 별개다.
직접 등록은 원본 ID가 NULL이며 임시 초안을 만들지 않는다. 전환 등록은 원본 FK·UNIQUE를 유지한다.
삭제는 CANCELLED 상태와 deleted_at을 함께 저장한다. 기존 cancel은 deleted_at=NULL로 해지 목록에 남는다.
같은 생성자의 반복 DELETE는 성공하고, 삭제된 원본의 전환 replay는 404다.

제목은 1..100자, 금액은 양수이고 참여자는 서로 다른 활성 그룹 회원 2명 이상이며 생성자를 포함한다.
기존 DutchPayAllocationPolicy의 균등 분배 규칙을 공유하며 나머지 원은 생성자가 부담한다.
0원 배분이 생기는 총액은 거절한다. 수령 계좌는 생성자의 활성 원화 입출금 계좌이자 활성 연결이어야 한다.
선택 가능한 공통 카테고리만 등록한다. 요일·일자·월말 보정·KST 09:00은 기존 RecurrenceSchedule을 사용한다.

수정은 ACTIVE이고 아직 미래이며 실패 정보가 없는 회차만 가능하다. 해지·도래·실패 대기는 409다.
동일 주기에서 firstRunDate를 생략하거나 기존 값을 그대로 보내면 기존 다음 회차를 보존한다.
주기 변경 또는 새 첫 날짜는 가장 가까운 미래 일정/명시한 미래 유효 일정으로 정규화한다.
설정 ID·생성자·원본 ID·생성 시각·과거 회차는 유지한다. 새 제목·총액은 MANUAL 지출 스냅샷 하나로 교체한다.

## 3. 계약과 경계

| API | 입력·권한·결과 |
|---|---|
| POST /api/v2/groups/{groupId}/recurring-settlements | 기존 전환 본문 또는 직접 폼 중 하나. 성공 200 |
| PUT /api/v2/groups/{groupId}/recurring-settlements/{id} | 직접 폼 전체 입력. 생성자만. 성공 200, 상태/경합 409 |
| DELETE /api/v2/groups/{groupId}/recurring-settlements/{id} | 생성자만. 성공 200, 같은 생성자의 반복 삭제도 성공 |
| POST .../{id}/cancel | 기존 해지 계약 유지 |
| GET 목록·상세·회차·홈 | 기존 권한 유지, 삭제 설정은 제외 |

직접 생성·PUT 본문:

```json
{
  "title": "월세",
  "amount": 20001,
  "categoryCode": "HOUSING_TELECOM",
  "cadence": { "frequency": "MONTHLY", "dayOfMonth": 31 },
  "participantUserIds": [101, 102],
  "receivingAccountId": 103
}
```

firstRunDate는 선택값이다. WEEKLY는 dayOfWeek만, MONTHLY는 dayOfMonth만 입력한다.
기존 sourceSettlementId 방식은 최상위 frequency/dayOfWeek/dayOfMonth를 사용한다.
두 방식을 섞거나 필수 폼 필드를 생략하면 400이다. 비생성자의 수정·삭제는 403,
다른 그룹 ID나 삭제된 설정의 조회·수정·해지는 404다.

| 계층 | 역할 |
|---|---|
| Web DTO → Command | CreateRecurringSettlementWebRequest가 배타적 본문 검증. Form DTO가 WriteRecurringSettlementCommand로 변환 |
| Input Port | CreateRecurringSettlementFromFormUseCase / UpdateRecurringSettlementUseCase / DeleteRecurringSettlementUseCase |
| Application | RecurringSettlementManagementService가 CurrentActor, 사용자·계좌·그룹·roster 사실과 트랜잭션 조정 |
| Policy | RecurringSettlementFormPolicy가 참여자·배분, Eligibility/Access Policy가 교차 자격·생성자 권한 판단 |
| Domain | RecurringSettlement가 수정 가능 상태·스냅샷 균형·삭제 전이 소유 |
| Output | 기존 Load/SaveRecurringSettlementPort 재사용. replace는 본문·자식 스냅샷 전체 교체, update는 lifecycle만 갱신 |
| 읽기 경계 | LoadRecurringSettlementPresentationPort가 페이지 ID 전체의 사용자·멤버·account_records·은행 표시값을 일괄 조회 |
| Result → Web | PresentationAssembler가 파일명 대신 avatarVersion을 조립, Web DTO가 기존 권한 검사 endpoint URL로 변환 |

새 Redis Key, 외부 API, 환경변수, Infra Bean/Policy wiring은 없다.

## 4. 동시성·일관성

사용자 ID 정렬 → 계좌 ID 정렬 → 설정 → 그룹/roster 순서를 유지한다. 수정은 기존/새 참여자와
계좌의 합집합을 먼저 잠근 뒤 설정을 잠그고 최초 snapshot과 비교한다. 다른 요청이 바꿨으면 409다.
Worker는 사용자·계좌 잠금 후 설정의 참여자·계좌 snapshot을 재검증한다. 달라졌으면 발행 전에
RecurringSettlementSnapshotChangedException으로 중단하며 기존 transient 분류·재시도 예산을 사용한다.
일정 자체가 바뀐 경우 기존 scheduledFor 일치 검증도 유지한다.

설정·참여자·지출 읽기는 한 SQL statement snapshot으로 구성한다. FOR UPDATE는 먼저 부모를
잠근 뒤 새 statement에서 부모·자식을 읽어 잠금 대기 중 바뀐 자식을 섞지 않는다.
상세·목록의 여러 표시 쿼리는 REPEATABLE_READ로 같은 snapshot을 사용한다.
삭제와 Worker는 설정 잠금으로 직렬화한다. 이미 발행한 정산은 보존하고 삭제 이후 신규 발행은 없다.

## 5. 응답·호환성

상세의 기존 scalar 필드와 participants의 userId/amount/displayOrder는 유지한다.
상세·그룹 목록에 participants[].name/profileImageUrl, category {code,name,icon},
receivingAccount {id,bankName,maskedNumber}를 제공한다. 비활성/없는 프로필은 null이고
마스킹 계좌는 account_records에서 읽어 계좌 삭제 뒤에도 보존한다. 암호문·계좌 원문은 반환하지 않는다.
표시 정보는 설정 수와 무관하게 참여자 1회·계좌 1회 batch 조회로 읽는다.
홈은 기존 계좌 정보 제외 규칙을 유지하며 표시 정보를 추가 조회하지 않는다.

시각은 기존 계약인 offset 없는 UTC LocalDateTime이다. 예: 2026-10-31T00:00:00.
timezone=Asia/Seoul, requestTime=09:00은 일정 해석용이다. 이슈의 ISO offset 제안은
기존 앱이 Z를 붙이는 동작과 기존 서버 계약을 고려해 이번에 변경하지 않는다.

## 6. 커밋 경계

1. feat(settlement): 정기 정산 폼 생성과 수정 삭제 API 구현 — 실제 UseCase·Domain·Policy·저장·Web·Worker 및 회귀 검증.
2. feat(settlement): 정기 정산 편집용 표시 정보 응답 확장 — 표시 읽기·응답·인증 HTTP·동시성 회귀 검증.
3. docs(settlement): 정기 정산 관리 API 계약과 검증 결과 정리 — 완료 문서·호환성·검증 한계.

각 단계에서 compileJava/compileTestJava를 확인한다. 의미 없는 Port-only 중간 상태는 없다.

## 7. 검증

격리 PostgreSQL 18.3·Redis, Java 21을 사용한다. HTTP 테스트의 별도 RECURRING_TEST_REDIS_HOST/PORT도
전용 Redis로 지정한다. .env를 읽거나 공용 DB를 사용하지 않는다.

```bash
./gradlew compileJava compileTestJava verifyMigrationVersions
bash scripts/test.sh test --tests '*RecurringSettlement*' --tests '*RecurrenceSchedule*' --tests '*DutchPayAllocationPolicy*'
bash scripts/test.sh clean test
```

최신 develop 통합 후 전체 Java **2,546개 / 297 suites**·JavaScript **35개**가
실패·오류·skip 0으로 통과했다. compileJava, compileTestJava, verifyMigrationVersions도 성공했다.
RECURRING_TEST_DB_URL과 NETTING_HTTP_PERF_QA=true를 지정하고 Docker의 PostgreSQL/Kafka
Testcontainers까지 포함했다. 실제 JWT HTTP 테스트도 전용 Redis를 사용했다.
문서의 상대 링크와 git diff --check를 확인했다.

검증 시나리오:

- 직접 등록에 임시 정산 없음, 나머지 원의 생성자 부담, 잘못된 참여자·계좌·본문 거절.
- 직접 등록→발행→수정→다음 회차 발행→삭제 후 두 정산의 원본 금액·이력 보존.
- cadence 변경, 원래 firstRunDate round-trip, 생성자·그룹 권한, 해지/도래/실패 수정 충돌.
- 삭제 재호출, 삭제 cursor 차단, 삭제된 원본 replay 차단, 목록·홈·상세·회차 제외.
- Worker/삭제 경합, 두 수정의 경합, Worker의 stale snapshot 차단 및 transient 분류.
- 실제 JWT·Redis 인증 POST/PUT/DELETE·권한·응답과 생성된 OpenAPI 경로/필드 확인.
- 이름·프로필 URL·카테고리·마스킹 계좌, 기존 UTC 문자열, 홈의 계좌 정보 제외 확인.

미검증: 실제 모바일·은행·푸시·운영 배포. 원격 CI 결과는 로컬 검증과 별도로 기록한다.

## 8. 진행·결정 기록

| 날짜 | 결정/진행 | 근거 |
|---|---|---|
| 2026-10-20 | 선행 SQL만 게시 후 종료 | 저장소 migration gate에 따라 PR #601 게시 |
| 2026-10-20 | V62 병합 확인 후 API 후속 작업 | 사용자 후속 요청, 추가 migration 불필요 |
| 2026-10-20 | 직접 등록과 삭제 전이·mutable Worker 검증 연결 | 이력 유지·다음 회차 반영·과거 snapshot 발행 방지 |
| 2026-10-20 | 표시 정보 batch 조회 및 기존 UTC 문자열 유지 | 편집 화면 호출 단순화와 기존 앱 호환 |

## 9. 완료 기록

- 기능 구현과 최신 develop 통합 전체 회귀 검증 완료.
- 배포·실제 모바일·외부 금융 서비스는 후속 확인 필요.
