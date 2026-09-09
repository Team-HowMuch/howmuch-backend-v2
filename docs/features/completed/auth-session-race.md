# 토큰 갱신 세션 경쟁 조건 수정

- 상태: 완료
- 작성일: 2026-09-09
- 관련 이슈: #295 (상위 #294)

## 목적과 완료 조건

기존 세션을 검증한 갱신 요청이 뒤늦게 저장하면서 로그아웃한 세션을 복원하거나 새 로그인 세션을 덮어쓰는 문제를 해결한다.

- [x] 로그아웃 완료 후 지연된 갱신은 INVALID_TOKEN으로 실패하며 세션을 복원하지 않는다.
- [x] 새 로그인 완료 후 이전 갱신은 실패하며 새 세션과 TTL을 보존한다.
- [x] 같은 refresh token의 동시 회전은 하나만 성공하고 나머지는 INVALID_TOKEN으로 실패한다.
- [x] 만료된 세션은 갱신으로 복원하지 않는다.

## 도메인과 시나리오

기존 auth 도메인과 RefreshTokenUseCase를 사용한다. 로그인은 새 sessionId를 발급해 사용자 세션을 교체하며, 회전 갱신은 자신이 검증한 세션만 교체할 수 있다. LogoutService는 사용자 세션을 삭제한다.

정상 흐름은 JWT 파싱 → 세션·사용자 검증 → 새 토큰 생성 → 조건부 세션 교체 → TokenResult 반환이다. 검증 이후 로그아웃·새 로그인·다른 갱신·만료가 먼저 발생하면 저장하지 않고 INVALID_TOKEN을 반환한다. 충돌 시 재시도하지 않는다.

## 계약과 설계

- Web Request → RefreshTokenCommand → RefreshTokenUseCase → TokenResult → TokenWebResponse 흐름 유지.
- AuthSessionPort.save는 새 로그인 저장용으로 유지하고, rotate(expected, replacement)를 추가한다. AuthTokenService가 실제 호출하고 AuthSessionRedisAdapter가 구현한다.
- Redis Lua 한 번으로 유효 TTL 및 검증한 세션의 필드 일치를 확인하고 교체한다. 새 만료 시각을 PXAT으로 지정해 실행 지연이 수명을 연장하지 않게 한다.
- sessionId·refreshToken뿐 아니라 expiresAt도 비교한다. 사용자 식별자는 같은 Redis key와 Java의 동일 사용자 검사로 제한한다.
- Application의 AuthSessionPolicy와 Domain의 AuthSession 검증 책임 유지. Infra나 새 Policy는 필요 없다.
- JVM 락은 여러 서버 사이 경쟁을 막지 못하므로 사용하지 않는다. Redis key·JSON 구조는 유지한다.
- Flyway migration required: No. DB Entity·쿼리·스키마 변경 없음.
- HTTP API·오류 코드·설정·로그인 정책 유지. Swagger에 갱신 충돌 실패 조건을 설명한다.
- 회전 비활성 정책은 기존처럼 저장 없이 Access Token만 반환한다. 토큰 재사용 탐지, 로그아웃과 로그인 자체의 순서 정책, CODEF는 범위 밖이다.

## 영향 범위와 실행 순서

1. AuthSessionPort, AuthTokenService, AuthSessionRedisAdapter의 갱신 계약과 구현을 함께 변경한다.
2. AuthDocs의 설명과 기존 JwtTokenProviderTest 메모리 저장 대역을 새 계약에 맞춘다.
3. 실제 JWT Provider·Service·Redis를 연결한 동시성 회귀 테스트를 추가한다. 사용자 조회만 대역으로 사용한다.
4. 컴파일·관련 테스트·문서 경로·diff 검사를 수행하고 완료 문서를 이동한다.

새 UseCase·Command/Result·Domain·Policy·DB migration·Infra 구현은 해당 없음: 기존 계약과 구성을 재사용한다.

### 커밋 경계

1. docs(auth): 토큰 갱신 세션 경쟁 조건 해결 계획
2. fix(auth): 검증한 세션에 한해 토큰 갱신 원자적 반영
3. test(auth): 토큰 갱신 세션 경쟁 회귀 검증

## 검증 계획

Java 21과 격리 Redis를 사용한다. 선택한 테스트는 Spring Context·PostgreSQL·외부 SMS가 필요 없다. 읽기 이후 사용자 조회 지점에서 latch로 갱신을 멈춰 로그아웃·로그인을 완료한 뒤 재개한다. 동일 토큰 동시 갱신은 모든 요청이 기존 세션을 읽은 뒤 진행시켜 승자 수와 실패 코드를 검증한다. 정상 회전, 이전 토큰 거부, TTL 만료 및 잘못된 교체 상태도 확인한다.

```bash
./gradlew compileJava
REDIS_HOST=127.0.0.1 REDIS_PORT=17395 ./gradlew test --tests '*AuthSessionRedisIntegrationTest' --tests '*JwtTokenProviderTest'
git diff --check
git status --short --untracked-files=all
```

## 결정과 남은 위험

- 구버전 서버는 무조건 저장하므로 해당 서버의 인증 트래픽을 종료한 뒤 전환해야 보장된다.
- Redis 원자적 교체 이후 로그아웃이 발생하면 이미 생성한 토큰도 세션 검사에서 거부된다.
- 전체 테스트·실제 HTTP·운영 배포는 실행하지 않았다. 변경 경계인 Service·JWT·Redis를 직접 검증했다.

## 검증 결과

- `./gradlew compileJava` 통과.
- Java 21·격리 Redis 8.2.3에서 AuthSessionRedisIntegrationTest 13개와 JwtTokenProviderTest 5개 통과.
- 원본 develop의 AuthTokenService로 같은 테스트를 실행하여 로그아웃·새 로그인·만료 이후 복원 및 중복 갱신 시나리오 4개가 모두 실패함을 확인했다. 이후 수정 코드를 복원했다.
- 기존 JWT/세션 저장 계약, 회전 시 sessionId 유지·변경 정책, 비회전 시 저장/TTL 불변, 만료된 교체 입력, 다른 사용자 교체 거부, 저장 상태 불일치·손상 JSON·무기한 TTL 차단을 검증했다.
- 로그아웃은 실제 LogoutService와 Redis를 사용했다. 새 로그인 경쟁은 LoginService가 호출하는 issueNewSession 경계로 검증했으며 PIN·DB 로그인 전체 흐름은 실행하지 않았다.
- `git diff --check`와 문서 참조 경로 확인 통과. 추가 상대 링크 없음.
- PostgreSQL·전체 Spring Context·별도 bootRun·실제 HTTP·운영 배포는 실행하지 않았다.

## 완료 기록

- 완료일: 2026-09-09
- 정상 갱신은 검증한 세션만 원자적으로 교체한다. 충돌한 갱신은 저장과 토큰 반환 없이 기존 INVALID_TOKEN(401)으로 실패한다.
- 계획 → 구현과 기존 테스트 적응 → 경쟁 회귀 검증의 3개 커밋으로 분리했다.
- 구버전 서버의 인증 트래픽 종료가 배포 전제다.
