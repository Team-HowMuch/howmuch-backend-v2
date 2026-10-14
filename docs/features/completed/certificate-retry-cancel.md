# Feature Plan: 인증서 재선택 및 종료 취소
- Status: 구현 및 로컬 검증 완료
- Updated: 2026-10-14

## 목적
확정된 인증 실패 후 PC에서 휴대폰 연결을 유지하며 인증서를 재선택한다. PC 정상 종료는 취소 응답을 기다리고, 모바일 종료 시 소유자 JWT로 취소할 API를 제공한다. 모바일 소스는 사용자가 추후 전달하므로 이번 작업에서 모바일 lifecycle 코드는 제외한다.

## 계약과 불변식
- 기존 `POST /api/v2/certificate-pairings/{id}/registrations`: 기존 session이 미승인 FAILED일 때만 새 등록을 만들어 동일 pairing의 session 링크를 교체한다. UNKNOWN/진행 중/승인된 세션은 재시도하지 않는다.
- 추가 `DELETE /api/v2/certificate-pairings/{id}`: JWT 소유자만 취소, 다른 사용자/미연결 pairing은 404. 반복 취소는 멱등.
- PC capability 경계는 유지하며 소유자 검증과 기존 cancelSession의 승인/UNKNOWN/consent 보호·암호문 폐기·CANCEL outbox 처리를 재사용한다.
- pairing → session 순서로 잠금을 잡아 새 등록/취소 경쟁을 직렬화한다.
- 완료된 금융 연결은 삭제하지 않는다. 단순 앱 종료는 외부 CODEF 인증 결과 롤백을 뜻하지 않는다.
- PC renderer는 PAIRED 이후에도 pairing 취소/만료를 감시한다.
- 강제 종료/네트워크 단절로 종료 요청 자체가 안 온 경우 즉시 취소는 보장하지 않으며 기존 세션 만료를 사용한다. 자동 감지 heartbeat는 모바일 연동 후 별도 설계한다.

## 영향과 migration 판단
Application Service/기존 UseCase, Web Controller/Docs, HTTP 통합 테스트 및 Electron UI/client/main. DB 컬럼·Redis 구조·wire contract 변경 없음. Flyway: V51 필요, SQL-only PR #523 병합 완료. 새 Output Port·Domain·Policy는 N/A(기존 session 불변식/트랜잭션 재사용).

## 작업과 검증
확정 실패 재시도·UNKNOWN/진행 중 거절·JWT 소유권/반복 취소 HTTP 통합 테스트, compileJava/대상 테스트, diff/status. 기존 backend 작업 폴더의 변경은 건드리지 않고 최신 origin/develop의 별도 worktree 사용.
커밋 경계: FAILED 재시도(Adapter/Service), 모바일 취소(UseCase/Web/Transactions), 통합 테스트·검증 문서. 각 구현 커밋은 compileJava로 검증한다. 사용자 후속 요청에 따라 커밋/푸시 및 develop 대상 PR을 게시한다. 서버 배포는 PR 병합 이후 진행한다.

## 구현 결과
- SQL-only PR #523의 V51을 반영한 develop 기반에서 후속 개발했다. 추가 migration은 없다.
- Adapter.link는 FAILED 링크 교체와 인증 준비 컨텍스트 초기화를 지원한다. DB trigger는 기존 소유권/승인/consent 보호를 유지한다.
- 모바일 소유자 ID를 값으로 비교한다. 모바일 취소는 기존 cancelSession의 암호문 폐기 및 CANCEL outbox를 재사용한다.
- HTTP 통합 테스트에 진행 중/UNKNOWN 재시도 거절, 실패 재시도, 익명/다른 사용자 취소 거절, 소유자 멱등 취소, BOUND 취소 거절을 추가했다.
- 테스트별 Redis 요청 제한 키를 초기화하여 동일 IP 요청 예산을 테스트 간 공유하지 않는다. 전용 Testcontainers Redis에만 적용된다.

## 모바일 인계
화면 뒤로가기·연결 취소·정상 종료에서 JWT로 DELETE /api/v2/certificate-pairings/{id}를 호출한다. 요청 body는 없다. 실패 후에도 pairing을 유지하고 GET 결과의 최신 sessionId를 사용한다. 확정 실패 재시도는 PC에서 수행한다. 모바일 lifecycle 구현은 소스 전달 후 진행한다. 강제 종료는 HTTP 전송이 보장되지 않으므로 기존 만료를 사용한다.

## 검증 기록
compileJava 및 CertificateHttpIntegrationTest 10개 통과(실제 PostgreSQL/Redis/Kafka). 첫 실행의 테스트 간 공유 요청 제한 429는 테스트 격리 후 해결했다. 테스트 종료 시 컨테이너가 먼저 내려가며 scheduler 연결 경고가 발생하지만 최종 BUILD SUCCESSFUL/실패 0건이다. 양쪽 저장소 git diff --check 통과. PC Node 테스트 41개 및 실제 Electron smoke 통과. 실제 CODEF/모바일 lifecycle 및 서버 배포는 수행하지 않았다. 관련 이슈 #525, 선행 SQL PR #523.
