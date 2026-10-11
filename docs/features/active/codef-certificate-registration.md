# Electron 공동인증서 등록과 API 내부 CODEF 실행

> 2026-10-11 현재 승인/저장 흐름은 [모바일 동의 계획](certificate-mobile-consent.md)을 따른다. 아래 PC 승인·독립 명의확인·별도 BIND 미구현 메모는 이전 설계 기록이다. 현재 사용자 결정에 따라 로그인한 모바일 예약자의 Yes/No를 사용하며, 금융 명의 일치는 전제하지 않는다.

- 현재 상태: 모바일 Yes/No 승인·기존 연결 재사용·사용자 귀속 저장·계좌 조회 구현 완료. 실제 로컬 우리은행 등록→BOUND→계좌 2건 조회 확인. 모바일 화면, 최신 Windows 설치본/실기기, dev/prod 배포는 남음. 아래 날짜별 검증은 당시 결과이며 현재 기준은 모바일 동의 계획을 따른다.
- 기준: 2026-10-10, `origin/develop` = `74070103`; V43·V44 및 PR #475·#476·#495 머지 확인
- 상위 이슈: [#489](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/489)
- API: [#491](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/491), Electron: [#492](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/492)
- 추가 SQL: [#496](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/496), [PR #498](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/498)
- 계좌 연계: [#494](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/494), QA: [#493](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/493)
- 이전 Connector 작업: [#23](https://github.com/Team-HowMuch/howmuch-finance-connector/issues/23); 이번 인증서 실행 역할은 API로 이전
- 결정: [ADR-0007](../../adr/0007-api-resident-certificate-execution-and-electron-client.md)
- 규칙: [AGENTS.md](../../../AGENTS.md), [architecture](../../architecture.md), [개발 절차](../../development-workflow.md)

## 1. 목적과 범위

사용자는 Windows 설치 앱에서 공동인증서 DER/KEY와 비밀번호, 금융사별 추가 정보를 입력한다. PC 로그인 없이 CODEF 등록을 요청하고 성공 후 일회용 QR을 표시한다. 로그인한 모바일 사용자가 QR을 예약하고 PC에서 계정·확인 코드를 확인해 최종 승인하면 계좌 조회·선택으로 이어진다.

이번 결정은 C#/WPF 대신 **Electron + 로컬 HTML/CSS/JavaScript renderer**를 사용하고, 인증자료 접수·암호화 보관·CODEF 등록/조회·명령 Inbox/job·결과 Outbox·정리를 **Backend API의 certificate 기능 내 전용 실행 Service/Output Port**에 구현하는 것이다. 설치 앱은 finance-connector 저장소의 `apps/windows-certificate` 독립 Node project로 관리한다. 서버와 설치 프로그램은 별도 artifact/CI로 관리하며 API에는 설치 앱을 게시하지 않는다. 이 문서의 과거 `apps/windows-certificate` 로컬 검증 경로는 이전 Backend 작업 트리 기준이다.

기존 별도 finance-connector의 거래 수집 기능을 이번 인증서 변경으로 복제하지 않는다. 이번에 그 저장소에서 개발하려던 인증서 작업 전체를 API 내부로 가져온다. 기존 ID/PW 연결·거래 조회는 현재 경계를 재사용한다. 실제 본인확인 계약·기관별 CODEF 권한 확인·코드서명 구매·실은행 호출·운영 활성화는 별도 증거가 필요하다.

성공 기준:

- [ ] Windows에서 설치/삭제/실행·명시적 파일 선택·금융사 입력·등록/QR/PC 승인 흐름 검증
- [x] API에서 원문을 로그·Kafka·QR에 넣지 않고 암호화 접수 및 CODEF 처리 — 합성 fixture 검증
- [x] 명령 Inbox/job 원자 기록, CODEF 무TX 호출, 결과 상태/Outbox 원자 기록 및 중복/lease 복구
- [ ] API의 사용자/계좌 소유권과 인증서 provider 실행 상태를 분리하고 실제 계좌 후보/선택 연결
- [x] 실제 PostgreSQL/Kafka 및 합성 CODEF fixture 검증과 미검증 운영 범위 구분

## 2. Domain 경계와 불변 조건

`certificate`는 PC 세션·QR·회원 예약·불변 승인 사실을 소유한다. provider 실행 Service/Output capability는 CODEF 실행·암호화 자료·연결 참조·메시지 실행 상태를 담당한다. 같은 API process/DB에 배치하지만 서로의 Adapter/Repository를 직접 호출하지 않고 Application capability를 통해 연계한다. 추후 분리는 provider 모듈과 그 소유 테이블/키를 옮기는 작업이며 처음부터 네트워크 왕복을 추가하지 않는다.

- 기존 V44 세션/claim/command Outbox/result Inbox를 유지한다. V44 파일은 수정하지 않는다.
- 인증서·key·비밀번호·계좌번호·CODEF connectedId 원문은 message/QR/일반 metadata에 넣지 않는다. API 내부의 암호화 전용 Adapter만 자료를 저장/복호화한다.
- PC capability·별도 upload capability·QR token은 서로 다른 256-bit 이상 난수이며 SHA-256 hash만 저장한다. header/body를 사용하고 URL query로 전달하지 않는다.
- session 10분, QR 60초, 예약 60초는 초기 정책값이다. upload deadline은 session을 넘지 않는다. 승인 이후에는 이 TTL로 연결을 삭제하지 않는다.
- session당 예약 1개, 사용자당 승인 중/BOUND 1개와 V44 approval tuple 불변을 유지한다.
- PC 승인·자기 입력 생일/이름은 명의 증명이 아니다. 검증된 ownership reference 없는 승인을 허용하지 않는다. 실제 확인 계약이 없으면 운영 활성화하지 않는다.
- CODEF create 요청 시작은 전송 직전에 별도 TX로 기록한다. 시작 뒤 timeout/worker 유실은 UNKNOWN으로 두고 같은 create를 자동 재호출하지 않는다.
- approved/BOUND/BINDING_UNKNOWN 연결은 orphan 정리 대상이 아니다. 사용자 탈퇴는 잠금·작업 취소/fencing·비밀 삭제·연결 해제를 공통 실행기에서 처리한다.

세션 상태는 V44의 `CREATED → SUBMITTED → REGISTERING → READY_FOR_CLAIM → CLAIM_RESERVED → APPROVED_BINDING → BOUND`를 따른다. 실패/불명확 상태와 승인 전 만료·취소는 별도 상태다.

## 3. 정상·실패·경계 흐름

1. Electron main이 API에서 기관 catalog·PC 세션 및 일회 upload capability를 발급받는다.
2. 사용자가 DER/KEY 파일과 기관별 필드를 선택한다. main이 파일 크기/형식을 확인하고 HTTPS JSON/Base64로 전송한다.
3. API가 capability/TTL/일회성을 검사한다. 암호화 upload, 세션 SUBMITTED 및 REGISTER command Outbox를 같은 TX에 기록한다.
4. relay가 Kafka ACK 후 publication 완료를 기록한다. certificate command listener는 payload hash 비교 후 Inbox/job 한 row를 commit하고 offset을 commit한다.
5. worker는 lease token/generation으로 job을 선점한다. CODEF 호출 전 provider-request marker를 commit하고 TX 밖에서 CODEF를 호출한다.
6. 성공 기관과 connectedId를 확인한 뒤 provider connection 암호문·job 결과·result Outbox를 같은 TX에 기록하고 upload 원문 복호화 버퍼를 버린다. 임시 암호문은 소비/만료 시 제거한다.
7. result listener는 V44 result Inbox와 세션 상태를 원자 반영한다. REGISTERED 후에만 QR을 표시한다.
8. 모바일 예약·확인 코드·PC 승인으로 V44 승인 사실과 BIND command가 생성된다. provider worker는 승인 tuple과 기존 연결 충돌을 확인하고 실제 connectedId를 기존 연결 암호화 형식으로 재암호화하여 ExternalConnection/기관/계좌 흐름과 연결한다.
9. BOUND 이후 인증된 사용자만 후보 조회·계좌 선택을 수행한다. 추가 계좌 조회 자격정보는 connection 전용 암호문에서 사용하며 인증서 DER/KEY와 분리한다.

중복 message ID는 payload hash가 같을 때만 멱등 처리한다. 다른 ID의 같은 session/type/version도 동일 operation인지 비교한다. lease 만료 CAS에서 지연 worker 결과를 거부한다. 전송 전 실패만 RETRY, 전송 시작 이후 불명확 응답은 UNKNOWN이다. 재기동/재전달은 provider 요청 marker를 지우지 않는다. BIND local TX의 결과 불명확은 저장된 승인·연결 사실을 조회해 복구한다.

동일 DB여도 두 메시지 경계는 유지한다. command topic `certificate.registration.command.v1`, result topic `certificate.registration.result.v1`, 각 DLT와 전용 group을 추가한다. notification/finance.sync 계약은 확대하지 않는다. 메시지에는 UUID 참조·버전·기관 코드·안전한 결과 코드만 포함한다. 생성 topic/ACL과 기존 producer/consumer 역할 분리는 후속 Infra 구현에서 검증한다.

Application의 공통 잠금 순서는 `session → claim → provider job/connection → external connection`이다. 이미 시작한 외부 요청을 DB rollback으로 취소할 수 없으므로 CODEF 호출은 이 잠금/TX 밖에서 실행한다. `40P01`/`40001`은 bounded retry하고, 완료 CAS에는 message ID·status·lease token/generation을 모두 사용한다. SQL 제약만으로 worker의 CAS·clock·deadlock 재시도가 구현되는 것은 아니다.

QR 발급/예약/승인은 세션을 먼저 잠근 뒤 provider 연결이 사용 가능하며 삭제 작업 중이 아닌지 재확인한다. orphan 정리도 같은 잠금에서 승인 사실과 세션 terminal 상태를 확인하고 삭제 의도를 기록한다. 승인 후에는 TTL 정리로 비밀을 지우지 않는다. REGISTER UNKNOWN은 확인된 외부 결과를 가진 명시적 reconciliation으로만 종료한다. BIND UNKNOWN은 동일 승인과 기존 매핑을 조회하는 local reconciliation으로 복구하며 새 CODEF create를 실행하지 않는다. 결과 Outbox에는 UNKNOWN과 후속 확정 결과를 서로 다른 outcome으로 남길 수 있고, 지연 결과는 세션 version/status/승인 tuple 검사 후 반영한다.

## 4. Application·HTTP·Electron 계약

| Application 목표 | Command/Result | 실제 외부 capability |
|---|---|---|
| CreateCertificateRegistrationUseCase | CreateCertificateRegistrationCommand / CertificateRegistrationResult | 세션 저장·난수/hash·rate limit |
| IssueCertificateUploadUseCase | session ID / CertificateUploadCapabilityResult | upload capability 저장 |
| SubmitCertificateUploadUseCase | SubmitCertificateUploadCommand / CertificateSubmissionResult | 암호화 upload 저장·세션/Outbox 원자 저장 |
| GetCertificateRegistrationUseCase / CancelCertificateRegistrationUseCase | ID 및 PC capability / 상태 Result | 세션 저장·CANCEL Outbox |
| ReserveCertificateClaimUseCase / ApproveCertificateClaimUseCase | reserve/approve Command / claim Result | CurrentActor·명의확인 사실·세션/claim 저장 |
| ReceiveCertificateProviderCommandUseCase | CertificateProviderCommand / 수신 결과 | Inbox/job 저장 |
| ProcessCertificateProviderJobUseCase | job ID/lease / 실행 결과 | job store·upload secret store·CertificateRegistrationPort·connection store·result Outbox |
| PublishCertificateProviderResultsUseCase | batch size / 발행 결과 | Kafka Publisher·result Outbox store |
| GetCertificateAccountCandidatesUseCase / LinkCertificateAccountsUseCase | 사용자와 connection 참조 / 후보·연결 Result | 기존 계좌/연결 capability·provider secret·계좌 조회 Port |

위 이름은 후속 구현 계약이며 현재 unused interface를 만들지 않는다. Application Service가 상태/정책·TX를 조정하고 Policy는 로드된 승인·명의확인·연결 충돌 사실만 받는다. Provider HTTP와 암호화/DB·Kafka 기술은 Output Adapter에 둔다. `@Service`/`@Transactional`은 프로젝트 허용 범위로 사용한다.

초기 HTTP 계약은 `/api/v2/certificate-registrations` 아래 create, `{id}/upload-capability`, `{id}/upload`, `{id}` 상태, `{id}/cancel`, `{id}/claim`, `{id}/approve`를 둔다. PC endpoint는 전용 capability, claim/계좌 endpoint는 JWT CurrentActor를 사용한다. Web DTO → Command → UseCase → Result → Web DTO를 지킨다. capability 없음/불일치, 만료, 중복 충돌, 명의확인 미완료, 추가인증, UNKNOWN을 원문 없는 안정된 error code로 반환한다. 무인증 업로드 전체 body 크기·빈도·보관량을 제한한다.

Electron:

- 로컬 JavaScript renderer는 입력·상태·QR·승인 화면만 담당한다. 파일 선택/읽기·API 요청은 main, preload는 명시적 함수 allowlist와 DTO만 제공한다.
- `contextIsolation=true`, `sandbox=true`, `nodeIntegration=false`, `webSecurity=true`; 로컬 bundle/CSP, IPC sender 검증, 외부 navigation/window 생성·불필요 permission 차단.
- renderer가 임의 경로·임의 URL·generic IPC를 전달할 수 없다. 사용자가 picker로 고른 DER/KEY만 main에서 허용한다. 전체 디스크 검색/관리자 권한/원문 임시 파일 보관을 하지 않는다.
- CODEF client secret·API 앱 JWT를 배포하지 않는다. PC capability는 메모리에만 두고 종료 시 버린다. JS 메모리의 완전한 zeroization을 보장한다고 주장하지 않는다.
- 설치 `.exe`/Windows CI·코드서명/업데이트 검증은 별도 artifact로 관리한다. 빌드 성공과 실제 Windows 설치/실행 성공을 구분한다.

근거: [Electron security](https://www.electronjs.org/docs/latest/tutorial/security), [IPC](https://www.electronjs.org/docs/latest/tutorial/ipc).

## 5. 금융사별 입력·CODEF 보안

기존 V40 BANK.attr3의 우리은행 `0020`·대구/iM뱅크 `0031` ID/PW 조건을 인증서 로그인 조건으로 복사하지 않는다. catalog는 certificateRegistration/accountList별 계좌번호·계좌 비밀번호·생년월일의 필수/선택·형식·최대 길이와 확인 근거를 제공한다. 사용자 확인에 따라 활성 `BANK`는 모두 기본 지원하고, 기관별 설정은 추가 입력 조건만 지정한다(§18).

공식 SDK의 공통 계정등록은 `loginType=0`, Base64 `derFile`/`keyFile`, CODEF 공개키 RSA 암호화 password다. 계좌 조회 추가 비밀번호도 제공사 계약에 따라 전송 직전 RSA 암호화한다. 기존 CodefClient/TokenClient/RsaEncryptor/관측기의 원문 없는 오류 처리를 재사용한다. 응답 successList의 선택 기관·connectedId를 모두 확인한다. 부분 실패/2-way/UNKNOWN을 성공으로 취급하지 않는다.

upload 암호문은 AES-256-GCM 및 upload UUID/session/version AAD에 바인딩한다. envelope DEK를 KMS로 감싸고 key ID를 저장한다. local fixture만 별도 테스트 키를 사용한다. connection 암호문은 다른 AAD로 connectedId 및 필요한 계좌 조회 자격정보만 보관한다. DER/KEY/인증서 비밀번호를 장기 connection payload에 포함하지 않는다. 복호화/비밀 DTO는 toString·로그·APM body·DLT 원문에 노출하지 않는다. TTL worker와 요청 시 expiry 검사 모두 필요하다.

## 6. 저장소와 Flyway 결정

Flyway 필요: **Yes**. V44는 QR 메타데이터만 제공하며 기존 Backend에는 익명 인증서 upload, 사용자 귀속 전 CODEF 연결, 인증서 command Inbox/job 및 provider result Outbox 저장소가 없다. Redis만으로 CODEF 연결 참조를 TTL 삭제하면 미귀속 원격 연결과 UNKNOWN 복구 근거를 잃는다. 기존 ExternalConnection은 user 필수이고 이를 익명 접수용으로 재사용하지 않는다.

최신 develop의 마지막 migration은 V44이고 열린 #342에는 SQL이 없다. 다음 버전 **V45__create_api_certificate_provider_storage.sql**을 최신 develop의 SQL-only branch에서 작성한다. 이 migration은 기존 V44를 변경하지 않고 다음 구조를 추가한다.

| 테이블 | 책임 |
|---|---|
| certificate_upload_secrets | 별도 capability hash·deadline·일회 상태와 임시 envelope 암호문; session 삭제 시 cascade |
| certificate_provider_command_inbox | messageId/hash·typed 참조와 lease/retry/전송 시작 marker를 가진 Inbox + 실행 job 한 row; session 정리 뒤에도 dedup 보존 |
| certificate_provider_connections | 확인된 CODEF 연결 암호문, 등록 출처, 불변 승인/사용자 귀속, 기존 ExternalConnection 매핑 및 비밀 제거 상태 |
| certificate_provider_result_outbox | 원문 없는 typed 결과, 실행 command 참조, 독립 publication lease/retry·ACK 상태 |

Inbox와 job을 한 row로 저장해 두 row 사이의 유실을 없앤다. 결과 Outbox의 publication retry는 CODEF 실행 retry를 증가시키지 않는다. provider connection의 BOUND tuple은 V44 승인 사실 및 기존 ExternalConnection 사용자와 일치해야 한다. 기존 external_connections에는 `(id,user_id)` unique key를 추가해 owner 복합 FK를 제공한다. 사용자 탈퇴/정리 시 connection 비밀 삭제 및 이 매핑 제거가 선행되어야 한다.

SQL PR에는 SQL 한 파일만 넣고 migration·빈 DB·V44 upgrade·재실행/validate·제약·동시성·lease fencing을 실제 PostgreSQL에서 검증한다. Feature Plan·ADR·QA·실험은 별도 plan worktree에 둔다. AGENTS.md gate에 따라 **추가 SQL PR 생성 직후 이 작업을 종료**하며 Java·Electron 구현은 다음 요청에서 진행한다.

## 7. 구현 순서·commit 경계

- [x] 기존 코드/테이블·머지된 V44·열린 migration PR 조사
- [x] Electron과 API 내부 provider 결정, Feature Plan/ADR 갱신
- [x] V45 SQL-only 구현·검증 — PR은 [#496](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/496)에서 추적; 생성 직후 현재 작업 종료
- [x] 접수/실행/QR 예약 UseCases·Domain·Policy·Application Service·Ports 및 실제 Adapters
- [x] 업로드/QR 예약 HTTP·CODEF 기관 catalog·암호화/worker·Kafka Infra
- [ ] 독립 명의확인 및 PC 최종 승인/BIND HTTP
- [ ] 기존 연결/계좌 후보·선택·탈퇴 복구 연계
- [x] Electron main/preload/renderer·설치 artifact·Windows CI 정의 — 실기기/CI 실행 미검증
- [ ] 실제 PostgreSQL/Kafka fixture E2E·Windows 설치·실은행/명의확인 운영 조건

Migration 외 구현 commit/push는 별도 사용자 요청 시 수행한다. 예상 경계: `build(certificate): API 내부 인증서 실행 저장소 추가`, `feat(certificate): 인증서 접수와 QR 승인 API 구현`, `feat(codef): API 내부 인증서 실행과 메시지 복구 구현`, `feat(windows): Electron 공동인증서 설치 앱 구현`, `test(certificate): 장애와 연결 경계 검증`.

## 8. 검증·미검증 범위

초기 SQL 작업: SQL 실제 Flyway V1→V45, V44→V45, 재실행 0건/validate, 기존 V44 회귀 77개, 신규 암호문/TTL/상태/owner binding/lease/job-result 원자성 SQL 실험 74개 통과. 기존 41개 테이블 데이터 유지 및 최소 Java compile 성공. [V45 QA](codef-certificate-provider-schema-qa.md)에 실제 경로별 증거와 미구현 범위를 기록했다. `git diff --check`와 문서 상대 링크를 확인한다. 실험은 Application 테스트가 아니다.

후속: Java 최소 compileJava, 필요한 서비스 전제 충족 후 focused tests, Kafka ACK/DLT/offset·중복·stale fencing, 합성 CODEF create timeout, HTTPS 업로드 제한과 body logging 제외, Electron IPC와 실제 Windows 설치/삭제·실행. 실제 은행 호출·인증서 열람·배포/운영 활성화는 수행 증거 없이 완료로 표시하지 않는다.

## 9. 결정·진행 이력

| 날짜 | 결정/결과 | 영향 |
|---|---|---|
| 2026-10-10 최초 | V44 SQL-only #495, 기존 SQL 42개 + 현재 예약 회귀 35개 통과 | [기존 QA](codef-certificate-schema-qa.md), [리뷰 QA](codef-certificate-pr495-review.md) |
| 2026-10-10 후속 | #475·#476·#495 머지, dev/prod broker 별도 설치 완료 | broker 설치와 인증서 앱 구현을 구분 |
| 2026-10-10 V45 | 빈 DB 45개 및 V44→V45 1개 적용/validate/재실행 0개, 기존 77개+신규 74개 통과 | [V45 QA](codef-certificate-provider-schema-qa.md); API/Electron 구현 전 |
| 2026-10-10 사용자 결정 | Electron, 인증서 Connector 역할을 API에서 먼저 구현 | WPF·별도 인증서 Connector 배치 계획을 대체; V45 추가 필요 |

기존 검증 자료: [42개 결과](codef-certificate-schema-results.json), [실험 script](../../../tools/verification/certificate-schema-check.py), [현재 예약 실험](../../../tools/verification/certificate-current-claim-check.py). CODEF 근거: [공식 SDK](https://github.com/codef-io/codef-java/blob/master/README.md).

## 10. PR #498 리뷰 수정

[리뷰 코멘트](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/498#discussion_r4237915056)는 한 REGISTER command에 `REGISTERED`와 `REGISTRATION_FAILED`가 함께 기록되어 상반된 확정 결과가 발행될 수 있음을 지적했다. 기존 `(command_message_id,message_type)` unique만으로는 서로 다른 두 type을 막지 못한다.

영향 범위는 아직 머지·배포하지 않은 V45의 result Outbox index 한 곳이다. `command_message_id`에 두 확정 registration type만 포함하는 partial unique index를 추가한다. `REGISTRATION_UNKNOWN`과 후속 확정 결과는 함께 보존하고, publication 상태와 관계없이 확정 결과는 command당 하나만 허용한다. trigger의 조회 후 검사로 대체하지 않고 unique index로 동시 transaction까지 보호한다. command terminal status와 result 간 모든 정합성 정책을 새로 추가하는 범위는 아니다.

검증은 원본 모순 재현, 양쪽 입력 순서 및 publication 이후 충돌 거부, UNKNOWN→성공/실패 허용, 서로 다른 command 독립성, 동시 writer와 transaction rollback, 기존 151개 SQL 회귀 및 빈 DB/V44 upgrade·Flyway validate/재실행이다. SQL-only PR 경계를 유지하고 script/QA는 plan worktree에 보관한다. 예상 commit: `fix(certificate): 상충하는 등록 확정 결과 차단`. 수정·검증 후 기존 PR branch에 push한다.

리뷰 수정 결과: 원본 모순을 재현하고 partial unique index를 추가했다. 빈 DB/V44 upgrade·validate·재실행 0개와 기존 77개+provider 89개(신규 리뷰 회귀 15개) 총 166개 SQL 실험 및 최소 Java compile을 통과했다. 기존 41개 테이블 데이터가 유지됐다. [PR #498 리뷰 QA](codef-certificate-pr498-review.md)에 증거를 보관한다.

수정 commit `ed3bcf09`을 기존 SQL-only PR #498 branch에 반영했다. 검증용 DB는 제거했으며 dev/prod에는 적용하지 않았다.

## 11. SQL PR 미머지 상태에서 후속 구현

사용자가 2026-10-10에 PR #498을 나중에 머지하고 구현을 계속하도록 요청했다. SQL gate의 후속 작업으로 기능 branch `feat/489-certificate-registration`을 `ed3bcf09`까지 fast-forward하여 검증된 V45를 로컬 기반으로 사용한다. #498 branch와 dev/prod는 수정하지 않고 Java/Electron 변경은 기능 branch의 working tree에 구현한다. 추가 schema는 계획하지 않는다. 구현의 commit/push/PR 생성은 이번 요청에서 수행하지 않는다.

HTTP path는 현재 서비스 컨벤션에 맞춰 `/api/v2/certificate-registrations`(PC capability) 및 `/api/v2/certificate-claims`(회원 JWT)로 정한다. 인증서 원문이 multipart 임시 파일로 디스크에 저장되는 것을 피하도록 제한된 JSON/Base64 upload를 사용한다. DER/KEY 각 128 KiB, 전체 request 768 KiB를 넘기지 않으며 HTTPS만 허용하는 Electron main이 전송한다. 서버의 endpoint·scheduler·Kafka listener는 certificate feature flag 기본 비활성으로 둔다.

구현 순서: PC 생성/일회 업로드/상태/취소와 envelope 암호화 → CODEF 단일 요청 및 Inbox/job/result Outbox·lease/UNKNOWN 복구 → Kafka 발행/수신과 QR/회원 예약 → Electron main/preload/renderer 및 Windows installer build → PostgreSQL/Redis/Kafka·CODEF 합성 fixture 통합 검증. 실제 금융사 certificate 입력 규격은 별도 catalog로 관리하고 ID/PW BANK.attr3를 복사하지 않는다. 독립 명의확인 연동 계약은 사용자에게 확인 중이며 임의 UUID로 승인 사실을 채우지 않는다.


## 12. 후속 구현 결과 — 2026-10-11

SQL gate 이후 별도 기능 작업으로 API와 설치 앱을 구현했다. 이 작업에서 commit/push/새 PR/서버 배포/머지를 실행하지 않았다. 작업 도중 GitHub를 다시 조회하니 #498이 이미 머지된 상태였다. 기능 worktree는 검증된 `ed3bcf09` 기반을 유지하며, PR 발행 전 최신 develop과 최종 diff를 맞춘다.

현재 동작과 검증 결과는 [구현 QA/인계](codef-certificate-api-client-qa.md)에 정리한다. [운영 runbook](../../../ops/certificate/README.md)과 [Electron 실행/빌드](../../../apps/windows-certificate/README.md)를 함께 사용한다.

구현된 범위:

- PC 세션·서로 독립적인 일회 upload/PC capability, 암호화 JSON/Base64 업로드, 상태/취소.
- KMS envelope + AES-256-GCM/AAD, 별도 local/test key, 원문 upload 삭제와 connection 전용 암호문 분리.
- REGISTER/CANCEL Outbox, ACK 기반 relay와 fencing, provider Inbox/job, 요청 시작 marker, UNKNOWN 복구, result Outbox/Inbox 및 중복/재정렬 보호.
- 기관별 등록/조회 추가 입력 분리 catalog. 조회에만 필요한 계좌 자격정보는 등록 요청에 보내지 않고 암호화된 connection 자료에 유지한다. 검증된 운영 catalog는 아직 없다.
- 60초 QR 발급·회전, JWT 회원의 배타적/멱등 예약, PC/모바일 동일 확인 코드, 예약 거절. QR은 `v/type/sessionId/qrToken`만 담는다.
- 미승인 취소/만료 연결 정리. 확인된 삭제 후 비밀 제거, 불명확한 삭제는 DELETE_UNKNOWN으로 보존하며 자동 재삭제하지 않는다.
- 독립 Electron main/preload/local renderer, 한 API origin 통신, Windows NSIS 빌드/CI 정의, 보안 fuse, 합성 UI smoke.

첫 renderer는 단일 폼에 React/TypeScript와 bundler를 추가하지 않고 로컬 JavaScript로 구현했다. IPC/HTTP 입력 검증을 명시적으로 수행하며 민감 작업은 main에 둔다. 이 선택은 Electron이라는 사용자 결정은 유지하고 실제 구현 범위를 줄인 것이다.

현재 API는 아래 계약만 제공한다. 처음 계획한 별도 upload-capability 발급 endpoint 대신 create 응답에서 일회 capability를 함께 발급한다.

| Method | Path | 인증/용도 |
|---|---|---|
| GET | `/api/v2/certificate-registrations/institutions` | 공개 catalog, 활성 BANK 전체 지원, 기관별 추가 입력 조건 제공 |
| POST | `/api/v2/certificate-registrations` | PC 세션/capability 발급 |
| POST | `/api/v2/certificate-registrations/{id}/upload` | PC + upload capability header, 제한된 JSON body |
| GET / DELETE | `/api/v2/certificate-registrations/{id}` | PC 상태/취소 |
| POST | `/api/v2/certificate-registrations/{id}/qr` | PC용 일회 QR, 60초 |
| GET | `/api/v2/certificate-registrations/{id}/claim` | PC 확인 코드 조회 |
| DELETE | `/api/v2/certificate-registrations/{id}/claims/{claimId}` | PC 예약 거절 |
| POST | `/api/v2/certificate-claims` | JWT + 활성 회원, QR 예약 |

아직 구현하지 않은 최종 범위:

1. 독립적인 금융 명의확인 서비스의 실제 계약과 증거 reference. 저장소에서 기존 연동을 찾지 못해 사용자에게 확인 중이다.
2. 그 증거를 사용하는 PC 승인, 불변 approval/BIND, 기존 ExternalConnection·계좌 후보/선택·탈퇴 연계. 임의 ownership UUID나 connectedId로 대신하지 않는다.
3. UNKNOWN/DELETE_UNKNOWN의 운영 reconciliation과 retention 절차. 알려진 지연 결과의 동일 generation 복구는 구현했지만, 식별자 자체를 확보하지 못한 외부 성공을 자동 확정할 수 없다.
4. 운영 기관별 입력 규격/권한 검토, 실제 Windows 설치·삭제·업데이트·서명/SmartScreen 검증과 실은행 인증. CI 정의는 아직 GitHub에서 실행하지 않았다.

## 13. Windows 11 흰 화면 조사 — 2026-10-11

사용자가 0.1.0 설치 후 Windows 11에서 오류 창 없이 흰 화면만 나타나는 현상을 보고했다. 수정 범위는 Electron 앱의 시작/화면 로딩과 패키징 검증에 한정하며 DB migration과 서버 변경은 필요 없다. 기존 개발 모드 smoke는 실제 설치 실행의 대체 증거가 아니었다.

ASAR의 main/preload/HTML/renderer가 현재 소스와 일치함을 확인했다. macOS에서 Windows artifact의 ASAR 직접 실행 및 동일 fuse를 적용한 ad-hoc signed 패키징 앱 실행은 정상이다. 따라서 Windows에서만 발생하는 원인은 아직 확정하지 않았다. GPU 가속을 끈 실행 결과를 사용자에게 확인 중이다. 검증 전에 보안 fuse/sandbox를 완화하거나 원인을 확정했다고 보고하지 않는다.

추가 검증은 실제 패키징 executable을 실행해 DOM·preload·스타일과 합성 screenshot을 확인하는 smoke로 보강한다. Windows 원본 재현은 사용자 실행 결과와 구분해서 기록한다. commit/push/PR/배포는 수행하지 않는다.

조사 결과: `test/packaged-smoke.cjs`를 추가해 실제 ASAR/fuse가 적용된 실행 파일의 HTML·스타일·sandbox preload·Node 비노출과 screenshot을 확인했다. macOS ad-hoc signed 패키징 실행은 통과했고 기본 화면을 직접 확인했다. 앱 unit 8개와 기존 Electron 전체 흐름 smoke도 다시 통과했다. Windows workflow에는 dir 패키징 이후 이 검사를 추가했지만 원격 Windows CI는 실행하지 않았다. 기존 0.1.0 Windows 설치본은 교체하지 않았다.

후속 응답 `ㅇㅇ`는 `--disable-gpu` 실행 시 화면이 정상화됐다는 뜻으로 해석하며, 이 해석을 사용자에게 명시했다. Windows에서만 `app.disableHardwareAcceleration()`을 ready 이전에 호출하는 보수적인 호환성 조치를 적용하고 0.1.1로 다시 빌드한다. sandbox와 ASAR integrity 등 보안 설정은 유지한다. 원인이 된 GPU/드라이버 조합 자체는 특정하지 않았다.

수정 전 Windows startup 순서 회귀 테스트의 실패를 확인한 후 최소 변경을 적용한다. 앱 전체 unit·Electron 흐름 smoke·실제 패키징 startup smoke를 다시 실행하고 새 설치 파일의 ASAR/버전/fuse를 검사한다. Windows 실기기에서 0.1.1 재설치 후 추가 인자 없이 실행되는지 확인은 별도로 필요하다. Java/SQL 수정과 dev/prod 활성화는 하지 않는다.

0.1.1 결과 (`DONE_WITH_CONCERNS`):

- 변경: Windows에서 `app.disableHardwareAcceleration()`을 ready 이전에 호출한다. 다른 OS의 그래픽 기본값과 sandbox는 유지한다. 호출 시점은 [Electron API 계약](https://www.electronjs.org/docs/latest/api/app#appdisablehardwareacceleration)을 따른다.
- 회귀: 수정 전 신규 테스트 2개 중 Windows 항목 1개가 실패했고, 수정 후 `npm test` 10개 모두 통과했다. `npm run test:smoke`와 0.1.1 macOS 실제 패키징 executable startup smoke도 통과했다. Windows 경로는 startup 단위 테스트로, 실제 패키징 화면은 macOS에서 검증한 것으로 구분한다.
- artifact: `apps/windows-certificate/dist/HowMuch-Certificate-0.1.1-x64-Setup.exe`, 111,651,713 bytes, SHA-256 `d2a11039cdcf196fa2448f74ccdbb6e4c95c77a1c8add4989fb0d93cf7ca81a8`. ASAR의 main이 수정 소스와 일치하고 package/lock/패키징 버전은 모두 0.1.1이다. 기존 보안 fuse 적용을 다시 확인했다.
- 제한: Windows 11 새 설치본의 실제 재실행 결과는 미확인이다. GPU/드라이버 원인은 특정하지 않았고 사용자 응답 해석에 기반한 호환성 조치다. 설치 파일은 여전히 `https://certificate-api.example.test`를 사용하는 미서명 화면/패키징 확인용이며 실제 금융 연결 배포본이 아니다.
- 게시 상태: 새 installer는 로컬 생성만 했고 commit/push/PR/서버 배포는 하지 않았다.

전 기능 기본 flag는 비활성이다. 현재 합성 검증을 전체 금융 연결 완료 또는 운영 준비 완료로 표시하지 않는다.

## 14. macOS 앱 서버 연결 조사 — 2026-10-11

사용자가 Windows 실행이 여전히 잘 되지 않는다고 보고했으므로 §13의 GPU 호환성 조치를 해결 완료로 판단하지 않는다. 요청에 따라 macOS 패키징 앱을 실행했으나 내장 API origin이 검증용 `https://certificate-api.example.test`여서 catalog 요청이 실패했다. 실제 dev/prod에 이번 API 구현을 배포한 상태도 아니다.

이번 수정 범위는 로컬 실행 환경 연결과 실행 문서다. 신규 SQL/Java 변경과 운영 배포는 필요 없다. 기존 원격 DB/Redis 설정을 사용하지 않고 별도 PostgreSQL·Redis·Kafka 컨테이너와 loopback 전용 API를 실행한다. CODEF 설정은 기존 환경에서 필요한 키만 메모리로 읽고 출력하거나 저장소에 기록하지 않는다. 앱은 기존의 unpackaged 전용 loopback override로 실행하며 패키징 HTTPS 검증을 완화하지 않는다.

검증 기준은 실제 API readiness, catalog HTTP 응답, Electron renderer의 서버 연결 오류 해소다. 운영 기관별 reviewed catalog가 아직 없어 금융사 지원 목록이 비어 있을 수 있으며 임의의 입력 규격을 검토 완료로 표시하지 않는다. 금융 인증·명의확인·최종 BIND 완료와 로컬 API 연결 성공을 구분한다. commit/push/PR은 수행하지 않는다.

실행 결과:

- 격리 compose project `howmuch-certificate-local`의 PostgreSQL 16.14(`127.0.0.1:15432`), Redis 7(`127.0.0.1:16379`), Kafka 4.2.2(`127.0.0.1:19092`) healthcheck 통과. 기존 컨테이너는 수정하지 않았다. `ops/kafka/topics.tsv`의 12개 토픽을 생성했다.
- local API `127.0.0.1:18081`를 `bootRun`으로 실행했다. 기존 V1–V45 migration 적용 및 Hibernate validate 이후 기동했다. management readiness `127.0.0.1:19091/actuator/health/readiness`는 HTTP 200/UP이다. 신규 migration은 추가하지 않았다.
- 실제 catalog endpoint가 HTTP 200, 금융사 20개, `supported=true` 0개를 반환했다. real CODEF 호출·인증서 업로드는 실행하지 않았다. CODEF 클라이언트 설정은 기존 환경에서 선택적으로 읽고 로컬 암호화 키만 저장소 밖 mode 0600 파일에 생성했다. SMS는 loopback 미사용 주소로 고정해 실제 메시지 발송을 연결하지 않았다.
- 기존 startup smoke를 로컬 API 전용 임시 harness로 실행해 실제 Electron의 HTML·스타일·sandbox preload·Node 비노출과 “현재 연결 가능한 금융사가 없습니다.” 문구를 확인했다. screenshot: `apps/windows-certificate/dist/local-api-startup.png`. 테스트 앱 종료 후 debugger 없이 개발 앱을 다시 열어 실행 중으로 유지했다.
- 이 세션 실행 파일·PID·로그·compose는 `/private/tmp/howmuch-certificate-local-k2sn8nog`에 있다. 사용자 앱 확인을 위해 API/앱/컨테이너를 실행 중으로 남겼다. 재시작 시 동일 로컬 키와 DB를 함께 유지한다. 임시 실행은 dev/prod 배포 또는 Windows 설치 파일의 서버 주소 교체가 아니다.

## 15. 개발 서버 주소 및 인증서 앱 디자인 정리 — 2026-10-11

사용자 요청: 기본 API origin을 `https://dev.howmuch.wars.p-e.kr`로 바꾸고 [Figma 앱 디자인](https://www.figma.com/design/F66LYtaZo3RE8TWnDwgpew?node-id=1-2)을 참고해 인증서 가져오기 화면으로 정리한다. 부제·보안 구현 설명·중복 안내는 제거한다. 별도 서버를 만드는 요청이 아니며 인증서 API는 기존 Spring 애플리케이션에 구현되어 있다.

사전 확인: 개발 서버 catalog 요청은 HTTP 401이고 `/v3/api-docs`에는 certificate 경로가 없다. 해당 API가 현재 노출되지 않았으며 미배포 또는 기능 비활성 상태일 수 있다. 주소 변경만으로 인증이 가능해졌다고 표시하지 않는다. PC 로그인이나 인증 우회는 추가하지 않는다.

변경 범위는 Electron의 기본 origin·정적 HTML/CSS·renderer 상태 표시·로컬 폰트 allowlist와 실행 문서다. Java/DB migration은 필요 없다. Figma design system의 Pretendard, blue `#0092FF`, gray scale와 codef 등록 화면(`4390:11548`, `10004:9912`)의 12px 입력 필드·54px 버튼을 사용한다. 기존 frontend의 동일 Pretendard 폰트를 로컬로 번들링한다. 모바일 상태바/키패드를 복제하는 대신 PC에서 실제 DER/KEY 파일을 선택하는 레이아웃으로 적용한다.

성공 기준: 기본/패키징 설정의 dev origin, 불필요한 설명 제거, 금융사별 추가 입력과 파일 선택/암호/QR/예약 거절 기능 유지, 오류·비활성·선택 완료 상태와 키보드 포커스, 실제 Electron screenshot 및 smoke 통과. 검증은 기존 unit·파일 업로드/QR 통합 smoke·실제 dev 응답과 앱 표시를 사용한다. 신규 fixture를 실제 금융 인증으로 표시하지 않는다. 예상 변경 단위는 `fix(certificate): 개발 서버 연결 설정 및 가져오기 화면 정리`이며 사용자 요청 없이 commit/push/PR/배포하지 않는다.

결과 (`DONE_WITH_CONCERNS`, UI/주소 변경 범위):

- main 기본 origin, 로컬 release config, workflow 수동 빌드 기본값을 dev 주소로 변경했다. 0.1.2 Windows/macOS ASAR 모두 동일 주소와 최종 main/HTML/CSS/renderer·폰트·라이선스가 포함됨을 검증했다.
- Figma blue/gray palette, Pretendard 400/600/700, 필드와 버튼 형태를 반영했다. PC용 단계 표시와 실제 파일명/선택 완료 상태를 추가하고 소개·암호화 설명·정상 상태 중복 안내·byte 단위 파일 크기 노출을 제거했다. 추가 입력, QR 만료, 확인 코드, 거절/취소 기능과 미완료 승인 안내는 유지했다.
- `npm test` 10개 통과. 기존 `npm run test:smoke`에서 실제 Electron의 폰트 로드·추가 입력·파일 선택 상태·업로드·QR·확인 코드·취소 후 초기화를 검증했다. 합성 파일/서버만 사용했으며 실제 금융사 인증 증거가 아니다.
- dev API를 사용하는 별도 로컬 실행 검증에서 HTTP 401에 맞는 “인증서 서비스가 아직 준비되지 않았어요.” 표시, 로컬 폰트 로드, 부제 제거, 비활성 CTA, Node 비노출, 900px/640px 레이아웃의 가로 overflow 없음을 확인했다.
- Windows NSIS 및 macOS ad-hoc signed dir 빌드 성공. 실제 macOS 패키징 executable의 `test/packaged-smoke.cjs` 통과: HTML·스타일·sandbox preload·Node 비노출·폰트·초기 API 응답 완료 후 화면 확인. screenshot은 `apps/windows-certificate/dist/packaged-startup-0.1.2.png`이며 가져오기/QR 합성 검증 이미지는 같은 dist의 `electron-import-smoke.png`, `electron-qr-smoke.png`다.
- Windows 설치 파일: `apps/windows-certificate/dist/HowMuch-Certificate-0.1.2-x64-Setup.exe`, 113,957,727 bytes, SHA-256 `5c5788a3b1f7e11a300d3094b628ca95ce87fa6d868ca27c375a1a59865a65fc`. Windows 실기기 실행과 서명/SmartScreen 검증은 하지 않았다. 원격 CI도 실행하지 않았다.
- 최신 macOS 패키징 앱을 debugger 없이 실행했다. 이전 세션의 임시 local API와 해당 compose 컨테이너는 정지하고 데이터/키/실행 파일은 보존했다. 기존 사용자 컨테이너는 수정하지 않았다. dev/prod 배포·PR·commit/push·머지는 하지 않았다.

남은 범위: dev의 certificate API 배포/활성화 및 운영 설정, 실제 금융사별 추가 입력 조건 확인, Windows 흰 화면의 실기기 재확인, 명의확인/최종 승인/BIND. UI·주소 변경을 전체 은행 연결 완료로 보고하지 않는다.

## 16. 인증서 목록 선택 및 HowMuch 아이콘 — 2026-10-11

문제: 사용자가 DER/KEY를 각각 고르는 현재 화면은 일반 공동인증서 선택 경험과 다르다. HowMuch 앱의 기존 마스코트 아이콘을 적용하고, 저장 위치 → 인증서 목록 → 암호 → 금융사 인증 → QR 순서로 변경한다. 별도 부제나 기술 설명은 추가하지 않는다.

참고: [한국장학재단 공동인증서 FAQ](https://www.kosaf.go.kr/ko/faq.do?ctgrId1=NODE0000001447&ctgrId2=&page=6&pg=&searchStr=&searchType=&ttab1=6&ttab2=tabitemlstA)의 인증서 목록/암호 입력 흐름과 Windows LocalLow NPKI·macOS Preferences NPKI 경로, [YESKEY 인증서 복사 안내](https://yeskey.or.kr/yeskey/yessign/myCert/common/manage/copy5)를 사용한다. 실제 지원하지 않는 브라우저·보안토큰·클라우드 저장소 탭은 만들지 않는다.

설계와 범위:

- Electron main에서 OS별 알려진 NPKI 폴더만 자동 탐색한다. USB/다른 위치는 native 폴더 선택창으로 사용자가 지정한다. 탐색 깊이·엔트리 수·인증서 수·파일 크기·시간을 제한하고 symlink를 따라가지 않는다.
- 같은 폴더의 `signCert.der`/`signPri.key` 쌍을 찾고 공개 인증서를 Node X509Certificate로 파싱한다. 화면에는 경로나 원문 subject 대신 opaque ID·소유자·발급기관·유효기간만 전달한다. 만료/미개시 인증서는 표시하되 선택 불가다. 유효기간 표시는 체인/명의 검증 완료를 뜻하지 않는다.
- 목록 탐색 시 개인키 내용을 읽지 않는다. 사용자가 인증서를 선택하고 가져오기를 누른 때 main이 두 파일을 제한된 크기로 읽고, 탐색 이후 변경 여부를 검사하여 기존 API 계약으로 전송한다. 버퍼는 finally에서 지운다. renderer는 임의 경로나 raw 파일을 요청할 수 없다.
- frontend-v2의 기존 `icon.png`/`logo.png`를 그대로 복사하여 패키지·dock/window·헤더에 적용한다. 폰트와 Figma 색상 토큰은 재사용한다.
- SQL/Java/API 계약/서버 설정 변경은 없다. Flyway migration 불필요. dev의 미노출 API와 Windows 흰 화면 실기기 검증은 이번 화면 개선과 별개로 남아 있다.

검증: 합성 공개 인증서 fixture로 자동 탐색·잘못된/불완전 파일·symlink·유효기간·파일 교체·탐색 상한을 확인하고, 실제 Electron에서 폴더 선택→목록 선택→기관별 입력→업로드→QR→취소를 검증한다. 실제 패키징 앱 startup/아이콘/ASAR와 900px·640px 화면을 확인한다. 실제 고객 인증서·비밀번호는 테스트에 사용하지 않는다.

예상 변경 단위: `feat(certificate): 인증서 자동 탐색과 목록 선택 적용`, `build(certificate): HowMuch 아이콘 및 새 설치 파일 반영`. 실제 commit/push/PR/배포는 요청되지 않아 수행하지 않는다. 완료 시 검증 결과를 이 절에 추가한다.

결과 (`DONE_WITH_CONCERNS`, 인증서 선택 UX 범위):

- `certificates.cjs`에 제한된 NPKI 탐색과 Node X509Certificate metadata 파싱을 구현했다. 개별 파일 선택 IPC를 제거하고 main이 발급한 opaque ID로 가져오기를 요청한다. 목록 조회는 공개 인증서만 읽고 개인키는 submit 때 읽는다. 변경된 파일·잘못된 ID·유효기간 오류는 업로드 이전에 거부한다.
- 저장 위치·소유자/발급기관/만료일 목록·암호 입력으로 바꿨다. 자동 탐색, USB/폴더 선택, 새로고침, 만료 항목 비활성, 선택 상태와 빈 목록/일부 탐색 안내를 적용했다. HowMuch 기존 PNG를 변형 없이 사용했다.
- `npm test` 16개 통과. 합성 공개 인증서만 사용한 discovery test 6개가 잘못된/불완전 파일, symlink 제외, 유효기간, 파일 교체, 이전 ID 무효화, 탐색 상한, 개인키 지연 읽기를 검증했다.
- `npm run test:smoke` 통과. 실제 Electron의 폴더 dialog 경계를 fixture로 대체하여 목록 선택→금융사별 추가 입력→업로드→QR→예약 코드→취소, 폴더 dialog 취소 시 선택 유지, 임의 discovery source 거부를 검증했다. 900px/640px screenshot의 가로 overflow 없음을 확인했다. 이미지: `apps/windows-certificate/dist/electron-import-smoke.png`, `electron-import-compact-smoke.png`, `electron-qr-smoke.png`.
- 0.1.3 Windows NSIS/macOS ad-hoc dir 빌드 성공. 실제 Mac 패키징 startup smoke에서 icon·discovery·local font·sandbox preload·Node 비노출을 확인했다. screenshot: `apps/windows-certificate/dist/packaged-startup-0.1.3.png`.
- Windows installer에서 실제 payload의 ASAR를 추출하여 소스/폰트/아이콘/config 15개와 일치함을 확인했다. Mac ASAR도 동일하다. package name/version/main, dev origin, 보안 fuse를 확인했다. Windows EXE/installer PE icon resource가 생성된 HowMuch ICO와 일치하며 Mac `icon.icns`와 ad-hoc signature도 검증했다.
- installer: `apps/windows-certificate/dist/HowMuch-Certificate-0.1.3-x64-Setup.exe`, 114,424,535 bytes, SHA-256 `cfae5c21eee07d9fce9ce78dcb3f7b1591e9d8e2622dc18c0df0ce52bff4d161`. PE security directory 확인 결과 미서명이며 Windows 실기기 실행 증거는 아니다. artifact 결과는 같은 dist의 `0.1.3-artifact-verification.json`에 있다.
- 이전 0.1.2 Mac 앱 프로세스를 확인 후 종료하고 0.1.3 앱을 debugger 없이 열었다. dev catalog는 재확인 시 HTTP 401이며 OpenAPI certificate 경로는 0개다. 실제 은행 인증·Windows 11 흰 화면 해결·브라우저/보안토큰/P12 인증서 지원을 완료로 표시하지 않는다. Java/SQL/서버 배포·commit/push/PR은 수행하지 않았다.

## 17. USB 인증서 자동 인식 — 2026-10-11

요청: 사용자가 USB에 복사한 인증서를 폴더 선택 없이 인식하고, 연결/분리에 따라 목록을 자동 갱신한다. 현재 macOS에서 외장 디스크에 마운트된 볼륨이 있음을 읽기 전용 `diskutil list -plist external`로 확인했다. 인증서 원문/소유자/개인키는 조사 로그에 남기지 않는다.

변경 설계:

- 새 main 전용 `volumes.cjs`가 Windows의 이동식 logical disk와 USB BusType 디스크 파티션, macOS의 외장 디스크 마운트 지점을 읽는다. OS 내장 명령을 고정 인자로 shell 없이 실행하고 timeout/output 상한을 적용한다. 새 native npm dependency, 관리자 권한, 실행 정책 완화는 추가하지 않는다.
- 앱 시작 시 USB가 있으면 해당 인증서 목록을 자동 표시한다. 등록 폼이 보이는 동안 3초 간격으로 마운트 상태만 확인하며, 상태가 바뀐 경우에만 인증서를 재탐색한다. USB 분리/교체 시 이전 선택·암호를 비운다. 금융사 접수/QR 중에는 자동 탐색하지 않는다. 사용자의 인증서 전송은 명시적인 가져오기 버튼에서만 실행한다.
- 내 컴퓨터/USB 위치를 분리하고 USB 버튼은 자동 탐색한다. 기존 폴더 선택은 “다른 위치에서 찾기” 보조 동작으로 유지한다. 알려진 USB `NPKI`를 먼저 확인한 뒤 기존 깊이/엔트리/시간/크기 상한 내에서 나머지를 탐색한다. 중첩 root 중복 탐색을 막는다.
- renderer에는 장치 수·opaque topology revision·오류 여부만 전달한다. 드라이브 경로/serial/UUID/파일 원문은 main 안에 남는다. 자동 탐색에서 개인키 내용을 읽지 않는 기존 계약을 유지한다.

Flyway migration은 필요 없다. Java/API/DB/서버 활성화는 변경하지 않는다. 예상 변경 단위는 `feat(certificate): USB 인증서 자동 탐색 및 연결 상태 갱신`이며 commit/push/PR은 요청되지 않아 수행하지 않는다.

검증 기준: OS 응답 fixture의 외장/내장/미마운트 구분, Windows USB fixed drive 포함, 오류·중복·경로 제한, 중첩 NPKI 중복 제거, 실제 Electron에서 시작 시 USB 인식/분리/재연결/암호 초기화/폴더 fallback/업로드·QR 회귀. 실제 연결된 Mac USB는 공개 metadata의 개수와 앱 목록만 확인하고 실제 비밀번호·개인키 전송은 하지 않는다. Windows native 테스트의 미검증 범위는 구분한다.

결과 (`DONE_WITH_CONCERNS`, USB 자동 인식 구현 범위):

- `volumes.cjs`와 좁은 `certificate:storage` IPC를 추가했다. OS volume query는 1초 cache/in-flight 공유를 사용하고 renderer에는 장치 수와 topology hash만 보낸다. Windows는 이동식 media와 USB BusType 파티션을 함께 확인한다. macOS는 외장 디스크의 mounted volume만 탐색한다.
- 시작 시 USB 자동 선택, 3초 topology polling에 따른 분리/재연결 자동 갱신, 선택·인증서/계좌 암호·계좌번호·생일 초기화를 적용했다. 접수/QR 동안 polling 탐색은 건너뛴다. USB는 폴더 dialog 없이 탐색하고 수동 폴더 선택은 별도 보조 버튼으로 남겼다.
- USB 탐색 지연을 실제로 관찰하여 기존의 loop 내부 시간 확인을 전체 scan 결과 deadline으로 보강했다. 5초 이후 반환된 filesystem 결과는 목록을 변경하지 못하고 새로운 inventory도 덮지 못한다. underlying OS I/O 자체를 취소한다고 주장하지 않는다. 중첩 NPKI/volume root 중복도 제거했다.
- Mac package에 `NSRemovableVolumesUsageDescription`을 넣었다. 첫 접근 권한은 [macOS의 사용자 허용 절차](https://developer.apple.com/documentation/bundleresources/information-property-list/nsremovablevolumesusagedescription)를 따른다. 빈 USB 목록이 부분/실패 상태였으면 앱 focus 복귀 시 재탐색한다. 권한을 우회하거나 자동 허용하지 않았다.
- `npm test` 22개 통과. 추가된 6개가 OS volume 응답 분류/경로 제한/장치 교체 hash/에러, 중첩 root 중복, stalled filesystem deadline과 late-result fencing을 검증했다. `npm run test:smoke`는 실제 Electron에서 USB startup→분리→재연결→모든 credential 초기화→수동 fallback→업로드/QR/예약/취소를 통과했다. 실제 사용자 저장소는 해당 합성 smoke에 연결하지 않았다.
- 실제 Mac OS query는 USB mounted volume 1개를 반환했다. 파일 탐색은 5,001ms에 `limited=true`, 발견 0개로 반환됐다. 이를 인증서 부재 또는 권한 원인 확정으로 해석하지 않는다. 실제 USB 인증서 목록/암호/은행 연결은 아직 검증하지 못했다. 자동 인식 기능과 사용자 USB 파일 접근 검증을 구분한다.
- 0.1.4 Windows NSIS 및 macOS ad-hoc dir 빌드 성공. 실제 Mac 패키징 startup에서 icon/discovery/fonts/preload/Node 비노출을 통과했다. USB I/O 대기 상태에서 테스트 자식의 정상 종료가 지연되어 해당 자식만 확인 후 SIGKILL로 정리했고, harness에 1초 종료 fallback을 추가했다. 앱 화면 검증과 종료 보강의 실기기 재검증을 구분한다.
- installer: `apps/windows-certificate/dist/HowMuch-Certificate-0.1.4-x64-Setup.exe`, 114,427,681 bytes, SHA-256 `3694dd58b9f893d32ba03d5ed3092b142614c002b0bd38a1cab7366274c3edd4`. 실제 installer payload/Mac ASAR의 소스·font·icon·config 16개, package name/version/main, HowMuch PE/ICNS icon, 보안 fuse, Mac ad-hoc signature 일치를 검증했다. 결과는 같은 dist의 `0.1.4-artifact-verification.json`에 있다.
- 이전 0.1.3 Mac 앱을 확인 후 닫고 0.1.4 앱을 debugger 없이 열었다. Windows native hotplug/실행과 코드서명/SmartScreen은 미검증이다. dev API 미노출·명의확인·최종 BIND 범위는 이번 변경으로 해결됐다고 표시하지 않는다. Java/SQL·서버 배포·commit/push/PR은 수행하지 않았다.

## 18. 인증서 금융사 기본 지원 — 2026-10-11

요청: 사용자가 인증서 방식은 금융사 전체에서 가능하다고 확인했다. 별도 reviewed allowlist 설정이 없어 은행 선택이 비어 있는 문제를 없애고 활성 `BANK` 전체를 선택할 수 있게 한다. 사용자 확인은 지원 정책의 근거이며 실제 모든 은행의 인증 성공을 검증했다는 의미는 아니다.

설계와 영향 범위:

- 기존 기관 catalog Output Adapter가 활성 BANK를 그대로 지원 목록으로 제공한다. 목록 밖/비활성 은행을 생성·처리하지 않는 Application 검사는 유지한다. 새로운 UseCase/Port/API/저장 구조는 추가하지 않는다.
- `howmuch.certificate.institutions`는 은행별 등록/계좌조회 계좌 정보와 생년월일 추가 입력 조건만 지정한다. 미설정 은행은 추가 입력을 요구하지 않는다. `BANK.attr3` ID/PW 요구사항을 복사하지 않는다.
- 기존 `reviewed` 필드는 설정 호환성을 위해 수용하지만 지원 판단에 사용하지 않는다. `reference`는 선택적인 근거 metadata이며 비어 있어도 지원한다. 운영 문서의 이전 allowlist 설명을 수정한다.
- 인증서 feature flag, capability/JWT, 암호화, 원문 전송 조건, 명의확인/최종 BIND 경계는 변경하지 않는다.

Flyway migration은 필요 없다. 예상 변경 단위는 `fix(certificate): 활성 금융사 인증서 기본 지원`이며 commit/push/PR/운영 배포는 요청되지 않아 수행하지 않는다.

검증 기준: 기본 설정에서 활성 BANK 전체 지원, 기관별 추가 입력 조건 유지 및 다른 은행에 전파되지 않음, reviewed/reference 없이 HTTP 등록→Kafka worker→QR 합성 회귀, 목록 밖 코드 거부, Java compile, 로컬 API 재시작 후 catalog 전체 지원과 실제 Electron 은행 선택 표시. 실제 고객 인증서 업로드/CODEF 호출은 하지 않는다.

결과 (금융사 선택 제한 제거 범위 완료):

- catalog의 reviewed/reference 지원 제한과 Properties의 reference 필수 검사를 제거했다. 추가 입력 설정과 활성 BANK 조회 경계는 유지했다. legacy reviewed 값은 지원 여부에 영향을 주지 않는다.
- `./gradlew compileJava test --tests '*CertificateInstitutionCatalogAdapterTest' --tests '*CertificateHttpIntegrationTest' --no-daemon` 성공. Java 21, PostgreSQL/Redis/Kafka Testcontainers 사용, 3개 테스트 실패/오류/skip 0. 설정 없는 전체 지원, ID/PW attr3 분리, 은행별 추가 입력, reviewed/reference 없이 HTTP→Kafka→QR, 목록 밖 코드 400을 확인했다. 통합 테스트 종료 중 컨테이너가 먼저 내려간 뒤의 연결 경고가 있었으며 테스트 실패는 없었다.
- 같은 로컬 DB/암호화 키를 유지하여 API를 재시작했다. readiness HTTP 200 `UP`, catalog HTTP 200 금융사 20개 중 지원 20개를 확인했다. 실제 Electron 개발 앱을 다시 열어 금융사 option 20개, 선택 활성화, 서버/빈 목록 오류 없음과 프로세스 실행 상태를 확인했다. API/앱은 실행 중으로 남겼다.
- 변경 문서의 상대 링크와 `git diff --check`를 확인했다. 실제 은행 인증/추가 입력 요구사항 확인, dev/prod 배포, 최종 명의확인/BIND는 수행하지 않았다. backend 응답 변경이므로 Windows 설치 파일은 다시 빌드하지 않았다. commit/push/PR은 수행하지 않았다.

## 19. 한국어 앱 이름 및 우리은행 계좌 입력 — 2026-10-11

요청: 화면의 HowMuch 표기, 앱/설치 이름을 `얼마 인증서 가져오기`로 통일한다. 추가로 사용자가 우리은행은 인증서 등록에 계좌번호와 계좌 비밀번호도 필요하다고 확인했으므로 우리은행 선택 시 해당 입력을 필수로 받는다.

사전 확인: 로컬 실행 기록의 우리은행 REGISTER 2건이 `REGISTRATION_REJECTED`로 종료됐다. 로그/저장소는 세부 CODEF 오류를 보관하지 않으므로 계좌 누락이 해당 2건의 직접 원인이라고 단정하지 않는다. 현재 catalog의 우리은행 추가 입력이 모두 false여서 앱이 계좌를 수집하지 않고 provider도 계좌 필드를 전송하지 않는 것은 코드와 응답에서 확인했다.

설계와 영향 범위:

- Electron HTML title/header, native app name, installer product/artifact 이름과 CI의 실행 경로를 변경하고 클라이언트 버전을 0.1.5로 올린다. 내부 npm ID, appId, protocol/API/QR 계약과 원본 아이콘은 유지한다.
- 기존 `howmuch.certificate.institutions` 기본 설정에 우리은행 `0020`의 `registration-account-required=true`를 넣는다. 활성 BANK 전체 기본 지원을 유지하며 다른 은행의 추가 입력 규격이나 생년월일을 추정하지 않는다. 외부 설정이 institutions 목록을 교체하면 우리은행 항목도 함께 지정해야 한다.
- 기존 renderer의 필수 입력, Application의 누락 거부, provider의 `withdrawAccountNo` 및 RSA 암호화된 `withdrawAccountPassword` 전송을 사용한다. 새로운 Port/UseCase/Endpoint/저장 구조나 원문 로그를 추가하지 않는다. 과거 실패 등록을 재실행하지 않는다.

Flyway migration은 필요 없다. 예상 변경 단위는 `fix(certificate): 우리은행 인증서 등록 계좌 입력 필수화`, `build(certificate): 인증서 앱 한국어 이름 적용`이다. commit/push/PR/운영 배포는 요청되지 않아 수행하지 않는다.

검증 기준: Java compile 및 선택 회귀, catalog의 우리은행 계좌 필수/나머지 활성 은행 지원, 계좌 없는 HTTP 업로드 400 및 올바른 합성 입력→Kafka→QR, CODEF payload 계좌/RSA 변환, Electron 이름/은행 필수 입력/기존 가져오기 회귀, Windows NSIS/macOS 패키징과 ASAR/이름 확인. 실제 고객 인증서와 은행 계좌 비밀번호를 합성 테스트에 사용하지 않는다. 서버와 개발 앱을 재시작하여 사용자 확인 상태로 남긴다.

패키징 보완 설계: electron-builder 26.15.3은 Mac helper 이름을 NFD로 만들지만 `CFBundleName`은 NFC로 기록한다. 한국어 이름 적용 후 실제 Mac 패키징 앱이 JS 실행 전에 SIGTRAP으로 종료되는 것을 재현했다. [upstream 분석/수정](https://github.com/electron-userland/electron-builder/pull/9962)의 이름 불일치와 현재 설치된 builder 코드를 확인했다. 기존 `before-pack.cjs`에서 Mac에만 `CFBundleName`을 builder의 실제 `sanitizedProductName`과 맞춘다. 사용자 표시 이름과 Windows 설정은 유지하며 주요 의존성 업그레이드나 보안 fuse 변경은 하지 않는다. 같은 이름의 Mac 패키지 실행으로 해결 여부를 검증한다.

결과 (이번 요청 범위 완료):

- 화면 title/header, native 앱 이름, Windows 실행 파일·installer product 이름을 `얼마 인증서 가져오기`로 적용했다. 내부 npm ID/appId/프로토콜은 유지하고 버전을 0.1.5로 올렸다. CI의 실제 실행 경로와 운영/앱 문서를 수정했다.
- 우리은행 `0020`의 등록 계좌 필수 설정을 공통 YAML에 추가했다. 계좌 입력 없는 업로드는 400으로 거부하고 provider를 호출하지 않는다. 올바른 합성 입력의 HTTP→Kafka→QR 회귀와 `withdrawAccountNo`, RSA 변환된 `withdrawAccountPassword` 전송을 확인했다. 다른 활성 은행 지원 정책은 유지했다.
- Java 21 `./gradlew compileJava test --tests '*CertificateInstitutionCatalogAdapterTest' --tests '*CertificateHttpIntegrationTest' --tests '*CodefCertificateRegistrationAdapterTest' --no-daemon` 성공. PostgreSQL/Redis/Kafka Testcontainers, 총 6개 실패/오류/skip 0. 테스트 종료 중 컨테이너 shutdown 뒤 연결 경고는 테스트 실패와 구분했다. Electron `npm test` 22개 및 `npm run test:smoke` 성공. 새 앱 이름·필수 입력·합성 계좌 전송과 기존 USB/폴더/QR/예약 회귀를 검증했다.
- Windows NSIS 빌드 성공: `apps/windows-certificate/dist/얼마-인증서-가져오기-0.1.5-x64-Setup.exe`, 114,427,020 bytes, SHA-256 `cae58323059b2cb400fac3e50840754e79d0e7870d62fe269da70512231a91ee`. installer payload/Mac ASAR의 소스·font·icon·config 16개, package name/version/main/description, PE/ICNS icon, 보안 fuse와 Mac ad-hoc signature를 검증했다. 결과는 같은 dist의 `0.1.5-artifact-verification.json`에 있다.
- 초기 Mac 빌드는 서명 미설정 및 이름 정규화 문제로 startup 검증에 실패했다. ad-hoc signing을 지정하고 pre-pack 이름 일치를 반영한 최종 패키지에서는 startup smoke가 성공했다. display name/version/4개 helper 경로를 확인했다. 실제 USB 목록을 캡처하지 않도록 screenshot은 header 76px만 저장했다 (`dist/packaged-header-0.1.5.png`). Windows native 실행, 배포 코드서명/SmartScreen과 Mac notarization은 검증하지 않았다.
- 기존 로컬 DB·암호화 키를 유지해 API를 재시작했다. readiness `UP`, catalog 20개 전부 지원, 우리은행 등록 계좌 필수/생년월일 선택 설정을 확인했다. 개발 Electron을 다시 열어 한국어 앱/title/header, 우리은행 선택, 계좌 두 필드 표시/필수, 서버 오류 없음과 금융사 20개를 확인했다. 로컬 API/개발 앱은 실행 중으로 남겼다.
- 공통/CI YAML parsing, 문서 상대 링크와 `git diff --check`를 확인했다. 실제 은행 등록 성공이나 과거 실패 원인은 확정하지 않았다. 이전 실패 등록을 재실행하지 않았고, dev/prod 배포·전체 Java suite·최종 명의확인/BIND·commit/push/PR은 수행하지 않았다. 설치본의 내장 origin은 dev이며 이번 로컬 서버 변경만으로 dev API가 활성화되지는 않는다.

## 20. CODEF 등록 오류 진단 — 2026-10-11

문제: 최근 우리은행 2건이 `REGISTRATION_UNCERTAIN`으로 종료됐지만 provider adapter가 원래 예외를 버려 HTTP 상태와 원인 종류를 확인할 수 없다. 이전 명시적 거절도 CODEF 상세 코드를 남기지 않는다.

설계: 기존 CODEF certificate outbound adapter 안에서만 진단을 추가한다. 응답의 `CF-` 숫자 코드, HTTP 상태, 예외/원인 클래스 및 내부 Application 상태 코드만 기록한다. 원문 응답/예외 메시지/stack trace/인증서/비밀번호/계좌/connectedId는 기록하지 않는다. RSA 준비 단계 예외도 같은 경계에서 포착한다. 기존 UNKNOWN/거절 판정, cause 비노출과 등록 무재시도 보장을 유지한다. API·Kafka·DB 계약 변경과 Flyway migration은 없다. 예상 변경 단위는 `fix(certificate): CODEF 등록 실패의 안전한 진단 코드 기록`이다. commit/push/PR은 수행하지 않는다.

검증: 합성 HTTP 오류와 RSA 준비 오류 및 CODEF 거절 응답의 코드가 기록되고 민감한 fixture 문자열이 기록되지 않는 회귀를 추가한다. 기존 no-cause/no-retry 회귀, Java compile과 관련 CODEF 테스트를 실행한다. 로컬 API에 적용하고 실제 등록을 자동 재시도하지 않는다. 별도 OAuth-only 점검에서 Python과 Java 모두 HTTP 200, Java RSA 공개키 설정 점검은 성공했다. 이는 과거 등록 실패 원인을 확정하거나 실제 은행 등록 성공을 의미하지 않는다.

결과:

- adapter에 단일 등록 진단 경계를 추가했다. RSA 준비와 HTTP/토큰 예외를 기록한 뒤 원문 cause 없는 기존 uncertain 예외로 변환한다. HTTP 오류 응답은 allowlist CODEF 코드만 추출하며, 정상 HTTP의 거절/불명확 응답은 result/account 코드만 기록한다. 기존 분류·단일 호출 동작은 유지했다. 운영 문서에 필드 의미와 과거 상세 오류 복원 불가를 기록했다.
- 먼저 회귀 3개가 실패하는 것을 확인했다 (5개 중 3개 실패). 수정 후 Java 21 `./gradlew compileJava test --tests '*CodefCertificateRegistrationAdapterTest' --tests '*CodefCertificateSingleAttemptTest' --no-daemon` 성공: 6개, 실패/오류/skip 0. HTTP 400/CF 코드 보존, RSA 예외 종류, 원문 fixture 비노출, cause 비노출, 401 포함 무재시도 및 기존 payload/거절 처리를 확인했다. 전체 suite와 실제 인증서 등록은 실행하지 않았다.
- Java 표준 HTTP/RSA 점검과 실제 `CodefTokenClient`/`CodefRestClientConfig`의 OAuth-only 점검은 성공했다. 토큰·키·응답 본문은 출력하지 않았다. 별도 source-file 방식 진단은 실행에 실패해 검증으로 계산하지 않고, javac/classpath 방식으로 실제 Spring client 점검을 완료했다. 이 점검은 인증서/계좌 등록 요청을 보내지 않는다.
- 같은 영구 로컬 키와 DB를 유지해 API를 재시작했고 readiness `UP`를 확인했다. 과거 UNKNOWN/실패 요청은 재실행하지 않았다. 이미 버린 과거 HTTP/CF 코드는 복원하지 못한다. 다음 실제 시도의 진단은 추가된 안전한 로그에서 확인해야 하며, 이번 변경을 실은행 오류 해결 완료로 판단하지 않는다. dev/prod 배포·SQL·commit/push/PR은 수행하지 않았다.


## 21. 개인키 전송 무결성 재검토 — 2026-10-11

요청: CODEF 대시보드에서는 `derFile`과 달리 `keyFile`이 반복된 `A`로 보인다. 비밀번호 오입력으로 단정하지 않고 파일 읽기부터 CODEF HTTP 직전까지 개인키 훼손 여부와 RSA 규격을 검토한다. 대시보드의 마스킹 정책은 확인되지 않았다.

설계/영향 범위: 기존 Electron discovery/client 테스트와 Java provider/protection 테스트에서 비영(非零) 합성 개인키의 바이트 보존을 확인한다. 파일 읽기 임시 버퍼 소거, 비동기 업로드 이후 소거, DTO Base64 decode, upload envelope seal/open, material 복사/close, provider Base64 변환, 실제 RSA/UTF-8 암호화와 URL-encoded HTTP 직렬화를 확인한다. 실제 고객 인증서·비밀번호는 테스트에 사용하지 않는다. 공식 계정 등록 규격의 DER/KEY `certType="1"`을 명시한다 (문서 기본값도 1이므로 과거 오류 원인이라고 주장하지 않는다). 실패 응답 진단에는 DER/KEY 바이트 길이 및 전체 0 여부만 추가한다. 원문, 비밀번호, 해시/지문, 경로, 사용자 정보를 로그에 추가하지 않는다. 호출 횟수와 세션/실패 판정은 변경하지 않는다.

API·Kafka·DB 계약 변경과 Flyway migration은 없다. 예상 변경 단위는 `fix(certificate): DER KEY 등록 규격 명시 및 무결성 진단 보강`이다. commit/push/PR과 dev/prod 배포는 하지 않는다.

검증: 기존 테스트를 확장해 실제 파일 read→Base64 body 보존, seal→원 material close→open→실제 RSA→CODEF HTTP 직렬화의 합성 경로를 확인한다. 기존 보호/무재시도 회귀와 Java compile, Electron unit을 실행한다. 로컬 DB/키를 유지해 서버에 반영하며 실제 은행 호출은 자동으로 수행하지 않는다. 결과는 검증 후 기록한다.


검토/검증 결과:

- Electron `readFile`은 임시 Buffer의 subarray를 `Buffer.from`으로 복사한 뒤 임시 영역을 소거한다. main의 `return await client.upload(...)` 이후 finally에서만 material을 지운다. Java material 생성/getter도 clone하며 seal은 동기 직렬화, open은 constructor clone 후 임시 배열을 소거한다. provider는 Base64 문자열을 만든 뒤 자기 복사본만 소거한다. 검토한 경로에서 전송 전에 원본을 소거하는 오류는 발견하지 못했다.
- 기존 Electron discovery 테스트를 2,048-byte 합성 개인키와 지연 fetch로 확장했다. 파일 read→Base64 요청 바이트 일치, upload 완료 후 buffer 소거, 이미 만들어진 요청 문자열과 파일 재조회 값 보존을 확인했다. `npm test` 22개 통과, 실패/skip 0.
- 기존 Java payload 테스트에서 실제 DTO decode→AES envelope seal→원 material close→open→실제 RSA PKCS#1/UTF-8→CODEF client URL encode→Mock HTTP body decode를 연결했다. DER/KEY 원본 바이트, 인증서 password의 공백/+/한글 보존, 계좌 password의 RSA 변환, 복사본 소거 후 worker material 보존과 단일 HTTP 요청을 확인했다. 별도 protection 회귀에도 key 바이트 검증을 보강했다.
- `certType="1"`과 진단 필드 기대를 추가한 첫 Java 실행은 10개 중 2개가 실패했다. 이를 반영한 뒤 Java 21 `./gradlew compileJava test --tests '*CodefCertificateRegistrationAdapterTest' --tests '*CertificateProtectionTest' --tests '*CodefCertificateSingleAttemptTest' --no-daemon` 성공: 10개 실패/오류/skip 0. 비영/전체 영 key 진단, 민감 fixture 비노출과 무재시도 회귀도 확인했다.
- 기존 DB/영구 키를 유지해 로컬 API를 재시작했고 readiness `UP`를 확인했다. 실제 은행 등록을 자동 수행하거나 과거 UNKNOWN 요청을 재실행하지 않았다. 다음 실제 거절 응답의 keyAllZero로 outgoing key가 전부 0인지 확인할 수 있다. 전체 0이 아니어도 잘못된 key/DER 조합 또는 비밀번호 문제를 배제하지는 못한다.
- 대시보드 마스킹 정책, 실제 사용자 USB 파일/비밀번호의 유효성과 실은행 등록 성공은 확인하지 않았다. `certType`의 미명시는 공식 default가 1이므로 CF-12302의 확인된 원인으로 보고하지 않는다. 전체 Java suite, Windows 실제 실행/설치본 재빌드, dev/prod 배포와 commit/push/PR은 수행하지 않았다. Electron production 소스 변경이 없어 기존 설치 파일은 그대로다.


## 22. QR 예약 API 직접 호출과 Swagger 인증 수정 — 2026-10-11

사용자가 현재 QR의 API 호출 검증을 요청했다. 현재 앱 QR은 60초 유효시간이 지나 만료됐고 원문 토큰은 서버에 저장하지 않으므로 그 QR의 성공 호출은 수행하지 못했다. 실제 사용자 계정과 등록 세션은 변경하지 않았다.

로컬 DB에 전용 합성 사용자 fixture를 만들고 공개 `/api/v2/auth/login`으로 정상 로그인해 JWT를 발급했다. 별도 합성 등록 완료 fixture에서 실제 HTTP·JWT 필터·PostgreSQL·Redis를 사용해 QR 발급, 예약 201, 6자리 확인 코드의 PC/모바일 일치, 동일 멱등키 중복 요청의 동일 결과, 다른 키의 QR 재사용 404, PC 거절과 재발급, 만료 QR 410, 무인증 401 및 잘못된 PC capability 404 등 17개 확인 항목을 통과했다. 합성 세션과 claim은 검증 후 제거했다. CODEF 요청은 보내지 않았고 토큰·capability·QR 원문은 검증 결과에 포함하지 않았다.

Swagger 명세의 claim operation에 `bearerAuth` 요구가 누락되어 있었다. `CertificateClaimDocs`에 기존 AuthDocs와 같은 `SecurityRequirement`를 추가했다. 인증 필터와 권한 규칙, DB 구조는 바꾸지 않았으며 신규 Flyway 마이그레이션은 필요하지 않다. `./gradlew compileJava` 성공 후 로컬 API에 반영했고 실제 `/v3/api-docs`의 operation security와 서버 재시작 뒤 JWT 수용을 확인했다. dev/prod 배포와 실제 사용자 QR 성공 호출, 명의확인·승인/BIND·계좌 연결 검증은 수행하지 않았다.

검증 집계: [QR API 결과](codef-certificate-qr-api-results.json). 커밋·push·PR은 생성하지 않았다.


## 23. 현재 실행 중인 실제 앱 QR 예약 검증 — 2026-10-11

사용자가 새로 표시한 실제 Electron 앱의 QR 이미지에서 payload를 읽어 전용 로컬 테스트 계정 JWT로 `/api/v2/certificate-claims`를 호출했다. 합성 등록 세션으로 대체하거나 실제 세션의 DB 상태·QR hash를 직접 변경하지 않았다. HTTP 201과 `RESERVED`, 6자리 확인 코드, DB의 실제 세션 `CLAIM_RESERVED` 및 응답 claim ID 일치를 확인했다. PC 앱에 표시된 확인 코드를 이미지 인식으로 읽어 API 응답과 일치하는 것도 확인했다.

이 검증에서는 금융사 등록 요청을 보내거나 명의확인·최종 승인/BIND를 진행하지 않았다. 실제 QR 원문·JWT·확인 코드는 검증 문서에 포함하지 않는다. 검증 집계: [실제 앱 QR 결과](codef-certificate-current-qr-results.json).


## 24. 인증서 앱 커스텀 상단 바 — 2026-10-11

사용자가 기본 창 제어 버튼을 없애고 종료만 가능한 커스텀 상단 바를 요청했다. HTML 제목과 상단 문구를 `인증서 가져오기`로 변경하고, 기존 로고와 드래그 영역, 접근 가능한 종료 버튼을 제공한다. Electron 창은 native frame을 제거하고 최소화·최대화·전체화면을 비활성화한다. 종료 요청은 기존 sender 검증을 거치는 전용 IPC만 노출한다. 설치 앱 이름과 금융사 인증·QR/API 계약은 유지한다. DB 변경이 없어 Flyway 마이그레이션은 필요하지 않다.

검증: 기존 단위 테스트와 실제 Electron smoke에서 창 속성, 제목, 드래그 영역, 종료 버튼의 IPC 동작 및 기존 인증·QR 흐름을 확인한다. 예상 변경 단위는 `fix(certificate): 인증서 앱 상단 바와 종료 버튼 적용` 하나이며, 사용자 요청에 따라 커밋·push·PR은 생성하지 않는다.

검증 결과: `npm test` 24개 성공, `npm run test:smoke` 성공. 실제 Electron에서 최소화·최대화·전체화면 비활성화, 상단 drag/종료 버튼 no-drag, 종료 버튼의 sandboxed IPC 전달을 확인했다. 종료 검증은 테스트 창의 close 이벤트를 가로채 요청 도착을 검증하며 실제 OS 종료 동작 전체를 검증하지 않는다. desktop/compact 화면을 캡처하고 desktop 화면을 확인했다. 로컬 앱 재시작 후 서버 연결과 금융사 목록 20개를 확인했다. `node --check` 3개 소스 성공. 최초 저장소 루트의 `npm test`는 package.json 부재로 실패했으며 앱 디렉터리에서 다시 실행해 성공했다. Java 변경이 없어 Java 검증은 수행하지 않았다. Windows 실제 실행 및 설치 파일 재빌드는 수행하지 않았다.
