# 기능 계획: CODEF 은행별 로그인용 계좌 자격정보

- 상태: 구현 및 `develop` 병합 검증 완료 (2026-09-28)
- 기능 이슈: [#438](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/438)
- 저장 구조 이슈/PR: [#439](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/439), [#440](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/440)
- 기능 PR: [#441](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/441) (`feat/439-bank-credentials-migration` 대상)
- 관련 기능: [CODEF 은행 계좌 연결](../completed/codef-bank-account-connection.md)

> 현재 계약 변경: 이 문서는 V40 당시 구현을 기록한다. [은행별 인증 방식 설정](bank-authentication-methods.md)의 V53 전환부터 `BANK.attr3`는 `1=공동인증서만`, `2=공동인증서+ID/PW`, `3=공동인증서+ID/PW+계좌정보`를 뜻한다. 수준 3이면 ID/PW와 인증서 등록 모두 계좌번호·계좌 비밀번호가 필요하며, 새 `GET /api/v3/banks`는 `authenticationLevel`을 반환한다. 레거시 `GET /api/v2/accounts/banks`는 기존 `requiresAccountCredentials` Boolean 계약을 유지한다.

## 목적과 완료 기준

우리은행(`0020`)·대구은행(`0031`)은 운영 `BANK.attr3=1`이다. 두 값은 계좌 목록의 추가 입력이 아니라 CODEF 로그인 시작부터 필요한 계좌번호·계좌 비밀번호다.

- [x] V40 당시 `GET /api/v2/accounts/banks`에 Boolean을 반환했다. V53 이후에도 v2 Boolean 응답을 유지한다. 새 v3 카탈로그는 `authenticationLevel`을 반환하며 계좌 자격정보 필요 여부는 수준 `3`으로 판단한다.
- [x] 연결 요청에서 필요한 두 값을 CODEF 호출 전에 검증하고 create/add/update 로그인과 account-list에 전달한다. 이후 link의 account-list에는 저장값을 사용한다.
- [x] 기관별 자격정보는 LOCAL AES-256-GCM 암호문을 필수 저장하고, KMS 사용 시 KMS 암호문도 저장한다. 평문은 DB·API 응답·로그·trace·Kafka에 남기지 않는다.
- [x] KMS 일시 장애(서비스 오류·연결 끊김·TLS 전송 중단) 때 LOCAL 암호문으로 자동 복호화하고, 복구 후 누락 KMS 암호문을 보충한다. 암호문 손상·KMS 권한 거부·인증서 검증 실패에는 자동 전환하지 않는다.
- [x] [CODEF 개인 은행 수시입출 거래내역 명세](https://docs.google.com/spreadsheets/d/1urqr97NxnJJhTOpeOb5WzfnNQrI9jsAI/edit?gid=622680604#gid=622680604)의 우리은행 `M11`·대구은행 `P11`은 `accountPassword`가 모두 `-`(미제공)다. 두 은행의 transaction-list에는 저장한 비밀번호를 전달하지 않는다. 필수 `account`는 기존 Backend·Connector 요청에 이미 있다.
- [x] 자가·관리자 대리 탈퇴 시 공통 실행기에서 두 암호문을 제거하고 기존 은행·connectedId 흐름을 회귀시키지 않는다.

프론트 화면 구현과 신규 기관 연결 해제 API는 제외한다.

## 흐름과 경계

1. 은행 목록: `CommonCode.attr3` → `BankCatalogResult` → `BankCatalogWebResponse`; 기존 allowlist에 `0031`을 추가한다.
2. 연결: `ConnectBankWebRequest` → `ConnectBankCommand` → `BankAccountService.connect`; `accountNumber`·`accountPassword`를 조건부 필수로 확인한 후 LOCAL 암호화, 가능하면 KMS 암호화, CODEF create/add/update와 account-list를 수행한다. 성공한 기관의 암호문만 저장하며 재연결은 두 암호문을 교체한다.
3. 후속 조회: `BankAccountService.link`는 소유자의 기관 암호문을 복호화해 account-list에 사용한다. 필요한 값이 없는 기존 연결은 CODEF를 호출하지 않고 재연결을 요구한다.
4. 거래조회: 위 CODEF 명세상 두 은행의 transaction-list는 계좌 비밀번호 입력 대상이 아니다. `AccountQueryService`·`SettlementDepositService`의 직접 조회와 Finance Connector의 비동기 조회 모두 기존 `account` 필드를 유지하고 암호문/평문 전달 계약을 추가하지 않는다.
5. 실패: KMS 네트워크/서비스 일시 오류만 LOCAL로 자동 전환한다. TLS 연결 끊김과 하위 전송 오류가 있는 핸드셰이크 중단은 일시 오류다. 인증서·호스트명·TLS 프로토콜 설정 실패, 잘못된 암호문·context·권한 오류는 실패로 드러낸다. KMS가 없는 환경은 LOCAL-only로 작동한다.

## 저장·보안 결정

- V40 migration은 `connected_institutions.account_credentials_local_ciphertext`와 `.account_credentials_kms_ciphertext`(nullable `TEXT`), `0020`·`0031`의 `BANK.attr3=1`을 추가한다. 기존 행은 두 컬럼 모두 null이다.
- 두 암호화 구현을 Application의 목적별 Port 뒤로 분리한다. LOCAL은 기존 connectedId 키와 별도의 256-bit 키로 AES-GCM과 사용자 ID·기관코드 AAD를 사용한다. KMS는 전용 대칭 키의 `Encrypt`/`Decrypt`를 사용하며 암호문 포맷을 버전으로 구분한다. 로컬 키와 KMS ciphertext를 함께 보유하므로 KMS 단독 키 폐기만으로 복호화를 막을 수 없다는 절충을 인정하고 로컬 키를 별도 보호한다.
- 암호화는 연결당 한 번, 복호화는 필요한 account-list 재조회당 한 번만 한다. 거래내역의 월별 CODEF 분할 호출에는 KMS를 호출하지 않으며 평문을 장기 캐시하지 않는다.
- `ConnectBankCommand`·`BankConnectionPort.BankRegistration`의 문자열 출력과 CODEF 관측 데이터를 마스킹한다. 저장용 암호화와 CODEF 전송용 RSA 암호화는 별개다.

## 이행 순서와 커밋 경계

1. [x] migration-only PR [#440](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/440)을 `develop` 기준으로 제출했다. 명시적 사용자 요청에 따라 여기서 중단하지 않고 후속 구현을 계속한다.
2. [x] 은행 목록과 조건부 입력·CODEF 로그인/계좌조회 경로를 구현한다.
3. [x] LOCAL/KMS 암호화·자동 전환·암호문 저장·탈퇴 정리를 구현한다.
4. [x] CODEF transaction-list 명세의 우리은행·대구은행 셀을 확인했다. 추가 자격정보 필드가 없어 Backend/Connector 변경은 하지 않는다.
5. [x] Swagger·테스트를 갱신하고 기능 PR #441을 migration PR 브랜치에 병합했다. #440이 `develop`에 먼저 병합되어 기능 커밋이 누락됐으므로, 최신 `develop`에 해당 브랜치를 직접 병합한다. 관리자 대리 탈퇴의 공통 실행기를 유지하고 자격정보 정리를 그 안에 합친다.

커밋 경계: `feat(account): 은행 로그인 자격정보와 이중 암호화 지원` → `test(account): 은행 로그인 자격정보 경로 검증` → `docs(account): 계좌 자격정보 계약과 검증 기록` → `fix(account): KMS 일시 TLS 장애의 로컬 전환 수정`. 첫 커밋은 신규 Port의 실제 호출자와 구현을 함께 포함하며 `compileJava`를 통과시킨다.

## 검증과 미확정 사항

- `attr3=1`/null/기타, 누락·잘못된 자격정보, create/add/update와 account-list, 기존 은행 회귀를 검증한다.
- LOCAL/KMS round-trip, KMS 일시 오류 fallback, 권한 거부·변조 시 실패, KMS 복구 보충, 재연결 교체, 탈퇴 정리를 검증한다. 테스트에는 가짜 값만 사용한다.
- 임시 PostgreSQL·Redis에서 `bash scripts/test.sh test` 전체 통과, 집중 Unit/통합 테스트와 `./gradlew compileJava` 통과. `git diff --check` 통과. 실제 은행·AWS KMS 인증 경로는 합성 테스트와 별도로 확인해야 한다.
- 최신 `develop`과 기능 브랜치 병합 후 `./gradlew compileJava`, 일회용 PostgreSQL·Redis의 `bash scripts/test.sh test` 전체, 자가·관리자 탈퇴의 공통 자격정보 삭제 테스트가 통과했다. 배포 전 `ACCOUNT_CREDENTIAL_LOCAL_KEY` 설정 여부는 운영 환경에서 별도 확인한다.
- stacked PR #441은 저장소 workflow의 PR 대상 브랜치 필터(`develop`/`main`)에 걸리지 않아 CI가 자동 시작하지 않는다. 선행 PR #440의 [CI #527](https://github.com/Team-HowMuch/howmuch-backend-v2/actions/runs/36401861971/job/108868297406)은 GitHub 계정 결제 실패 또는 지출 한도 때문에 job이 시작되지 않았다. 재실행도 동일하며 코드/테스트 실패가 아니다.
- 실제 은행 호출은 아직 검증하지 않았다. 특히 [보유계좌 명세](https://docs.google.com/spreadsheets/d/1urqr97NxnJJhTOpeOb5WzfnNQrI9jsAI/edit?gid=1921394219#gid=1921394219)는 우리은행의 추가 출금 계좌 필드를 `-`, 대구은행은 선택으로 표시하므로, 두 기관 모두에 필드를 전달하는 현재 요청이 실제 CODEF에서 수용되는지 확인해야 한다.
