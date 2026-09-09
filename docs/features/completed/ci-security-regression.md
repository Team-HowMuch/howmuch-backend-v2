# Feature Plan: CI 테스트 실행과 인증·인가 회귀 검증

- 상태: 완료
- 작성일: 2026-09-09
- 관련 이슈: [#307](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/307)

## 1. 목적과 완료 조건

테스트를 제외한 CI 빌드로 인해 인증·인가 회귀가 이미지 발행과 배포 전에 발견되지 않는다.
전체 Java 테스트와 기존 관리자 JavaScript 테스트를 CI에서 실행하고, 실패하면 이미지 빌드·발행 및 배포가 차단되어야 한다.
실제 SecurityFilterChain, JWT 발급·검증, Redis 세션, DB 사용자 조회를 통해 미인증 401, 유효 토큰 200, 만료·폐기 토큰 401, 관리자 권한 부족 403을 검증한다.
CODEF 구현, 인증 정책 변경, 동시성 감사 항목 F01/F03/F07, 운영 배포는 제외한다.

## 2. 도메인과 시나리오

기존 auth/user/commoncode를 재사용하며 새 도메인을 추가하지 않는다.
Access JWT가 유효해도 Redis의 활성 sessionId와 일치해야 인증된다.
관리자 권한은 CommonCodeAdminService가 DB의 UserRole을 조회해 판단한다.

- 보호 API `/api/v2/user/me`: 헤더 없음·잘못된 서명·만료·Refresh 토큰은 401, 활성 Access 토큰은 본인 프로필 200.
- `/api/v2/auth/logout` 호출 전 200, 세션 삭제 이후 같은 Access 토큰은 401.
- 세션 교체 후 이전 토큰은 401, 새 sessionId 토큰은 200.
- `/api/v2/admin/common-codes/groups`: 미인증 401, USER 403, ADMIN 200.
- 공개 관리자 화면은 미인증 200을 유지한다.

## 3. 계약과 설계

새 UseCase, Command/Result, Output Port, Domain, Policy, Adapter, Web DTO, Infra Bean: 모두 N/A. 기존 구현을 실제 서버로 호출한다.
흐름: HTTP → SecurityFilterChain → JwtAuthenticationFilter → AuthTokenPort/JwtTokenProvider → AuthSessionPort/Redis → Controller → Service → CurrentActorPort 및 LoadUserPort/JPA.
Flyway migration required: **No**. 스키마·Redis 키/TTL·운영 설정 변경 없음.

CI에 PostgreSQL 18/Redis 8 서비스와 health check를 제공한다. 독립 test job 성공을 build의 needs로 요구하고 deploy는 build 성공 및 push 이벤트 조건을 유지한다.
테스트 전용 실행 스크립트는 명시적인 DB·Redis 주소를 요구하고 합성 인증 설정·로컬 외부 API 주소·스케줄 비활성 설정을 사용한다. 기존 @ActiveProfiles("local") 테스트와 호환된다.
테스트 결과 HTML/XML과 JavaScript 결과를 실패 시에도 artifact로 남긴다. 기존 standalone 테스트를 유지하고 새 테스트는 RANDOM_PORT 실제 HTTP 서버로 검증한다.

## 4. 영향 범위

- `.github/workflows/backend-ci-cd.yml`: test → build → deploy 의존성 및 결과 업로드.
- `compose.test.yml`, `scripts/test.sh`: 격리 서비스와 동일 실행 방법.
- `src/test/java/com/amateur/howmuchbackend/infra/security/`: 실제 인증·인가 회귀 테스트.
- `docs/testing.md`: 테스트 실행 및 실패 배포 차단 확인 절차(영문 운영 문서).
- API 응답 및 운영 코드: 변경 없음.

## 5. 구현 및 커밋 계획

1. `docs(ci): 테스트 실행과 보안 회귀 검증 계획 수립`
2. `ci(test): DB와 Redis 기반 테스트 통과 후 이미지 빌드 허용`
3. `test(security): 실제 필터 체인의 인증과 관리자 권한 회귀 검증`
4. `test(settlement): 기존 통합 테스트 fixture를 현재 계약에 맞게 보완`
5. `test(ci): 전체 테스트의 연결 자원과 프로필 설정 격리`
6. `docs(ci): 테스트 실행 절차와 검증 결과 기록`

각 코드 커밋 전 compileJava를 확인한다. 마이그레이션 전용 PR은 N/A.

## 6. 검증 계획

격리 PostgreSQL·Redis readiness 확인 → `./gradlew compileJava` → 전체 `bash scripts/test.sh` → 의도적 임시 JUnit 실패로 비정상 종료 확인 및 파일 제거 → 정상 재검증.
문서 상대 링크·실제 경로, `git diff --check`, `git status --short --untracked-files=all`을 확인한다.
gh로 develop 대상 PR 생성 후 실제 CI 결과와 배포 skip을 확인한다. 운영 배포나 의도적 실패 코드를 병합하지 않는다.

## 7. 진행 및 결정 기록

- 2026-09-09: 최신 origin/develop 45f5ff5 기준 분석. 기존 PR의 type(scope) 한국어 제목 및 develop 대상 흐름 적용.
- 검증 결과: 전체 Java 146개 클래스 800개 테스트 및 Node 31개 테스트 통과.

- 2026-09-09: 전체 테스트에서 기존 SettlementAmountDeserializationTest의 RenameSettlementParticipantUseCase 대역 누락 발견. CI 활성화에 필요한 테스트 fixture 보완을 독립 커밋에 포함한다.

- 2026-09-09: 전체 실행 중 PostgreSQL `too many clients already` 재현. 테스트 JVM의 Spring context cache를 4개로 제한하여 이전 컨텍스트의 연결 풀을 해제한다. 운영 연결 풀 설정은 변경하지 않는다.

- 2026-09-09: 전체 실행에서 기존 0원 초안·음수 수령계좌 ID fixture가 현재 Domain 불변식에 위배됨을 확인해 유효한 값으로 수정한다. 테스트 스크립트의 송금 어댑터 강제 설정은 프로필 기본값 검증을 방해하므로 제거한다.


## 8. 완료 기록

- 완료일: 2026-09-09
- 변경: CI test → build → deploy, 격리 PostgreSQL/Redis 및 안전한 실행 스크립트, 실제 HTTP 보안 테스트 11개, 기존 fixture 누락/불변식 보완, 테스트 컨텍스트 캐시 4개 제한.
- 운영 Java 코드·스키마·보안 정책 변경 없음.
- 최초 전체 실행은 기존 fixture/DB 연결 고갈로 실패했다. 원인 보완 후 `bash scripts/test.sh`의 `clean test`에서 **800개 성공, 실패·오류·skip 0**, Node **31개 성공**을 확인했다.
- `./gradlew compileJava --no-daemon`: 성공. 전체 실행의 compileJava/compileTestJava도 성공.
- 임시 `CiFailureProbeTest`: 실제 JUnit 실패 → 실행 스크립트 exit 1 → Node 미실행 확인. 임시 파일 제거 후 정상 전체 실행 완료.
- YAML 파싱과 test/build/deploy needs 검증, `docker compose -p howmuch-test -f compose.test.yml config --quiet`, `bash -n scripts/test.sh`, 상대 문서 링크, `git diff --check`: 성공.
- 로컬 테스트 인프라: 전용 포트의 native PostgreSQL 18.3 및 Redis 8.2.3. Compose 설정은 구문 검증했고 컨테이너 실행은 GitHub CI에서 확인한다.
- 미수행: 운영 배포, 실제 외부 SMS/CODEF/Firebase 호출, 의도적 실패를 push 이벤트로 배포 브랜치에 올리는 검증. 로컬 실패 전파와 workflow 의존성 검증을 구분한다.
- GitHub CI 링크 및 최종 결과는 PR에 기록한다.
- 실행 절차: [Test infrastructure and CI](../../testing.md).

- GitHub 첫 실행: [34291002521](https://github.com/Team-HowMuch/howmuch-backend-v2/actions/runs/34291002521)에서 Java 800개 중 시간 정밀도 비교 3개 실패, `build`·`deploy` 모두 skipped 확인. 실패 artifact 다운로드 성공. Linux 시계의 nanosecond와 PostgreSQL microsecond 반올림 차이가 원인이므로 timestamp 비교만 1 microsecond 허용하고 나머지 필드는 그대로 비교한다. 후속 커밋: `test(persistence): DB 시간 정밀도에 맞춰 감사 시각 비교`.

- 시간 비교 보완 후 로컬 관련 영속성 테스트 12개와 Node 31개 성공. 최종 전체 실행은 GitHub CI에서 재검증한다.
