# Feature Plan: 동시 PIN 로그인 시도 제한

- 상태: 완료
- 갱신일: 2026-09-09
- 관련 이슈: #300 (상위 #294)

## 1. 목적과 완료 조건

동일 전화번호의 요청이 한도 직전 카운터를 함께 읽으면 모두 PIN 검증에 진입한다. 검증 전에 허용량을 원자적으로 확보하여 추측 가능한 PIN 수를 제한한다.

- [x] 동시 요청의 PIN 검증 수가 남은 허용량 이하이다.
- [x] 성공 초기화, 한도 차단, 잠금 만료 후 재시도가 일관된다.
- [x] 지연 성공·실패가 초기화/만료 이후 새 제한 상태를 변경하지 않는다.
- 제외: SMS, CODEF, 세션 정책 변경, DB 변경.

## 2. 도메인과 시나리오

기존 auth의 LoginUseCase를 유지한다. 전화번호는 IdentityPolicy로 정규화하고 HMAC한 값만 Redis key에 사용한다.

시도는 PIN 검증 진입 전에 소비한다. 동일 제한 세대에서 진행 중 요청도 한도에 포함한다. 실패는 추가 쓰기 없이 확보한 시도 번호로 기존 오류를 반환한다. 성공은 자신의 세대가 남아 있을 때만 초기화한다. 만료/성공 후 새 시도는 새 세대가 된다.

한도 미만 실패는 INVALID_LOGIN_CREDENTIALS, 마지막 실패와 진입 거부는 PIN_ATTEMPTS_EXCEEDED이다. 형식 오류는 소비하지 않는다. 없는 사용자, 비활성 사용자, 조회/검증 중 예외는 확보한 시도를 반환하지 않는다. 검증 여부가 불확실한 요청을 환급하여 한도를 우회하지 않도록 보수적으로 처리한다.

## 3. 계약과 저장

- 기존 LoginRequest → LoginCommand → LoginUseCase → TokenResult → TokenWebResponse 유지.
- LoginAttemptStorePort: reserve(phoneHash, maxAttempts, lockDuration)는 Optional<LoginAttempt> 반환. resetAfterSuccess(phoneHash, generation)은 해당 세대만 제거.
- LoginAttempt: generation과 attemptCount를 담은 Application record.
- PinPolicy의 maxAttempts/lockDuration 재사용. 별도 Policy/Infra 추가 없음.
- Redis auth:login:attempt:<phoneHash>: 기존 숫자를 generation:count 문자열로 전환한다. 기존 숫자와 TTL을 읽어 잠금을 보존한다. 단일 key Lua로 판정·소비를 수행한다.
- 첫 시도와 마지막 허용 시도 확보 시 lockDuration을 설정한다. 중간 시도/거부는 TTL을 연장하지 않는다. TTL 없는 상태·손상 값은 차단한다.
- Flyway migration required: No. JPA/SQL 변경 없음.
- API 경로·입출력·오류 코드·환경변수 유지. Swagger에 동시 시도 제한 설명 추가.

## 4. 결정과 영향

선확보 방식은 외부 PIN 해시 검증을 Redis 내부에서 수행하지 않고 진입 수를 보장한다. 분산 mutex의 임대 만료/갱신 관리 대신 시도 자체를 소비한다. 실패 시 증가 방식과 달리 진행 중·예외 요청도 포함하며, 잠금 시작은 마지막 시도의 검증 진입 시점이다.

세대 식별자는 성공 뒤 늦은 성공 또는 만료 전 요청이 새 카운터를 지우는 것을 방지한다. 실패 완료에는 쓰기가 없으므로 늦은 실패가 삭제된 상태를 복원하지 않는다. 구버전 서버는 새 문자열을 이해하지 못하므로 구버전 로그인 트래픽을 종료한 뒤 전환해야 한다.

영향: LoginService, LoginAttemptStorePort, LoginAttemptRedisAdapter, Application LoginAttempt record, AuthDocs, 실제 Redis 기반 회귀 테스트. 다른 저장소·세션 발급·사용자 상태 전이는 기존 계약을 유지한다.

## 5. 구현 및 커밋 경계

1. docs(auth): 동시 PIN 로그인 제한 설계 기록
2. fix(auth): PIN 검증 전 시도 횟수 원자적 확보
3. test(auth): 동시 PIN 로그인 제한 회귀 검증

UseCase/Command/Result, Domain, Policy, Infra 변경은 N/A (기존 계약 재사용). Output Port와 실사용 Service/Adapter는 함께 커밋한다.

## 6. 검증

실제 격리 Redis와 LoginService를 연결하고 사용자 저장소/PasswordEncoder/토큰 서비스만 대역으로 둔다. PostgreSQL 및 Spring Context는 해당 테스트에 불필요하다. 전체 테스트는 별도 DB 환경이 필요하다.

- ./gradlew compileJava
- REDIS_HOST=127.0.0.1 REDIS_PORT=17400 ./gradlew test --tests '*LoginAttemptRedisIntegrationTest'
- 한도 직전 동시 요청, 최초 동시 요청, 성공 초기화, 성공/실패 지연, 실제 TTL 만료, legacy 카운터/잠금, 예외/비활성/없는 사용자 검증.
- git diff --check 및 문서 경로 확인.

## 7. 진행 및 완료 기록

- 2026-09-09: #300과 기존 PR #314–#316 확인. 최신 origin/develop에서 fix/pin-login-attempt-limit 분기.
- 2026-09-09: Service와 실제 Redis를 연결한 신규 회귀 16개, 기존 세션 경쟁/JWT 18개로 총 34개 통과. compileJava 통과.
- 한도 직전 8개 요청 중 1개만 PIN 검증에 진입하고, 나머지는 해당 검증이 끝나기 전에 차단되는 것을 latch로 확인했다. 빈 카운터에서는 최대 5개만 진입한다.
- 성공/실패의 지연 완료, 실제 TTL 만료와 새 세대 보호, legacy 숫자 카운터/TTL, 잠금 중 무변경, 손상/무기한 상태, 형식 오류·없는 사용자·비활성 사용자·검증 예외를 확인했다.
- git diff --check 통과. 문서에 새 상대 링크는 없으며 참조한 구현 파일 경로를 확인했다.
- 전체 DB 의존 테스트와 bootRun/실제 HTTP는 로컬에서 미실행. 변경 경계의 Service/Redis 통합 검증을 수행했다.
- 배포 제약: 구버전 로그인 트래픽 종료 후 전환 필요. 검증 진입 이후 예외도 시도로 소비한다. 운영 배포는 수행하지 않는다.
