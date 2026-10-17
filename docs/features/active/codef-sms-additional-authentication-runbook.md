# 금융 SMS 추가 인증 — 로컬 실행과 연동

Updated: 2026-10-18. [Feature Plan](codef-sms-additional-authentication.md), [이슈 #576](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/576).

## 적용 순서와 설정

V60 SQL PR을 먼저 병합·적용하고 Backend 기능 PR을 병합한다. 기존 인증서·Kafka 설정을 유지하고 CODEF_SMS_ADDITIONAL_AUTH_ENABLED=true를 켠다. 기본값은 false이며 비활성 상태에서는 SMS API와 worker가 생성되지 않는다. CERTIFICATE_ENABLED=true, KAFKA_ENABLED=true 및 기존 인증서 wrapping/confirmation key, CODEF connected ID key, ACCOUNT_CREDENTIAL_LOCAL_KEY가 필요하다. 환경에 따라 기존 CERTIFICATE_LOCAL_WRAPPING_KEY 또는 KMS key/접근 권한, CERTIFICATE_CONFIRMATION_KEY, CODEF_CONNECTED_ID_ENCRYPTION_KEY와 JWT 설정을 유지한다. 암호화 키를 임의로 재생성하지 않는다. 실제 푸시에는 Firebase 설정·서비스 계정과 유효한 사용자 기기 토큰이 별도로 필요하다. 운영 키를 로컬 Mock에 복사하지 않는다.

인수인계 당시 실제 CODEF 환경(현재 실행 상태 보장은 아님):
- API: http://127.0.0.1:18081
- readiness: http://127.0.0.1:18082/actuator/health/readiness
- PostgreSQL: 127.0.0.1:15432, 앱 DB howmuch_sms_real
- Redis: 127.0.0.1:16379
- Kafka: 127.0.0.1:9092

이전 28457/26379 주소는 아래 별도 disposable 검증 환경에서만 사용한다. 기존 사용자 앱 DB로 테스트를 실행하지 않는다. 서비스 중복 기동을 피하고, 검증용 API 로그인으로 휴대폰 세션을 교체하지 않는다.

## 클라이언트 계약

- 로그인/잠금 해제 후 GET /api/v2/finance-authentications로 활성 인증을 찾는다.
- 인증서 pairing GET의 additionalAuthentication 또는 계좌 API 오류 details.challengeId로 같은 모달을 연다.
- WAITING_INPUT: 번호 입력, SUBMITTED/PROCESSING: 확인 중, SUCCEEDED: 완료. FAILED/EXPIRED/CANCELLED/UNKNOWN은 구분해 안내한다.
- 날짜는 UTC LocalDateTime 응답이다. Z가 없을 때 UTC로 해석한다.
- 중앙 모달에는 남은 시간·닫기 X·floating 배너가 없다. 배경/뒤로가기로 닫히지 않으며 명시적 인증 취소만 DELETE를 호출한다. 성공 확인·취소 후에는 모달만 닫고 기존 화면을 유지한다.
- 알림 FINANCE_AUTH_REQUIRED의 action.type=FINANCE_AUTHENTICATION, action.targetId=challengeId로 재진입한다. 완료·취소·만료된 인증은 활성 CTA를 제거하고, 옛 알림은 retryChallengeId successor를 따른다.
- POST /{id}/sms에는 매 제출마다 UUID submissionId를 만든다. 통신 실패 재전송은 같은 UUID를 사용한다. 번호는 선행 0을 보존하는 숫자 문자열 4~8자리로 전달한다. 실제 CODEF 입력은 최상위 smsAuthNo이며 reqSMSAuthNo는 출력 수신처 정보다. 재입력은 유효한 retryChallengeId의 다음 generation에서만 수행한다. UNKNOWN·임의 CF 오류를 재입력으로 바꾸지 않는다.
- 인증서 응답의 소유 pairingId/sessionId로 앱 재실행·알림 재진입 후에도 기존 최종 승인 흐름을 복구한다. 모달 확인 자체로 화면 이동을 강제하지 않는다. 인증서 성공 후에도 기존 최종 Yes/No를 유지한다. ID/PW 성공은 bankConnection.connectionId로 후속 계좌 조회를 수행하며 최초 로그인 POST를 반복하지 않는다.
- 계좌 목록 성공의 bankConnection.candidates는 검증된 결과다. 거래 성공 후 기존 GET은 저장된 거래/월 요약을 읽는다.
- 타인의 인증 GET/제출/취소는 404. 앱 로그아웃 시 대기 상태와 비동기 응답을 폐기한다.

## Mock 설정

관리 웹의 ‘SMS 추가 인증 테스트’에서 사용자·금융사·작업·번호·횟수·유효 시간을 설정한다. POST /admin/api/sms-settings도 제공한다. 인증서와 ID/PW는 BANK_CONNECT, 목록은 ACCOUNT_LIST, 거래는 TRANSACTION_LIST다.

Mock 번호 오류 CF-99998는 합성 오류다. 실제 은행의 오류코드 의미로 사용하지 않는다. 원 요청 해시·사용자·금융사·작업·시각과 일치하는 single-use continuation만 통과한다. 인증서/비밀번호/계좌 소유권 검증은 SMS 전에도 수행한다.

## 게시 전 검증

[공통 테스트 안내](../../testing.md)의 scripts/test.sh를 사용한다. 이 스크립트는 .env를 읽지 않고 synthetic 설정을 주입한다. CertificateSmsAuthenticationIntegrationTest는 기본적으로 자체 PostgreSQL 컨테이너를 생성하며, 아래 주소의 전용 테스트 DB도 허용한다.

```bash
HOWMUCH_SMS_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:28457/sms_continuation_test \
DB_URL=jdbc:postgresql://127.0.0.1:28457/sms_continuation_test \
DB_USERNAME=postgres DB_PASSWORD=unused REDIS_HOST=127.0.0.1 REDIS_PORT=26379 \
bash scripts/test.sh test \
  --tests '*CertificateSmsAuthenticationIntegrationTest' \
  --tests '*CodefSmsContinuationAdapterTest' --tests '*AdditionalAuthenticationWiringTest' \
  --tests '*CodefBankTransactionAdapterTest' --tests '*CodefBankConnectionAdapterTest' \
  --tests '*BankAccountServiceTest' --tests '*AccountQueryServiceTest' \
  --tests '*BankConnectionRegistrationServiceTest' --tests '*Notification*Test'
```

위 postgres/unused 값은 trust 인증의 disposable PostgreSQL 컨테이너 전용이다. 다른 테스트 환경에서는 그 DB의 인증 설정에 맞춘다.

2026-10-18 최신 develop 통합본에서 Java 154개/19 suites, 관리자 JavaScript 35개, Java/테스트 컴파일과 Flyway migration 31개 검증이 통과했다. 이번 게시에서는 실제 CODEF·휴대폰·FCM과 전체 Java suite를 다시 검증하지 않았다.

## 운영 확인과 롤백

배포 후 readiness, 인증 소유권, SMS 접수·worker 상태 전이와 알림 outbox를 확인한다. 안전한 상태/오류코드만 조사하며 인증정보·금융사 응답 원문은 출력하지 않는다. 기능 flag 활성화만으로 배포·실기기 검증 완료로 보고하지 않는다.

응답 유실로 UNKNOWN이 된 작업을 자동 재호출하거나 DB 상태·잠금을 강제로 지워 복구하지 않는다. 롤백 전 WAITING_AUTH 작업과 활성 challenge를 안전하게 종료하고 처리 중 요청을 확인한다. 구형 Backend가 추가 인증 대기를 처리할 수 있다고 가정하지 않으며 적용된 V57/V60 migration을 삭제·수정하지 않는다.
