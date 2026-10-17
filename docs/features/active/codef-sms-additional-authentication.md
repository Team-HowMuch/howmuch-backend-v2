# Feature Plan: CODEF SMS 추가 인증과 모바일 재개

- Status: 로컬 서버·PC·Android 통합 구현 및 검증
- Updated: 2026-10-18
- 기준: develop `0c717187`, 기존 V57 challenge, 인증서 Kafka, 계좌 등록·조회와 알림 outbox

## 목적과 범위

인증서 등록, ID/PW 연결, 계좌 목록, 거래 내역에서 SMS가 필요하면 같은 사용자의 모바일 공통 모달로 이어간다. 중앙 모달에서 인증번호를 입력하거나 명시적으로 인증을 취소한다. 앱 재실행·알림 진입 때 소유 인증을 다시 조회한다. 로그인 정보/송금·최종 귀속 정책을 바꾸지 않는다.

ARS·OTP·보안문자와 임의 인증 재발송은 제외한다. 유효한 SMS continuation이 반환되는 재입력만 다음 generation으로 이어간다. CODEF가 반환한 SMS method, continue2Way 및 재개 식별값이 모두 있는 계약만 처리한다. SMS 입력 필드는 명세의 `smsAuthNo`를 사용하고 출력 휴대폰 정보로 결정하지 않는다. 오류코드만 보고 SMS 데이터를 만들어내지 않는다.

## 사용자 흐름

1. PC는 최초 QR로 모바일 사용자와 연결한다. 금융사 인증 중 SMS가 필요하면 PC는 휴대폰 인증 대기를 표시한다.
2. 계좌 API는 CODEF_ADDITIONAL_AUTH_REQUIRED의 details.challengeId를 반환한다. 인증서는 기존 pairing 응답의 optional additionalAuthentication도 제공한다.
3. 모바일은 로그인과 앱 잠금 해제 후 중앙 모달을 자동 표시한다. 남은 시간·닫기 X·floating 배너는 없고 배경·뒤로가기로 닫히지 않는다. 인증 취소 버튼만 DELETE를 호출하며 성공 확인·취소 후에는 모달만 닫고 원래 화면을 유지한다.
4. 알림 목록에는 challenge UUID를 저장한다. 인증번호, 개인키, 원 요청, twoWayInfo는 알림/푸시/Kafka로 보내지 않는다.
5. SMS는 submissionId로 한 번 접수한다. 같은 ID 재전송은 기존 상태 반환, 다른 ID 덮어쓰기는 409다.
6. 인증서 성공은 기존 결과 Kafka → READY_FOR_CLAIM → 모바일 Yes/No → BIND로 진행한다. SMS만으로 사용자 연결을 확정하지 않는다.
7. ID/PW 성공은 검증된 provider 결과로 연결을 저장한다. 최초 로그인 요청은 재발행하지 않는다.
8. 계좌 목록 결과는 소유 연결에 결합해 암호화 캐시한다. 선택 계좌 등록도 이 검증 결과를 재사용한다. 거래 내역 결과는 기존 거래 mapping/중복 제거/월 요약 저장 경로를 재사용한다.

## 아키텍처와 저장

- Application Command/Result, CurrentActorPort 및 목적별 Output Port를 사용한다. provider JSON과 HTTP DTO는 Adapter에서 처리한다.
- 기존 CertificateSecretProtectionAdapter의 envelope/local wrapping/KMS 구현을 재사용한다. 공유 CertificateEnvelope는 암호문 운반 형식으로만 사용하고 인증서 원본/개인키를 계좌 계약에 넣지 않는다.
- 요청과 결과의 AAD를 구분하며 challenge·사용자·operation UUID·generation을 결합한다.
- V57의 BANK_CONNECT/ACCOUNT_LIST/TRANSACTION_LIST 및 encrypted_result를 재사용한다. 결과는 5분 보관 후 scheduler가 제거한다.
- WAITING_INPUT → SUBMITTED → PROCESSING → SUCCEEDED/FAILED/UNKNOWN. 취소·만료도 지원한다.
- DB SKIP LOCKED와 lease token/generation으로 선점하고 HTTP 시작 사실을 먼저 커밋한다. 시작 후 응답을 잃으면 UNKNOWN으로 종료하고 자동 재호출하지 않는다.
- 원 요청/SMS 암호문은 종료 시 제거한다. 모달의 번호는 메모리에만 두고 제출·닫기·로그아웃 때 제거한다.
- 인증서 최초 inbox는 WAITING_AUTH로 내려 최초 REGISTER 재발행을 막는다. 부모 취소와 활성 사용자 확인을 완료 직전에 다시 검증한다.
- SMS 실행/복구 scheduler는 2개 thread로 분리해 긴 최초 인증서 요청이 추가 인증의 유효 시간을 소진하지 않도록 한다.
- 기존 NotificationRecorder → notification outbox → Kafka delivery를 재사용한다. 새로운 SMS Kafka topic을 추가하지 않는다.

## 2026-10-17 중앙 모달 계약

- Figma `115:6389`의 입력/확인 중/재입력/완료 모달을 따른다. 남은 시간 표시만 제거하며 서버 만료 검증을 유지한다. 배경/뒤로가기는 닫지 않고 명시적 인증 취소로 종료한다.
- V57의 완료 상태, submissionId, provider 실행 기록은 불변으로 유지한다. CODEF가 유효한 SMS continuation을 다시 반환할 때만 이전 challenge를 `AUTH_SMS_RETRY_REQUIRED`로 종료하고 같은 operation의 다음 generation을 생성한다. 사용자 입력 없이 provider를 재호출하지 않는다.
- 재입력은 기존 V57 generation과 암호화 저장 구조를 재사용한다. 금융 인증 알림 제약 확장은 별도 V60 SQL PR로 발행한다. 선택적 `retryChallengeId` 응답으로 후속 인증으로 이동한다.
- UNKNOWN/만료/취소/임의 CF 오류는 재입력으로 변환하지 않고 원 deadline을 연장하지 않는다. 최종 Yes/No와 BIND를 유지한다.
- 검증: 실제 PostgreSQL 제약과 멱등/취소/만료, Adapter 회귀, Mock 오입력→성공, 모바일 typecheck/상태 테스트, PC unit/smoke, 로컬 HTTP 통합. 실금융사/Android 실기기는 별도 보고한다. 과거 로컬 검증 기록이며 이번 발행 검증은 아래에 별도로 기록한다.

## API

- GET /api/v2/finance-authentications: 내 활성 인증 목록
- GET /api/v2/finance-authentications/{challengeId}: 소유 인증 상태와 안전한 완료 결과
- POST /api/v2/finance-authentications/{challengeId}/sms: {submissionId, code}, HTTP 202 후 상태 조회
- DELETE /api/v2/finance-authentications/{challengeId}: 인증 취소. 인증서면 pairing도 취소
- 타인/미존재 404, 만료 후 최초 제출 410, 제출 충돌 409. 만료 상태 GET은 EXPIRED로 표시한다.

## 마이그레이션과 작업 경계

이슈: [#576](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/576).

V57 challenge 스키마는 이미 develop에 있다. 2026-10-18 최신 develop의 V59와 열린 PR #342를 확인했고, 금융 인증 알림 유형·대상 허용은 `V60__add_finance_authentication_notifications.sql`로 분리한다. 기존 정산·그룹 압축·초대·리캡 알림 CHECK 제약을 보존한다. 원래 로컬 V58 파일은 발행하지 않는다.

이번 사용자가 이슈 생성과 SQL/기능 PR 동시 게시를 명시적으로 요청했다. SQL-only gate의 턴 중단 순서보다 이번 요청을 우선하되, SQL PR에는 versioned SQL 하나만 넣는다. 두 PR의 대상은 develop이며 SQL을 먼저 병합한다. 기능 PR에 같은 선행 SQL commit을 포함해 단독 검증 가능하게 하고, SQL 병합 후 기능 PR에서 SQL diff가 사라지는지 확인한다. squash/rebase로 SQL을 병합하면 기능 브랜치를 최신 develop에 재정렬한다.

커밋·브랜치 경계:
1. `feat/576-finance-sms-notification-schema`: `build(notification): 금융 SMS 추가 인증 알림 제약 확장` — V60 단일 SQL.
2. `feat/576-finance-sms-continuation`: 위 SQL commit을 선행한 `feat(external): 금융 SMS 추가 인증 재개와 알림 연동` — Backend Java/config/test 및 이 계획·runbook.

백업과 기존 dirty checkout을 보존하고 별도 checkout에서 SMS 변경만 통합한다. Frontend·Electron·Mock·정산·고정지출 변경은 이 기능 PR에 포함하지 않는다. Backend 병합·dev/prod 배포도 이번 요청 범위 밖이다.

## 검증

### 실제 SMS 요청 필드 수정 (2026-10-17)

- 실제 우리은행 재개 요청이 `CF-12102`를 반환했다. 출력부 `extraInfo.reqSMSAuthNo`(SMS 수신 휴대폰 번호)를 입력 인증번호 필드로 잘못 재사용한 것을 확인했다.
- [공식 은행 명세](https://developer.codef.io/products/bank/overview)의 출력 휴대폰 번호와 입력 `smsAuthNo`를 분리한다. [공통 추가인증 명세](https://developer.codef.io/common-guide/add-auth)의 SMS method는 `smsAuthNo`다.
- 인증번호는 원 요청의 최상위 `smsAuthNo`로 보내고 `is2Way` 및 원본 `twoWayInfo`를 유지한다. 인증서 등록과 계좌·거래내역 재개가 같은 본문 조립 함수를 사용한다.
- 기존 암호화 context에 저장된 `field=reqSMSAuthNo`도 읽을 수 있게 유지하되 실제 전송 필드는 `smsAuthNo`로 고정한다. DB 구조 변경과 Flyway migration은 필요하지 않다.
- 회귀 검증: 공식 응답 형태의 `method=smsAuthNo` / `extraInfo.reqSMSAuthNo`에서 최상위 입력 필드·원본 파라미터·선행 0을 보존하고 잘못된 출력 필드로 인증번호를 보내지 않는지 확인한다. 휴대폰 정보가 없는 응답도 SMS method와 재개 식별값이 있으면 처리한다.
- 로컬 수정과 테스트 후 기존 실제 CODEF 환경으로 서버를 재기동한다. 이는 당시 로컬 수정 기록이다. 현재 발행 승인과 검증은 2026-10-18 항목을 따른다.
- 검증 결과: 공식 응답 fixture로 수정 전 9개 중 4개 실패를 재현했다. 수정 후 SMS adapter·거래내역 adapter·계좌연결 adapter 총 32개 테스트 및 compileJava가 통과했다. 2026-10-17 04:54:30 KST에 실제 CODEF 개발 환경을 유지한 18081 서버 재기동을 확인했다. 실제 금융사 SMS 성공은 아직 재검증하지 않았다.

- 과거 로컬 PostgreSQL V1–V58: 인증서 및 범용 challenge 상태·소유권·암호화·멱등·만료 테스트
- 서버 계좌 서비스/파서/활성·비활성 Bean wiring 회귀 테스트
- Mock: 기존 인증서·ID/PW·계좌 검증 + SMS 성공·오입력·만료·원 요청 변조·단일 사용·재시작
- 실제 JWT HTTP + 로컬 Kafka: ID/PW, 계좌 목록, 거래 내역, 인증서 REGISTER/SMS/Yes/BIND
- 모바일 typecheck 및 npx expo run:android --device USB Android Debug
- 실은행 SMS와 FCM/APNs 원격 전달은 로컬 Mock 검증과 구분해 보고한다.

### 2026-10-18 최신 develop 통합·발행 검증

- 최신 develop의 그룹 압축 정산 알림과 거래 감지 호출을 보존하고 SMS 변경을 통합했다.
- Java 21 `./gradlew compileJava compileTestJava --no-daemon` 통과.
- 격리 PostgreSQL 18.6에서 실제 Flyway로 V1–V59 설치 → V60 단독 업그레이드 → validate(59개 파일; V49 없음) → 재실행 0건을 확인했다.
- migration 검증 31개 통과: 기존 inbox 행 보존, 기존 정산·입금·압축 정산·초대·그룹·리캡 알림과 금융 인증 UUID 허용, 잘못된 UUID/NULL/배분 방식/유형·대상 조합/그룹 ID 범위/리캡 월 거절.
- 최신 통합본의 SMS·CODEF Adapter·계좌 서비스·알림·Bean wiring Java 19 suites/154개 및 기존 관리자 JavaScript 35개 테스트 통과. 실제 PostgreSQL의 인증서 SMS 통합 20개도 포함한다.
- 실제 금융사/FCM/Android 검증은 이번 게시 작업에서 재실행하지 않았다. 10월 18일 인수인계에는 실제 CODEF 인증서 BOUND와 계좌·거래 조회 SMS SUCCEEDED가 기록되어 있으나 이번 테스트 결과와 구분한다.
- 기능 flag 기본값은 false다. V60 스키마만 먼저 적용해도 금융사 요청을 시작하지 않는다. 전체 Java suite와 배포는 별도 CI/운영 검증이다.
