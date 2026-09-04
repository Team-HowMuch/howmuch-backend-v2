# Feature Plan: 회원 알림함과 정산 자동 리마인드

- Status: In progress
- Last updated: 2026-09-05
- Related issue: #269, #270, #272, #273, #274, #275
- Related ADR: N/A

## 1. Purpose

정산 요청·수동 독촉·자동 독촉을 회원별 알림함에 저장하고 클릭한 항목만 읽음 처리한다. 푸시 토큰과 전달 성공 여부에 관계없이 알림함을 유지한다.

### Success criteria

- [ ] 본인 목록·미읽음 개수·최초 읽음 시각 처리
- [ ] 정산 요청·알림·Outbox 원자성 및 중복 방지
- [ ] 수동 독촉은 저장 후 응답, 기존 30분 쿨다운 유지
- [ ] 확정 Share 생성 24시간 이후 미납 자동 독촉 1회
- [ ] 레거시 발송 재시도·복구와 기존 API 권한 유지

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
- [ ] UseCase·Command/Result·필요한 Output Ports
- [ ] Domain 상태와 Application Policy
- [ ] Application Service와 Outbound Adapter
- [ ] Web DTO·Controller·Swagger
- [ ] Infra 자동 독촉 설정·스케줄러
- [ ] 검증 결과와 문서 완료

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

Pending.

## 9. Progress log

- 2026-09-05: V31 병합과 최신 develop 확인. 추가 migration 없이 구현 시작.

## 10. Decision log

- 2026-09-05: 저장 성공과 푸시 성공을 분리하고, 기존 Outbox를 확장해 전달 신뢰성을 재사용한다.

## 11. Completion record

Pending.

### Rollout

마이그레이션은 이미 병합되었다. 구버전 Outbox Worker를 중지한 후 신규 Producer/Worker를 함께 배포한다. 알림 조회·읽음·수동 발송 연결을 확인하고 자동 독촉을 명시적으로 활성화한다. 기존 null notificationId 이벤트는 계속 처리한다. 자동 독촉을 끄더라도 알림함과 기존 푸시 Worker는 유지된다.
