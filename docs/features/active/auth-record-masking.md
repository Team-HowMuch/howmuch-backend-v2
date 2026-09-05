# Feature Plan: 인증 계약 객체의 민감정보 문자열 마스킹

- Status: In Progress
- Last updated: 2026-09-06
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/288
- Related ADR: N/A (기존 계약 유지)

## 1. Purpose

### Problem
인증 record 기본 toString에 자격 증명과 개인정보가 포함됩니다. 실제 유출 사고를 확인한 것은 아닙니다.

### Why now
작은 독립 수정으로 보안 결함 또는 실수에 의한 노출 가능성을 줄인다.

### Success criteria
- [ ] 인증 Request/Response·Command/Result 민감 필드를 ***로 마스킹, 비민감 필드와 JSON·매핑 계약 유지
- [ ] 대상 전체 원문·null 마스킹, JSON 왕복·accessor·equals/hashCode·매핑 회귀
- [ ] PR 게시 및 CI 확인

### Out of scope
입력 제한, 서버 인증 동시성 재설계, Domain·Properties·전역 로깅 변경, 병합·배포.

## 2. Domain analysis
기존 auth와 관리자 화면을 사용한다. 새 Domain·UseCase·Port·Policy는 필요하지 않다.
인증 의미와 JSON 계약을 유지하며 명시된 출력·토큰 발급·클라이언트 로그아웃 동작만 보강한다.

## 3. Scenarios
대상 전체 원문·null 마스킹, JSON 왕복·accessor·equals/hashCode·매핑 회귀. 합성 데이터만 사용하고 실제 SMS·은행 호출은 하지 않는다.

## 4. Contracts
인증 Request/Response·Command/Result 민감 필드를 ***로 마스킹, 비민감 필드와 JSON·매핑 계약 유지
Web Request → Command → UseCase → Result → Response 매핑과 기존 엔드포인트 유지.
Flyway migration required: **No**. SQL·DB·Redis 키/TTL/직렬화·환경변수 변경 없음.

## 5. Design decisions
기존 구현에 국소적으로 반영한다. 새 프레임워크와 운영 의존성을 추가하지 않는다.
대상 전체 원문·null 마스킹, JSON 왕복·accessor·equals/hashCode·매핑 회귀로 실제 결과를 확인한다. 실패·미실행 결과를 성공으로 보고하지 않는다.

## 6. Impact surface
- API/Application: 기존 계약 유지
- Security: 인증 Request/Response·Command/Result 민감 필드를 ***로 마스킹, 비민감 필드와 JSON·매핑 계약 유지
- Persistence/Infra: N/A
- Documentation: 이 계획과 해당 이슈·PR

## 7. Implementation plan
- [x] Migration-only PR: N/A (스키마 변경 없음)
- [x] 새 UseCase/Port/Domain/Policy: N/A
- [ ] 구현 및 관련 테스트
- [ ] 문서·검증 결과 기록

### Expected commit boundaries
1. `docs(auth): 인증 계약 객체의 민감정보 문자열 마스킹 구현 계획 수립`
2. `fix(auth): 인증 계약 객체의 민감정보 문자열 마스킹` — 구현·회귀 테스트·완료 문서

## 8. Verification
- `./gradlew compileJava`
- 변경 관련 테스트, 문서 상대 링크 검사
- `git diff --check`, `git status --short --untracked-files=all`
- 현재 CI는 `clean build -x test`: 로컬 테스트 결과와 구분

### Results
Pending

## 9. Progress log
| 날짜 | 완료 | 다음 |
|---|---|---|
| 2026-09-06 | 최신 develop 기반 독립 fix 브랜치, 이슈 생성, 계획 확정 | 구현·검증 |

## 10. Decision log
| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-09-06 | 이슈·PR 1:1, 상위 이슈 없음 | 독립적 소규모 수정 |

## 11. Completion record
구현·검증 후 기록한다.
