# 알림함 클라이언트 계약

관련 작업: [#269](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/269), [구현 계획](notification-inbox.md).

## API

로그인이 필요하며 수신자 ID는 요청하지 않습니다. 모든 시각은 UTC `LocalDateTime`입니다. JSON에 offset이 없으므로 클라이언트에서 UTC로 해석한 뒤 상대 시간으로 표시합니다.

| 요청 | 응답 `data` | 동작 |
|---|---|---|
| `GET /api/v2/notifications?size=20&cursor=...` | `items`, `nextCursor`, `hasNext` | 기본20, 최대50. 최신 `createdAt DESC, id DESC` |
| `GET /api/v2/notifications/unread-count` | `{ "count": 3 }` | 내 미읽음 개수 |
| `PATCH /api/v2/notifications/{notificationId}/read` | `id`, `readAt` | 최초 읽음 시각 유지 |

목록을 열기만 해서는 읽음 처리하지 않습니다. 항목 클릭 시 해당 ID로 PATCH를 호출합니다. `readAt=null`이면 파란 배경으로 표현합니다. 타인 ID와 존재하지 않는 ID는 모두 `404 NOTIFICATION_NOT_FOUND`입니다. 잘못된 커서·size는 400입니다.

목록 항목 예시:

```json
{
  "id": "39560012-3c7c-43d9-bd10-1935bbb19002",
  "type": "SETTLEMENT_REQUEST",
  "allocationType": "ITEM_ALLOCATION",
  "title": "정산 요청",
  "message": "현승민님이 영수증 정산을 요청했어요",
  "createdAt": "2026-09-05T03:00:00",
  "readAt": null,
  "settlementId": "175b7c37-7756-47bb-8a92-84d8e9b4a261",
  "action": {
    "type": "SETTLEMENT_DETAIL",
    "label": "영수증 정산하러 가기",
    "enabled": true
  }
}
```

커서는 응답받은 `nextCursor`를 그대로 URL 인코딩해 전달합니다. 마지막 페이지는 `hasNext=false`, `nextCursor=null`입니다. 서버가 저장한 `title`, `message`, `action.label`을 표시합니다. 이름 변경으로 과거 메시지는 바뀌지 않습니다.

## 이동과 푸시

- `action.type=SETTLEMENT_DETAIL`: 인증된 정산 상세로 이동하고 현재 상태에 따라 참여·송금 화면을 연결합니다.
- 정산이 삭제되면 본문은 유지하지만 `settlementId=null`, `action.enabled=false`입니다. 완료된 정산은 상세 조회할 수 있습니다.
- `type`: 요청은 `SETTLEMENT_REQUEST`, 수동·자동 독촉은 `SETTLEMENT_REMINDER`입니다.
- 새 푸시 데이터에는 `notificationId`, `settlementId`, `type`, `allocationType`을 제공합니다. `allocationType`은 알림에 저장된 `DUTCH_PAY` 또는 `ITEM_ALLOCATION`이며 요청·수동·자동 독촉에 모두 포함됩니다. 기존 푸시 타입을 유지해 요청은 `SETTLEMENT_PARTICIPATION`, 독촉은 `SETTLEMENT_REMINDER`입니다. 새 푸시는 공개 `shareLink` 없이 정산 상세로 이동합니다.
- 배포 전 레거시 Outbox 이벤트는 기존 `shareLink` 경로로 발송될 수 있습니다. 레거시 이벤트에는 `notificationId`와 `allocationType`이 없습니다.
- 푸시 토큰이 없거나 발송에 실패해도 알림함에 남습니다. 읽음 처리·목록 조회는 참여·납부 상태를 바꾸지 않습니다.

## 수동·자동 독촉

수동·자동 독촉 메시지는 `정산을 요청받은지 N시간이나 지났어요`입니다. N은 Share 확정 시각(`share.createdAt()`)부터 알림 생성 시각까지 UTC로 계산한 경과 시간이며 시간 미만은 버립니다(59분 → 0시간, 90분 → 1시간). 품목별 정산의 최초 참여 요청 시각이나 실제 기기 수신 시각을 뜻하지 않습니다.

메시지는 생성 시 저장되는 스냅샷입니다. 조회·푸시 발송·재시도에서 경과 시간을 다시 계산하지 않으며 이미 저장된 알림 문구는 소급 변경하지 않습니다. 푸시 본문도 같은 저장 메시지를 사용합니다.

기존 수동 API 주소와 응답 필드를 유지합니다. `sentCount`는 **알림함에 생성한 알림 수**입니다. 실제 FCM 수락 건수가 아니며, API는 푸시 완료를 기다리지 않습니다. 토큰 부재나 FCM 장애를 수동 독촉의 422/503으로 반환하지 않습니다. 기존 생성자 권한·입금 전 대상 조건과 참여자별 30분 제한은 유지합니다.

자동 독촉은 Share가 확정된 후 24시간이 지나고 확인된 입금액이 0일 때 한 번만 생성합니다. 품목 선택 미제출자·생성자·비활성 회원·완료 정산은 제외합니다. 신규 요청 알림이 있는 수신자부터 적용하며, 최근 수동 독촉으로 30분 제한 중이면 다음 주기에 재평가합니다. 읽었다는 이유로 취소하지 않습니다.

## 운영 설정

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `SETTLEMENT_AUTOMATIC_REMINDER_ENABLED` | `false` | 자동 독촉 스케줄러 활성화 |
| `SETTLEMENT_AUTOMATIC_REMINDER_POLL_INTERVAL` | `1m` | 배치 조회 주기 |
| `SETTLEMENT_AUTOMATIC_REMINDER_BATCH_SIZE` | `50` | 주기당 후보 수, 1..1000 |

설정 변경은 재시작 시 적용합니다. 구버전 Outbox Worker를 중지한 뒤 신규 Producer·Worker를 함께 배포합니다. 알림함·읽음·수동 독촉을 확인한 후 자동 독촉을 활성화합니다. 자동 독촉을 비활성화해도 요청·수동 독촉 알림과 기존 Outbox Worker는 계속 동작합니다.
