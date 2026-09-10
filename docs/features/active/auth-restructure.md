# Feature Plan: 인증 기능 개편

- Status: Implemented - 정적 검증 완료, 런타임·운영 DDL 검증 대기
- Last updated: 2026-09-10
- Related issue: N/A
- Related ADR: N/A - 현재 결정은 인증 기능 내부 계약으로 기록하며, 다른 도메인까지 정책이 확장되면 별도 ADR을 작성한다.
- Source specification:
  - [AUTH-SMS-001 SMS 사용자 인증](https://app.notion.com/p/e5f6e559a650831a8a7d0140439d72af)
  - [AUTH-ACC-004 로그아웃](https://app.notion.com/p/b9b6e559a6508378b911817f0185ed38)
  - [AUTH-ACC-005 회원가입](https://app.notion.com/p/6366e559a65083e69a8901d9b6e66b24)
  - [AUTH-ACC-006 로그인](https://app.notion.com/p/b1d6e559a650833389d2813062ee7d16)

## 1. Purpose

### Problem

현재 인증은 이름, 주민등록번호 앞 7자리, 휴대폰 번호로 SMS 인증을 시작한 뒤 `POST /api/v2/auth/complete`가 기존 사용자면 로그인하고 신규 사용자면 회원가입하는 통합 흐름이다. 이 구조는 다음 문제가 있다.

- 단순 SMS OTP는 휴대폰 소유만 확인하지만 현재 흐름은 입력한 이름과 주민등록번호가 실제 사용자 정보인 것처럼 취급한다.
- 회원가입과 로그인의 Request, 실패 조건, 사용자 경험이 하나의 UseCase에 섞여 있다.
- 공개 로그인 API가 없어 기존 사용자도 새 세션을 발급받으려면 매번 SMS 인증을 거쳐야 한다.
- 명세의 회원가입 항목인 PIN 확인, 필수 약관 동의, 마케팅 동의를 서버에서 검증하지 않는다.
- 기존 사용자 식별과 영속성은 `identityKey`, 생년월일, 성별에 의존하지만, 개편 계약은 회원가입에서 `birthDate`를 필수로 받고 `gender`는 선택 프로필 정보로 유지한다.

### Why now

회원가입, 로그인, 토큰 재발급, 로그아웃의 공개 계약을 프론트엔드가 사용하기 전에 인증 흐름을 명세와 일치시켜야 한다. 현재 통합 계약을 유지한 채 다른 도메인 개발을 진행하면 사용자 식별 방식과 프로필 계약까지 더 많은 코드가 기존 구조에 의존하게 된다.

### Success criteria

- [ ] 사용자는 휴대폰 번호로 6자리 OTP를 받고 3분 안에 최대 5회까지 확인할 수 있다.
- [ ] 인증번호 재전송은 휴대폰 번호별로 60초에 1회만 허용한다.
- [ ] 신규 사용자는 SMS 인증 완료 후 이름, 생년월일(`yyyy-MM-dd`), 6자리 PIN, PIN 확인, 필수 약관 동의를 제출하여 가입할 수 있다.
- [ ] `birthDate`가 누락되거나 유효하지 않은 회원가입 요청은 HTTP 400을 반환하고, OpenAPI에는 required `string`/`date`로 노출된다.
- [ ] `SignUpRequest`와 `SignUpCommand`의 `toString()`에서 생년월일이 `birthDate=***`로 마스킹된다.
- [ ] 이미 가입된 휴대폰 번호로 중복 가입할 수 없다.
- [ ] 기존 사용자는 휴대폰 번호와 PIN으로 로그인하여 `accessToken`, `requestToken`을 발급받는다.
- [ ] 로그인 성공 시 해당 사용자의 기존 Redis 세션을 새 세션으로 교체하여 한 기기에서만 로그인 상태를 유지한다.
- [ ] 이전 세션의 Access Token과 Request Token은 새 로그인 직후 사용할 수 없다.
- [ ] 로그아웃하면 Redis 세션이 삭제되고 기존 토큰으로 보호 API에 접근할 수 없다.
- [ ] 로그아웃 시 사용자 ID, UTC 시각, Client IP, User-Agent를 감사 로그로 남긴다.
- [ ] 정상 로그아웃 요청의 95%는 3초 안에 응답한다.
- [ ] PIN, OTP, 환경변수 Secret과 전체 인증 토큰을 평문으로 저장하거나 로그에 남기지 않는다.
- [ ] SMS 인증을 휴대폰 소유 확인으로만 설명하고 실명 또는 CI 본인확인으로 표현하지 않는다.

### Out of scope

- PASS, 통신사 본인확인, CI/DI 발급 및 실명 인증
- 생체인증, 앱 잠금 해제, Keychain/Keystore 저장 등 클라이언트 기능
- 다중 기기 동시 로그인과 기기별 세션 목록
- 전체 기기 로그아웃 API
- PIN 재설정, 휴대폰 번호 변경, 계정 복구
- 탈퇴, 정지, 장기 미사용 계정 상태 개편
- 저장 항목이 정의되지 않은 별도 사용자 설정 Entity와 설정 API
- Flyway 활성화
- 인증 개편 범위를 벗어난 새로운 Test Case 작성

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `auth`, `user`
- Reason a new domain is required, if applicable: N/A - SMS 확인, PIN 인증, JWT와 Redis 세션은 기존 인증 도메인의 책임이다.
- Relationships to other domains:
  - `user`: 가입된 사용자와 PIN Hash, 휴대폰 번호, 프로필 기본값을 저장한다.
  - `profile`: 생년월일은 회원가입에서 필수로 받지만 기존 데이터 호환을 위해 조회 응답의 Nullable 계약을 유지하고, 성별은 선택 프로필 정보로 취급한다.
  - `common`: 기존 `ApiResponse`, `ApplicationException`, `StatusCode` 오류 형식을 유지한다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 휴대폰 소유 인증 | 입력한 번호로 전달된 OTP를 확인해 해당 번호를 현재 사용할 수 있음을 증명하는 과정 | `SmsVerificationUseCase`, `SmsOtpState` |
| 인증 Challenge | SMS 인증 성공 후 회원가입에서 한 번 사용할 수 있는 짧은 수명의 증명 | `AuthChallenge` |
| PIN | 로그인에 사용하는 6자리 숫자 비밀번호 | Request의 `pin`, 저장 시 `pinHash` |
| 단일 기기 세션 | 사용자 한 명당 Redis에 활성 세션 하나만 존재하는 정책 | `AuthSession`, `auth:session:{userId}` |
| Access Token | 보호 API 호출에 사용하는 단기 JWT | `accessToken` |
| Request Token | Access Token 재발급에 사용하는 장기 JWT. 기능명세의 Refresh Token과 같은 역할 | `requestToken` |
| 휴대폰 식별자 | 국내 휴대폰 번호를 하이픈 없이 정규화한 로그인 ID | `phoneNumber` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 비회원 | 가입에 사용할 휴대폰 번호를 인증한다. | `SendSmsVerificationUseCase`, `ConfirmSmsVerificationUseCase` |
| SMS 인증을 완료한 비회원 | 새 사용자 계정을 생성한다. | `SignUpUseCase` |
| 기존 사용자 | 휴대폰 번호와 PIN으로 새 단일 세션을 발급받는다. | `LoginUseCase` |
| 로그인 사용자 | 만료된 Access Token을 갱신한다. | `RefreshTokenUseCase` |
| 로그인 사용자 | 현재 활성 세션을 종료한다. | `LogoutUseCase` |

### State and invariants

```text
SMS_REQUESTED -> SMS_CONFIRMED -> SIGNED_UP
                      |
                      +-> EXPIRED

LOGGED_OUT -> AUTHENTICATED -> LOGGED_OUT
                    |
                    +-> 새 로그인으로 기존 세션 교체
```

- 휴대폰 번호는 정규화 후 사용자 테이블에서 유일해야 한다.
- OTP는 6자리 숫자이며 Hash만 Redis에 저장한다.
- OTP는 발급 후 3분이 지나거나 확인 실패가 5회에 도달하면 사용할 수 없다.
- 인증 Challenge는 만료 전 한 번만 회원가입에 사용할 수 있고 성공 후 즉시 삭제한다.
- PIN은 BCrypt 등 `PasswordEncoder`로 단방향 Hash한 값만 저장한다.
- PIN은 정확히 6자리 숫자여야 하며 `000000`, `112233`, `123456`, `654321`을 허용하지 않는다.
- 로그인 실패 응답은 존재하지 않는 휴대폰 번호와 잘못된 PIN을 구분하지 않는다.
- 사용자 한 명은 활성 Redis 세션을 하나만 가진다. 새 로그인은 이전 세션을 덮어쓴다.
- JWT 서명 검증만 통과해도 인증된 것으로 처리하지 않는다. JWT의 `sessionId`가 Redis 활성 세션과 일치해야 한다.
- SMS OTP는 실명, 주민등록번호 또는 휴대폰 명의를 검증한 것으로 취급하지 않는다.

Invalid states:

- 같은 정규화 휴대폰 번호를 가진 사용자가 둘 이상 존재하는 상태
- 평문 PIN 또는 OTP가 데이터베이스, Redis, 로그에 저장된 상태
- Redis 세션과 일치하지 않는 JWT가 보호 API에 접근 가능한 상태
- 사용한 인증 Challenge로 회원가입을 반복할 수 있는 상태
- 새 로그인 후 이전 기기의 세션이 계속 유효한 상태

## 3. Scenarios

### Normal flow: SMS 인증

1. 사용자가 휴대폰 번호를 제출한다.
2. 서버가 국내 휴대폰 번호를 하이픈 없는 형식으로 정규화한다.
3. 서버가 휴대폰 번호별 재전송 제한을 확인하고 6자리 OTP를 생성한다.
4. OTP Hash와 만료 시각을 Redis `auth:sms:otp:{phoneHash}`에 저장한다.
5. 기존 `SmsSenderPort`가 Discord Mock 또는 SOLAPI로 OTP를 발송한다.
6. 사용자가 `verificationRequestId`와 OTP를 제출한다.
7. 서버가 OTP와 유효시간을 확인하고 회원가입용 `challengeId`를 반환한다.

### Normal flow: 회원가입

1. 사용자가 `challengeId`, 이름, 생년월일(`yyyy-MM-dd`), PIN, PIN 확인, 약관 동의를 제출한다.
2. 서버가 Challenge의 존재, 만료, 재사용 여부를 확인한다.
3. 서버가 휴대폰 번호 중복, PIN 정책과 필수 약관 동의를 확인한다.
4. 서버가 PIN을 Hash하고 사용자와 기본 프로필 값을 저장한다.
5. 서버가 사용한 Challenge를 삭제한다.
6. 가입 성공만 반환하며 토큰은 발급하지 않는다. 클라이언트는 로그인 화면으로 이동한다.

### Normal flow: 로그인과 단일 기기 세션

1. 사용자가 정규화 가능한 휴대폰 번호와 PIN을 제출한다.
2. 서버가 사용자 존재 여부, 활성 상태와 PIN을 확인한다.
3. 서버가 `lastLoginAt`을 UTC로 갱신한다.
4. 서버가 새 `sessionId`, Access Token, Request Token을 생성한다.
5. Redis `auth:session:{userId}`에 새 세션을 저장한다. 기존 값은 덮어쓴다.
6. 이후 이전 기기의 JWT는 Redis `sessionId`가 일치하지 않아 인증되지 않는다.

### Normal flow: 토큰 재발급

1. 클라이언트가 `requestToken`을 제출한다.
2. 서버가 JWT 종류, 서명, 만료, 사용자 ID와 `sessionId`를 확인한다.
3. 서버가 Redis의 활성 세션과 Request Token이 일치하는지 확인한다.
4. 서버가 Access Token과 회전된 새 Request Token을 발급하고 Redis 세션을 교체한다.
5. 직전 Request Token은 재사용할 수 없다.

### Normal flow: 로그아웃

1. 클라이언트가 Access Token으로 로그아웃을 요청한다.
2. 서버가 `CurrentActorPort`로 현재 사용자 ID를 확인한다.
3. 서버가 Redis `auth:session:{userId}`를 삭제한다.
4. 클라이언트는 Access Token, Request Token과 자동 로그인 정보를 삭제한다.
5. 기존 토큰으로 보호 API를 호출하면 `401 Unauthorized`를 반환한다.

### Failure flows

| Condition | Result |
|---|---|
| 잘못된 휴대폰 번호 형식 | `INVALID_PHONE_NUMBER` / `400` |
| OTP 재전송 제한 전 재요청 | `SMS_RESEND_TOO_SOON` / `429` |
| OTP 불일치 | `SMS_VERIFICATION_CODE_MISMATCH` / `400` |
| OTP 만료 | OTP 상태 삭제 후 `SMS_VERIFICATION_EXPIRED` / `400` |
| OTP 확인 실패 5회 | OTP 상태 삭제 후 `SMS_VERIFICATION_ATTEMPTS_EXCEEDED` / `429` |
| SMS 발송 실패 | 저장한 OTP를 보상 삭제하고 `SMS_SEND_FAILED` / `502` |
| Challenge 없음 또는 만료 | `AUTH_CHALLENGE_EXPIRED` / `400` |
| 이미 가입된 휴대폰 번호 | `PHONE_NUMBER_ALREADY_REGISTERED` / `409` |
| PIN 형식 또는 금지 패턴 | `INVALID_PIN_FORMAT` / `400` |
| PIN 확인 불일치 | `PIN_CONFIRMATION_MISMATCH` / `400` |
| 필수 약관 미동의 | `REQUIRED_TERMS_NOT_AGREED` / `400` |
| 로그인 휴대폰 번호 또는 PIN 오류 | `INVALID_LOGIN_CREDENTIALS` / `401` |
| 비활성 사용자 | `USER_INACTIVE` / `403` |
| 유효하지 않거나 재사용된 Request Token | `INVALID_TOKEN` / `401` |
| 인증 없이 로그아웃 요청 | 공통 `UNAUTHORIZED` / `401` |

### Boundary conditions

- 동시 회원가입 요청은 Application의 선조회만 믿지 않고 데이터베이스 Unique Constraint로 한 건만 성공시킨다.
- 동시에 같은 Request Token으로 재발급을 요청하면 Redis 세션을 먼저 교체한 한 요청만 성공해야 한다. 원자성 확보 방식은 구현 전 Redis 연산 단위에서 재검토한다.
- 인증 성공과 동시에 OTP가 만료되는 경우 서버의 UTC 시각을 기준으로 만료를 우선한다.
- 새 로그인과 기존 기기 요청이 경합하면 새 세션 저장 이후의 기존 기기 요청은 실패한다.
- 휴대폰 번호가 재사용될 수 있으므로 향후 PIN 재설정 또는 계정 복구에는 SMS만 사용하지 않는다. 해당 기능은 별도 명세가 생기기 전까지 제공하지 않는다.
- 로그인 실패가 5회에 도달하면 해당 휴대폰 식별자를 Redis에서 30분 잠그고 TTL 만료 시 자동 해제한다.

## 4. Contracts

### Input Ports

```text
UseCase: SendSmsVerificationUseCase.send
Request: SendSmsVerificationRequest(phoneNumber)
Response: SmsVerificationSentResponse(verificationRequestId, phoneNumber, expiresAt, resendAvailableAt)

UseCase: ConfirmSmsVerificationUseCase.confirm
Request: ConfirmSmsVerificationRequest(phoneNumber, verificationRequestId, verificationCode)
Response: SmsVerificationConfirmedResponse(challengeId, expiresAt)

UseCase: SignUpUseCase.signUp
Request: SignUpRequest(challengeId, name, birthDate, pin, pinConfirmation, requiredTermsAgreed, marketingAgreed)
Response: void

UseCase: LoginUseCase.login
Request: LoginRequest(phoneNumber, pin)
Response: TokenResponse(accessToken, requestToken)

UseCase: RefreshTokenUseCase.refresh
Request: RefreshTokenRequest(requestToken)
Response: TokenResponse(accessToken, requestToken)

UseCase: LogoutUseCase.logout
Request: LogoutRequest(clientIp, userAgent)
Response: void
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SmsVerificationStorePort` | OTP 상태와 TTL 저장 | SMS 발송·확인 Service | 기존 `SmsVerificationRedisAdapter` |
| `SmsSenderPort` | 생성한 OTP 전달 | SMS 발송 Service | 기존 Discord/SOLAPI Adapter |
| `AuthChallengeStorePort` | SMS 인증 완료 Challenge 저장 | SMS 확인·회원가입 Service | 기존 `AuthChallengeRedisAdapter` |
| `LoadUserPort` / `SaveUserPort` | 휴대폰 번호 조회, 사용자 생성·갱신 | 회원가입·로그인 Service | `UserPersistenceAdapter` |
| `AuthTokenPort` | Access/Request JWT 생성·검증 | 기존 `AuthTokenService` | 기존 `JwtTokenProvider` |
| `AuthSessionPort` | 사용자 단일 세션 저장·검증·삭제 | 로그인·재발급·로그아웃·Filter | 기존 `AuthSessionRedisAdapter` |
| `LoginAttemptStorePort` | 휴대폰 식별자별 로그인 실패 횟수를 원자 증가·초기화 | `LoginService` | `LoginAttemptRedisAdapter` |
| `CurrentActorPort` | 로그아웃 사용자의 ID 확인 | `LogoutService` | 기존 `SpringSecurityAdapter` |
| `AuthAuditPort` | 로그아웃 감사 이벤트 저장 | `LogoutService` | JPA 감사 로그 Adapter |

명세가 요구하는 감사 로그와 Redis 원자 증가가 각각 실제 외부 경계이므로 `AuthAuditPort`, `LoginAttemptStorePort`를 추가했다. 사용자 조회와 저장은 `LoadUserPort`, `SaveUserPort`로 분리하고 하나의 `UserPersistenceAdapter`가 구현한다.

### Web API

#### SMS 인증번호 발송

```http
POST /api/v2/auth/sms/send
Content-Type: application/json

{
  "phoneNumber": "01012345678"
}
```

- Authentication: 불필요
- Response: 기존 `SmsVerificationSentResponse` 유지
- Rate limit: 정규화 휴대폰 번호별 60초에 1회

#### SMS 인증번호 확인

```http
POST /api/v2/auth/sms/confirm
Content-Type: application/json

{
  "phoneNumber": "01012345678",
  "verificationRequestId": "request-id",
  "verificationCode": "123456"
}
```

- Authentication: 불필요
- Response: 기존 `SmsVerificationConfirmedResponse(challengeId, expiresAt)` 유지

#### 회원가입

```http
POST /api/v2/auth/signup
Content-Type: application/json

{
  "challengeId": "challenge-id",
  "name": "홍길동",
  "birthDate": "1995-03-14",
  "pin": "135790",
  "pinConfirmation": "135790",
  "requiredTermsAgreed": true,
  "marketingAgreed": false
}
```

- Authentication: 불필요
- Response: `ApiResponse<Void>`
- 가입 성공 시 토큰을 발급하지 않는다.

#### 로그인

```http
POST /api/v2/auth/login
Content-Type: application/json

{
  "phoneNumber": "01012345678",
  "pin": "135790"
}
```

- Authentication: 불필요
- Response: `TokenResponse(accessToken, requestToken)`
- 존재하지 않는 휴대폰 번호와 잘못된 PIN은 같은 오류를 반환한다.
- 로그인 성공 시 기존 Redis 세션을 새 세션으로 교체한다.

#### 토큰 재발급

```http
POST /api/v2/auth/token/refresh
Content-Type: application/json

{
  "requestToken": "..."
}
```

- Authentication: 불필요
- 기존 Request/Response와 Request Token 회전 정책을 유지한다.

#### 로그아웃

```http
POST /api/v2/auth/logout
Authorization: Bearer <accessToken>
```

- Authentication: 필요
- Response: `ApiResponse<Void>`
- 서버는 현재 사용자 단일 세션을 삭제한다.
- 서버는 사용자 ID, UTC 시각, Client IP, User-Agent를 감사 로그로 저장한다.
- 클라이언트 Token과 Secure Storage 삭제는 클라이언트 책임이다.

### Removed public API

- `POST /api/v2/auth/complete`: 회원가입과 로그인을 분리하므로 제거한다.
- `POST /api/v2/auth/dev/login`: 공개 로그인과 역할이 중복되므로 제거한다.

프론트엔드는 배포 전에 `/complete` 호출을 `/signup` 또는 `/login`으로 전환해야 한다. 별도 호환 Endpoint는 실제 동시 배포 요구가 확인된 경우에만 추가한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes:
  - `users.phone_number`에 정규화 값 기준 Unique Constraint를 추가한다.
  - Unique Constraint 적용 전 기존 중복 번호를 조회하고 충돌을 해결한다.
  - `LoadUserPort.findByPhoneNumber`와 동일 목적의 JPA 조회를 추가한다.
  - `identity_key`는 기존 데이터 호환을 위해 유지하지만 새 로그인 식별에는 사용하지 않는다.
  - 회원가입 API는 `birthDate`를 필수(`yyyy-MM-dd`)로 받고 `users.birth_date`에 저장한다.
  - `users.birth_date`와 `users.gender`는 기존 데이터 호환을 위해 nullable 상태를 유지한다. 이 계약 변경을 위한 Flyway migration은 생성하지 않는다.
  - `MyProfileResult`와 `MyProfileWebResponse`는 신규 사용자의 생년월일을 반환하고, 레거시 데이터의 생년월일·성별 `null`을 허용한다.
  - 필수 약관 동의 시각과 마케팅 동의 시각을 저장한다. 마케팅 철회 시각과 약관 버전은 해당 변경 UseCase가 생길 때 추가한다.
  - 로그아웃 감사 로그에 사용자 ID, 이벤트 종류, UTC 시각, Client IP, User-Agent를 저장한다.
  - 현재 인증 스키마는 Flyway V1 기준선으로 관리하며 이후 변경은 새 버전 마이그레이션으로 추가한다.

운영 DDL 초안은 아래와 같다. 적용 전 `phone_number` 정규화와 중복 조회 결과가 0건인지 확인해야 한다.

```sql
SELECT phone_number, COUNT(*)
FROM users
GROUP BY phone_number
HAVING COUNT(*) > 1;

ALTER TABLE users ADD COLUMN required_terms_agreed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN marketing_agreed_at TIMESTAMP;
CREATE UNIQUE INDEX uk_users_phone_number ON users (phone_number);

CREATE TABLE auth_audit_logs (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    client_ip VARCHAR(64),
    user_agent VARCHAR(500)
);
```

Rollback DDL은 감사 로그와 신규 제약 변경을 되돌리는 용도로만 사용하며, 기존 `birth_date`, `gender` nullable 상태는 변경하지 않는다.

```sql
DROP TABLE auth_audit_logs;
DROP INDEX uk_users_phone_number;
ALTER TABLE users DROP COLUMN marketing_agreed_at;
ALTER TABLE users DROP COLUMN required_terms_agreed_at;
```
- Redis key, TTL, or serialization changes:
  - `auth:sms:otp:{phoneHash}` Key와 OTP 3분 TTL을 유지한다.
  - `auth:challenge:{challengeId}`는 이름·주민등록번호 기반 `AuthIdentity` 대신 인증된 휴대폰 번호만 저장한다.
  - 배포 시 직렬화 형식이 달라지는 기존 OTP·Challenge Key만 만료 또는 삭제한다. 활성 사용자 세션 Key는 유지한다.
  - `auth:session:{userId}` 단일 세션 Key와 JWT 만료 기반 TTL을 유지한다.
  - `auth:login:attempt:{phoneHash}`에 실패 횟수를 저장하고 5회 도달 시 TTL을 30분으로 재설정한다. 성공 시 Key를 삭제한다.
- External API or message changes:
  - 기존 `SmsSenderPort`와 SOLAPI/Discord Adapter를 재사용한다.
  - SMS 문구와 발송 Provider 선택 방식은 변경하지 않는다.
- Environment variable or profile changes:
  - 기존 SMS, JWT, Identity HMAC 환경변수를 유지한다.
  - PASS/CI 관련 환경변수나 의존성은 추가하지 않는다.

## 5. Design decisions

### Business logic

- `IdentityPolicy`는 주민등록번호 파싱과 실명에 가까운 식별키 생성을 제거하고 휴대폰 번호 정규화와 HMAC 기반 `phoneHash`만 담당한다.
- `SmsOtpState`와 `AuthChallenge`는 휴대폰 소유 확인에 필요한 정보만 보관한다.
- `PinPolicy`가 PIN 길이, 숫자 구성, 금지 패턴과 최대 로그인 시도 횟수를 소유한다.
- `User`는 이름, 생년월일, 휴대폰 번호, PIN Hash와 사용자 상태를 소유한다. 생년월일은 회원가입에서 필수로 받고, 성별은 선택 프로필 정보로 유지한다.
- 회원가입 Service는 Challenge 소비, 중복 확인, PIN 검증, 사용자 저장 순서를 조정한다.
- 로그인 Service는 사용자 조회, PIN 검증, 마지막 로그인 갱신, 단일 세션 발급을 조정한다.
- 로그아웃 Service는 세션을 먼저 삭제한 뒤 감사 로그 저장을 요청한다. 감사 로그 저장 실패로 이미 종료한 세션을 복원하지 않는다.
- 토큰 발급·회전과 Redis 세션 검증은 기존 `AuthTokenService`, `JwtAuthenticationFilter`를 재사용한다.

### Technology and patterns

- Constraint to solve: 외부 CI 제공자를 사용할 수 없는 상태에서 기능명세의 휴대폰 인증과 PIN 로그인을 구현해야 한다.
- Selected approach: SOLAPI OTP를 휴대폰 소유 확인으로 제한하고, 정규화 휴대폰 번호를 Unique 로그인 식별자로 사용한다.
- Reason for selection: 현재 설치된 Provider와 Redis/JWT 구조를 그대로 사용할 수 있고 검증하지 못한 이름·주민등록번호를 신뢰하지 않는다.
- Alternatives considered:
  - PASS/CI 연동: 즉시 사용할 계약과 자격이 없어 현재 범위에서 제외한다.
  - 기존 이름·주민등록번호·휴대폰 조합 `identityKey`: SMS가 이름과 주민등록번호를 검증하지 못하므로 제외한다.
  - 이메일·비밀번호 로그인: 기능명세에 없으므로 추가하지 않는다.
  - 다중 기기 Session Collection: 단일 기기 정책에 필요하지 않으므로 추가하지 않는다.
- Trade-offs and limits:
  - 휴대폰 번호 변경과 재사용에 대응하는 복구 수단이 없다. PIN 재설정·번호 변경 기능을 제공하기 전에 별도 강한 인증 수단을 정의해야 한다.
  - 신규 사용자는 회원가입 생년월일을 프로필 응답에서 받으며, 레거시 사용자의 생년월일과 성별은 `null`일 수 있다.
  - 새 로그인은 기존 기기의 세션을 즉시 무효화하지만 기기명, 최근 기기 목록, 새 기기 알림은 제공하지 않는다.
  - 로그인 5회 실패 잠금은 30분 후 자동 해제한다. SMS 재인증 기반 즉시 해제는 제공하지 않는다.

### Security decisions

- PIN과 OTP는 서로 다른 용도로 사용한다. OTP는 휴대폰 소유 확인, PIN은 계정 인증이다.
- 전화번호 존재 여부를 추측할 수 없도록 로그인 실패는 `INVALID_LOGIN_CREDENTIALS` 하나로 통일한다.
- 전화번호 중복 가입 오류는 회원가입 단계에서만 반환한다.
- PIN 입력 실패 횟수, Request Token 재사용과 OTP 실패는 서버에서 제한한다. 클라이언트 제한만 신뢰하지 않는다.
- JWT는 Redis 활성 세션과 함께 검증한다.
- 로그에 OTP, PIN, 전체 JWT, Secret, 전체 휴대폰 번호를 기록하지 않는다.
- 감사 로그의 Client IP는 신뢰된 Proxy 설정을 거친 값만 사용하고 전체 인증 Header를 저장하지 않는다.

### Open decisions before production rollout

| Decision | Current proposal | Completion condition |
|---|---|---|
| 기존 중복 휴대폰 번호 | Unique Constraint 적용 전 운영 데이터 조회 | 중복 건별 병합·정리 기준 확정 |
| 약관 버전 | 동의 여부와 시각은 저장하고 버전은 저장하지 않음 | 실제 약관 버전 식별값이 확정되면 Request·Schema 계약 추가 |

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | `/signup`, `/login` 추가, `/complete`, `/dev/login` 제거 | `AuthController`, `DevAuthController`, `AuthDocs`, Web DTOs |
| Application | 회원가입·로그인 UseCase 분리, 기존 SMS·Token·Logout 재사용 | `CompleteAuthService`, `SignUpService`, `LoginService`, `AuthTokenService`, `LogoutService` |
| Domain | 인증 Challenge를 휴대폰 기반으로 축소, PIN 금지 규칙 추가 | `AuthIdentity`, `AuthChallenge`, `SmsOtpState`, `User`, `PinPolicy` |
| Persistence | 휴대폰 번호 조회·Unique Constraint, 회원가입 생년월일 저장, 기존 생년월일·성별 Nullable 유지, 동의·감사 로그 저장 | `UserJpaEntity`, `UserJpaRepository`, `UserPersistenceAdapter`, `UserMapper`, 신규 감사 로그 Adapter |
| Redis | OTP/Challenge 직렬화 변경, 로그인 실패 제한 추가, 단일 세션 Key 유지 | `SmsVerificationRedisAdapter`, `AuthChallengeRedisAdapter`, `LoginAttemptRedisAdapter`, `AuthSessionRedisAdapter` |
| Security | `/signup`, `/login` 공개 경로 추가, JWT+Redis 검증 유지 | `SecurityConfig`, `JwtAuthenticationFilter` |
| Configuration | OTP 3분, 재전송 60초, 최대 시도 5회, 단일 세션과 Token 회전 유지 | `application.yaml`, Auth Properties와 Config |
| Profile | 신규 사용자의 생년월일 반환과 레거시 생년월일·성별 Nullable 응답 | `MyProfileResult`, `MyProfileWebResponse` |
| Documentation | 인증 경계와 기존 SOLAPI 문서의 Request 설명 갱신 필요 | `docs/architecture.md`, `docs/features/active/solapi-sms-adapter.md`, Swagger |

Existing behavior that must remain unchanged:

- `/api/v2` Prefix와 공통 `ApiResponse` 오류 형식
- Discord Mock과 SOLAPI 조건부 SMS Adapter
- OTP 6자리, 3분 TTL, 60초 재전송 제한, 확인 최대 5회
- SMS 발송 실패 시 저장한 OTP 보상 삭제
- `TokenResponse(accessToken, requestToken)` 외부 필드명
- Request Token 회전과 Redis 활성 세션 검증
- `CurrentActorPort`를 통한 인증 사용자 조회
- PostgreSQL과 Redis에 UTC `LocalDateTime` 저장
- 기존 사용자 ID와 다른 도메인의 사용자 참조

## 7. Implementation plan

- [x] UseCase and Application Command/Result
  - `SignUpUseCase`, `LoginUseCase`와 Command를 추가한다.
  - 기존 `SendSmsVerificationRequest`에서 이름과 주민등록번호를 제거한다.
  - `CompleteAuthUseCase`, `DevLoginUseCase`와 사용하지 않는 Request를 제거한다.
- [x] Required Output Ports
  - 로그아웃 감사 로그를 위한 `AuthAuditPort`와 원자적 로그인 실패 제한을 위한 `LoginAttemptStorePort`를 추가한다.
  - `LoadUserPort`에 휴대폰 번호 조회 Capability를 추가한다.
- [x] Domain models and rules
  - `AuthIdentity` 의존을 제거하고 OTP·Challenge를 휴대폰 번호 기반으로 축소한다.
  - `PinPolicy`에 명세의 금지 패턴을 추가한다.
  - `User.register`에 필수 생년월일을 전달하고 저장한다.
- [x] Application Service
  - `SignUpService`와 `LoginService`를 공개 UseCase 구현으로 변경한다.
  - `/complete`의 자동 분기와 Challenge 기반 기존 사용자 로그인을 제거한다.
  - 로그인 실패 횟수를 Redis에서 원자 증가하고 5회 실패 시 30분 잠근다.
- [x] Output Adapters
  - 휴대폰 번호 JPA 조회를 추가한다.
  - 사용자 Schema 제약, Nullable 필드와 약관 동의 필드를 반영한다.
  - 로그아웃 감사 로그 JPA Adapter를 추가한다.
  - 변경된 OTP·Challenge Redis 직렬화를 반영한다.
- [x] Web DTOs, Controllers, and Swagger
  - `/signup`, `/login` DTO와 Controller Method를 추가한다.
  - `/complete`, `/dev/login`을 제거한다.
  - `SecurityConfig` 공개 경로와 Swagger 설명을 갱신하고 `birthDate` required/date 계약을 노출한다.
- [x] Infra configuration and Bean wiring
  - 기존 설정을 재사용한다.
  - `PinProperties.lockDuration` 기본 설정을 30분으로 추가한다.
- [x] Documentation and verification results
  - `docs/architecture.md`의 인증 흐름과 개인정보 규칙을 갱신한다.
  - 기존 SOLAPI Feature Plan의 SMS Request 설명을 새 계약에 맞춘다.
  - 회원가입 생년월일 계약과 구현 검증 결과를 이 문서에 기록한다.

### Expected commit boundaries

실제 Commit은 사용자가 요청한 경우에만 수행한다.

```text
docs(auth): 인증 기능 개편 계획 작성
refactor(auth): SMS 인증을 휴대폰 소유 확인 흐름으로 변경
feat(auth): 회원가입과 휴대폰 PIN 로그인 분리
feat(auth): 사용자 휴대폰 식별 계약과 스키마 반영
feat(auth): 단일 세션 로그아웃 감사 기록 추가
docs(auth): 인증 API와 구현 결과 문서화
docs(auth): 회원가입 생년월일 계약 문서 보완
```

각 Commit은 사용하지 않는 Port만 남기지 않고 최소한 컴파일 가능한 상태로 끝낸다.

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

회원가입 생년월일 계약에 대한 단위·통합 Test Case를 포함해 관련 Test를 실행한다. PostgreSQL, Redis와 필수 환경변수가 필요한 Test는 해당 조건이 준비된 경우에만 실행한다.

수동 검증 전제조건:

- 실행 가능한 PostgreSQL과 Redis
- Discord Mock 또는 유효한 SOLAPI 설정
- 수신 가능한 테스트 휴대폰 번호
- 운영 데이터 적용 전 휴대폰 번호 중복 조회 결과
- 운영 환경에 적용할 Schema 변경 DDL과 Rollback DDL

### Manual verification

1. 올바른 번호로 OTP를 발송하고 6자리 코드, 3분 만료와 60초 재전송 제한을 확인한다.
2. 잘못된 OTP 5회, 만료 OTP와 SMS 발송 실패 응답을 확인한다.
3. SMS 인증 후 생년월일(`yyyy-MM-dd`)과 정상 PIN으로 신규 가입하고 `users.birth_date` 저장 및 같은 번호의 동시·반복 가입을 확인한다.
4. 생년월일 누락·잘못된 날짜가 HTTP 400을 반환하고, 금지 PIN·PIN 확인 불일치·필수 약관 미동의를 확인한다.
5. 가입된 전화번호와 PIN으로 로그인하고 두 Token과 `lastLoginAt` 갱신을 확인한다.
6. 없는 번호와 잘못된 PIN이 같은 로그인 오류를 반환하는지 확인한다.
7. 같은 사용자로 다시 로그인한 뒤 이전 Access Token과 Request Token이 모두 실패하는지 확인한다.
8. Request Token 재발급 후 직전 Token 재사용이 실패하는지 확인한다.
9. 로그아웃 후 Access Token과 Request Token이 모두 실패하는지 확인한다.
10. 로그아웃 감사 로그에 사용자 ID, UTC 시각, Client IP, User-Agent만 저장되는지 확인한다.
11. 신규 사용자의 프로필 조회에서 저장한 생년월일이 반환되고, 레거시 사용자의 생년월일·성별 `null`도 정상 응답하는지 확인한다.
12. 필수 약관 동의 시각과 마케팅 동의 여부·시각이 저장되는지 확인한다.
13. 로그와 PostgreSQL·Redis 데이터에 평문 PIN, OTP, 전체 Token과 Secret이 없는지 확인한다.
14. 정상 로그아웃 요청의 95%가 3초 안에 응답하는지 측정한다.

### Completion criteria

- [ ] Purpose의 모든 사용자 결과를 검증했다.
- [ ] 정상, 실패, 만료, 중복, 동시성 흐름을 검증했다.
- [ ] 새 로그인으로 이전 단일 기기 세션이 무효화되는지 검증했다.
- [ ] 데이터베이스 Unique Constraint와 Rollback DDL을 검증했다.
- [ ] Swagger와 저장소 문서가 실제 API 계약과 일치한다.
- [ ] 실행하지 못한 환경 의존 검증과 이유를 기록했다.

### Results

| Item | Result | Evidence |
|---|---|---|
| Feature Plan 작성 | Complete | `docs/features/active/auth-restructure.md` |
| Java Compile | Complete | `./gradlew compileJava` 성공 |
| Existing Tests | Pending | PostgreSQL·Redis 선행 조건 확인 후 실행 |
| Manual API verification | Pending | 구현 및 실행 환경 준비 후 수행 |
| Production data migration | Pending | 중복 번호 조회와 DDL 검토 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-15 | Notion 인증 명세와 현재 Controller, Service, Domain, JPA, Redis, JWT 흐름 대조 | 잠금 해제 정책과 운영 데이터 중복 여부 확인 후 구현 | 기존 SMS는 이름·주민등록번호를 검증하지 않으며 초기 개편 초안에는 생년월일·성별 입력이 없었음 |
| 2026-08-15 | SMS OTP + 전화번호/PIN 로그인 + 단일 기기 세션 개발 계약 작성 | Feature Plan 리뷰 | `/complete` 제거는 프론트엔드 동시 변경이 필요한 Breaking Change |
| 2026-08-15 | SMS 입력 축소, 가입·로그인 분리, 30분 로그인 잠금, 단일 Redis 세션, 약관 시각, 로그아웃 감사 로그 구현 및 Java 컴파일 | 기존 테스트와 런타임 수동 검증, 운영 DDL 준비 | 운영 데이터 중복 번호와 `validate` Profile Schema는 별도 배포 작업 필요 |
| 2026-09-10 | 회원가입 요청에 필수 `birthDate`와 `yyyy-MM-dd` 형식, 저장·400 검증 기준을 반영 | 프론트엔드 동시 배포와 런타임 수동 검증 | 기존 클라이언트가 `birthDate`를 누락하면 HTTP 400을 반환함 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-15 | PASS/CI 연동을 현재 범위에서 제외한다. | 바로 사용할 수 있는 계약과 자격이 없다. | SMS를 실명 인증으로 표현하지 않는다. |
| 2026-08-15 | SMS OTP는 휴대폰 소유만 확인한다. | SOLAPI는 입력한 이름·주민등록번호의 진위를 검증하지 않는다. | 이름과 주민등록번호를 SMS 발송 입력에서 제거한다. |
| 2026-08-15 | 정규화 휴대폰 번호를 Unique 로그인 ID로 사용한다. | 기능명세가 휴대폰 번호와 PIN 로그인을 요구한다. | 번호 변경·재사용 복구는 별도 기능이 필요하다. |
| 2026-08-15 | 사용자당 활성 세션을 하나만 허용한다. | 사용자가 단일 기기 원칙을 선택했고 현재 Redis 구조가 이미 이를 지원한다. | 별도 Device Entity나 Session Collection을 만들지 않는다. |
| 2026-08-15 | 가입 완료 후 Token을 발급하지 않는다. | 기능명세가 가입 완료 뒤 로그인 화면 이동을 정의한다. | 로그인에서 새 단일 세션을 발급한다. |
| 2026-08-15 | 외부 Token 필드명은 `requestToken`을 유지한다. | 현재 프론트엔드-facing 계약과 아키텍처 문서의 Breaking Change를 줄인다. | 기능명세의 Refresh Token과 의미는 동일하다. |
| 2026-08-15 | 로그인 실패 오류를 하나로 통일한다. | 휴대폰 번호의 가입 여부 노출을 방지한다. | 기능명세의 세분화 메시지를 서버 오류 코드로 그대로 제공하지 않는다. |
| 2026-08-15 | `/complete`, `/dev/login`은 제거 대상으로 둔다. | 회원가입·로그인 분리 후 역할이 중복된다. | 동시 배포가 불가능할 때만 한시 호환 경로를 별도 결정한다. |
| 2026-08-15 | 별도 사용자 설정 Entity는 만들지 않는다. | 기능명세에 설정 저장 항목과 사용처가 정의되어 있지 않다. | 구체적인 설정 기능이 생기면 별도 Feature로 추가한다. |
| 2026-08-15 | 로그아웃 감사 로그는 세션 삭제 후 기록한다. | 감사 기록 실패 때문에 보안상 필요한 세션 종료를 되돌리지 않는다. | 감사 로그 저장 실패는 운영 로그로 남기고 로그아웃 결과는 성공으로 유지한다. |
| 2026-08-15 | 로그인 5회 실패 시 휴대폰 식별자를 30분 잠근다. | 영구 잠금을 피하면서 기능명세의 서버 측 시도 제한을 제공한다. | Redis TTL 만료 후 자동 해제되며 즉시 해제 API는 제공하지 않는다. |

## 11. Completion record

- Completed on: Pending - 런타임과 운영 DDL 검증 후 완료 처리
- Final behavior: SMS 휴대폰 소유 인증 후 생년월일을 저장하는 별도 가입, 휴대폰 번호와 PIN 로그인, 사용자당 Redis 단일 세션, 5회 실패 시 30분 잠금, 로그아웃 감사 로그
- Unverified items: 운영 휴대폰 번호 중복, 실제 SMS·PostgreSQL·Redis API 흐름, 운영 DDL
- Remaining risks: 휴대폰 번호 변경·재사용 계정 복구 수단 부재, 레거시 프로필의 생년월일·성별 Nullable 유지, 필수 `birthDate`에 따른 프론트엔드 Breaking Change
- Follow-up work: PIN 재설정, 휴대폰 번호 변경, 강한 본인확인 Provider와 약관 버전 이력은 별도 기능명세가 생길 때 계획한다.
