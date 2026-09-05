# Feature Plan: 관리자 로그아웃 시 서버 세션 폐기

- Status: Completed
- Last updated: 2026-09-06
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/286
- Related ADR: N/A (기존 계약 유지)

## 1. Purpose

### Problem
관리자 로그아웃이 브라우저 저장소만 비워 기존 토큰이 서버에서 유효합니다.

### Why now
작은 독립 수정으로 보안 결함 또는 실수에 의한 노출 가능성을 줄인다.

### Success criteria
- [x] 서버 세션 폐기, 만료 토큰의 1회 갱신, 10초 제한, 로컬 정리·실패 안내, 늦은 인증 응답 무시
- [x] Node DOM/fetch 회귀, 격리 PostgreSQL·Redis HTTP·화면 검증
- [x] PR 게시 및 최초 CI 확인

### Out of scope
입력 제한, 서버 인증 동시성 재설계, Domain·Properties·전역 로깅 변경, 병합·배포.

## 2. Domain analysis
기존 auth와 관리자 화면을 사용한다. 새 Domain·UseCase·Port·Policy는 필요하지 않다.
인증 의미와 JSON 계약을 유지하며 명시된 출력·토큰 발급·클라이언트 로그아웃 동작만 보강한다.

## 3. Scenarios
Node DOM/fetch 회귀, 격리 PostgreSQL·Redis HTTP·화면 검증. 합성 데이터만 사용하고 실제 SMS·은행 호출은 하지 않는다.

## 4. Contracts
서버 세션 폐기, 만료 토큰의 1회 갱신, 10초 제한, 로컬 정리·실패 안내, 늦은 인증 응답 무시
Web Request → Command → UseCase → Result → Response 매핑과 기존 엔드포인트 유지.
Flyway migration required: **No**. SQL·DB·Redis 키/TTL/직렬화·환경변수 변경 없음.

로그아웃은 전체 10초 제한·401 시 갱신 1회 후 재시도. 모든 결과에서 토큰/PIN/편집 데이터 정리.
403·5xx·네트워크·시간 초과는 로그인 화면에 서버 로그아웃 확인 실패 안내. 최종 401은 인증 만료.
인증 세대 번호로 이전 로그인·갱신·데이터 응답 반영을 차단한다.

## 5. Design decisions
기존 구현에 국소적으로 반영한다. 새 프레임워크와 운영 의존성을 추가하지 않는다.
Node DOM/fetch 회귀, 격리 PostgreSQL·Redis HTTP·화면 검증로 실제 결과를 확인한다. 실패·미실행 결과를 성공으로 보고하지 않는다.

## 6. Impact surface
- API/Application: 기존 계약 유지
- Security: 서버 세션 폐기, 만료 토큰의 1회 갱신, 10초 제한, 로컬 정리·실패 안내, 늦은 인증 응답 무시
- Persistence/Infra: N/A
- Documentation: 이 계획과 해당 이슈·PR

## 7. Implementation plan
- [x] Migration-only PR: N/A (스키마 변경 없음)
- [x] 새 UseCase/Port/Domain/Policy: N/A
- [x] 구현 및 관련 테스트
- [x] 문서·검증 결과 기록

### Expected commit boundaries
1. `docs(auth): 관리자 로그아웃 시 서버 세션 폐기 구현 계획 수립`
2. `fix(auth): 관리자 로그아웃 시 서버 세션 폐기` — 구현·회귀 테스트·완료 문서
3. `fix(auth): 관리자 인증 요청과 로그아웃 순서 보장` — PR 리뷰의 갱신·로그인 동시 요청 회귀 수정

## 8. Verification
- `./gradlew compileJava`
- 변경 관련 테스트, 문서 상대 링크 검사
- `git diff --check`, `git status --short --untracked-files=all`
- 현재 CI는 `clean build -x test`: 로컬 테스트 결과와 구분

### Results
- `node --check src/main/resources/static/admin/common-codes.js`: 통과.
- `node --test src/test/js/common-code-admin.test.cjs`: 최초 20개, 리뷰 반영 후 31개 통과.
- `./gradlew compileJava bootJar --no-daemon`: 통과.
- `./gradlew test --tests '*CommonCodeAdminControllerTest' --tests '*CommonCodeAdminServiceTest' --no-daemon`: 6개 통과.
- 격리 PostgreSQL에서 Flyway V1~V31 및 Hibernate validate 후 실제 local 서버 기동.
- 실제 브라우저 로그인 → 보관된 Access Token HTTP 200 → 화면 로그아웃 → 동일 Access/Refresh Token 각각 HTTP 401 확인.
- localhost 프록시로 로그아웃 503 주입: 로그인 화면 복귀, PIN 비움, 실패 안내 노출 확인.
- baseline develop의 JS·HTML을 같은 격리 서버에 제공: 기존 화면 로그아웃 후 Access Token HTTP 200 재현.
- 합성 관리자만 사용, 외부 SMS·은행 호출 없음. 임시 서버·프록시·PostgreSQL·Redis 종료 및 합성 자격 증명 파일 정리 완료.
- [Before](../assets/admin-session-logout/before.png): 기존 화면은 서버 폐기 호출·실패 안내 없이 로그인 화면으로 돌아온다.
- [After](../assets/admin-session-logout/after.png): 서버 503 시 로컬 정리 후 폐기 확인 실패 안내를 표시한다.
- 브라우저 검증은 정상·503 두 경로와 baseline 비교이며, 만료 토큰·10초 타임아웃·늦은 응답은 Node 대역 테스트로 검증했다.

- 문서 링크·`git diff --check` 검사 수행. 전체 테스트 스위트·운영 배포는 범위에서 제외.
- GitHub 게시·CI 결과는 연결 이슈와 PR에 기록한다. CI는 테스트를 제외하므로 위 결과와 구분한다.

## 9. Progress log
| 날짜 | 완료 | 다음 |
|---|---|---|
| 2026-09-06 | 최신 develop 기반 독립 fix 브랜치, 이슈 생성, 계획 확정 | 구현·검증 |

## 10. Decision log
| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-09-06 | 이슈·PR 1:1, 상위 이슈 없음 | 독립적 소규모 수정 |

## 11. Completion record
2026-09-06 구현·관련 검증 완료. API·저장 계약과 기존 토큰 호환성을 유지한다.
서버 인증 경쟁 상태 해결·실제 로그 유출 조사·병합·배포는 수행하지 않았다.
PR 게시와 CI 확인은 연결 이슈에서 추적한다.

## 12. PR 리뷰 후속 계획
- #289의 진행 중 갱신과 로그아웃 경쟁, 중복 로그인 응답 순서 지적을 반영한다.
- 같은 화면의 진행 중 갱신을 공유하고, 로그아웃은 그 응답을 기다린 뒤 최신 토큰으로 서버 세션을 폐기한다. 갱신 결과는 브라우저에 다시 저장하지 않는다.
- 기존 10초 제한에 갱신 대기도 포함한다. 대기 시간 초과·갱신 실패는 로컬 정리 후 서버 폐기 확인 실패로 안내한다.
- 로그인 요청 처리 중 폼을 비활성화하고 중복 submit을 무시한다. 완료 시 로그아웃 진행 상태를 고려해 폼을 복구한다. 갱신 대기가 10초를 넘으면 로컬 정리와 안내는 즉시 완료하되, 같은 화면의 미완료 갱신 응답이 도착할 때까지 새 로그인을 차단한다.
- Node 대역으로 서버 세션 저장→삭제 순서, 갱신 공유, 대기 시간 초과·오류, 중복 로그인·재시도를 검증한다.
- 서버의 Redis 원자성, 다른 탭·클라이언트의 갱신, 응답을 확인할 수 없는 요청의 서버 처리까지 보장하지 않는다. Flyway·API·Java 변경은 없다.

### 후속 검증 결과
- 최초 PR 코드에서 두 리뷰의 회귀 테스트가 각각 실패함을 확인했다(갱신 완료 전 폐기 호출, 중복 로그인 HTTP 2회).
- 수정 후 Node 회귀 31개 및 JS 구문 검사 통과. 대기 중 갱신의 세션 저장→로그아웃 삭제 순서, 갱신 공유, 시간 초과 후 늦은 응답 무시, 로그인 중복 제출·재시도·폼 잠금 복구를 포함한다.
- `./gradlew compileJava test --tests '*CommonCodeAdminControllerTest' --tests '*CommonCodeAdminServiceTest' --no-daemon`: 통과, 기존 Java 6개 통과. DB·Redis를 요구하지 않는 기존 단위 테스트만 실행했다.
- 후속 변경은 Node DOM/fetch 대역으로 검증했으며, 실제 브라우저·PostgreSQL·Redis 검증은 최초 구현 때의 결과이고 이번에는 재실행하지 않았다.
