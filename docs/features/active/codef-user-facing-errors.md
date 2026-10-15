# 기능 계획: CODEF 오류 전달과 사용자 안내

## 앱 확인 체크와 오류 안내 보강 — 2026-10-11

상태: 오류 재현·수정 및 실제 Electron 화면 검증 완료. 수정한 로컬 앱 재실행 완료.

사용자 요청: 6자리 확인 코드가 정상 수신되면 성공 체크를 표시하고, 인증서 암호 오류가 화면에서 누락되거나 눈에 띄지 않는 문제를 확인한다. 실제 로컬 등록 실패에는 `CF-12302`가 기록돼 있다. 기존 200/FAILED 경로는 안전한 문구를 전달하지만 회색 보조 문구로 표시하며, renderer의 상태 조회/QR 발급 catch는 구체적인 공통 오류를 일반 연결 문구로 치환한다. 당시 화면은 보관되지 않았으므로 그때 어느 경로를 거쳤는지는 단정하지 않는다.

변경 범위: Electron의 `renderer.js`, `style.css`, `index.html`과 실제 renderer/IPC를 실행하는 `test/smoke.cjs`. Application/API/Kafka/DB 계약과 인증 규칙은 유지한다. Flyway 마이그레이션은 필요하지 않다. 기존 오류 allowlist를 사용하며 provider 원문·암호를 UI나 로그에 노출하지 않는다. 예정 변경 단위는 앱 상태/오류 안내 개선 하나이며, 사용자 지시대로 commit·push·PR은 수행하지 않는다.

완료 기준: 유효한 RESERVED/6자리/만료 전 claim 응답을 받은 뒤 체크와 연결 요청 확인 문구를 표시한다. claim 조회 실패, 잘못된 코드, 만료 또는 실패로 전환되면 이전 체크와 코드/QR을 제거한다. 인증서 암호 오류는 FAILED 결과와 HTTP 예외 모두에서 눈에 띄는 안내로 표시하고 알려지지 않은 원문은 노출하지 않는다. 일시적인 조회 실패 후 성공하면 오래된 실패 안내를 지운다.

검증: 실제 Electron smoke의 화면 표시·computed style·IPC 및 비동기 시나리오를 확장해 수정 전 실패를 확인하고 수정 후 통과시킨다. 앱 전체 unit suite와 smoke를 실행하고 합성 성공/오류 화면을 직접 확인한다. 실제 인증자료로 자동 재시도하거나 CODEF 등록을 추가 호출하지 않는다. 로컬 API가 정상이며 진행 중 등록 작업이 없으면 수정 앱을 재실행한다. Windows 네이티브 실행과 설치 파일 재빌드는 이번 검증 범위에 포함하지 않는다.

검증 결과:

- 수정 전 실제 Electron smoke에서 상태 조회의 `BANK_CERTIFICATE_PASSWORD_INVALID`가 일반 연결 문구로 바뀌고 암호 안내가 비어 있는 실패를 재현했다. 수정 후 동일 회귀가 통과했다.
- `npm test`: 전체 24개 통과. `npm run test:smoke`: 정상 체크, 잘못된 코드와 만료된 claim의 체크 제거, 일시 조회 실패와 복구, 일반 FAILED/접수 HTTP/상태 HTTP/QR HTTP/즉시 FAILED 응답의 안내를 검증했다. 원문 노출과 잔존 코드·QR이 없으며 안내가 화면 안에 실제 표시되는지 확인했다.
- 합성 성공 화면과 인증서 암호 오류 화면을 직접 확인했다. 관련 artifact는 앱 `dist/electron-claim-success-smoke.png`, `dist/electron-certificate-error-smoke.png`에 있으며 개인 인증자료가 아닌 고정 테스트 데이터만 포함한다.
- 로컬 API readiness UP 및 진행 중 REGISTER 작업과 유효한 등록 세션이 각각 0개인 것을 확인한 후 실제 로컬 앱을 수정 코드로 다시 실행했다. Java/API 계약 변경은 없어서 Java 테스트는 추가 실행하지 않았다. 실제 금융사 인증 재호출, Windows 네이티브 검증·설치 파일 재빌드, commit·push·PR은 수행하지 않았다.

디버깅 보고: 증상은 성공 체크 부재와 암호 오류 안내 누락, 확인된 원인은 claim 표시 시점 검증 부족과 조회/QR catch의 공통 오류 폐기 및 낮은 안내 강조도다. renderer에서 확인된 claim만 체크를 표시하고 실패 시 코드/QR을 제거하며, allowlist의 안전한 안내를 오류 카드로 전달한다. 회귀는 `apps/windows-certificate/test/smoke.cjs`에 있다. 당시 실제 시도의 화면 경로를 사후 확정한 것은 아니며 Windows 검증은 남아 있어 보고 상태는 `DONE_WITH_CONCERNS`다.

- 상태: 로컬 구현·검증 완료, 사용자 지시로 PR 게시 보류
- 갱신일: 2026-10-11
- 관련 기능: [공동인증서 등록](codef-certificate-registration.md), [기존 오류 분류](codef-failure-specificity.md)
- 관련 이슈: [#489](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/489), [#493](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/493)

## 1. 목적과 완료 조건

인증서 등록의 `CF-04000` 바깥 응답 안에 `CF-12302` 같은 구체적인 기관 오류가 있어도
현재 worker는 `REGISTRATION_REJECTED`만 저장한다. 사용자는 인증서 암호, 계좌 암호,
인증서 만료 중 무엇을 확인해야 하는지 알기 어렵다. 동기식 은행 연결·계좌·거래내역 조회의
`CodefFailureMapper`도 일부 코드만 분류한다.

- [x] 인증서 등록과 은행 연결·계좌 목록·거래내역 조회가 같은 오류 의미와 정적 안내 문구를 사용한다.
- [x] 비동기 등록의 확정 실패 코드는 command inbox → result outbox → Kafka → API result inbox → session에서 보존된다.
- [x] API는 우리 `StatusCode`의 코드와 한국어 메시지로 사용자에게 안내한다. 앱은 이 메시지를 표시한다.
- [x] 암호·인증서·계좌번호·생년월일·CODEF 원문 메시지/예외/응답은 이벤트나 사용자 오류에 포함하지 않는다.
- [x] 전송 실패·부분 성공·성공 흔적이 있는 결과는 `UNKNOWN`을 유지하며 CODEF create를 자동 재실행하지 않는다.

범위 밖: 새로운 CODEF 상품, 현재 consumer가 없는 finance-sync 실패 흐름 신설, 이체·카드·보험 오류 전수 지원, BIND/명의확인 구현, 알림 기능 추가,
현재 성공한 등록의 재실행, dev/prod 배포, 인증서/암호 원문 분석.

## 2. 도메인과 흐름

기존 `certificate`, `external` 도메인과 `common.exception.StatusCode` 계약을 재사용한다.
새 도메인이나 Output Port는 필요하지 않다.

| 사용자 목표 | 기존 UseCase/경계 | 변경 |
|---|---|---|
| 인증서 등록 진행/실패 확인 | `GetCertificateRegistrationUseCase` | 실패 코드·메시지 제공 |
| 인증서로 금융기관 등록 | `CertificateProviderAccountPort` / worker | 확정 거절의 안전한 CF 코드 보존 |
| 등록 결과 수신 | `ReceiveCertificateMessagesUseCase` | 안전한 CF 코드 수신·중복 처리 유지 |
| 은행 연결/계좌/거래 조회 | 기존 CODEF outbound adapters | 공통 오류 매핑 재사용 |

```text
CODEF 응답
  → Adapter: 대상 organization의 errorList 코드를 우선 선택
  → 확정 거절만 FAILED + 안전한 CF 코드
  → command inbox + result outbox (동일 transaction)
  → Kafka 결과
  → API result inbox + session (동일 transaction)
  → 공통 오류 분류
  → Web Result/Response.failureCode + failureMessage
  → Electron/모바일 안내
```

중복 메시지는 payload hash로 같은 사실인지 확인한다. 동일 messageId의 변경 payload는 거부한다.
확정 결과의 불변성, 선점 lease/generation, stale result CAS, UNKNOWN의 사후 확정 처리 규칙은 유지한다.

## 3. 오류 선정과 공통 코드

근거: [CODEF 공식 오류 코드](https://developer.codef.io/common-guide/error-code).
2026-10-11 공식 페이지에서 내려받은 `CODEF_API_오류코드_20261011.xlsx`와 현재 호출 경로를 확인했다.
아래는 **Java 구현의 계약**. 기관의 원문 메시지는 복사하지 않는다.

| CF 코드 | 공통 코드(기존 또는 추가 예정) | 사용자 안내 방향 |
|---|---|---|
| `CF-12302` | `BANK_CERTIFICATE_PASSWORD_INVALID` | 인증서 암호를 확인. 반복되면 인증서를 다시 가져와 확인 |
| `CF-12300`, `CF-12301`, `CF-11205` | `BANK_CERTIFICATE_FILE_INVALID` | 인증서 파일을 다시 가져오기 |
| `CF-12805`, `CF-12813`, `CF-04030` | `BANK_CERTIFICATE_REGISTRATION_REQUIRED` | 금융기관에서 인증서 등록 상태 확인 |
| `CF-12806` | `BANK_CERTIFICATE_EXPIRED` | 인증서 갱신/재발급 |
| `CF-12807`, `CF-12808`, `CF-12819` | `BANK_CERTIFICATE_UNAVAILABLE` | 폐기·만료·효력 정지 여부를 금융기관에서 확인 |
| `CF-12809`, `CF-12810`, `CF-12814` | `BANK_CERTIFICATE_INVALID` | 사용 가능한 인증서 종류와 금융기관 로그인 확인 |
| `CF-12800`, `CF-12801`, `CF-12803`, `CF-12861` | `BANK_CREDENTIAL_INVALID` | 은행 로그인 정보 확인 |
| `CF-12802`, `CF-12834` | `BANK_CREDENTIAL_LOCKED` | 금융기관에서 로그인 제한 해제 |
| `CF-12920`, `CF-12922` ~ `CF-12926`, `CF-13030`, `CF-13031`, `CF-13033` ~ `CF-13035` | `BANK_ACCOUNT_PASSWORD_INVALID` | 계좌 암호와 입력 형식 확인 |
| `CF-12921`, `CF-13032` | `BANK_ACCOUNT_PASSWORD_LOCKED` | 재입력 반복 중단, 금융기관에서 제한 해제 |
| `CF-12094`, `CF-12910` ~ `CF-12912`, `CF-13013`, `CF-13020` ~ `CF-13024` | `BANK_ACCOUNT_NUMBER_INVALID` | 본인 보유 계좌번호 확인 |
| `CF-13005`, `CF-13060` ~ `CF-13064` | `BANK_BIRTH_DATE_INVALID` | 생년월일 입력 확인 |
| `CF-12804` | `BANK_IDENTITY_MISMATCH` | 입력한 본인 정보와 인증 정보 일치 여부 확인 |
| `CF-03002`, `CF-04034`, `CF-04035` / `continue2Way` | `CODEF_ADDITIONAL_AUTH_REQUIRED` | 추가 인증 필요 안내. 등록 사실이 불확실하면 상태는 UNKNOWN |
| `CF-12001` | `CODEF_USER_ACTION_TIMEOUT` | 사용자 인증 대기 만료 안내 |
| `CF-00012`, `CF-00022`, `CF-00023` | `CODEF_RATE_LIMITED` | 조회 제한 안내, 자동 create 재시도 없음 |
| `CF-00011`, `CF-00016`, `CF-00018`, `CF-01001` ~ `CF-01007`, `CF-09999`, `CF-12203` | `CODEF_UPSTREAM_UNAVAILABLE` | 외부 연결/이용시간 문제 안내 |
| `CF-00004`, `CF-00005`, `CF-00010`, `CF-00013` ~ `CF-00015`, `CF-00017`, `CF-00026`, `CF-04020`, `CF-04028` | `CODEF_CONFIGURATION_INVALID` | 서비스 연결 설정 확인이 필요함. 사용자 암호 오류로 안내하지 않음 |
| `CF-04008`, `CF-04014`, `CF-04015`, `CF-04019` | `BANK_ACCOUNT_RECONNECT_REQUIRED` | 저장된 연결 확인/재연결 안내. `registeredOrganizations`의 CF-04014 → 빈 목록 예외 유지 |
| `CF-04000` | `BANK_CONNECTION_FAILED` | 내부 구체 오류가 없을 때만 일반 등록 실패 |
| 형식상 안전하지만 미지원 CF 코드 | `CODEF_UNMAPPED_ERROR` | 확인되지 않은 금융기관 오류 안내 |
| 정상 CODEF 코드/응답 구조 없음 | `CODEF_RESPONSE_INVALID` 또는 불확정 등록 | 잘못된 응답을 암호 오류로 추정하지 않음 |

인증서 암호 오류 코드는 실제 원인이 언제나 사용자의 오타임을 증명하지 않는다.
앞선 KEY 파일 손상 사례처럼 반복 실패 시 인증서 재가져오기도 안내한다.
부분 성공 `CF-04012`, `connectedId`, 성공한 기관 목록 등의 생성 흔적이 있으면
상세 오류가 있어도 확정 실패로 단정하지 않는다.

## 4. 계약과 저장 설계

### 확정 실패의 내부 값

현재 `failureCode` 슬롯을 재사용해 legacy 내부 실패 코드 또는 **정확히 8자 `CF-[0-9]{5}`**를 보존한다.
`CF-00000`은 성공이므로 실패 슬롯에 허용하지 않는다. 원문 메시지는 저장하지 않는다.
알 수 없는 안전한 CF 코드도 보존해 공통 fallback으로 안내할 수 있다.
새 컬럼·JSON payload·DB 매핑 테이블을 만들지 않는다.

CF 값은 registration 확정 실패에만 허용한다:

- `certificate_provider_command_inbox`: `command_type='REGISTER' AND status='FAILED'`.
- `certificate_provider_result_outbox`: `message_type='REGISTRATION_FAILED'`.
- `certificate_registration_sessions`: `FAILED`; 취소/만료 이후에는 원래 사유를 보존할 수 있어 `CANCELLED`, `EXPIRED`도 허용.
- `REGISTRATION_UNKNOWN`에는 계속 `REGISTRATION_UNCERTAIN`만 허용한다.
- outbox 발행 실패 `last_failure_code`는 기존 브로커/lease 오류 전용으로 유지한다.

### Kafka/API 호환성

CF 전달은 현재 v1 enum을 그대로 확장해 곧바로 발행하면 구버전 consumer가 거부한다.
v1 consumer가 legacy 값과 안전한 CF 값을 모두 읽도록 먼저 배포한 뒤
`CERTIFICATE_DETAILED_ERRORS_ENABLED=true`로 producer의 상세 오류 발행을 활성화한다. 기본 false는 legacy `REGISTRATION_REJECTED`를 발행한다. 기존 receipt/outbox 재발행은 같은 failureCode를 유지한다.
신규 topic이나 message ID 재발급으로 같은 CODEF 작업을 재실행하지 않는다.
스키마·listener·wire contract 회귀와 배포 순서를 함께 검증해야 한다.

공통 코드/정적 문구 정의는 `ExternalConnectionStatusCode`, CODEF → 공통 코드 기술 변환은
`CodefFailureMapper`를 재사용한다. Kafka의 CF 값은 DB에 원형을 보존하고 session 조회의
outbound mapper가 공통 코드로 바꾸어 반환한다. Application은 공통 코드의 정적 메시지를 조립하며
Web DTO는 Result를 그대로 매핑한다. Application이 outbound mapper를 import하지 않는다.

비동기 조회는 HTTP 200의 등록 상태 조회를 유지하면서 `data.failureCode`(우리 코드)와
`data.failureMessage`를 반환한다. 동기 API는 기존 `ErrorResponse.code/message`에 같은 코드를 쓴다.
앱은 임의 CODEF 원문을 표시하지 않고 정해진 메시지만 표시한다.

### Flyway 결정

- **필요: Yes**. V44 session과 V45 command inbox/result outbox의 CHECK가 고정된 실패 코드만 허용한다.
- `V46__allow_codef_certificate_failure_codes.sql`: 네 CHECK만 확장한다.
- latest `origin/develop` 최대 버전 V45, 열린 develop 대상 PR에 migration 추가 없음(2026-10-11 확인).
- migration PR: 사용자 지시로 보류. SQL 이외의 문서·Java·테스트·설정은 이 PR에 포함하지 않는다.
- Redis·TTL·인증 권한 변경 없음. 환경변수 `CERTIFICATE_DETAILED_ERRORS_ENABLED` 추가(기본 false). 새 컬럼/인덱스/데이터 backfill 없음.
- 기존 Flyway 파일은 변경하지 않는다. ALTER TABLE 동안 잠금이 발생하므로 배포 시 고려한다.

## 5. 영향과 구현 순서

| 영역 | 변경/검증 대상 |
|---|---|
| CODEF adapters | 기관별 errorList 우선 선택, 공통 mapper, HTTP/불확정 결과 구분 |
| Application | 기존 registration 결과 전달, 공통 오류 의미 해석 |
| DB | V44/V45 네 CHECK 확장, 이전 실패 값/정상 데이터/불변 trigger 유지 |
| Kafka | listener/schema의 legacy+CF 허용, 재발행/중복/미지원 코드 테스트 |
| API/앱 | 공통 코드와 정적 한국어 메시지, 사용자 입력/잠금/운영 장애 안내 구분 |
| 보안 | 원문/credential 유출 금지, UNKNOWN 자동 create 재시도 금지 |
| 문서 | 실제 오류 표와 배포 순서, 기존 CF-04000 설명 정정 |

- [x] SQL-only migration을 별도 `feat/codef-error-migration` 브랜치에서 검증.
- PR gate 예외: 2026-10-11 사용자가 “PR은 나중에 생성하자”라고 지시하여 commit/push/PR을 보류하고 로컬 구현을 계속한다. SQL은 별도 worktree에 보관하며 feature worktree에도 검증용 동일 파일을 둔다. 게시 시 SQL-only 경계를 지킨다.
- [x] 로컬 구현 완료: 공통 코드/정적 메시지와 CODEF mapper 구현.
- [x] 로컬 구현 완료: adapter → inbox/outbox → Kafka → session 오류 보존.
- [x] 로컬 구현 완료: 조회 Result/Web DTO/Electron 안내 연결.
- [x] Java/Electron 회귀와 단계적 배포 설정/절차 검증. dev/prod 배포는 수행하지 않음.
- Input Port/Output Port 추가: N/A, 기존 목표와 외부 경계를 사용.
- 신규 Domain Aggregate/Policy/Infra Bean: N/A, 신규 비즈니스 상태/판단/기술 설정 없음.

예상 commit 경계(이번에는 사용자 지시로 어떤 commit도 게시하지 않음):

```text
build(certificate): CODEF 등록 실패 코드 보존 제약 추가
feat(codef): 주요 금융 API 오류를 공통 코드로 변환
feat(certificate): 비동기 등록 오류 전달과 사용자 안내 연결
```

## 6. 검증 계획과 진행 기록

SQL 전용 격리 worktree와 합성 데이터 전용 PostgreSQL에서 확인한다.

- [x] 빈 DB V1→V46 migrate/validate, 재실행 0개.
- [x] V45 합성 legacy 실패/정상 데이터가 있는 DB의 V46 upgrade, 데이터 보존.
- [x] session/inbox/outbox 각각 정상 CF 코드와 기존 실패 코드 저장.
- [x] 성공 코드·잘못된 형식·공백/원문·NULL 관련 회귀 검증.
- [x] UNKNOWN의 상세 CF 사용 거부. BIND/CANCEL 제약은 기존 SQL을 유지.
- [x] 실패 결과 불변성·중복 수신·기존 lease/recovery 회귀 확인. publication/중복 확정 제약 유지.
- [x] `./gradlew compileJava`, `git diff --check`, PR diff가 SQL 한 파일임을 확인.

Java·Kafka·Electron 구현/통합 테스트는 로컬에서 수행한다. 실제 금융기관 요청과 dev/prod 적용은
실행하지 않는다. API/앱은 SQL과 실행 코드를 함께 적용해야 상세 오류를 표시한다.

| 날짜 | 완료 | 다음 단계 | 위험 |
|---|---|---|---|
| 2026-10-11 | 공통 매핑·Kafka/DB 전달·API/앱 안내·V46 검증 완료 | 사용자 검토 후 SQL/구현 PR | 구버전 consumer 종료 확인 뒤 상세 발행 활성화 |

## 7. 결정 기록

| 결정 | 이유 | 대안/한계 |
|---|---|---|
| 기존 failureCode 슬롯 재사용 | inbox/outbox/replay에 이미 안전한 오류 코드 경로가 있음 | 별도 raw 응답/메시지 저장 불필요 |
| 상세 CF는 확정 실패만 | 암호 오류 안내와 생성 결과의 불확실성을 혼동하지 않음 | HTTP timeout의 상세 기관 오류는 운영 진단으로만 확인 |
| 미지원 CF는 정적 fallback | 공식 목록 전체를 임의 사용자 문구로 노출하지 않음 | 새 상품/코드는 필요할 때 매핑 확장 |
| PR 게시 보류와 로컬 구현 계속 | 최신 사용자 지시 우선 | SQL-only branch 유지, commit/push/PR 없음 |


## 8. 최종 검증 기록

- Java 21 / Testcontainers PostgreSQL 16.14 / Kafka 4.2.2 / Redis 7 사용.
- `./gradlew compileJava` 성공. 선택 Java 테스트 112개는 suite별 최종 실행에서 failure/error/skip 0.
  [검증 집계](codef-user-facing-errors-results.json)에 결과를 기록했다.
- 최초 선택 실행에서 새 테스트가 terminal FAILED 이후 취소를 CANCELLED로 잘못 기대했다.
  기존 정책은 FAILED에 대한 취소를 no-op으로 처리한다. 새 테스트의 기대를 수정하고
  기존 취소 전 실행 테스트의 CANCELLED 기대를 유지하도록 복구했다. Persistence suite 20개 재실행 통과.
- 이후 실제 Spring Binder의 상세 발행 flag 활성화와 V45 정상/실패 두 row 보존을 보강한
  `CodefCertificateRegistrationAdapterTest` / `CertificateFailureMigrationTest` 15개 재실행 통과.
- `npm test`: 24개 통과. `npm run test:smoke`: 실제 macOS Electron renderer/IPC에서
  QR/예약 기존 흐름과 인증서 암호·계좌 잠금·미분류·HTTP 입력 오류 안내, 비밀 필드 제거 검증 통과.
  새 오류 시나리오의 금융사 선택과 기존 초기 USB 비동기 대기를 fixture에 명시했다.
- V45→V46 Flyway migrate 1개 / validate 성공 / 재실행 0개. 정상/실패 legacy row JSON 동일.
  빈 DB V1→V46은 HTTP/persistence 통합 테스트에서 검증했다.
- 실제 Kafka 발행/수신 및 양쪽 inbox 중복 수신에서 CF-12302/미지원 CF 코드를 보존하고
  API에서는 공통 코드/정적 문구를 반환한다. 성공 코드·비정상 CF·원문 필드·UNKNOWN 오용은 거부한다.
- 양쪽 V46 SQL SHA-256 일치:
  `8195a357969f0c575e27f6c877b13b18943fa81c7e6bd723872dd64494e7ba60`.
- 양쪽 worktree의 `git diff --check` 통과, 변경 문서 상대 링크 누락 0.
- local API DB V46 적용 / readiness UP / 상세 발행 flag는 로컬에서만 true.
  앱 재시작 후 금융사 20개·우리은행 추가 입력·서버 연결 확인.
- commit/push/PR 없음. migration worktree에는 V46 SQL 한 파일만 있으며 feature worktree에는 검증용 동일 복사본이 있다.

미검증: 실제 CODEF/금융기관 재인증(합성 provider 결과 사용), dev/prod 배포,
Windows native 실행 및 installer 재빌드, 전체 Gradle 테스트 suite. 기존 배포/설치 파일은 바뀌지 않았다.
PR 게시 전 최신 origin/develop migration 버전과 열린 migration PR을 다시 확인해야 한다.
[운영 절차](../../../ops/certificate/README.md#user-facing-codef-errors)를 따른다.
매핑은 사용하는 CODEF API 중심이며 공식 오류 전체를 전수 지원한다는 의미는 아니다.
