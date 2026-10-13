# Feature Plan: 초기 QR 사용자 연결과 PC 금융 인증

- Status: 구현·전체 검증·PR 및 앱 release 게시 완료
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
V50에서 certificate_pc_pairings 테이블과 등록 세션 capability를 검증하는 복합 UNIQUE/FK 및 사용자 귀속 제약을 추가한다. origin/develop은 V48까지이며 로컬 미게시 V49 Batch 번호를 피한다. 사용자가 SQL-only와 서버 PR을 함께 게시하도록 명시했으므로 root gate의 즉시 종료 대신 별도 SQL/서버 PR을 모두 만든다. 기존 migration 파일은 변경하지 않는다. 서버 기능 커밋에는 SQL을 섞지 않는다. 원격 CI에서도 검증하도록 SQL PR의 동일 선행 커밋 위에 서버 브랜치를 쌓는다. 두 PR 모두 develop을 대상으로 하며 SQL PR 병합 후 서버 PR의 SQL diff는 자동으로 사라진다. 기존 API는 유지하며 새 앱은 서버/SQL 선행 배포가 필요하다. 서버 PR만 게시하고 dev/prod 자동 배포를 실행하지 않는다.

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

- 전체 232 suites/Java 1,763개 실행에서 `uncertainAuthenticationIsQuarantinedAndCannotBeRepeatedWithAnotherPairing` 한 건이 실패했다. 외부 호출 전 차단 결과에 BIND 전용 `CONNECTION_CONFLICT`를 사용해 REGISTER result 제약에 거절되는 문제였다. 기존 허용 코드 `CONTRACT_CONFLICT`로 수정했으며 전체 재검증 중이다.
- 앱 첫 CI는 변경된 h1/초기 탐색 대기 정책을 패키징 smoke에 반영하지 않아 실패했고 Intel 가상 러너는 GPU 초기화가 실패했다. 새 첫 화면·preload·입력 잠금을 검증하도록 수정하고 테스트 실행에만 소프트웨어 렌더링을 지정했다. 실제 macOS arm64 ad-hoc 패키지의 API origin·서명·startup 검증 통과. 0.1.7 릴리스는 빌드 중단 표시, 수정본은 0.1.8-rc.1 / 0.1.8로 게시한다.
- 서버 PR: https://github.com/Team-HowMuch/howmuch-backend-v2/pull/519. 원격 CI 진행 중. 서버 merge/deploy는 실행하지 않았다.

## 최종 로컬 검증
- `bash scripts/test.sh compileJava test bootJar`: **232 suites / Java 1,763개, JavaScript 35개 통과**, 실패/오류/skip 0. 실제 PostgreSQL V50/불변 제약, JWT HTTP→초기 pairing→Kafka 인증→임시 READY→최종 Yes→BOUND, 기존 ID 재사용·No 보존·동시 연결 변경 거절·UNKNOWN 재인증 차단·QR 만료를 포함한다. `build/pair-final-verification.log` 및 `build/test-results/test`에 결과를 보관한다.
- Connector는 일회용 별도 DB에서 `./gradlew clean build --no-daemon` 성공, Java 25개 실패 0. 앱 Node 40개/실제 Electron smoke 및 macOS arm64 실제 패키지의 임시 서명·API origin·sandboxed preload·첫 화면 검증 통과.
- QR claim Web DTO의 `toString()`도 토큰을 가려 기존 민감 DTO 규약을 적용했다. 이후 compileJava로 최종 소스를 확인한다.
- SQL #518 원격 테스트·Docker 빌드 통과. 기능 #519/앱 #32를 게시했다. dev/prod 서버 배포와 실제 모바일 UI/실은행 E2E는 실행하지 않았다.

## 릴리스 완료
- dev: https://github.com/Team-HowMuch/howmuch-finance-connector/releases/tag/certificate-v0.1.8-rc.1
- prod: https://github.com/Team-HowMuch/howmuch-finance-connector/releases/tag/certificate-v0.1.8
- 양쪽 release CI 성공, 각 Windows x64 EXE·macOS arm64/x64 DMG·SHA-256 총 6개 Assets 첨부. checksum 파일과 GitHub 업로드 digest 대조 통과. 서버 최종 redaction 이후 compileJava도 성공했다.
- 현재 #519는 최종 문서 커밋 기준 원격 CI 진행 중이다. 로컬 전체 테스트 성공과 원격 CI 완료를 구분한다. 서버 배포·모바일 UI 구현은 이번 게시에 포함되지 않는다.

## PR #518/#519 리뷰·CI 보완 계획
- 원격 실패 11건은 새로 develop에 합쳐진 정산 영수증 테스트의 Java nanosecond와 PostgreSQL microsecond 시각 비교 때문이다. CI artifact에서 실제 값 차이를 확인했고 수정 #520(2654deb5)이 이미 develop에 있어 merge로 반영했다. 정산 업무 코드는 별도 수정하지 않는다.
- #519 P1: 세션 CANCELLED 뒤에도 외부 요청이 시작된 REGISTER 작업이 PROCESSING/UNKNOWN이거나 미귀속 READY 결과가 있으면 새 인증을 차단한다. 세션 문자열만으로 결과 불명을 판단하지 않는다. 취소→UNKNOWN 순서, 요청 중 및 READY 승인 대기 경계를 실제 PostgreSQL에서 검증한다.
- #519 P2: pairing capacity count+insert를 기존 registration과 같은 transaction-scoped advisory lock 방식으로 직렬화한다. 199개 상태에서 동시 요청으로 한 건만 추가되는 검증을 남긴다.
- #518 P1: 초기 pairing과 등록 세션의 PC capability는 의도적으로 서로 다르다. 같은 해시를 강제하는 대신, 생성 응답의 등록 PC capability hash를 pairing 링크 증거로 저장하고 `(session_id,registration_pc_capability_hash)` FK로 연결한다. 연결된 세션의 current claim/consent/approved user가 pairing 사용자와 같도록 deferred constraint trigger로 최종 트랜잭션 상태를 검증한다. 잘못된 세션 증거·타인 claim/이미 승인된 타인 세션 연결 거절과 정상 흐름을 검증한다.
- V50은 미병합 #518에서만 수정하고 서버 #519는 그 SQL 커밋을 merge로 반영한다. 별도 migration 버전을 추가하지 않는다. 사용자 요청의 SQL/서버 PR 병행 규칙을 유지한다. 커밋은 SQL 제약, 재인증 차단, capacity 직렬화, 테스트·검증 문서로 분리한다.

- 취소 경합 회귀는 CANCEL command와 UNKNOWN result를 먼저 명시적으로 전달한 다음 새 REGISTER를 처리한다. 같은 고정 시각에 생성된 outbox UUID 정렬에 테스트 성공 여부가 의존하지 않게 했다.

## 리뷰 수정 최종 로컬 검증
- 최신 develop의 #520 정산 시각 정밀도 보정을 merge로 반영했다. 정산 제품 코드 변경은 없다.
- PostgreSQL/Kafka 기반 인증서 및 기존 실패 정산 focused Java 138개 + JavaScript 35개 통과.
- 수정 후 전체 `bash scripts/test.sh compileJava test bootJar` 성공: Java 1,792개 / 233 suites, 실패·오류·skip 0, JavaScript 35개 통과. V50 신규 적용과 정상 초기 QR→인증→최종 Yes/No, 기존 QR 흐름을 포함한다.
- 신규 회귀 7개: 요청 중/UNKNOWN 취소 경합 2개, 미귀속 READY 재인증 차단, 199개 상태에서 12개 동시 생성 시 한 건만 허용, 등록 capability 증거 불일치, 타인 claim, 승인된 타인 세션 연결 거절.
- `git diff --check` 통과. #518은 SQL 한 파일만 유지하며 서버 코드·검증은 #519에 반영한다. 원격 CI 결과는 로컬 성공과 별도로 확인한다.
