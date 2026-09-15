# Feature Plan: 로그인 전 휴대폰 번호 가입 여부 확인

- 상태: 구현 중
- 갱신일: 2026-09-15
- 관련 이슈: #389
- 관련 ADR: N/A

## 1. 목적

### 문제

로그인 화면은 `POST /api/v2/auth/login`의 401 `INVALID_LOGIN_CREDENTIALS`만으로 가입되지 않은 번호와 잘못된 PIN을 구분할 수 없다. 사용자는 가입하지 않은 번호로 PIN 6자리를 입력한 뒤에야 부정확한 오류 안내를 받는다.

### 성공 조건

- [ ] 미인증 사용자가 `POST /api/v2/auth/phone-number/check`에 휴대폰 번호를 body로 전달해 활성 계정 가입 여부를 확인한다.
- [ ] 활성 계정은 `registered: true`, 존재하지 않거나 비활성인 계정은 `registered: false`를 받는다.
- [ ] 잘못된 휴대폰 번호 형식은 400 `INVALID_PHONE_NUMBER`로 거절한다.
- [ ] 번호별·IP별 제한을 초과한 요청은 사용자 조회 전에 429로 거절한다.
- [ ] 기존 로그인 401 계약과 회원가입/SMS 흐름은 변경하지 않는다.

### 범위 제외

- `POST /api/v2/auth/login`의 오류 구분 변경
- 사용자·인증 저장 스키마 변경
- 프록시 전달 헤더 신뢰 또는 전역 rate limiting 인프라 도입
- SMS 발송 제한 자체의 IP 정책 변경

## 2. 도메인 분석

- 기존 `auth` 도메인과 `LoadUserPort`를 재사용한다.
- Actor는 아직 로그인하지 않은 앱 사용자이며, 목표는 PIN 입력 전에 번호의 활성 계정 가입 여부를 확인하는 것이다.
- 가입 여부는 현재 저장된 `User`의 존재와 `active` 상태로 판단한다. 상태 전이는 없다.
- 휴대폰 번호 정규화·검증·HMAC은 기존 `IdentityPolicy`가 소유한다.

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 가입된 번호 | 활성 사용자 계정에 연결된 정규화 휴대폰 번호 | `PhoneNumberRegistrationResult.registered` |
| 비활성 계정 | 저장되어 있으나 로그인할 수 없는 사용자 | `User.active() == false` |
| 확인 제한 | 번호 또는 IP가 허용량을 소진한 상태 | `CommonStatusCode.TOO_MANY_REQUEST` |

## 3. 시나리오

### 정상 흐름

1. 클라이언트가 body에 `phoneNumber`를 넣어 공개 API를 호출한다.
2. Web Adapter가 연결 원격 주소와 번호를 Command로 변환한다.
3. Service가 번호를 정규화하고 HMAC한 뒤 번호·IP 제한을 원자적으로 소비한다.
4. `LoadUserPort`로 번호를 조회해 활성 상태만 `registered: true`로 반환한다.

### 실패 및 경계

- 번호가 null, 공백 또는 국내 휴대폰 형식이 아니면 400 `INVALID_PHONE_NUMBER`를 반환하고 quota를 소비하지 않는다.
- 같은 번호의 두 번째 요청은 최초 요청부터 60초 동안 429로 거절한다.
- 같은 IP의 61번째 요청은 최초 요청부터 60초 동안 429로 거절한다.
- 제한 상태 저장소가 실패하면 사용자 존재 여부를 조회하지 않고 503으로 실패해 무제한 조회를 허용하지 않는다.
- `X-Forwarded-For`는 신뢰하지 않고 현재 공개 초대 제한과 같이 `HttpServletRequest.getRemoteAddr()`를 사용한다.

## 4. 계약

### Input Port와 매핑

```text
CheckPhoneNumberRegistrationUseCase
PhoneNumberRegistrationCheckCommand(phoneNumber, clientAddress)
PhoneNumberRegistrationResult(registered)
PhoneNumberRegistrationCheckRequest.toCommand(clientAddress)
PhoneNumberRegistrationWebResponse.from(result)
```

### Output Ports

| Port | 목적 | 호출자 | Adapter |
|---|---|---|---|
| `PhoneNumberRegistrationCheckLimitPort` | 번호·IP 확인 quota를 원자적으로 소비 | `CheckPhoneNumberRegistrationService` | Redis Adapter |
| 기존 `LoadUserPort` | 정규화 번호로 사용자 조회 | `CheckPhoneNumberRegistrationService` | `UserPersistenceAdapter` |

### Web API

```text
POST /api/v2/auth/phone-number/check
Request: { "phoneNumber": "01012345678" }
Response data: { "registered": true | false }
Errors: 400 INVALID_PHONE_NUMBER, 429 TOO_MANY_REQUEST, 503 TEMPORARILY_UNAVAILABLE
Authentication: 불필요
```

### 저장 및 외부 연동

- PostgreSQL 변경: 없음. 기존 `findByPhoneNumber` 조회를 재사용한다.
- Flyway migration required: **No**.
- Redis key:
  - `auth:phone-number-check:address:<remoteAddr>`: 60초 고정 창, 최대 60회
  - `auth:phone-number-check:phone:<phoneHmac>`: 60초 고정 창, 최대 1회
- 두 quota는 단일 Lua 실행으로 함께 확인·소비한다.
- Redis 직렬화, 외부 API, 메시지, 환경 변수 변경은 없다.

## 5. 설계 결정

- 기존 `SmsVerificationPolicy.resendCooldown()`의 60초를 번호·IP 제한 창으로 재사용한다. 번호는 SMS 재발송과 동일하게 창당 1회로 제한한다.
- IP는 기존 공개 그룹 초대 API의 60초당 60회 정책과 `getRemoteAddr()` 신뢰 경계를 따른다. 현재 SMS 발송에는 IP 제한이 없으므로 SMS 흐름 자체는 이 작업에서 바꾸지 않는다.
- 제한 key에는 원문 번호 대신 기존 HMAC을 사용한다. IP는 현재 공개 초대 제한과 같은 방식으로 사용하며 proxy/NAT 사용자는 quota를 공유할 수 있다.
- 제한을 사용자 조회 전에 소비해 존재 여부와 무관하게 같은 비용과 응답 제한을 적용한다.
- 비활성 계정을 찾더라도 `false`를 반환하며 별도 Domain 상태나 Policy를 추가하지 않는다.

## 6. 영향 범위

| 영역 | 영향 |
|---|---|
| API | 새 공개 POST 경로, Request/Response DTO, Swagger |
| Application | 새 UseCase, Command/Result, Service, rate-limit Port |
| Domain | 변경 없음 |
| Persistence | 기존 `LoadUserPort.findByPhoneNumber` 재사용 |
| Redis | 번호·IP 고정 창 quota Adapter와 Lua 추가 |
| Security | 새 경로 `permitAll` |
| Configuration | 기존 SMS cooldown 재사용, 신규 설정 없음 |
| Documentation | 본 Feature Plan |

## 7. 구현 계획과 커밋 경계

1. `docs(auth): 전화번호 가입 여부 확인 설계 기록`
2. `feat(auth): 전화번호 가입 여부 확인 API 추가`
3. `test(auth): 전화번호 가입 여부 확인 계약 검증`

- [x] Migration-only pull request: N/A
- [ ] UseCase와 Command/Result
- [ ] rate-limit Output Port와 Redis Adapter
- [ ] Application Service
- [ ] Web DTO, Controller, Swagger, Security 공개 경로
- [ ] 기존 auth 통합 테스트 보강
- N/A: Domain 모델·Application Policy·DB migration·신규 Infra Properties

## 8. 검증

```bash
./gradlew compileJava
./gradlew test --tests com.amateur.howmuchbackend.adapter.in.web.auth.AuthSignupContractIntegrationTest
./gradlew test --tests com.amateur.howmuchbackend.infra.security.SecurityFilterChainIntegrationTest
git diff --check
git status --short --untracked-files=all
```

PostgreSQL과 Redis가 필요한 통합 테스트는 실행 전 연결 가능 여부를 확인한다. 활성·비활성·미가입, 형식 오류, 번호 제한, IP 제한, 공개 보안 경로와 OpenAPI 계약을 검증한다.

## 9. 진행 및 결정 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견한 위험 |
|---|---|---|---|
| 2026-09-15 | 이슈·최근 PR 관례와 auth/SMS/공개 API 흐름 분석, migration 불필요 확인 | 계약과 구현 추가 | NAT 사용자는 IP quota를 공유하며 trusted proxy 처리는 별도 과제 |

## 10. 완료 기록

- 완료일: 미완료
- 최종 동작: 미완료
- 미검증 항목: 미완료
- 잔여 위험: 미완료
