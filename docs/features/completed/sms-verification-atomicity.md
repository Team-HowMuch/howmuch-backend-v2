# Feature Plan: SMS 인증번호 검증·소비 원자성

- 상태: 완료
- 갱신: 2026-09-09
- 관련 이슈: #297, 상위 #294

## 1. 목적
동일 OTP를 동시에 확인하면 실패 횟수가 유실되거나 소비된 OTP가 복원되고 challenge가 중복 발급된다. 동일 요청의 실패 횟수를 누적하고 최대 시도 도달 후 정답도 차단하며, 소비한 요청 하나만 challenge를 발급한다. 소비·차단 후에도 재발송 가능 시각을 보존한다.

## 2. 도메인 분석
기존 auth 도메인과 ConfirmSmsVerificationUseCase를 사용한다. OTP는 요청 식별자, 코드 해시, 실패 횟수, 만료 시각, 재발송 시각, 소비 여부를 가진다. 활성 → 실패 누적/차단/소비 전이는 동일 상태에서 한 번만 반영된다. 새로운 도메인과 Policy는 불필요하며 최대 횟수는 기존 SmsVerificationPolicy가 전달한다.

## 3. 시나리오
- 정상 코드: 원자적 소비 성공 후 challenge 한 건 저장.
- 오답: 횟수 증가, 한도 도달부터 SMS_VERIFICATION_ATTEMPTS_EXCEEDED.
- 잘못된 요청 식별자: SMS_VERIFICATION_CODE_MISMATCH, 현 요청 횟수 보존.
- 만료·소비: SMS_VERIFICATION_EXPIRED, challenge 발급 없음.
- 동시 실패/성공/재발송: 비교·교체 실패 시 최신 상태를 다시 판정하며 과거 상태를 저장하지 않는다.

## 4. 계약
- Web Request → ConfirmSmsVerificationCommand → ConfirmSmsVerificationUseCase → SmsVerificationConfirmedResult → Web Response 유지.
- SmsVerificationStorePort.verifyAndConsume(phoneHash, requestId, codeHash, maxAttempts): 기술 중립 판정 반환. 실제 호출자는 ConfirmSmsVerificationService.
- Domain SmsOtpState가 판정과 다음 상태를 정의하고 Redis Adapter는 원본 JSON 비교와 TTL 보존을 담당한다.
- POST /api/v2/auth/sms/confirm 경로·입출력·기존 오류 코드 유지.
- Flyway migration required: No. DB 및 외부 SMS API·환경 변수 변경 없음.
- 기존 Redis 키/TTL을 유지하고 JSON에 consumed를 추가한다. 기존 JSON의 누락 필드는 Adapter에서 false로 보완한 뒤 읽는다.

## 5. 설계 결정
Redis Lua compare-and-set으로 읽은 원본 JSON이 동일한 경우만 다음 상태를 KEEPTTL로 저장한다. Lua 실행 시점의 Redis PTTL이 양수일 때만 전이를 허용한다. JVM에서 만든 expiresAt과 Redis 시계를 비교하지 않아 서버 간 시계 차이로 조기 만료되지 않는다. 충돌하면 재조회한다. JVM 잠금은 여러 서버를 보호하지 못하며, Lua 안에 도메인 규칙과 날짜 파싱을 중복 구현하는 방식은 피한다.
소비·차단 상태를 삭제하지 않아 재발송 쿨다운을 유지한다. challenge 저장 실패 시 OTP를 복원하지 않는다(재발송 필요). OTP 소비와 challenge 저장의 장애 시 원자적 복구, 발송 예약 경쟁(F02), CODEF, 다른 인증 흐름 수정은 범위 밖이다. 구버전 서버는 분리된 저장을 수행하므로 배포 시 구버전 인증 트래픽을 종료한 뒤 전환해야 한다.

## 6. 영향 범위
- Domain: SmsOtpState 상태 전이.
- Application: SmsVerificationStorePort, ConfirmSmsVerificationService.
- Redis: SmsVerificationRedisAdapter 원자적 상태 반영.
- API 문서: AuthDocs 일회성·쿨다운 설명.
- 테스트: 실제 Redis와 서비스의 동시성·만료·재시도·쿨다운.
- 유지: 발송 서비스, challenge 구조, HTTP 계약, DB, Infra.

## 7. 구현 및 커밋 계획
1. docs(auth): SMS 검증 원자성 해결 계획 수립
2. fix(auth): SMS 검증과 소비를 원자적 상태 전이로 변경
3. test(auth): SMS 검증 Redis 동시성 회귀 검증 추가
마이그레이션, 새 UseCase/Command/Result, 새 Policy, Web DTO, Infra는 변경 불필요(N/A).

## 8. 검증
Java 21과 격리 Redis를 사용한다. 해당 테스트는 직접 Redis 연결을 구성하여 DB·외부 SMS·운영 자격 증명이 불필요하다. 루트 AGENTS.md와 이슈 완료 조건에 따라 회귀 테스트를 추가한다(상세 workflow의 오래된 테스트 추가 금지보다 우선).
- ./gradlew compileJava
- ./gradlew test --tests '*SmsVerification*Test'
- git diff --check 및 git status --short --untracked-files=all
- 동시 실패 누적, 한도 차단, 성공·실패 경쟁, 중복 challenge 방지, TTL 보존, 요청 교체·만료 경계, 기존 JSON 호환 검증.

## 9. 진행 기록
2026-09-09: gh로 이슈와 기존 PR 관례 확인. 최신 origin/develop에서 fix/sms-verification-atomicity 생성. 구현 전 설계·영향·migration 불필요 확인.

## 10. 결정 기록
2026-09-09: 상태 판정은 Domain, 원자성은 Redis CAS로 분리. 성공·차단 상태를 TTL까지 보존해 쿨다운 유지.

## 11. 완료 기록
- 완료일: 2026-09-09
- 정상/실패/경계 및 도메인·포트·서비스·Redis Adapter 구현 완료. 새 Web 계약·Policy·Infra·migration은 N/A.
- `./gradlew compileJava`: 통과.
- `REDIS_HOST=127.0.0.1 REDIS_PORT=17397 ./gradlew compileJava test --tests '*SmsVerificationRedisIntegrationTest'`: 리뷰 반영 후 15개 통과, 실패·건너뜀 없음.
- 실제 Redis에서 동일 snapshot을 읽도록 latch로 강제한 동시 실패 누적, 8개 실패의 한도 차단, 성공·실패 경쟁, 성공 2개의 challenge 단일 저장 검증.
- 지연 실패와 요청 교체/소비/Redis TTL 만료, 읽기 후 TTL 만료와 JVM 시계 편차, TTL·쿨다운 보존, 쿨다운 이후 재발송, 이전 JSON, challenge 저장 실패 시 복원 금지 검증.
- 초기 9개 테스트 중 기존 JSON 호환 테스트 1개가 실패했다. Jackson 3의 누락 primitive 역직렬화 거부를 확인하고 consumed 누락을 Adapter에서 보완한 뒤 확장한 12개 모두 통과.
- `git diff --check` 및 문서 경로 확인 통과. 전체 테스트·별도 bootRun·외부 SMS 발송·운영 배포는 변경 관련 격리 Redis 검증으로 범위를 제한해 수행하지 않음.
- 남은 한계: challenge 저장 실패 시 재발송이 필요하다. 구버전 인증 서버와 혼합 운영하면 원자성을 보장할 수 없어 구버전 트래픽을 종료하고 전환해야 한다. 발송 예약/발송 실패 정리 경쟁(F02)은 별도 이슈 범위다.


## 12. PR 리뷰 후속 계획
- 2026-09-09: PR #314의 시계 혼용 지적을 수용한다. JVM expiresAt과 Redis TIME의 비교를 제거하고 Lua의 PTTL > 0을 검증·소비의 만료 기준으로 사용한다. 응답용 expiresAt과 기존 키·TTL·쿨다운 구조는 유지한다.
- 영향: SmsVerificationRedisAdapter 및 Redis 회귀 테스트. Flyway migration 불필요.
- 검증: expiresAt을 Redis 시각보다 과거/미래로 이동한 상태에서 TTL 유효 시 성공, TTL 만료 시 정답도 거부, 만료 없는 비정상 키 거부, 기존 동시성 회귀 전체 실행.
- 예상 커밋: fix(auth): SMS 인증 만료 판정을 Redis TTL로 통일
- 결과: 수정 전 시계 편차(-1시간) 및 만료 없는 키 테스트 2개 실패로 문제를 재현했다. 수정 후 compileJava 및 전체 Redis 회귀 15개 통과. git diff --check와 참조 경로 검사 통과. 전체 테스트·bootRun·운영 배포는 후속 수정에서 수행하지 않았다.
