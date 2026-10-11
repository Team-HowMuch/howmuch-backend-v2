# 공동인증서 모바일 승인과 사용자 연결 저장

## 문제와 완료 기준

QR 예약만으로는 사용자 계정에 연결되지 않는다. 인증서 자료를 짧게 암호화 보관하고, 로그인한 모바일 사용자가 금융사를 확인한 뒤 Yes/No를 선택한다. Yes이면 그 사용자의 기존 connectedId를 재사용하여 금융사를 추가하거나 인증 정보를 갱신한다. 없는 사용자만 새 connectedId를 만든다. 성공하면 기존 암호화 `external_connections`와 `connected_institutions`에 저장하고 PC와 모바일에 `BOUND`를 표시한다. No이면 CODEF 호출 없이 세션과 임시 자료를 취소한다.

## 계약과 흐름

- 업로드 → `READY_FOR_CLAIM` → QR 예약 → `CLAIM_RESERVED`.
- `GET /api/v2/certificate-claims/{sessionId}/{claimId}`: 예약자만 금융사, 상태, 로컬 connectionId 조회. connectedId와 인증 자료는 반환하지 않는다.
- `POST /api/v2/certificate-claims/{sessionId}/{claimId}/decision`, `{ "approved": true | false }`: JWT 예약자만 결정. 만료, 세대, 사용자, 현재 예약을 검증한다. 동일 결정 재요청은 멱등, 반대 결정은 409.
- Yes: 승인 사실 및 REGISTER outbox를 한 트랜잭션에 저장. 승인자는 immutable consent에서 읽으며 Kafka에는 사용자 비밀을 싣지 않는다.
- Worker: 요청 시작 마커를 먼저 커밋한다. 별도 트랜잭션에서 세션 → 사용자 행 잠금으로 기존 ID/PW 연결과 직렬화한다. 기존 연결이 있으면 CODEF 등록 금융사 목록에 따라 add/update, 없으면 create. 응답 ID가 기존 ID와 다르면 불확실 처리하며 덮어쓰지 않는다.
- 성공: 사용자 연결, 금융사 추가 입력 암호문, provider 결과/outbox, BOUND 상태를 한 트랜잭션에 커밋한다. BIND는 별도 CODEF 호출이 아니라 동일 API worker의 사용자 귀속 저장 단계다. 결과 Kafka 이벤트는 알림/후속 소비를 위해 기존 inbox/outbox를 이용한다.
- CODEF 요청 이후 통신/DB 결과 불명은 UNKNOWN으로 남기며 자동 재호출하지 않는다. 기존 connectedId를 취소 정리 대상으로 만들지 않는다.
- 동의 reference는 로그인한 예약자의 금융정보 연결 동의를 뜻한다. 금융 명의가 앱 사용자와 같다는 검증 사실로 사용하지 않는다. 기존 스키마의 `ownership_verification_ref`는 이 흐름에서 consent ID를 보관하는 호환 필드다.

## 영향 범위와 마이그레이션

기존 certificate/Application, JDBC 저장소, CODEF adapter, 모바일 API 문서, Electron 완료 상태에 한정한다. 계좌 선택/저장 API와 결제 흐름은 변경하지 않는다.

V47이 필요하다. 인증 전 QR 상태에 connection_ref를 필수로 요구하던 제약을 조정하고, 불변 모바일 동의와 사용자 연결 FK를 추가한다. 한 사용자/연결에 여러 과거 인증 세션이 남도록 세션 및 provider 기록의 유일성 제약을 조정한다. `external_connections.user_id`의 한 사용자 한 연결 제약은 유지한다.

`origin/develop`과 열린 migration PR을 조회했다. 현재 V45까지이며 기존 별도 SQL 작업 V46 다음에 V47을 배정한다. 사용자의 기존 지시(마이그레이션은 별도 브랜치에 준비하고 PR은 전체 개발 후)에 따라 SQL은 별도 migration worktree에도 준비하되 이번에는 commit/push/PR을 수행하지 않는다. 로컬 검증용 작업 트리에도 동일 SQL을 두고 적용한다.

## 선택 이유와 한계

승인 전 CODEF create를 하면 기존 사용자 ID에 합치기 위해 인증서를 다시 요구하게 된다. 사용자 식별 후 등록하면 기존 ID와 계좌 기록을 보존할 수 있다. 사용자 행 잠금을 외부 호출 동안 유지하므로 동일 사용자의 연결 요청은 직렬 처리된다. CODEF 타임아웃으로 점유를 제한하고 처리량 증가 시 분산 직렬 큐로 개선할 수 있다. 원본 인증서는 세션 TTL 동안만 암호화 보관하고 완료/실패/취소/만료 시 지운다.

## 검증과 예상 커밋 단위

1. SQL-only: V47 제약 및 consent 저장 구조.
2. feat(certificate): 모바일 승인, 기존 연결 add/update, 귀속 저장, 완료 UI와 회귀 테스트.

검증: Java compile, 승인/거절/타인 접근/만료/멱등/기존 연결 재사용 단위 테스트, 실제 PostgreSQL 제약 및 연결 저장 통합 테스트, CODEF create/add/update 요청 계약, Electron 테스트. 실제 인증서 비밀번호는 입력하거나 기록하지 않는다. 실제 금융사 E2E는 사용자 신규 QR/승인 없이는 성공했다고 보고하지 않는다.

## 실행 설정과 모바일 연동

기존 `CERTIFICATE_ENABLED`, Kafka command/result/DLT topic 및 인증서 wrapping/confirmation 키에 더해 `CODEF_CONNECTED_ID_ENCRYPTION_KEY`, `ACCOUNT_CREDENTIAL_LOCAL_KEY`가 필요하다. 두 값은 각 32-byte Base64 키이며 dev/prod 별도로 안전하게 보관하고 임의 재생성하지 않는다. 후자는 우리은행 등 조회에 필요한 계좌 비밀번호를 사용자/금융사에 묶어서 암호화한다. 로컬 런타임에 누락되었던 후자 키를 별도로 추가했고 기존 키는 유지했다. 배포는 V46 → V47 → API/app 순서이며 기존 임시 QR은 취소 후 재생성한다.

모바일은 기존 QR 예약 응답의 claimId를 사용한다. 상태 조회 응답의 organization으로 금융사를 표시하고 Yes/No로 decision API를 호출한다. Yes 응답의 SUBMITTED는 접수만 의미하며 상태를 폴링해 BOUND와 connectionId를 받으면 성공 처리한다. 이후 `GET /api/v2/accounts/connections` 또는 `GET /api/v2/accounts/connections/{connectionId}/accounts?organization=...`를 사용한다. FAILED는 공통 failureCode/failureMessage를 보여주고 UNKNOWN은 중복 연결 재요청을 유도하지 않는다. 모바일 앱 저장소의 화면 구현은 이번 백엔드/Electron 작업 범위에 포함되지 않는다.

## 검증 결과 (2026-10-11)

- `./gradlew compileJava test --tests '*Certificate*' --tests '*BankAccountServiceTest' --tests '*CodefBankConnectionAdapterTest'`: 103개 통과, 실패/skip 0. 실제 PostgreSQL migration/제약 및 Kafka 왕복, JWT HTTP → QR 예약 → Yes → DB 저장 → 양쪽 BOUND → 연결 목록 조회, No/타인/만료/동시 Yes/기존 ID add/update/ID 불일치/저장 장애 후 UNKNOWN을 검증했다. CODEF 금융사 응답은 합성 fixture다.
- `npm test --prefix apps/windows-certificate`: 26개 통과. 완료된 연결을 취소 API로 삭제하지 않고 로컬 상태만 비우는 동작을 포함한다.
- `npm run test:smoke --prefix apps/windows-certificate`: 실제 Electron 통과. 완료 체크와 다시 가져오기, 승인 중 예약이 사라지는 경쟁 상태, 오류 및 USB/창 제어를 확인했다. `dist/electron-bound-success-smoke.png`를 직접 열어 완료 화면을 확인했다.
- 초기 회귀 테스트는 새 승인 순서에 맞지 않는 fixture 및 누락된 테스트 계좌 암호화 키로 실패했다. fixture와 테스트 설정을 수정하고 전체 관련 범위를 재실행해 위 결과를 확인했다. 앱 재시작 시 금융사 선택을 다시 하는 정상 흐름도 smoke에 반영했다.
- 로컬 DB V46/V47 적용, readiness 200, OpenAPI bearerAuth 문서, 비로그인 상태/결정 요청 401 확인. 실행 중 등록 0건을 확인한 뒤 API와 앱을 재시작했다.
- 실제 사용자 인증서로 새로운 등록·승인 E2E는 수행하지 않았다. Windows 설치 파일은 다시 빌드하지 않았다. commit/push/PR은 수행하지 않았다.

세부 결과: [검증 기록](certificate-mobile-consent-results.json).

## 게시 커밋 경계 — 2026-10-11

사용자가 세부 커밋·connector 앱 push·서버 이슈/PR 게시를 요청했다. 서버는 최신 origin/develop을 fast-forward로 반영했다. 원래 작업 트리의 무관한 배포/관측 변경은 포함하지 않는다.

1. `fix(codef): 금융 인증 오류 공통 변환과 단일 호출 지원` — CODEF client/mapper/공통 상태 및 mapper 테스트.
2. `feat(certificate): QR와 모바일 승인 기반 금융 연결 구현` — Domain/Application/Port/Adapter, 보안/설정/Kafka schema와 topic.
3. `test(certificate): 승인·중복·장애 복구와 Kafka 회귀 검증` — 인증서 focused 통합/단위와 보안 회귀 테스트.
4. `feat(account): 금융사 연결 목록과 기존 연결 계좌 조회 추가` — 조회 API/Application/persistence 및 계좌 테스트.
5. `docs(certificate): 운영 절차와 실제 금융 연결 검증 정리` — 계획·ADR·운영·민감정보 없는 결과와 검증 도구.

SQL은 #500 V46, #501 V47의 독립 SQL-only PR로 develop에 게시하며 서버 PR은 두 migration에 의존한다. 서버 PR에 SQL 파일을 섞지 않는다. 병합 순서 V46→V47→서버. 앱은 connector #24에서 소스/테스트/CI/문서 커밋으로 분리하며 서버 PR에 앱 파일을 넣지 않는다.

## 실제 금융사 검증 — 2026-10-11

사용자가 새 QR을 생성하고 실제 연결 테스트를 요청했다. 현재 앱의 QR을 읽어 로그인한 로컬 테스트 사용자로 예약(201)·Yes(200), SUBMITTED→REGISTERING→BOUND를 확인했다. DB에서 사용자 연결/활성/connectedId 암호화와 계좌 자격정보 저장을 확인하고 연결 목록 200, 우리은행 계좌 후보 조회 200/2건을 확인했다. command Outbox 발행, provider Inbox 성공, result Outbox 발행 및 result Inbox 수신도 확인했다. 계좌 선택·등록은 하지 않았다. 실제 토큰·QR·인증서·계좌번호는 게시 문서에 넣지 않는다. [공개 검증 집계](certificate-live-connection-results.json).

기존 연결 add/update의 실제 금융사 검증, 모바일 Yes/No 화면, 최신 Windows 설치본·실기기, dev/prod 활성화는 여전히 후속 범위다.

## 게시 전 전체 회귀 검증

최신 develop을 반영한 작업 트리에서 별도 PostgreSQL 18·Redis 8을 준비하고 `bash scripts/test.sh compileJava test`를 실행했다. Java 223 suites/1,667개와 JavaScript 35개가 모두 통과했으며 실패·오류·skip은 0이다. V46/V47은 별도 SQL PR로 게시하되 이 로컬 검증에는 같은 SQL을 함께 적용했다. 서버 PR 단독 checkout에서는 승인 스키마 테스트가 두 SQL PR의 병합 전까지 충족되지 않으므로 초안 PR로 게시하고 먼저 병합하지 않는다.

서버 기능 커밋은 unstaged 파일을 제외한 index snapshot으로 compileJava를 확인했다. 앱은 finance-connector 작업 트리에서 단위 26개, 실제 Electron smoke, production 의존성 audit 0개 및 Java connector/contracts 전체 build를 통과했다. Windows native CI/실기기와 dev/prod 배포를 로컬 성공으로 대신하지 않는다.

## PR #504 리뷰 반영 — 완료 정보의 NULL 우회 차단

리뷰에서 PostgreSQL CHECK의 UNKNOWN 허용으로 external_connection_id만 기록되고 completed_at은 NULL인 동의 행이 허용되는 점을 지적했다. 완료 branch에 `completed_at IS NOT NULL`을 명시해 연결 ID와 완료 시각을 함께 기록하도록 한다. 완료 뒤 연결/시각 변경은 기존 immutable trigger가 계속 거부한다.

기존 PostgreSQL 통합 검증에 부분 완료 두 형태와 승인 이전 완료 시각의 거부, 정상 원자 완료 및 완료 기록 변경 거부를 추가한다. 수정 전 실패를 재현하고 수정 후 migration/persistence 테스트를 실행한다. 새 테이블·컬럼이나 새 migration 버전은 필요하지 않으며 아직 미병합인 V47 한 파일을 수정한다. SQL은 #504의 `fix(certificate)` 커밋, 회귀 테스트/검증 기록은 #505의 `test(certificate)` 커밋으로 분리한다. 앱과 다른 SQL에는 수정할 리뷰가 없다.

검증 결과: 수정 전 `yesBindsEncryptedUserConnectionAndReusesItOnNextCertificate`가 연결 ID만 저장하는 UPDATE를 거부하지 못해 실패했다. V47 수정 후 `./gradlew compileJava test --tests '*CertificatePersistenceIntegrationTest' --tests '*CertificateFailureMigrationTest' --no-daemon`이 성공했다. PostgreSQL 통합 29개와 V45→V46→V47 migration 검증 1개, 합계 30개 모두 실패·오류·skip 0이다. 서버 PR에는 회귀 테스트와 이 기록만 추가하고 SQL은 #504에만 게시한다.

## PR #505 CI 실패 수정 — 저장소 분리 이후의 계약 검증

CI 로그에서 앱 저장소로 이전한 `apps/windows-certificate/src/user-errors.json`을 서버 테스트가 직접 읽어 실패하는 문제를 확인했다. 앱 파일이 남은 로컬 작업 트리에서는 이 의존이 가려졌다. 현재 connector 앱에 게시한 오류 코드/문구를 서버의 test resource 계약 snapshot으로 명시하고 기존 비교 테스트를 classpath resource 기반으로 바꾼다. 앱 실시간 일치 검증이라는 잘못된 이름도 게시 계약 호환성 검증으로 바꾼다. API·보안·DB 스키마 변경이나 새 migration은 없다. 커밋은 `test(codef): 분리된 앱 파일 의존을 오류 계약 스냅샷으로 교체`로 한 의도로 묶는다.

별개로 #503/V46·#504/V47이 아직 develop에 없어 서버 PR 단독 CI의 migration/승인 통합 테스트가 실패한다. SQL은 기존 PR에 유지한다. 검증은 앱 잔여 파일 없는 Git 추출본에서 진행하고, 실제 서버 테스트에는 두 SQL PR의 게시된 버전만 추가해 전체 회귀를 실행한다. 이 통합 검증을 #505 단독 CI 성공으로 보고하지 않는다. 병합 순서는 #503→#504→#505이며 선행 SQL 병합 후 #505의 develop 반영 및 CI 재실행이 필요하다.

오류 계약 snapshot 출처는 `Team-HowMuch/howmuch-finance-connector`의 `1bfa1a3` 커밋, `apps/windows-certificate/src/user-errors.json`이다. 서버 `src/test/resources/contracts/certificate-user-errors.json`에 보존해 게시한 코드/문구의 호환성을 검증한다. 계약을 의도적으로 변경할 때 서버 snapshot과 connector 앱을 함께 검토한다. 다른 저장소의 최신 파일을 실시간으로 비교하는 테스트는 아니다.

검증 결과: 앱 폴더와 V46/V47이 없는 Git 추출본에서 `./gradlew compileJava test --tests '*CodefFailureMapperTest' --no-daemon`이 성공했다(26개, 실패·오류·skip 0). 같은 추출본에 #503 `570b820b`와 #504 `7d91b6cd`의 SQL만 추가하고 일회용 PostgreSQL 18·Redis 8에서 `bash scripts/test.sh clean compileJava test`를 실행했다. Java 223 suites/1,667개 및 JavaScript 35개가 모두 통과했다. 로컬 앱 잔여 파일을 참조하지 않음을 확인했다. SQL 선행 PR이 아직 미병합이므로 이 결과는 #505 단독 원격 CI 통과를 의미하지 않는다.

## PR #505 리뷰 반영 — 결과 지연과 계좌 인증 정보 보존

리뷰 3건을 코드·기본 설정·조회 경로와 대조해 모두 재현 가능한 문제로 확인했다. 변경 범위는 certificate JDBC 완료 처리, 기본 금융사 입력 조건, 승인 완료 트랜잭션 및 관련 테스트다. DB 구조/제약 변경은 없어 Flyway migration은 필요하지 않다.

1. REGISTER 실행의 `UNKNOWN` 기록과 세션의 `UNKNOWN`/안내 코드를 같은 트랜잭션에서 저장한다. 결과 topic 장애로 전달이 지연돼도 승인 동의가 있는 불확실 세션이 TTL 복구에서 EXPIRED로 바뀌지 않아야 한다. 이미 취소·만료·완료된 세션은 되돌리지 않는다. 결과 소비 전 TTL 경과와 이후 지연 결과 소비, 요청 시작 뒤 lease 만료를 PostgreSQL 통합 테스트로 검증한다.
2. 기본 certificate 기관 설정에 `0031`의 `account-list-account-required: true`를 명시한다. 등록 자체의 입력 조건과 조회 입력 조건을 구분하면서 V40/계좌 조회 경로가 필요로 하는 계좌번호·계좌 비밀번호를 수집한다. 기본 설정을 사용하는 HTTP 테스트에서 catalog 플래그, 입력 누락 거부, 정상 수집/완료를 검증한다.
3. 이미 연결된 기관 갱신에서 새 계좌 인증 정보가 없으면 기존 ciphertext를 유지한다. 새 쌍이 있으면 기존처럼 갱신하고 새 기관/새 연결 저장은 유지한다. Application 트랜잭션 테스트로 기존 정보 유지와 명시적 교체를 검증한다.

커밋 경계는 각 항목별 `fix(certificate): 불확실한 인증 결과를 세션에 원자적으로 보존`, `fix(certificate): 대구은행 계좌 조회 인증 정보 수집`, `fix(certificate): 인증서 갱신 시 기존 계좌 인증 정보 보존`으로 나눈다. 각 변경의 실패 재현 후 관련 compile/test를 실행하고 최종 변경을 깨끗한 소스에서 검증한다. 실제 금융사 호출이나 사용자 인증 정보 변경은 검증에 포함하지 않는다.

선행 SQL #503·#504는 현재 develop 및 #505에 반영됐다. 새 head `9372249a`에서 원격 Java 1,667개·JavaScript 35개와 Docker 빌드까지 통과한 뒤 이번 리뷰 수정을 진행한다.

첫 번째 수정 검증: 결과 소비 전 세션이 REGISTERING으로 남는 실패를 기존 timeout 테스트에서 재현했다. 수정 후 `./gradlew compileJava test --tests '*CertificatePersistenceIntegrationTest' --no-daemon`의 29개가 모두 통과했다. 결과 미수신 상태로 TTL이 경과한 뒤에도 UNKNOWN 및 안내 코드가 유지되고 CANCEL이 생성되지 않으며, 지연 결과 수신 후에도 재등록하지 않음을 확인했다. 이미 취소된 세션의 후속 정리와 lease 복구 기존 검증도 통과했다.

두 번째 수정 검증: 기본 catalog의 `0031` 계좌 조회 필수 플래그가 false인 실패를 HTTP 테스트로 재현했다. 설정 수정 후 일회용 PostgreSQL·Redis 및 Testcontainers Kafka를 사용해 `compileJava`, HTTP 8개와 catalog 2개, JavaScript 35개가 모두 통과했다. `0020`·`0031` 모두 입력 누락 거부→승인→BOUND→계좌 조회 200을 확인했고, 저장한 계좌번호/비밀번호가 복호화되어 조회 Port에 전달됨을 검증했다. CODEF 등록에는 `0020`만 계좌 입력을 전달하고, `0031`은 조회를 위해 저장한다. 금융사 호출은 mock 경계로 대체했다.
