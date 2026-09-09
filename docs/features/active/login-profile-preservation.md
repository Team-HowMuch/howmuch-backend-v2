# Feature Plan: 로그인 시 프로필 변경 보존

- 상태: 설계 완료
- 갱신일: 2026-09-09
- 관련 이슈: #301

## 1. 목적과 완료 조건

로그인이 읽은 User 전체를 저장하면서 그 사이 커밋된 publicId와 avatarFileName이 유실될 수 있다. 삭제된 이전 이미지 참조까지 복원되는 문제를 방지한다.

- [ ] 로그인과 프로필 변경의 교차 실행에서 최신 publicId와 이미지 참조를 보존한다.
- [ ] lastLoginAt과 updatedAt을 갱신한다.
- [ ] 이미지 삭제 후에도 이전 참조가 복원되지 않는다.

CODEF, 이미지 정리 실패 처리, 다른 사용자 수정 경로의 일반적인 동시성 개선은 제외한다.

## 2. 도메인과 시나리오

기존 auth 로그인과 user 영속 경계를 사용한다. LoginUseCase의 PIN 확인·활성 상태 확인 이후 로그인 기록만 저장한다. 프로필 수정은 기존 잠금·저장·커밋 후 이미지 정리 흐름을 유지한다.

로그인이 사용자를 조회 → 별도 프로필 수정 트랜잭션 커밋·기존 이미지 정리 → 로그인 저장 순서에서도 새 프로필이 남아야 한다. 역순 실행에서도 프로필 수정은 로그인 시각을 보존해야 한다. 인증 실패에서는 로그인 기록과 세션 발급을 하지 않는다.

## 3. 계약과 설계

- LoginRequest → LoginCommand → LoginUseCase → TokenResult → TokenResponse 계약은 유지한다.
- `RecordUserLoginPort.recordLogin(Long userId, LocalDateTime now)`를 LoginService가 호출하고 UserPersistenceAdapter가 구현한다.
- JPQL 부분 갱신은 lastLoginAt과 updatedAt만 변경한다. 갱신 대상이 없으면 기존 update의 IllegalArgumentException 의미를 유지한다.
- 기존 User.markLoggedIn은 세션 발급에 넘기는 Domain 값에 사용하되 전체 스냅샷을 저장하지 않는다.
- 새로운 Domain, Policy, Web/Infra 구성은 불필요하다.
- Flyway migration: **No**. 기존 컬럼의 쿼리만 변경한다. Redis 형식과 환경 변수도 변경하지 않는다.

## 4. 결정과 영향

재조회는 JPA 1차 캐시와 재조회 이후 경쟁을 해결하지 못한다. 전체 로그인 구간 잠금 대신 목적별 부분 UPDATE를 사용해 PIN·Redis 처리 중 장시간 행 잠금을 피한다. bulk UPDATE 이후 영속 컨텍스트를 비워 오래된 엔티티의 재사용을 막는다. 기존 푸시 대상 갱신과 같은 방식이다.

영향: LoginService, 신규 Output Port, UserPersistenceAdapter, UserJpaRepository, 기존 로그인 테스트의 생성자 의존성. 일반 SaveUserPort, UserJpaEntity, 프로필 API, 보안 필터, JWT/Redis 계약은 유지한다.

## 5. 구현·커밋 계획

1. `docs(auth): 로그인 프로필 변경 보존 설계 기록`
2. `fix(auth): 로그인 시각만 부분 갱신하여 프로필 변경 보존` — Port·호출·쿼리와 기존 테스트 계약 적응을 함께 커밋한다.
3. `test(auth): 로그인과 프로필 변경 교차 실행 회귀 검증` — 실제 PostgreSQL 교차 트랜잭션과 인증 실패 회귀를 검증한다.

- [x] 설계와 migration 판단
- [ ] Port·Service·Adapter 구현
- [ ] 회귀 테스트 및 컴파일
- [ ] 완료 기록과 PR
- N/A: Migration, Input 계약, Domain/Policy, Web DTO/Swagger, Infra 변경

## 6. 검증

Java 21과 격리 PostgreSQL·Redis를 사용한다. [테스트 실행 지침](../../testing.md)의 합성 환경 설정을 사용하며 실제 외부 발송은 하지 않는다.

- `./gradlew compileJava`
- 관련 로그인·프로필·사용자 영속 테스트
- `git diff --check`, 변경 문서 링크와 `git status --short --untracked-files=all`

## 7. 결과·완료 기록

구현 전. 구버전 로그인 서버는 전체 저장을 수행하므로 배포 전환 시 구버전 로그인 요청을 종료해야 보존을 보장한다.
