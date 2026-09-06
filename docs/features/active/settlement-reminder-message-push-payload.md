# 정산 리마인드 문구와 푸시 배분 유형 수정

- 상태: 구현 예정
- 관련 이슈: [#292](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/292)
- 기준: origin/develop, 2026-09-06

## 목적과 완료 기준
수동·자동 독촉의 디자인 문구를 통일하고 앱이 푸시의 배분 유형으로 참여 화면을 선택할 수 있게 한다.
기존 요청 문구, 발송 조건, 쿨다운, 자동 스케줄, 과거 알림은 유지한다.

## 계약과 설계
- 기존 settlement / notification 경계를 재사용한다. 신규 UseCase, Command/Result, Policy, Output Port, Infra 설정은 N/A다.
- SettlementReminderWriter의 공통 헬퍼가 Share.createdAt부터 기존 Clock의 now까지 Duration.toHours()로 계산한다.
- 문구는 정확히 “정산을 요청받은지 N시간이나 지났어요”이며 1시간 미만은 0시간이다.
- 사용자가 Share 확정 기준을 선택했다. 최초 요청 시각과 다를 수 있으며 실제 기기 수신 시각을 뜻하지 않는다.
- NotificationRecorder의 메시지 스냅샷을 그대로 전송한다. 조회·재시도에서 시간을 재계산하지 않는다.
- SettlementPushSenderPort.NotificationMessage에 SettlementAllocationType allocationType을 추가한다.
- Worker는 UserNotification의 스냅샷을 전달하고 Firebase Adapter는 enum.name()을 data에 넣는다.
- 요청/독촉 × DUTCH_PAY/ITEM_ALLOCATION에 적용한다. 기존 type 매핑과 notificationId 없는 레거시 shareLink 경로는 유지한다.
- HTTP API 변경 없음. Flyway migration: No. DB/Redis 구조 변경 없음.

## 영향과 구현 순서
1. 문구 생성·경계 테스트 수정.
2. 푸시 내부 계약·Worker·Firebase Adapter 및 payload 테스트 수정.
3. 클라이언트 계약 갱신, 관련 테스트·컴파일·문서 링크 검증.
4. 완료 기록 후 completed로 이동하고 develop 대상 PR 생성.

## 예상 커밋 경계
- docs(notification): 리마인드 문구와 푸시 계약 수정 계획 수립
- fix(notification): 수동 자동 리마인드 문구 통일
- fix(notification): 정산 푸시에 배분 유형 전달

각 코드 커밋은 실제 호출부와 테스트를 함께 포함하며 컴파일 가능하게 유지한다.

## 검증 계획
- 고정 시각 기준 0분/59분/60분/25시간과 자동 24시간 직전/정각.
- 요청 시각과 Share 확정 시각이 다른 사례, 기존 메시지 보존.
- 두 알림 유형 × 두 배분 유형의 payload, 저장 본문 전달 및 레거시 링크 회귀.
- 기존 쿨다운·롤백·재시도·동시성 검증.
- PostgreSQL/Redis 사전 확인 후 compileJava 및 관련 테스트, git diff --check, 문서 링크 검사.
- 실제 Firebase 기기 수신과 앱 이동은 환경이 없으면 미검증으로 보고.

## 진행 및 결과
- 2026-09-06: 이슈와 PR #277, 현재 코드 확인. 사용자 결정과 PR 생성 승인 반영.
- 구현 및 검증: 대기.
