# 공동인증서 API·Electron 구현 및 QA 인계

- 검증일: 2026-10-11
- 기능 branch: `feat/489-certificate-registration`, 기반 commit `ed3bcf09`
- 상태: 인증자료 접수 → CODEF 등록 → QR 예약/거절까지 구현·합성 검증. 최종 사용자 귀속과 계좌 연결은 미완료.
- 관련 문서: [Feature Plan](codef-certificate-registration.md), [ADR-0007](../../adr/0007-api-resident-certificate-execution-and-electron-client.md), [운영 설정](../../../ops/certificate/README.md), [Electron 실행/빌드](../../../apps/windows-certificate/README.md), [검증 결과 JSON](codef-certificate-api-client-results.json)

## 구현한 동작

Windows 앱의 main process가 사용자가 직접 선택한 DER/KEY 파일을 읽고, 비밀번호와 기관별 추가 입력을 고정된 API origin으로 전송한다. renderer에는 파일 경로·원문·PC capability를 돌려주지 않는다. 서버는 서로 독립적인 PC/upload capability를 검사하고 AES-256-GCM envelope로 암호화한 upload와 REGISTER Outbox를 같은 transaction에 기록한다. local/test 외에는 KMS가 필요하다.

API 내부 provider는 Kafka 명령을 Inbox/job에 기록하고 별도 worker에서 CODEF를 호출한다. 외부 호출 동안 DB transaction을 유지하지 않는다. 확인된 결과와 암호화된 연결 자료, result Outbox, 원문 upload 제거를 함께 반영한다. result consumer가 세션을 `READY_FOR_CLAIM`으로 전환하면 PC가 60초 QR을 발급한다. 로그인한 활성 회원은 QR을 배타적·멱등하게 예약하고 PC와 같은 확인 코드를 받는다. PC 상태 조회·예약 거절·취소 및 미승인 세션 만료 정리를 제공한다.

기관 catalog는 활성 `BANK` 전체를 공동인증서 방식에서 기본 지원하며 별도 reviewed allowlist를 요구하지 않는다. 응답의 `BANK.attr3` 인증 수준은 ID/PW 제공 여부를 포함해 표시하되, 등록 시 필요한 계좌 정보와 나중에 계좌 조회에 필요한 정보는 기관별 설정으로 계속 구분한다. 조회에만 필요한 정보는 암호화해 유지하며 CODEF 등록 요청에는 보내지 않는다. 실제 은행별 추가 입력 조건과 금융 인증 성공은 합성 테스트로 확인한 범위가 아니다.

2026-10-11 기본 지원 정책 변경 검증: `CertificateInstitutionCatalogAdapterTest` 2개와 `CertificateHttpIntegrationTest` 1개 및 `compileJava` 통과. 로컬 API 재시작 후 catalog 20개 전부 지원, 실제 Electron 개발 앱의 금융사 선택 20개와 오류 메시지 없음 확인. 이전 catalog 지원 0개 관찰 결과는 [Feature Plan §18](codef-certificate-registration.md#18-인증서-금융사-기본-지원--2026-10-11)에서 변경했다. 실제 고객 인증자료는 사용하지 않았다.

2026-10-11 실제 USB 미표시 원인 조사 (`DONE`, 진단 범위):

- OS에서 외장 NTFS USB 볼륨 1개를 확인했다. 실제 Electron main의 파일 접근을 경로/파일명/소유자 없이 집계한 임시 진단으로 재현했다. 마지막 전체 탐색은 135ms, 발견 0개, `limited=false`, `unavailable=false`, pending/error 0이었다. 이전 5초 지연은 재현되지 않아 현재 원인으로 판단하지 않았다.
- 탐색에서 디렉터리 10개/파일 25개가 관찰됐다. `.der` 24개 중 공개 X509로 파싱 가능한 22개 모두 CA 인증서였고, 나머지 2개는 X509로 파싱되지 않았다. 개인용 end-entity 인증서 0개, `.key`/`signCert.der`/`signPri.key`/`USER` 디렉터리 0개였다. 따라서 현재 탐색한 USB에는 선택 가능한 개인 인증서 쌍이 없다. CA 인증서를 개인 인증서로 표시하도록 코드를 완화하지 않았다.
- 진단은 공개 DER만 최대 128KiB로 제한해 읽었다. 개인키 내용, 비밀번호, 소유자/subject, 원문은 출력하거나 전송하지 않았다. USB 파일을 수정하지 않았다. 진단 앱을 종료하고 원래 소스의 로컬 개발 앱을 다시 열었다.
- 사용자 조치: 개인 인증서를 [은행 인증서 관리의 USB 복사 절차](https://svc.wooribank.com/svc/Dream?withyou=CTCER0049)로 옮긴다. [한국장학재단 인증서 위치 안내](https://www.kosaf.go.kr/ko/faq.do?ctgrId1=NODE0000001447&ctgrId2=&page=6&pg=&searchStr=&searchType=&ttab1=6&ttab2=tabitemlstA)의 Windows 사용자 LocalLow NPKI/발급기관/USER/본인 폴더에는 `signCert.der`와 `signPri.key`가 함께 있다. 현재 USB의 원본 복사 경로는 추정하지 않았다.
- 제품 코드 변경이나 신규 빌드는 없으며 자동 테스트를 다시 실행하지 않았다. 진단 집계는 저장소 밖 `/private/tmp/howmuch-certificate-local-k2sn8nog/usb-diagnostic.json`에 있다.

인증서 원문, 비밀번호, 계좌번호, connectedId와 capability는 Kafka/QR에 포함하지 않는다. QR에는 `v`, `type`, `sessionId`, `qrToken`만 있다. byte buffer는 사용 후 지우지만 JVM/JavaScript의 immutable String까지 완전히 소거한다고 보장하지 않는다.

## 발행 선점과 실행 선점

| 구분 | 선점 대상과 보장 | 장애 시 동작 |
|---|---|---|
| Publication claim | command/result Outbox의 30초 lease, token/generation과 만료 조건으로 ACK 후 완료 반영을 제한 | lease 만료 후 같은 messageId로 재발행. 중복 도착은 Inbox에서 제거 |
| Execution claim | provider Inbox/job의 10분 lease와 실행 generation. HTTP 전송 시작 marker를 먼저 commit | 시작 전 실패만 재시도. 시작 후 응답 불명확/worker 유실은 UNKNOWN, 자동 create 재호출 금지 |
| QR claim | session lock, QR hash/만료/generation, 사용자와 idempotency key로 단일 예약 | 동일 요청은 같은 예약/코드 반환. 거절·만료 후 기존 QR 재사용 차단 |

Kafka의 at-least-once 전달과 DB의 멱등 처리를 사용한다. CODEF까지 exactly-once를 보장하는 구조는 아니다. broker ACK 유실은 메시지 중복으로 복구할 수 있지만 외부 create의 timeout은 실제 생성 여부를 확정할 수 없다. 같은 실행 generation의 지연된 확정 응답은 UNKNOWN을 복구할 수 있다. 성공 후 KMS/DB 오류로 connectedId 자체를 보관하지 못한 경우는 별도 운영 확인이 필요하다.

취소가 불명확한 REGISTER를 기다릴 때는 CANCEL을 실행 후보에서 제외해 retry를 반복하지 않는다. 같은 generation의 지연 성공이 연결을 확정하면 취소 정리가 진행된다. 삭제 결과도 불명확하면 `DELETE_UNKNOWN` 증거를 보존하고 자동으로 반복 삭제하지 않는다.

## 수행한 검증

macOS에서 Java 21/Gradle Wrapper, Node 24 및 Docker를 사용했다. PostgreSQL 16.14, Kafka 4.2.2, Redis 7 Testcontainers와 합성 CODEF 응답·파일을 사용했다. 실제 금융 인증자료와 운영 서비스는 사용하지 않았다.

| 검증 | 결과 | 확인 범위 |
|---|---|---|
| `./gradlew compileJava` | 성공 | 신규 Java와 기존 공통 코드 컴파일 |
| 아래 Java 선택 테스트 2회 | 서로 다른 42개 모두 성공, 실패/오류/skip 0 | 최초 40개, 보강 후 14개 재실행. 중복 실행 12개를 제외한 수 |
| `npm test` | 8개 성공 | API origin, IPC frame, 입력 제한, capability 비공개, upload 무재시도, 응답 제한, 불명확한 취소 후 명시적 재시도/404 종료 |
| `npm run test:smoke` | 성공 | 실제 Electron main/preload/renderer 및 로컬 fixture API를 통한 파일 선택·등록·QR 이미지·예약 코드·비밀번호 필드 비움 |
| `npm audit --omit=dev --audit-level=high` | 취약점 0 | runtime npm dependencies |
| `npm run dist:win` | x64 NSIS artifact 생성 | macOS에서 Windows 패키징, ASAR/fuse 적용 확인 |

Java 명령:

```bash
./gradlew test --tests '*Certificate*Test' --tests '*KafkaMessagingConfigurationTest' --tests '*KafkaPublicationTest' --no-daemon
./gradlew test --tests '*CertificatePersistenceIntegrationTest' --tests '*CertificateWireSchemaTest' --no-daemon
```

주요 회귀 시나리오:

- 실제 HTTP 접수 → 실제 Kafka command/result 두 단계 → READY → QR. 잘못된 PC capability 404, 비인증 모바일 401, 초과 body 413, 취소 성공.
- 같은 messageId/payload 중복의 단일 효과와 payload 충돌 거부. 실제 publisher의 REGISTER/CANCEL 및 세 결과 variant가 공개 JSON Schema와 일치.
- 동시 실행 worker 8개의 단일 선점, stale lease 완료 차단, 시작 전 재시도와 시작 후 UNKNOWN의 구분, 동일 generation 지연 성공, 확정 결과 뒤 UNKNOWN 무시.
- 업로드 일회 소비/Outbox 원자성, QR 배타·멱등 예약/거절 및 이전 QR 차단, 미승인 연결의 확정 삭제/불명확 삭제 보존, UNKNOWN 등록을 기다리는 취소의 비실행 및 지연 성공 후 정리.
- CODEF 인증서 create의 401 자동 재호출 금지와 cached OAuth token 제거. envelope/AAD 및 입력/암호문 경계.

Electron smoke의 OS 파일 선택 dialog는 합성 파일을 반환하도록 대체했다. Electron 자체와 IPC/DOM/HTTP는 실제 실행했다. QR 화면 캡처는 `apps/windows-certificate/dist/electron-qr-smoke.png`에 있는 로컬 ignored artifact이며 Windows 실행 증거는 아니다. GitHub Windows workflow는 정의만 했고 실행하지 않았다.

## 설치 artifact

최신 0.1.5는 `apps/windows-certificate/dist/얼마-인증서-가져오기-0.1.5-x64-Setup.exe`다. 앱 표시 이름은 `얼마 인증서 가져오기`, 내장 API는 dev origin이다. 미서명이며 Windows native 실행은 미검증이다. dev API의 인증서 기능 배포/활성화가 필요한 상태는 유지된다. 아래 0.1.0은 최초 fixture 기록이며 최신 실행 검증은 마지막 0.1.5 절을 참고한다.

`apps/windows-certificate/dist/HowMuch-Certificate-0.1.0-x64-Setup.exe`는 `https://certificate-api.example.test`를 사용하는 **패키징 검증 전용** 파일이다. dev/prod 사용 가능한 설치본이 아니다. 크기는 111,651,775 bytes이고 SHA-256은 `7f60bbeee7a449c394589c0cc458603e66a34a01a19fc31fa7bf667eba078040`이다.

PE security directory가 0으로 코드서명되지 않았다. `runAsNode`, Node options 환경변수, CLI inspect는 껐고 ASAR integrity/ASAR-only는 켰다. 실제 Windows 설치/삭제·시작·업데이트·SmartScreen 및 코드서명 검증은 수행하지 않았다. 서명된 배포본에는 검토된 API origin과 별도 signing secrets/environment가 필요하다.

## 남은 개발과 활성화 조건

1. **독립적인 금융 명의확인 계약.** 기존 연동을 저장소에서 찾지 못해 사용자에게 확인 중이다. PC의 동의, 확인 코드, 자기 입력 생일만으로 인증서 소유자와 모바일 회원이 같다고 확정하지 않는다.
2. **최종 승인/BIND와 계좌 연계.** 검증된 ownership reference를 사용하는 승인 API/불변 approval, 기존 ExternalConnection·계좌 후보/선택 및 사용자 탈퇴 처리를 이어서 구현해야 한다. 이번 API/앱에는 승인 endpoint가 없다.
3. **운영 reconciliation/retention.** UNKNOWN/DELETE_UNKNOWN을 실제 CODEF 결과와 대조하는 관리 절차와 완료 자료 retention worker는 아직 없다. 불명확한 연결 생성 결과를 DB 조작이나 재생성으로 덮지 않는다.
4. **운영 기관/Windows 검증.** 우리은행·iM뱅크 등의 등록/조회 추가 입력 및 CODEF 권한을 실제 계약/문서와 대조하고, Windows 실기기 및 실은행 수용 테스트를 진행해야 한다.

현재 `CERTIFICATE_ENABLED=false`가 기본이며 dev/prod에서 활성화하지 않았다. V45, Kafka topic/ACL, KMS 권한, HMAC/CODEF secrets, HTTPS/프록시 제한 등 환경별 설정은 [운영 runbook](../../../ops/certificate/README.md)에 있다. `git diff --check`, 변경 문서의 로컬 링크와 신규 텍스트 공백, YAML parsing을 확인했다. 직접 만든 임시 PostgreSQL 컨테이너도 제거했다. 전체 repository test suite, 실제 AWS KMS, 실은행 호출, dev/prod 배포는 수행하지 않았다.

## 기술 선택 설명과 비교

동기 HTTP에서 CODEF까지 한 번에 처리하면 구현은 짧지만 응답 지연과 서버 재시작이 사용자 요청에 직접 영향을 주고, DB 저장과 작업 전달 사이에 유실 경계가 생긴다. Outbox는 업무 상태와 작업 기록을 한 transaction으로 남기고, Kafka는 전달과 consumer 분리를 담당하며 Inbox는 replay 중복 효과를 막는다. 대신 lease/재시도/UNKNOWN 상태와 운영 관측·정리 비용이 늘어난다.

Kafka transaction만으로 외부 CODEF 호출이나 우리 PostgreSQL 변경을 하나의 원자 작업으로 만들 수 없다. 따라서 broker의 producer idempotence와 별개로 DB claim/fencing이 필요하고, 외부 호출의 불확실성은 UNKNOWN으로 표현한다. 알림 발송과 인증서 등록의 재시도 정책은 같은 Kafka 기반에서도 다르다. 인증서 create는 중복 외부 자산을 만들 수 있어 시작 후 자동 재호출을 막았다.

인증서 provider를 처음부터 별도 finance-connector 서비스로 두면 배포·보안 권한·자원 격리가 쉽다. 이번에는 사용자 결정에 따라 API 내부로 모아 배포와 계약 검증을 단순하게 했고, Application Port와 command/result 계약으로 추후 분리 경계를 남겼다. 현재는 API와 자원·장애 영향·KMS 권한을 공유한다.

Electron은 웹 기술로 UI와 QR을 만들고 Windows installer를 함께 관리하기 쉽다. C#/WPF보다 Chromium/Node runtime의 용량과 업데이트 부담이 크며 IPC 보안이 필요하다. 작은 단일 폼에는 React/TypeScript/bundler를 추가하지 않고 local HTML/CSS/JavaScript를 사용했다. main에 파일/네트워크를 제한하고 sandboxed renderer에는 좁은 preload API만 노출했다.

## Git 및 PR 상태

SQL-only [PR #498](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/498)은 조회 시 이미 MERGED/APPROVED였고 merge 시각은 2026-10-10 23:45:02 KST였다. 이번 작업에서 머지를 실행하지 않았다. SQL 파일 변경은 추가하지 않았으며 기능 코드는 별도 기능 worktree에 미커밋 상태로 보관한다. 구현 commit/push/새 PR 생성과 서버 배포는 수행하지 않았다. 발행 전 최신 `develop`과 diff를 맞추고 남은 승인/계좌 연결 범위를 함께 검토해야 한다.

## 2026-10-11 인증서 목록 선택 / 0.1.3 추가 검증

개별 DER/KEY 선택을 OS 기본 NPKI 자동 탐색과 USB/폴더 선택으로 교체했다. 목록은 공개 인증서의 소유자·발급기관·만료일을 표시하고 유효기간 밖의 인증서는 선택할 수 없다. 개인키는 명시적인 가져오기 요청 때만 읽으며 경로/bytes는 renderer에 전달하지 않는다. 기존 HowMuch 앱 아이콘을 Windows EXE·installer, Mac 앱·dock과 화면 헤더에 적용했다.

`npm test` 16개, 실제 Electron 합성 API smoke, Mac 실제 패키징 startup smoke가 통과했다. Windows installer payload와 Mac ASAR의 최종 소스/폰트/아이콘/config 일치, PE icon resource, Mac ICNS/서명, 버전과 보안 fuse를 확인했다. 이번 범위에는 Java/DB 변경이 없어 Gradle이나 DB migration 검증은 다시 실행하지 않았다.

최신 installer는 `apps/windows-certificate/dist/HowMuch-Certificate-0.1.3-x64-Setup.exe`다. 크기 114,424,535 bytes, SHA-256 `cfae5c21eee07d9fce9ce78dcb3f7b1591e9d8e2622dc18c0df0ce52bff4d161`. 기본 서버는 dev 주소이며 현재 catalog HTTP 401/OpenAPI certificate 경로 0개로 실제 등록은 아직 사용할 수 없다. Windows 설치본은 미서명이고 실기기 흰 화면 재현/해결, 실제 금융 인증, 명의확인/최종 BIND는 미검증 또는 미구현 상태를 유지한다. 자세한 범위와 제한은 [Feature Plan §16](codef-certificate-registration.md#16-인증서-목록-선택-및-howmuch-아이콘--2026-10-11)에 기록했다. 새 Mac 앱을 열어두었으며 서버 배포·게시·머지는 하지 않았다.

## 2026-10-11 USB 자동 인식 / 0.1.4 추가 검증

USB mounted volume을 OS에서 자동 열거하고, 앱 시작과 연결/분리·교체를 반영하도록 변경했다. 자동 갱신 시 이전 선택과 credential 입력을 지우며, 기존 수동 폴더 선택은 보조 버튼으로 유지한다. 실제 인증서·개인키의 서버 전송은 가져오기 버튼에서만 발생한다. Mac의 최초 이동식 디스크 접근은 OS 권한 허용이 필요할 수 있다.

`npm test` 22개와 실제 Electron 합성 USB startup/unplug/replug/암호 초기화·폴더 fallback·업로드/QR smoke를 통과했다. 실제 Mac 패키징 startup와 양쪽 패키지 source/config/icon/fuse 일치를 확인했다. 실제 Mac에서는 USB mounted volume 1개를 인식했지만 파일 탐색은 5초 deadline에 도달해 실제 인증서 읽기 성공은 확인하지 못했다. Windows native USB 검증과 실은행 연결 검증도 남아 있다.

당시 0.1.4 파일은 `apps/windows-certificate/dist/HowMuch-Certificate-0.1.4-x64-Setup.exe`다. 114,427,681 bytes, SHA-256 `3694dd58b9f893d32ba03d5ed3092b142614c002b0bd38a1cab7366274c3edd4`. 미서명이며 자동 인식 코드/패키징 검증을 Windows 실기기 동작 완료로 표시하지 않는다. 새 Mac 앱을 열어두었다. 상세 결과와 종료 지연 정리는 [Feature Plan §17](codef-certificate-registration.md#17-usb-인증서-자동-인식--2026-10-11)에 기록했다.

## 2026-10-11 한국어 앱 이름 / 우리은행 계좌 입력 / 0.1.5 검증

- 로컬 실행의 우리은행 REGISTER 2건은 `REGISTRATION_REJECTED`였다. 세부 CODEF 사유는 남아 있지 않아 계좌 누락을 직접 실패 원인으로 확정하지 않는다. 사용자가 확인한 등록 요구사항에 따라 우리은행 `0020`에 계좌번호·계좌 비밀번호 필수 설정을 적용했다.
- 계좌 누락 시 HTTP 400 및 CODEF 미호출, 정상 합성 계좌를 사용한 HTTP→Kafka→QR, CODEF 요청의 계좌번호/RSA 암호 변환을 확인했다. `compileJava`와 Java 선택 테스트 6개, Electron unit 22개와 실제 Electron 합성 smoke가 통과했다. 실제 고객 인증서/계좌 비밀번호를 테스트에 사용하거나 이전 실패 요청을 재생하지 않았다.
- 화면·앱·installer 이름은 `얼마 인증서 가져오기`다. Windows NSIS 0.1.5는 114,427,020 bytes, SHA-256 `cae58323059b2cb400fac3e50840754e79d0e7870d62fe269da70512231a91ee`. installer payload/Mac ASAR의 소스와 assets/config 16개, package metadata, icon, 보안 fuse와 Mac ad-hoc signature를 확인했다.
- 실제 Mac 패키지는 초기 JS 실행 전 SIGTRAP을 재현했다. electron-builder 26의 NFC `CFBundleName`과 NFD helper filename 불일치를 pre-pack hook으로 맞춘 뒤 startup smoke가 통과했다. 사용자 표시 이름, 4개 helper 경로, HTML/style/fonts/icon/discovery/sandbox preload/Node 비노출을 확인했다. screenshot은 개인정보가 포함되지 않는 header만 저장했다. 상세 원인과 [upstream 근거](https://github.com/electron-userland/electron-builder/pull/9962)는 [Feature Plan §19](codef-certificate-registration.md#19-한국어-앱-이름-및-우리은행-계좌-입력--2026-10-11)에 있다.
- 로컬 API/개발 앱을 재시작했다. readiness `UP`, catalog 20개 지원, 우리은행 등록 계좌 필수, 실제 앱의 우리은행 계좌 입력 두 필드 표시/필수 및 오류 없음 확인. API와 개발 앱은 실행 중이다. 설치본은 내장 dev HTTPS origin을 사용하므로 아직 배포되지 않은 dev 인증서 API에 로컬 변경이 자동 반영되지는 않는다.
- Windows 실기기/SmartScreen, Mac notarization, 실은행 재인증, dev/prod 배포와 전체 Java suite는 수행하지 않았다. 최종 명의확인/승인/BIND 개발 범위도 유지된다. 문서 상대 링크, YAML parsing과 `git diff --check`를 확인했다. commit/push/PR은 수행하지 않았다.
