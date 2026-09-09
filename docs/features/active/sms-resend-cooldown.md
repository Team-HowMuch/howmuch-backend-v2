# Feature Plan: SMS 재발송 제한 우회 방지

- 상태: 구현 전 설계 완료
- 갱신일: 2026-09-09
- 관련 이슈: #296 (상위 #294), 선행 PR #314 / #297

## 1. 목적 및 성공 조건

SMS 발송 직후 실패 한도를 소진해도 기존 쿨다운 동안 재발송을 차단한다. #314에서 검증 종료 상태 보존은 반영되었으나, 발송의 조회/저장 분리와 무조건 삭제는 동시 발송 및 지연 실패에서 제한을 우회할 수 있다.

- [ ] 발송 → 실패 한도 소진 → 즉시 재발송을 차단한다.
- [ ] 쿨다운 경과 후 새 OTP를 발송하고 검증할 수 있다.
- [ ] OTP 만료·삭제·소비와 무관하게 재발송 제한을 유지한다.
- [ ] 동시 발송은 하나만 예약하며 이전 발송 실패가 새 OTP를 삭제하지 않는다.

CODEF, 일일/IP 제한, 인증 외 도메인, 실제 SMS 발송·배포는 제외한다.

## 2. 도메인과 시나리오

기존 auth 도메인을 재사용한다. 비인증 사용자가 SendSmsVerificationUseCase로 발송하고 ConfirmSmsVerificationUseCase로 소유를 확인한다. OTP는 검증 수명, cooldown은 재발송 제한 수명이다. 성공·실패 한도 소진·발송 실패로 cooldown을 해제하지 않는다. 발송 오류는 공급자가 이미 전달했을 가능성이 있으므로 원래 제한까지 재시도를 기다린다.

정상: 번호 정규화/HMAC → 발송 예약 → SMS 전달 → 확인. 제한 중 예약 실패는 SMS_RESEND_TOO_SOON이며 외부 SMS를 호출하지 않는다. 전달 오류는 원래 예외를 유지하고 해당 요청의 OTP만 제거한다.

## 3. 계약 및 설계

- Input Port, Command/Result, Web DTO, HTTP 경로와 오류 코드는 유지한다.
- SmsVerificationStorePort에 tryReserve(phoneHash, state, otpTtl, cooldown)와 discardRequest(phoneHash, requestId)를 정의한다. 호출자는 SendSmsVerificationService, 구현은 SmsVerificationRedisAdapter이다. 분리 조회/저장/무조건 삭제를 Application 계약에서 제거한다.
- Redis Lua로 cooldown 확인 및 OTP/cooldown 저장을 한 번에 수행한다. OTP 검증은 #314의 CAS와 기존 TTL을 유지한다.
- cooldown key: auth:sms:cooldown:<HMAC>, 값: verificationRequestId, TTL: resendCooldown. OTP key/JSON/TTL은 유지한다.
- 이전 JSON만 남은 상태는 원본 CAS 하에 Redis PTTL - (expiresAt - resendAvailableAt)으로 남은 제한을 복구한다. JVM 현재 시각을 비교하지 않으며, 소비·실패 상태에도 적용한다. 만료 없는 OTP는 예약을 거부한다. 읽을 수 없는 JSON은 오류로 처리해 제한을 우회하지 않는다.
- Domain의 기존 verify 상태 전이는 유지한다. 새로운 Policy나 Infra 설정은 필요 없다. 기존 SmsVerificationPolicy의 기간을 전달한다.
- Flyway migration: **No**. PostgreSQL 구조·쿼리·환경 변수·SMS 메시지는 변경하지 않는다.
- Redis 단일 인스턴스 기준이다. 두 key의 Lua 사용은 현재 구성에 맞으며 Redis Cluster 도입 시 동일 hash slot 설계가 필요하다.
- 구버전 서버는 cooldown key를 모르므로 구버전 인증 트래픽 종료 후 전환한다. 기존 시각 필드와 OTP TTL 차이로 이전 제한을 복구하므로 기존 저장 계약의 TTL 일치를 전제한다.

## 4. 영향 범위

| 영역 | 변경 |
|---|---|
| Application | SendSmsVerificationService, SmsVerificationStorePort 예약/정리 계약 |
| Redis | SmsVerificationRedisAdapter의 원자적 예약, 별도 cooldown TTL, 요청 조건부 삭제 |
| 검증 | SmsVerificationRedisIntegrationTest 실제 Redis 회귀 |
| API/Domain/DB/Infra | 변경 없음 |
| 문서 | 본 계획 및 검증 결과 |

## 5. 구현 단계와 커밋 경계

1. docs(auth): SMS 재발송 제한 보존 설계 기록
2. fix(auth): SMS 재발송 예약과 제한 수명 분리
3. test(auth): SMS 재발송 제한 우회 회귀 검증

- [x] 설계·영향 분석 및 migration 불필요 확인
- [ ] Output Port, Service, Redis Adapter 구현
- [ ] 회귀 테스트 및 문서 완료 후 completed로 이동
- N/A: 새 UseCase·Command/Result·Domain·Policy·Web·Infra·migration — 기존 계약 재사용

## 6. 검증

Java 21과 격리 Redis를 사용한다. 이 테스트는 Spring Context/PostgreSQL 및 외부 SMS 없이 실제 Redis Adapter와 Service를 검증한다. 상위 AGENTS.md의 변경 동작 테스트 요구에 따라 테스트를 추가한다(하위 개발 문서의 이전 테스트 금지 문구보다 우선).

```bash
./gradlew compileJava
REDIS_HOST=127.0.0.1 REDIS_PORT=17396 ./gradlew test --tests '*SmsVerificationRedisIntegrationTest'
git diff --check
git status --short --untracked-files=all
```

실패 한도·성공 소비·OTP 만료 이후 제한 유지, 제한 만료 이후 새 코드 검증, 동시 발송, 지연 발송 실패, 기존 JSON 호환과 기존 #314 회귀를 확인한다. 전체 테스트·bootRun·실제 SMS·배포는 범위 밖이다.

## 7. 진행 및 결정 기록

- 2026-09-09: 최신 develop의 #314를 확인하고 중복 검증 구현 대신 발송 경계와 독립 cooldown을 보완하기로 결정.
- 구현/검증 결과: 대기.
