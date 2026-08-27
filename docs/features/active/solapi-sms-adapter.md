# Feature Plan: SOLAPI SMS Adapter

- Status: In Progress - 구현 및 컴파일 완료, 실제 SMS 발송 검증 대기
- Last updated: 2026-08-05
- Related issue: N/A
- Related ADR: N/A - 인증 도메인 내부의 단일 외부 연동 구현으로 한정한다.

## 1. Purpose

### Problem

현재 SMS 인증번호 발송은 Discord Webhook 기반 Mock Adapter만 지원한다. 운영 환경에서는 사용자의 휴대폰으로 실제 인증번호를 전달할 수 없어 SMS 인증 흐름을 완료할 수 없다.

### Why now

기존 인증 API와 OTP 생성·검증 흐름을 유지하면서 운영 환경에서 실제 SMS를 발송할 Output Adapter가 필요하다.

### Success criteria

- [ ] `howmuch.auth.sms.sender=solapi`일 때 기존 SMS 인증번호가 SOLAPI를 통해 등록된 발신번호에서 수신자 휴대폰으로 단건 즉시 발송된다.
- [ ] `discord` 설정에서는 기존 Discord Mock Adapter가 그대로 동작한다.
- [ ] SOLAPI 발송 실패 시 성공 응답을 반환하지 않고, 저장된 미발송 OTP 상태를 제거하여 사용자가 즉시 다시 요청할 수 있다.
- [x] API Key, API Secret, 인증번호와 전체 휴대폰 번호를 응답 또는 로그에 노출하지 않는다.

### Out of scope

- LMS, MMS, 카카오 알림톡, RCS, 음성 및 FAX
- 다건·예약 발송
- 재시도 큐, Outbox, 발송 결과 Webhook 및 발송 이력 조회
- 기존 인증 API Request/Response 변경
- OTP 생성, 만료, 재전송 제한 및 확인 규칙 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: `auth`
- Reason a new domain is required, if applicable: N/A - 실제 SMS 전달은 기존 인증 UseCase의 외부 상호작용이다.
- Relationships to other domains: 사용자 생성 전 본인 확인에 사용되지만 `user` 도메인 계약은 변경하지 않는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| SMS 인증번호 | 사용자의 휴대폰 소유를 확인하는 6자리 일회용 코드 | `verificationCode` |
| SMS 발송 포트 | 인증 애플리케이션이 외부 문자 전달을 요청하는 계약 | `SmsSenderPort` |
| SOLAPI Adapter | SOLAPI SDK를 이용해 SMS 발송 포트를 구현하는 Output Adapter | `SolapiSmsSenderAdapter` |
| 발신번호 | SOLAPI에 사전 등록되어 실제 SMS에 표시되는 번호 | `SOLAPI_SENDER` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 인증을 시작하는 사용자 | 휴대폰으로 인증번호를 받는다. | `SendSmsVerificationUseCase` |
| 운영자 | 환경 설정으로 Mock 또는 실제 SMS 발송자를 선택한다. | 런타임 설정 |

### State and invariants

- State transitions: 기존 `SmsOtpState` 생성 및 Redis 저장 흐름을 유지한다. SOLAPI 발송 실패 시 방금 저장한 상태를 삭제한다.
- Rules that must always hold: 인증번호는 기존과 같이 6자리 숫자이며 저장 시 평문이 아닌 Hash만 보관한다. 수신번호는 `IdentityPolicy`가 정규화·검증한 값만 사용한다.
- Invalid states: 발송에 실패한 인증번호가 Redis에 남아 재전송 제한을 발생시키는 상태, SOLAPI 설정이 비어 있는데 SOLAPI Adapter가 활성화된 상태.

## 3. Scenarios

### Normal flow

1. 사용자가 `POST /api/v2/auth/sms/send`로 휴대폰 번호를 보낸다.
2. `SendSmsVerificationService`가 기존 정책으로 입력을 정규화하고 재전송 가능 여부를 확인한 뒤 OTP 상태를 Redis에 저장한다.
3. `SmsSenderPort`가 활성화된 `SolapiSmsSenderAdapter`를 호출한다.
4. Adapter가 `[HowMuch] 인증번호는 123456입니다.` 형식의 단건 메시지를 SOLAPI로 즉시 발송한다.
5. 발송 요청이 성공하면 기존 `SmsVerificationSentResponse`를 반환한다.

### Failure flow

- Condition: SOLAPI가 잘못된 요청, 인증 실패, 빈 응답 또는 발송 실패 예외를 반환한다.
- Returned error or state transition: Adapter가 SOLAPI 세부정보를 노출하지 않는 `SMS_SEND_FAILED` 애플리케이션 예외로 변환한다. Application Service는 저장한 OTP 상태를 삭제하고 오류 응답을 반환한다.
- Condition: `SMS_SENDER=solapi`인데 필수 SOLAPI 환경변수가 비어 있다.
- Returned error or state transition: 애플리케이션 시작 단계에서 명확한 설정 오류로 실패한다.

### Boundary conditions

- 발송 문구는 UTF-8 기준 42바이트로 SOLAPI의 SMS 80바이트 제한 안에 유지한다.
- 동일 번호 재요청의 기존 60초 제한은 성공적으로 발송된 OTP에만 유지한다.
- 발송 요청 성공은 통신사 최종 수신을 보장하지 않는다. 최종 배달 추적은 현재 범위에 포함하지 않는다.

## 4. Contracts

### Input Port

```text
UseCase: SendSmsVerificationUseCase.send
Request: SendSmsVerificationRequest(phoneNumber)
Response: SmsVerificationSentResponse(verificationRequestId, phoneNumber, expiresAt, resendAvailableAt)
```

### Output Ports

| Port | Purpose | Caller | Expected Adapter |
|---|---|---|---|
| `SmsSenderPort.send(phoneNumber, verificationCode)` | 정규화된 휴대폰 번호로 인증번호를 전달한다. | `SendSmsVerificationService` | `SolapiSmsSenderAdapter` |

새 Port를 만들지 않고 기존 `SmsSenderPort`를 그대로 사용한다.

### Web API

```text
POST /api/v2/auth/sms/send
Request: SendSmsVerificationRequest(phoneNumber)
Response: 기존 SmsVerificationSentResponse 유지
Errors: 기존 검증/재전송 오류 + SMS_SEND_FAILED (502)
Authentication: 불필요
```

### Persistence and external integrations

- Database Entity, constraint, or query changes: N/A
- Redis key, TTL, or serialization changes: 기존 `auth:sms:otp:{phoneHash}` 계약 유지. 발송 실패 시 생성한 Key를 삭제한다.
- External API or message changes: `com.solapi:sdk:1.1.0`의 `DefaultMessageService.send(message, null)`로 단건 즉시 발송한다.
- Environment variable or profile changes:
  - `SMS_SENDER=solapi`: SOLAPI Adapter 선택
  - `SOLAPI_API_KEY`: SOLAPI API Key
  - `SOLAPI_API_SECRET`: SOLAPI API Secret
  - `SOLAPI_SENDER`: SOLAPI에 등록된 발신번호

## 5. Design decisions

### Business logic

- Rules and their locations: OTP 생성, 재전송 제한, Hash 및 TTL은 기존 `SendSmsVerificationService`, `SmsVerificationPolicy`, `SmsOtpState`가 계속 소유한다.
- Behavior owned by Domain: 기존 OTP 상태와 만료 규칙을 변경하지 않는다.
- Flow orchestrated by Application: Redis 상태 저장, SMS 발송, 발송 실패 시 Redis 상태 삭제 순서를 `SendSmsVerificationService`가 조정한다.

### Technology and patterns

- Constraint to solve: Java 21/Spring Boot 애플리케이션에서 SOLAPI 인증과 요청 형식을 직접 구현하지 않고 실제 SMS를 발송해야 한다.
- Selected approach: 공식 LLM Guide에 명시된 `com.solapi:sdk:1.1.0`과 Java 단건 발송 패턴을 사용한다.
- Reason for selection: SDK가 API 인증, 요청 직렬화 및 SOLAPI 예외 모델을 제공하므로 기존 `RestClient`로 인증 서명을 직접 구현하는 것보다 코드와 오류 가능성이 작다.
- Alternatives considered: SOLAPI REST API 직접 호출은 인증 서명과 요청 모델을 자체 유지해야 하므로 제외한다. Discord Adapter 교체는 로컬 Mock 경로를 잃으므로 제외한다.
- Trade-offs and limits: SDK 의존성이 추가된다. 현재는 동기 단건 발송만 지원하므로 외부 응답 시간만큼 API 응답이 지연된다. SDK의 `SolapiException`은 Java에서 Catch할 수 있는 예외 기반 클래스가 아닌 sealed interface이므로 `send` 호출 구간의 `Exception`을 Adapter 경계에서 한 번에 변환한다.
- Reference: [SOLAPI Kotlin/Java SDK LLM Guide](https://raw.githubusercontent.com/solapi/solapi-kotlin/main/LLM_GUIDE.md)

구현 형태:

- `infra.solapi.config.SolapiSmsSenderProperties`: Key, Secret, 발신번호 설정 전달
- `infra.solapi.config.SolapiSmsSenderConfig`: 설정 검증 및 `DefaultMessageService` Bean 생성
- `adapter.out.sms.SolapiSmsSenderAdapter`: `SmsSenderPort` 구현과 SOLAPI 예외 변환
- `@ConditionalOnProperty(name = "howmuch.auth.sms.sender", havingValue = "solapi")`: 기존 Discord Adapter와 상호 배타적으로 활성화

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 기존 성공 계약 유지, 발송 실패 오류 추가 | `AuthController`, `AuthDocs` |
| Application | 발송 실패 시 OTP 저장 상태 삭제 | `SendSmsVerificationService` |
| Domain | `SMS_SEND_FAILED` 상태 코드만 추가, OTP 규칙 변경 없음 | `AuthStatusCode`, `SmsOtpState` |
| Persistence | 변경 없음 | N/A |
| Redis | Key/TTL 계약 유지, 발송 실패 보상 삭제 추가 | `SmsVerificationRedisAdapter` |
| Security | Secret은 환경변수로만 주입하고 로그·응답에 노출하지 않음 | `SolapiSmsSenderProperties` |
| Configuration | SOLAPI 설정 및 조건부 Bean 추가 | `application.yaml`, `SolapiSmsSenderConfig` |
| Documentation | 구현 및 검증 결과를 이 Feature Plan에 기록 | 이 문서 |

Existing behavior that must remain unchanged:

- 기본 `SMS_SENDER=discord`에서 Discord Mock 발송이 동작한다.
- `/api/v2/auth/sms/send`는 휴대폰 번호만 받고 기존 Response 형식과 SMS 검증 오류를 유지한다.
- OTP 만료 3분, 재전송 제한 60초, 최대 확인 횟수 5회 설정을 변경하지 않는다.
- 인증번호 평문은 Redis, 데이터베이스 및 로그에 저장하지 않는다.

## 7. Implementation plan

- [x] UseCase and Application Request/Response - N/A, 기존 계약 재사용
- [x] Required Output Ports - N/A, 기존 `SmsSenderPort` 재사용
- [x] Domain models and rules - `SMS_SEND_FAILED` 상태 코드 추가 외 변경 없음
- [x] Application Service - 발송 실패 시 저장한 OTP 상태 삭제
- [x] Output Adapters - `SolapiSmsSenderAdapter` 추가
- [x] Web DTOs, Controllers, and Swagger - 기존 DTO/Controller/성공 계약 변경 없음
- [x] Infra configuration and Bean wiring - SOLAPI Properties, Config, 조건부 Bean 및 환경변수 연결
- [x] Documentation and verification results - 구현 결과와 실행하지 못한 검증 기록

### Expected commit boundaries

실제 Commit은 사용자가 요청한 경우에만 수행한다.

```text
docs(auth): SOLAPI SMS 연동 계획 작성
feat(auth): SOLAPI SMS 발송 어댑터 추가
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test
git diff --check
git status --short --untracked-files=all
```

새 Test Case는 작성하지 않는다. 기존 Test는 PostgreSQL과 Redis 등 선행 조건이 준비된 경우에만 실행한다.

수동 검증 전제조건:

- SOLAPI에 등록된 발신번호
- 유효한 `SOLAPI_API_KEY`, `SOLAPI_API_SECRET`, `SOLAPI_SENDER`
- 실행 가능한 PostgreSQL 및 Redis
- 실제 수신 가능한 테스트 휴대폰 번호와 외부 SMS 발송 비용 승인

수동 검증 절차:

1. 기본 `SMS_SENDER=discord`로 실행하여 기존 Mock 발송 경로가 유지되는지 확인한다.
2. `SMS_SENDER=solapi`와 필수 SOLAPI 환경변수를 설정하고 기존 SMS 발송 API를 1회 호출한다.
3. 수신 메시지의 발신번호와 6자리 인증번호를 확인하고 기존 확인 API로 인증을 완료한다.
4. 잘못된 SOLAPI 자격증명으로 발송 실패 응답과 Redis OTP 상태 삭제를 확인한다.
5. SOLAPI 선택 상태에서 필수 설정을 제거하고 시작 실패 메시지를 확인한다.

### Completion criteria

- [ ] Verified the user outcome defined under Purpose.
- [ ] Verified normal, failure, and boundary flows.
- [x] Confirmed documentation and Swagger match the actual contract.
- [x] Recorded verification that could not be performed and why.

### Results

| Item | Result | Evidence |
|---|---|---|
| Feature Plan 작성 | Complete | `docs/features/active/solapi-sms-adapter.md` |
| Java Compile | Passed | `./gradlew compileJava` - BUILD SUCCESSFUL |
| Existing Tests | Blocked | `./gradlew test` - 로컬 PostgreSQL의 `howmuch` 사용자 인증 실패로 `contextLoads` 중단 |
| 실제 SMS 발송 | Pending | 자격증명, 테스트 번호 및 비용 승인 필요 |

## 9. Progress log

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-05 | 기존 인증 흐름과 SOLAPI SDK Guide를 기준으로 구현 범위와 계약 정의 | Adapter 및 설정 구현 | SMS 발송과 Redis 저장은 원자적이지 않아 발송 실패 보상 삭제 필요 |
| 2026-08-05 | SOLAPI Adapter, 조건부 설정, 발송 실패 상태 코드와 OTP 보상 삭제 구현 및 컴파일 | 실제 SOLAPI 발송 검증 | 기존 Test는 로컬 PostgreSQL 인증 실패로 실행 불가 |

## 10. Decision log

| Date | Decision | Reason | Alternative or impact |
|---|---|---|---|
| 2026-08-05 | 기존 `SmsSenderPort`를 유지한다. | 이미 SMS 외부 경계를 정확히 표현한다. | 새 SOLAPI 전용 Port를 만들지 않는다. |
| 2026-08-05 | Discord Mock과 SOLAPI를 설정으로 선택한다. | 로컬 개발 경로를 유지하면서 운영 발송을 추가한다. | 기본 Provider는 계속 `discord`이다. |
| 2026-08-05 | 공식 SDK의 동기 단건 발송을 사용한다. | 현재 OTP UseCase에 필요한 최소 기능이다. | 배치, 예약, 재시도 및 배달 추적은 제외한다. |
| 2026-08-05 | 발송 실패 시 저장한 OTP를 삭제한다. | 미발송 OTP 때문에 사용자가 재전송 제한에 걸리는 것을 방지한다. | 분산 트랜잭션이나 Outbox는 도입하지 않는다. |

## 11. Completion record

- Completed on: Pending
- Final behavior: `SMS_SENDER=solapi` 설정에서 기존 인증번호 발송 Port가 SOLAPI SDK 단건 발송을 사용하도록 구현됨
- Unverified items: SOLAPI 자격증명을 사용한 실제 SMS 발송, 발송 실패 시 Redis 상태 삭제의 런타임 확인
- Remaining risks: SOLAPI 요청 성공 이후 통신사 최종 전달 실패는 추적하지 않는다.
- Follow-up work: 실제 운영 요구가 생길 때만 발송 결과 Webhook 또는 재시도 정책을 별도 Feature로 검토한다.
