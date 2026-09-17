# Feature Plan: iOS APNs dev/prod 푸시 발송

- Status: In progress
- Last updated: 2026-09-17
- Related issue: #401

## 1. Purpose

### Problem

iOS 앱은 `getDevicePushTokenAsync()`의 APNs 토큰을 기존 `firebaseInstallationId` 필드로 등록한다. 서버는 이 값을 FCM registration token으로 가정하여 `FirebaseMessaging.send`에 전달하므로 iOS 원격 푸시가 실패한다.

### Success criteria

- [x] iOS APNs 토큰은 `com.teamhowmuch.app` topic으로 발송되며 `dev`는 Sandbox, `prod`는 Production 엔드포인트를 사용한다 (모의 HTTP 검증).
- [x] 기존 Android FCM 토큰은 Firebase 발송을 유지한다 (단위 테스트).
- [x] APNs 실패 시 HTTP status와 `reason`을 토큰·본문·인증 정보 없이 확인할 수 있다 (코드·모의 응답 검증).
- [x] APNs 자격 증명이 없거나 발송이 거절되면 성공으로 기록하지 않는다 (코드 검증).
- [ ] 실제 APNs 응답과 기기 수신 확인.

### Out of scope

- iOS 클라이언트 변경, 한 서버에서 dev/prod 토큰 동시 라우팅, DB 스키마 변경, 실제 기기 수신 보장.

## 2. Domain analysis and scenarios

- 기존 정산 알림 Outbox와 `SettlementPushSenderPort`를 재사용한다.
- 등록 API의 `firebaseInstallationId` 필드와 `users.fcm_token` 저장 공간을 당장 유지한다. 저장된 값은 플랫폼별 네이티브 푸시 토큰이다.
- 정상: Outbox가 수신자 토큰을 읽고, APNs 형식이면 서버의 `APNS_ENVIRONMENT`에 따라 전송하고, 그 외 기존 FCM 경로를 사용한다.
- 실패: APNs가 무효 토큰을 거절하면 `INVALID_TARGET`, 기타 오류는 기존 재시도 경로로 보낸다.
- 경계: APNs 토큰 크기를 64자로 고정하지 않는다. 이행 기간에는 16진수 형식으로 구분하며, 토큰 형식이 겹칠 경우 명시적 플랫폼 필드가 필요하다.

## 3. Contracts and integrations

- Web API: 기존 `PUT /api/v2/user/me/push-target` 유지. 새 필드 없음.
- Output Port: 기존 `SettlementPushSenderPort` 유지.
- Flyway migration required: No. 기존 `VARCHAR(255)`에 토큰 문자열 그대로 저장한다.
- APNs: HTTP/2 `POST /3/device/{token}`; `dev`는 `api.sandbox.push.apple.com`, `prod`는 `api.push.apple.com`; ES256 provider JWT; `apns-topic=com.teamhowmuch.app`; `apns-push-type=alert`.
- 환경설정: `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY_PATH`, `APNS_ENVIRONMENT=dev|prod`. `prod` 프로필 기본값은 `prod`, 그 외 프로필 기본값은 `dev`이다. 잘못된 값은 시작 시 거절한다. 비밀 값은 저장소에 두지 않는다.

## 4. Design and impact

- `FirebaseSettlementPushSenderAdapter` 진입점에서 토큰 형식을 판별하고 APNs 발송기로 위임한다. 기존 FCM payload는 변경하지 않는다.
- APNs 발송기는 표준 Java HTTP/2 클라이언트와 기존 JJWT 의존성으로 요청한다.
- 기존 `SendResult`와 Outbox 재시도 정책을 재사용한다. APNs status/reason만 비식별 로그에 남긴다.
- `ponytail:` 형식 판별은 현재 앱 계약을 위한 간소화다. 플랫폼별 토큰 형식 충돌이 관측되면 API·저장소에 명시적 platform을 추가한다.

## 5. Implementation and verification

- [x] APNs 설정과 발송기
- [x] Firebase Adapter 라우팅과 실패 변환
- [x] 최소 단위 테스트: iOS 라우팅·APNs reason·Android 유지
- [x] `./gradlew compileJava`, 집중 테스트, `git diff --check`, `git status --short --untracked-files=all`
- [ ] 실제 APNs 응답은 해당 환경의 배포 키와 테스트 기기 토큰이 있는 환경에서 확인

2026-09-17: 최신 `origin/develop` 기반 브랜치에서 `./gradlew compileJava test --tests '*FirebaseSettlementPushSenderAdapterTest' --tests '*ApnsPushSenderTest' --tests '*ManageMyPushTargetServiceTest' --tests '*SettlementNotificationOutboxWorkerTest' --tests '*UserControllerTest' --offline` 통과. 실제 서버에는 `.p8` 파일이 아직 없어 APNs 응답 `reason`은 미확인.

2026-09-17: `APNS_ENVIRONMENT` 추가 후 `./gradlew compileJava test --tests '*ApnsPushSenderTest' --tests '*ApnsConfigTest' --tests '*FirebaseSettlementPushSenderAdapterTest' --offline` 통과. 프로필 기본값과 명시적 override를 확인했으며 실제 dev/prod APNs 응답은 미확인.

Expected commit boundaries (커밋 요청 시에만):

1. `fix(notification): iOS APNs 토큰 발송 경로 수정` — 설정·발송 코드·테스트·Feature Plan.
2. `docs(notification): APNs 키 배포 절차 기록` — 서버 키 마운트와 환경변수 안내.
3. `fix(notification): APNs dev/prod 발송 환경 선택 지원` — 배포 환경별 주소 선택과 검증.
