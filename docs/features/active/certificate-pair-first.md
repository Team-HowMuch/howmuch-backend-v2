# Feature Plan: 초기 QR 사용자 연결과 PC 금융 인증

- Status: 구현 중
- Issue: #517 / connector #31
- 기준: 최신 origin/develop c066193a. 기존 netting 작업은 보존하고 격리 worktree 사용.

## 문제와 완료 기준
초기 QR로 모바일 사용자를 PC와 연결한다. 모바일은 대기하고 PC에서 인증서를 선택해 금융사 인증한다. 실패는 양쪽 공통 오류로 표시하며 성공 후 모바일 Yes/No를 받는다. Yes 전에 사용자 DB 연결/금융사/계좌 자격정보를 저장하지 않는다. 기존 사용자의 connectedId 재사용, 기존 QR 클라이언트, Kafka durable 실행·단일 외부 호출·UNKNOWN 및 저장 암호화 형식을 보존한다.

## 설계
기존 certificate registration에 별도 pairing 입구를 추가한다. pairing의 PC/QR capability는 hash만 저장하며 인증된 모바일 actor가 단 한 번 귀속된다. pairing은 금융 명의 확인이나 최종 금융 연결 동의를 뜻하지 않는다. PC pairing capability로 금융사를 지정해 기존 등록 세션을 한 번만 생성한다. 업로드 후 기존 REGISTER Inbox/Outbox를 통해 연결된 사용자로 CODEF create/add/update를 수행한다. 결과 connectedId와 계좌 자격정보는 기존 암호화 provider connection READY에 임시 보관한다. 모바일은 pairing 상태 조회로 대기/오류/승인 가능 상태를 구분하고, 승인 요청 API로 본인의 최종 claim을 예약한다. 기존 decision API의 Yes는 외부 재호출 없이 암호화 결과를 사용자 DB에 원자 저장하고 BOUND로 전환한다. No는 임시 자료를 폐기한다.

기존 connectedId의 금융사 인증 정보를 CODEF에서 add/update하는 외부 효과는 PC 인증 시점에 발생한다. 최종 Yes/No는 우리 DB 귀속 저장에 대한 결정이며 CODEF의 이전 인증자료를 복원할 수 있다는 뜻이 아니다. 기존 연결은 취소 시 삭제하지 않는다. 새 create도 provider가 이전 ID를 재사용할 수 있으므로 pairing 경로 취소는 provider 연결의 안전한 소유권을 증명할 수 없는 상태에서 delete하지 않고 암호화 임시 결과만 폐기한다. 운영상 미귀속 CODEF 인증자료 정리는 후속 reconciliation 대상이다. 이 제한은 문서/PR에 명시한다.

pairing 사용자/등록 세션/기존 연결 snapshot은 변경 불가다. 같은 사용자에게 인증 이후 다른 연결이 만들어졌으면 최종 저장 때 사용자 행 잠금과 snapshot 비교로 충돌을 거절한다. 타 사용자·만료·재스캔·PC capability 불일치는 거절하고 raw 토큰/암호/계좌/connectedId를 로그나 Kafka에 넣지 않는다.

## 계약
- POST /api/v2/certificate-pairings: 초기 QR 및 PC capability(생성 응답만).
- GET/DELETE /api/v2/certificate-pairings/{id}/pc: PC capability 상태/취소.
- POST /api/v2/certificate-pairings/{id}/claim: JWT + qrToken으로 사용자 연결.
- GET /api/v2/certificate-pairings/{id}: JWT 소유자 상태(대기/등록 sessionId/공통 오류).
- POST /api/v2/certificate-pairings/{id}/registrations: PC capability + organization, 기존 create/upload 계약 반환.
- POST /api/v2/certificate-pairings/{id}/approval: JWT 소유자만 인증 성공 후 최종 claim 예약.
- 기존 certificate-claims decision API를 최종 Yes/No에 재사용.

## 영향·SQL·게시
새 certificate_pc_pairings 테이블만 V50으로 추가한다. origin/develop은 V48까지이며 로컬 미게시 V49 Batch 번호를 피한다. 사용자가 SQL-only와 서버 PR을 함께 게시하도록 명시했으므로 root gate의 즉시 종료 대신 별도 SQL/서버 PR을 모두 만든다. 기존 migration 파일은 변경하지 않는다. 서버 PR 검증 checkout에 SQL을 포함하되 서버 커밋에는 포함하지 않는다. 기존 API는 유지하며 새 앱은 서버/SQL 선행 배포가 필요하다. 서버 PR만 게시하고 dev/prod 자동 배포를 실행하지 않는다.

## 커밋·검증
1. SQL-only: feat(certificate): 초기 QR 사용자 연결 저장 구조 추가.
2. 서버: feat(certificate): 초기 QR 연결과 인증 후 사용자 승인 구현.
3. 테스트·문서: test(certificate): 초기 연결 권한과 최종 승인 회귀 검증.
4. connector: feat(certificate): 앱 시작 QR와 금융사 인증 대기 화면 적용.
5. 앱 release: build(certificate): 환경별 인증서 앱 버전 갱신.

실제 PostgreSQL migration/제약/권한·만료·경쟁·멱등, 승인 전 미저장, 오류/거절, 기존 ID 재사용/경쟁 변경, 기존 인증서/Kafka 회귀 및 Java compile/test/bootJar. 앱 client·IPC·renderer 단위/실제 Electron smoke·Windows/macOS packaged startup CI. 실제 사용자 인증자료 사용 및 실제 금융사 요청은 하지 않는다. release는 dev prerelease/prod release를 구분하고 서버 선행 배포 필요성을 명시한다.

## 검증·게시 진행
- 초기 구현과 기존 인증서 focused 회귀 106개 통과(실패/오류/skip 0). 실제 PostgreSQL 및 Kafka HTTP 검증 포함. 이후 기존 ID 재사용/동시 연결 변경/UNKNOWN 격리/초기 QR 만료 경계를 추가했다.
- SQL-only PR: https://github.com/Team-HowMuch/howmuch-backend-v2/pull/518 (55e2e92f). 사용자 요청에 따라 서버 PR도 같은 작업에서 게시한다.
- Connector 앱 PR: https://github.com/Team-HowMuch/howmuch-finance-connector/pull/32. Node 40개 및 실제 Electron 초기 QR/만료 재발급/입력 잠금/PC 인증/최종 승인·6자리 코드·완료/비밀번호 오류 smoke 통과.
- 마지막 전체 회귀 실행 도중 Docker daemon이 종료돼 프로세스가 중단됐다. 기존 XML의 focused 성공을 전체 회귀 성공으로 보고하지 않고 테스트용 DB/Docker 복구 후 다시 검증한다.
