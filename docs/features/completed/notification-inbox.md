# Feature Plan: 회원 알림함과 정산 자동 리마인드

> 2026-09-16: 이 문서의 24시간 후 1회 자동 독촉 일정은 [12시간 반복과 심야 이월 정책](settlement-recurring-automatic-reminder.md)으로 대체되었다. 알림함·Outbox 설계 기록은 그대로 유효하다.

- Status: Implemented
- Last updated: 2026-09-05
- Related issue: #269, #270, #272, #273, #274, #275
- Related ADR: N/A

## 1. Purpose

정산 요청·수동 독촉·자동 독촉을 회원별 알림함에 저장하고 클릭한 항목만 읽음 처리한다. 푸시 토큰과 전달 성공 여부에 관계없이 알림함을 유지한다.

### Success criteria

- [x] 본인 목록·미읽음 개수·최초 읽음 시각 처리
- [x] 정산 요청·알림·Outbox 원자성 및 중복 방지
- [x] 수동 독촉은 저장 후 응답, 기존 30분 쿨다운 유지
- [x] 확정 Share 생성 24시간 이후 미납 자동 독촉 1회
- [x] 레거시 발송 재시도·복구와 기존 API 권한 유지

### Out of scope

과거 알림 백필, 삭제·전체 읽음 API, 실시간 연결, 자동 독촉 반복, 실제 FCM 발송 테스트.

## 2. Domain analysis

notification은 회원이 읽는 알림 내용과 상태를 소유한다. settlement는 생성 시점·수신자·미납 조건을 결정한다. Outbox는 전달 의도와 재시도를 소유한다. user의 활성 상태와 연결 참여자만 대상이며 생성자는 제외한다.

## 3. Scenarios

정산 요청 트랜잭션에서 활성 연결 회원에게 요청 알림과 Outbox를 함께 저장한다. 수동 독촉은 별도 트랜잭션으로 잠근 정산과 Share·입금을 재확인하고 Redis 예약을 확보한 후 저장한다. 롤백이면 소유 예약만 해제한다. 토큰 부재·FCM 실패는 알림을 지우지 않는다.

자동 독촉은 Share.createdAt+24시간이 지난 REQUESTED/AWAITING_DEPOSIT/SUSPENDED 정산의 확정 Share 중 확인된 입금액이 0인 활성 연결 회원을 대상으로 한다. 신규 정산 요청 알림이 있는 수신자만 대상으로 하여 과거 미납자를 일괄 독촉하지 않는다. 생성 직전 잠금·상태 재검증 및 수동과 같은 쿨다운을 사용한다. 사용자별 AUTO_REMINDER:<shareId> 키로 한 번만 저장한다. 읽음은 자동 독촉 취소 조건이 아니다.

## 4. Contracts

- GET /api/v2/notifications?cursor=...&size=20: items, nextCursor, hasNext; 기본20 최대50, createdAt/id 내림차순
- GET /api/v2/notifications/unread-count: count
- PATCH /api/v2/notifications/{notificationId}/read: id, readAt; 반복 호출은 최초 시각 유지
- 인증: CurrentActorPort, 사용자 ID 외부 입력 없음. 타인/미존재 알림 동일404, 잘못된 cursor/size 400.
- 항목: id, type, allocationType, title, message, createdAt, readAt, settlementId, action(type/label/enabled)
- action은 SETTLEMENT_DETAIL. 정산 참조가 null이면 enabled=false. 완료된 정산 상세 이동은 유지한다.
- 조회로 읽음·납부 상태를 변경하지 않는다. 클라이언트가 UTC 시각으로 상대 시간을 표시한다.
- 입력 Command/출력 Result는 plain record. Web DTO/Docs와 분리한다.

### Persistence and integrations

V31이 PR #276으로 병합되었다. 추가 Flyway migration: No. user_notifications 및 기존 Outbox.notification_id를 매핑한다. 조회·저장·읽음 Output Port를 구분한다. native INSERT ON CONFLICT로 원자적 중복 방지를 처리하며 JPA Entity/Mapper/Repository는 adapter.out.persistence.notification 아래에 둔다.

## 5. Design decisions

- 메시지·요청자 이름은 생성 당시 스냅샷. 일반/품목별은 DUTCH_PAY/ITEM_ALLOCATION으로 구분한다.
- 수동 sentCount는 FCM 수락 수가 아닌 알림 생성 수. 푸시 실패로 쿨다운 해제하지 않는다.
- 신규 푸시는 notificationId/type/settlementId를 전달하고 공개 링크에 의존하지 않는다. 레거시 null notificationId 이벤트만 기존 공개 링크 검증을 유지한다.
- Outbox의 notificationId는 모든 상태 전이에 보존한다. 현재 등록된 토큰으로 발송하며 토큰이 없으면 기존 TARGET_UNAVAILABLE로 종료한다.
- 자동 스케줄러 기본 비활성, 주기1분, batch50. 설정은 Infra, 24시간/미납 판단은 Application Policy.
- Clock은 기존 UTC Clock Bean을 주입한다.

## 6. Impact surface

notification Domain/Application/Web/Persistence 신규, 정산 요청·독촉·Outbox Domain/Mapper/Entity/Worker·FCM Adapter 변경, 자동 독촉 Application Policy/후보 조회 Adapter/Infra Scheduler 추가. Security는 기존 /api/** 인증 경계를 재사용한다. V31 SQL 수정 없음.

## 7. Implementation plan

- [x] Migration-only PR #276 병합
- [x] UseCase·Command/Result·필요한 Output Ports
- [x] Domain 상태와 Application Policy
- [x] Application Service와 Outbound Adapter
- [x] Web DTO·Controller·Swagger
- [x] Infra 자동 독촉 설정·스케줄러
- [x] 검증 결과와 문서 완료

### Expected commit boundaries

브랜치 feat/notification-inbox, develop 대상 PR 한 개. GitHub 조작은 gh CLI만 사용한다.

```text
docs(notification): 알림함 구현 계약과 검증 계획 기록
feat(notification): 내 알림 조회와 읽음 처리 구현
feat(notification): 정산 알림 저장과 비동기 푸시 연결
feat(settlement): 확정 금액 미납자 자동 독촉 구현
test(notification): 권한과 알림 생성·발송 회귀 검증
```

## 8. Verification

컴파일, notification/정산 요청·독촉/Outbox/FCM 관련 단위 테스트, 격리 PostgreSQL·Redis 통합 테스트, 실제 프로필 기동과 OpenAPI/인증 경계, git diff --check 및 링크 검증. 상태 경계24시간·쿨다운·동시성·롤백·레거시 처리와 API 타인 접근을 검증한다. 실패·미실행 사항은 명확히 기록한다.

### Results

- `./gradlew compileJava`: 통과.
- 격리 PostgreSQL `howmuch_notification_impl`·로컬 Redis에서 관련 7개 테스트 클래스 **51개 통과**. 이 중 DB/Web 통합 테스트 15개는 실제 저장·동시성·롤백·인증·OpenAPI를 검증했다.
- 전체 `./gradlew test`: **689개 중 686개 통과, 3개 실패**. 전체 성공으로 보고하지 않는다.
- 실패 3개는 기준 `origin/develop`(5da0163) 격리 복사본에서도 동일하게 재현했다: `SettlementAmountDeserializationTest` 2개는 기존 `RenameSettlementParticipantUseCase` mock 누락, `SettlementListPersistenceIntegrationTest.hidesDraftSettlement`는 기존 0원 초안 fixture가 현재 Domain 검증에 거부됨. 이번 범위 밖이므로 수정하지 않았다.
- 테스트 실행 시 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, 테스트용 `DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused`, `SETTLEMENT_NOTIFICATION_OUTBOX_ENABLED=false`, `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3`을 사용했다. 초기 전체 실행의 SMS 설정 부재·DB 연결 한도 오류는 테스트 환경을 보완한 최종 실행에서 해소했다.
- `local` 프로필 실제 `bootRun`(18085) 성공. Flyway V31 및 Hibernate validate 성공, `/v3/api-docs`의 3개 API와 응답 필드 확인, 실제 HTTP에서 모두 미인증 401 확인. 검증 후 해당 서버 종료.
- `git diff --check` 및 변경 문서 상대 링크 검증 통과.
- 실제 모바일 화면·실제 Firebase 발송·배포 환경 전환은 미실행. Firebase payload는 mock SDK 및 Worker 테스트로 검증했다.

### Verified scenarios

동일 생성 시각 커서 경계, 본인만 조회·읽음 및 타인/미존재 동일404, 동시 읽음 최초 시각 유지, 정산 삭제 후 내용 보존, 요청 중복 저장 시 Outbox 한 건, 토큰 없는 알림 보존, Outbox 저장 실패 시 DB·Redis 예약 롤백, 원래 상태 변경과 알림 원자 롤백, 24시간 직전/정각/이후, 과거 요청 없는 정산·미제출·비활성·완료·입금 대상 제외, 후보 조회 뒤 입금 재확인, 수동/자동 동시 실행 및 쿨다운 후 재평가, 다중 실행·재시작 후 자동 독촉 1회, 신규/레거시 payload·실패 재시도·오래된 선점 복구.

## 9. Progress log

- 2026-09-05: V31 병합과 최신 develop 확인. 추가 migration 없이 구현 시작.
- 2026-09-05: API·알림 저장·Outbox·수동 독촉·자동 독촉을 계획한 기능 커밋으로 분리했다.
- 2026-09-05: 후보 조회는 기존 Share 저장/조회와 같은 JPA 시각 바인딩으로 통일했다. 로컬 JVM 시간대에서도 24시간 기준이 일치함을 통합 테스트했다.
- 2026-09-05: legacy allocationType=null인 기존 정산의 수동 독촉은 DUTCH_PAY로 표시해 기존 독촉 가능 대상을 유지했다. 신규 요청은 확정된 allocationType을 사용한다.

## 10. Decision log

- 2026-09-05: 저장 성공과 푸시 성공을 분리하고, 기존 Outbox를 확장해 전달 신뢰성을 재사용한다.

## 11. Completion record

구현·관련 검증 완료. 브랜치 `feat/notification-inbox`, PR 대상 `develop`. 마이그레이션 PR #276과 별도이며 추가 SQL 변경은 없다. [클라이언트 계약](notification-inbox-client-contract.md)에 화면 연결과 배포 설정을 기록했다. 전체 테스트의 기존 실패 3개와 실제 FCM/모바일 미검증 범위는 PR에도 명시한다.

### Rollout

마이그레이션은 이미 병합되었다. 구버전 Outbox Worker를 중지한 후 신규 Producer/Worker를 함께 배포한다. 알림 조회·읽음·수동 발송 연결을 확인하고 자동 독촉을 명시적으로 활성화한다. 기존 null notificationId 이벤트는 계속 처리한다. 자동 독촉을 끄더라도 알림함과 기존 푸시 Worker는 유지된다.
