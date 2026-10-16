# Feature Plan: 기존 공동인증서 참조 금융기관 연결 API

- Status: PR #570 리뷰 반영 · dev/prod 사전 설정
- Last updated: 2026-10-17
- 범위: 모바일 앱이 호출할 Backend API, 비동기 참조 연결 worker, CODEF Mock, 검토용 DB 변경안
- 사용자 결정: 화면 구현 제외. DB 변경은 나중에 검토한 후 API 변경과 함께 PR 작성.
- 2026-10-16 개발 시작 승인: Backend/Mock 구현과 격리 로컬 검증을 진행한다. 화면 구현·기존 DB 적용·commit·push·PR·배포는 하지 않는다.

## 1. 목적과 도메인

우리은행을 공동인증서로 연결한 사용자가 같은 인증정보로 신한은행을 추가할 때 PC 앱·QR·DER/KEY·인증서 암호 업로드를 반복하지 않는다. 기존 account/external 도메인과 같은 connectedId를 사용한다.

성공 기준:

- 우리 서버에 공동인증서 로그인 성공으로 기록된 본인 기관만 출처 후보로 제공. 실제 참조 실행 시 CODEF에서 현재 상태 재확인.
- 명시적 승인 요청 후 CODEF가 대상 기관 로그인을 확인하고 기존 connectedId에 기관 추가.
- 원격 결과 확인과 로컬 기관 저장 완료 후에만 CONNECTED.
- 인증서 자료 재보관 없음. 계좌 선택·등록은 기존 API 사용.
- 중복·앱 종료·응답 유실·추가인증·권한 위반 처리.

출처 은행은 **인증서를 사용한 기존 연결 기관**이다. 인증서 발급기관이라는 의미가 아니다. 특정 DER의 발급 이력이나 인증서 파일을 선택하는 기능도 아니다.

제외: 화면/모달, 인증서 장기 저장, 은행 사이트의 타기관 인증서 사전등록, SMS 입력/재전송 API 구현, 배포.

## 2. 확인한 CODEF 공식 계약

- [계정 추가(레퍼런스)](https://developer.codef.io/common-guide/connected-id/add-ref): POST /v1/account/reference-add. 브라우저 본문 확인, 문서 수정일 2025-10-31.
- [계정 목록](https://developer.codef.io/common-guide/connected-id/list-account): POST /v1/account/list.
- 구형 SDK의 /cert/account/cid-overview 링크는 현재 404여서 근거에서 제외.

CODEF 요청 예시:

```json
{
  "connectedId": "<서버에 저장된 기존 CODEF ID>",
  "countryCode": "KR",
  "businessType": "BK",
  "organization": "0020",
  "clientType": "P",
  "accountList": [
    {
      "countryCode": "KR",
      "businessType": "BK",
      "organization": "0088",
      "clientType": "P"
    }
  ]
}
```

최상위 organization은 **출처 우리은행**, accountList 안의 organization은 **대상 신한은행**이다. 로그인 ID/PW나 인증서 파일·암호를 보내지 않는다. 대상에서 요구하면 birthDate/withdrawAccountNo/RSA 암호화 withdrawAccountPassword를 대상 항목에 넣는다. 임의 loginType을 보내 인증 방식을 변경하지 않는다.

실제 참조 worker가 CODEF 현재 계정 목록에서 KR/BK/P, loginType=0인 출처를 확인한다. 화면 후보 GET은 로컬 성공 기록을 사용한다. CODEF가 해당 출처에 저장한 인증정보로 대상 기관 로그인을 확인하며, 은행 자체의 타기관 인증서 등록까지 보장하지 않는다.

## 3. 모바일 API 계약

모든 요청은 기존 Bearer 인증을 사용한다. connectionId는 HowMuch DB 식별자이며 CODEF connectedId 원문은 앱 입력/응답에 포함하지 않는다. 기존 ApiResponse/BaseResponse를 따른다.

### 3.1 기존 인증서 연결 후보 조회 — 신규

```http
GET /api/v2/accounts/certificate-references?organization=0088
```

organization은 연결하려는 대상 은행 코드다.

```json
{
  "success": true,
  "data": {
    "organization": "0088",
    "authenticationLevel": 1,
    "birthDateRequired": false,
    "connectionId": 15,
    "alreadyConnected": false,
    "references": [
      {
        "sourceOrganization": "0020",
        "name": "우리은행",
        "iconUrl": "<기존 은행 아이콘 URL>"
      }
    ],
    "activeOperation": null
  }
}
```

- authenticationLevel: 기존 v3 은행 카탈로그의 1/2/3. 3이면 대상 계좌번호/계좌 비밀번호 입력. requiresAccountCredentials를 API에 중복 추가하지 않는다.
- birthDateRequired: 기존 CertificateInstitutionCatalogPort의 기관 규칙.
- 후보: 본인 활성 연결 → connected_institutions의 authentication_method=CERTIFICATE → 활성 은행 카탈로그. 대상과 같은 기관은 후보에서 제외. 후보 GET은 CODEF를 호출하지 않는다.
- 로컬 인증 방식이 UNKNOWN인 기존 행은 후보에서 제외한다. worker에서도 CODEF loginType이 없거나 불명확하면 인증서로 추정하지 않는다.
- 연결이 없으면 connectionId=null, references=[]. 후보 조회는 CODEF 가용성에 의존하지 않는다. DB 조회 장애를 빈 목록으로 숨기지 않는다.
- alreadyConnected=true는 대상의 로컬 본인 활성 기관 연결이 저장돼 있다는 뜻이다. CODEF의 실시간 가용성/유효성 보장은 아니다. 기존 ID/PW 연결도 사용할 수 있으며 인증서를 덮어쓰지 않는다.
- 로컬에 대상이 없으면 alreadyConnected=false. 실제 작업에서 CODEF에만 대상이 있는 경우를 확인해 로컬 연결 및 인증 방식 기록을 복구한다.
- 미완료 작업은 activeOperation={operationId,organization,status}. 다른 대상 작업도 동시 인증 변경 방지를 위해 반환.
- GET에서 기관 추가/수정·사용자 데이터 저장을 하지 않는다.

### 3.2 참조 연결 작업 접수 — 신규

```http
POST /api/v2/accounts/connections/15/certificate-references
Idempotency-Key: <앱에서 생성한 UUID>
Content-Type: application/json
```

```json
{
  "sourceOrganization": "0020",
  "organization": "0088",
  "birthDate": null,
  "accountNumber": null,
  "accountPassword": null
}
```

- 앱에서 사용자가 승인한 시점에만 호출.
- path connectionId는 현재 본인의 활성 연결과 일치해야 한다. 다른 사용자/비활성 연결은 404.
- 출처/대상은 서로 다른 활성 은행 코드. 서버가 출처 인증 방식을 다시 확인.
- authenticationLevel=3이면 accountNumber/accountPassword 한 쌍 필수. 선택 입력도 한쪽만 보내면 거절.
- birthDate는 해당 기관 요구 시 필수. 기존 6/8자리 날짜 계약을 따름.
- 인증서 자료·source connectedId·사용자 ID·로그인 ID/PW는 입력받지 않음.
- 비밀 입력을 URL/로그/평문 DB/메시지/Request 또는 Command의 toString에 포함하지 않음.

**HTTP 202 Accepted** — 접수이며 은행 연결 성공을 뜻하지 않는다.

```json
{
  "success": true,
  "data": {
    "operationId": "7d48f4d9-441c-46dc-83d2-aaf698173acf",
    "connectionId": 15,
    "sourceOrganization": "0020",
    "organization": "0088",
    "status": "QUEUED",
    "expiresAt": "2026-10-16T12:20:00Z",
    "pollAfterMs": 2000
  }
}
```

- 접수 기한은 10분. worker가 호출 전 남은 실행 예산을 확인하며, 이미 전송한 은행 요청까지 10분 안에 확정된다고 보장하지 않는다.
- 동일 사용자/동일 Idempotency-Key/동일 정규화 입력이면 같은 operationId와 현재 상태 반환. 응답 유실 시 앱은 **같은 키**로 재요청.
- 같은 키로 다른 입력이면 409. 다른 키라도 다른 미완료 인증 변경 작업이 있으면 409.
- 요청 비교에는 서버 키를 사용하는 HMAC/canonicalization 사용. 짧은 비밀번호를 추측할 수 있는 단순 비밀번호 해시는 저장하지 않음.

### 3.3 작업 상태 조회 — 신규

```http
GET /api/v2/accounts/certificate-reference-operations/{operationId}
```

```json
{
  "success": true,
  "data": {
    "operationId": "7d48f4d9-441c-46dc-83d2-aaf698173acf",
    "connectionId": 15,
    "sourceOrganization": "0020",
    "organization": "0088",
    "status": "CONNECTED",
    "alreadyConnected": false,
    "failureCode": null,
    "failureMessage": null,
    "action": null
  }
}
```

상태 조회 자체는 HTTP 200이며 작업 실패는 status/failureCode/failureMessage로 구별한다. 타 사용자 작업은 404.

| status | 의미 | 앱 처리 |
|---|---|---|
| QUEUED | 접수 완료, 원격 변경 전 | 안내 간격으로 조회 |
| RUNNING | 은행 처리 중 | 조회 유지, 새 POST 금지 |
| CONNECTED | 원격 결과 확인 및 로컬 기관 저장 완료 | 기존 계좌 조회 |
| FAILED | 명확한 제공사 거절 또는 전송 전 검증 실패 | 공통 오류 안내 |
| ACTION_REQUIRED | 금융기관 추가 인증 요구 | 추가 인증 안내, 새 참조 요청 금지 |
| UNKNOWN | 전송 이후 결과 미확정 | 서버 확인 유지, 재발행 금지 |
| EXPIRED | 전송 전 기한 만료 | 새 요청 가능 |

- 예: FAILED / BANK_CERTIFICATE_REGISTRATION_REQUIRED / “해당 금융기관에 인증서를 등록한 뒤 다시 시도해주세요.”
- 추가 인증은 CODEF_ADDITIONAL_AUTH_REQUIRED와 action={type:"ADDITIONAL_AUTH_REQUIRED",supported:false}. SMS 재개 API는 별도 기능에서 연결할 확장점.
- GET 폴링으로 은행 로그인/reference-add를 재실행하지 않음.
- 화면/앱 종료는 은행 작업 취소가 아님. 서버 처리를 계속하며 3.1의 activeOperation 또는 operationId로 재진입.
- 전송한 RUNNING/ACTION_REQUIRED/UNKNOWN 작업을 기한 경과만으로 “미실행”으로 판단해 재발행/삭제하지 않음.
- 민감 입력 암호문은 완료/실패 또는 기한 경과 시 제거. UNKNOWN 확인용 메타데이터는 분리 보존.
- 연결 후 계좌 조회 실패는 이미 완료한 기관 연결을 FAILED로 되돌리지 않음.

### 3.4 성공 이후 — 기존 API 재사용

```http
GET /api/v2/accounts/connections/15/accounts?organization=0088
POST /api/v2/accounts/external
```

첫 번째 API로 계좌 후보를 조회하고 앱에서 선택한 후보만 두 번째 API로 저장한다. 필요 시 기존 birthDate query 계약을 사용한다. 계좌 비밀번호는 성공 시 기존 저장소에 암호화한 값을 사용한다. 계좌 없음은 candidates=[]이며 계좌 자동 선택/등록은 하지 않는다.

## 4. 처리·실패·일관성

1. 사용자/연결 소유권/기관/입력 검증.
2. 짧은 접수 트랜잭션에서 멱등 키·미완료 인증 작업 검사, 작업+Outbox 원자 저장.
3. 공통 Kafka의 ACK 발행, Inbox dedup, lease/fencing 패턴 재사용. 메시지는 operationId/버전 등 메타데이터만 포함.
4. worker 선점 후 현재 출처 인증 방식/연결 지문/기한 재검증. 전송 시작을 영속 기록한 뒤 reference-add 한 번 호출. 장시간 호출 동안 사용자 행 DB 잠금을 잡지 않음.
5. 원격에 이미 대상이 있으면 인증정보를 덮어쓰지 않고 로컬 저장 복구. 기존 ID/PW 연결도 변경하지 않음.
6. 기존 ID/PW·PC 인증서·참조 연결이 같은 사용자 작업 가드를 공유. 동일 연결의 동시 변경·교체·삭제 차단, 조회는 허용.
7. 성공 판정: result/code 및 대상 successList 성공, 대상 loginType=0, 반환 connectedId 일치, 대상 오류/추가 인증 없음.
8. CODEF 성공 후 로컬 장애는 새 요청 대신 원격 목록 확인 후 로컬 기관 저장 재실행.
9. timeout/응답 유실/불일치는 UNKNOWN. 목록에 아직 안 보인다는 이유만으로 미실행이라고 단정하지 않음. 확인 불가 시 운영 확인 대상으로 유지.
10. 출처의 인증서 발급기관/fingerprint를 CODEF 목록만으로 증명하지 못함. 설명과 API는 “현재 출처에 저장된 인증정보 참조”로 한정.

| 상황 | 계약 |
|---|---|
| 미로그인/토큰 만료 | 기존 401 |
| 다른 사용자/비활성/설정 불일치 connectionId | 404 EXTERNAL_CONNECTION_NOT_FOUND |
| 미지원 기관 | 400 BANK_NOT_SUPPORTED |
| 입력/필수 자격정보 오류 | 기존 400 오류 및 details |
| 출처 삭제/ID·PW로 변경 | 409 BANK_CERTIFICATE_REFERENCE_INVALID (신규) |
| 동일 키에 다른 입력 | 409 CERTIFICATE_REFERENCE_IDEMPOTENCY_CONFLICT (신규) |
| 다른 미완료 인증 변경 | 409 CERTIFICATE_REFERENCE_IN_PROGRESS (신규) |
| 인증서/계좌/암호/생년월일 오류 | FAILED + 기존 CodefFailureMapper 공통 코드 |
| 추가 인증 요구 | ACTION_REQUIRED + CODEF_ADDITIONAL_AUTH_REQUIRED |
| worker의 전송 전 CODEF 조회 장애 | 원격 변경 없이 남은 시간 내 재선점; 후보 GET은 CODEF를 호출하지 않음 |
| 전송 후 미확정 | UNKNOWN; 자동 재전송 없음 |

## 5. 현재 코드 재사용 및 계층 계약

현재 CODEF read timeout 기본값은 305초, 앱의 계좌 관련 timeout은 120초다. 모바일 요청을 은행 처리 완료까지 붙잡지 않고 비동기 접수/조회로 설계한다.

- UseCases: GetCertificateReferencesUseCase / StartCertificateReferenceConnectionUseCase / GetCertificateReferenceOperationUseCase.
- Plain records: StartCertificateReferenceConnectionCommand / CertificateReferencesResult / CertificateReferenceOperationResult.
- InstitutionAuthenticationStorePort: 로컬 기관별 성공한 인증 방식 기록 및 인증서 후보 읽기. 기존 JPA 필수 컬럼 매핑을 늘리지 않고 JDBC로 추가 메타데이터를 관리한다.
- BankCertificateReferencePort: worker의 현재 인증 방식 조회 및 참조 추가. CodefBankConnectionAdapter의 공통 파싱/오류/관측/RSA 재사용.
- CertificateReferenceOperationStorePort: 접수·멱등·선점·요청 시작 marker·결과 확정.
- 기존 ExternalConnectionStorePort: 소유권/설정 지문/기관 저장.
- 기존 CertificateInstitutionCatalogPort: authenticationLevel/birthDateRequired.
- 기존 AccountCredentialProtectionService 및 LOCAL/KMS 기반: 추가 입력 보호, 성공 후 기관 자격정보 저장.
- 기존 BankConnectionRegistrationService / CertificateApprovedRegistrationTransactions: 인증 변경 작업 가드 공유 대상.
- 계층: Web Request.toCommand -> UseCase/Service -> Result -> Web Response.from -> ApiResponse.
- 진행 작업과 인증 방식의 결합 판단은 Application Policy. Service가 Port로 읽은 plain facts를 전달. Infra는 설정/Bean wiring만 담당.
- certificate PC session/claim/capability를 가짜로 만들거나 인증서 업로드 command에 부적합한 참조 작업을 끼워 넣지 않음.

## 6. DB 변경 — API와 함께 PR 검토

**Flyway migration required: Yes.** 비동기 접수·멱등·UNKNOWN 복구 및 기관별 인증 방식 저장을 위한 변경이 필요하다. [V58 마이그레이션](../../../src/main/resources/db/migration/V58__create_certificate_reference_connection.sql)을 백엔드 PR에 함께 포함한다. 최신 develop 및 열린 migration PR 확인 후 버전을 선택했고, 기존 dev/prod DB에는 적용하지 않았다.

기존 external_connections(사용자 연결), connected_institutions(연결별 기관), 인증서 비보관 정책은 유지한다.

| 제안 저장소 | 내용 |
|---|---|
| connected_institutions 추가 컬럼 | authentication_method (CERTIFICATE/ID_PASSWORD/UNKNOWN), authentication_method_confirmed_at. 기존 행은 UNKNOWN/null. 인증 성공 시 같은 트랜잭션에서 갱신 |
| certificate_reference_operations | operationId/userId/externalConnectionId, 출처/대상 기관, 멱등 키/요청 HMAC, status/deadline, 선점 lease/fencing, 전송 시작/완료 시각, 안전한 실패 코드, 기존 연결 지문 스냅샷, 추가 입력 암호문/key metadata |
| certificate_reference_command_outbox / certificate_reference_command_inbox | operationId/messageId/version, 발행 lease/ACK, 수신 dedup 및 원본 ID 매칭. 공통 Kafka 전송·엄격 파싱·DLT·consumer 설정을 재사용 |

제약:

- (userId,idempotencyKey) 유일, 작업 connectionId/userId 소유권 FK.
- 사용자 인증 변경 동시 실행을 DB와 Application 가드로 차단.
- CONNECTED 결과와 기관 저장을 같은 로컬 트랜잭션에서 확정.
- EXPIRED는 전송 전 작업만. 전송 후 불확실 메타데이터는 확인 완료 전 삭제하지 않음.
- 추가 입력 암호화 AAD: operation/user/connection/source/target/version. 기존 암호화 정책·도구 재사용, DER/KEY 보관 없음.
- 암호문과 멱등 메타데이터 수명 분리. 현재 완료 메타데이터는 자동 삭제하지 않아 24시간 이상 멱등 재요청이 가능하다. 운영 보존/삭제 주기는 후속 작업이다. UNKNOWN은 해결 전 보존한다.
- 완료 후 계좌 자격정보는 기존 기관 저장 경로로 이전하고 작업 비밀 payload 제거.
- 사용자 행 잠금, 활성 작업 부분 유일 인덱스, 연결 소유권 FK, 입력/식별자 불변 trigger 및 기존 ID/PW·PC 인증서·탈퇴 가드를 통합했다. V58은 별도 PostgreSQL에서 Flyway로 검증했다.

## 7. 구현과 커밋 경계

사용자 승인으로 Backend/Mock 구현을 진행했다. 원래 migration-only PR gate보다 사용자 지시(검토 후 API와 함께 PR)가 우선하므로 versioned migration/commit/push/PR을 만들지 않았다.

예상 커밋 경계(현재 커밋 안 함):

- docs(account): 기존 인증서 참조 연결 API 설계 및 DB 초안
- feat(account): 참조 작업·동시 변경 가드·CODEF 참조 호출·API 구현
- test(account): 암호화·권한·HTTP·메시징·DB 장애 복구 검증
- feat(mock): 레퍼런스 계정 추가 및 HTTP 회귀 검증 (별도 Mock 저장소)

초기 API 서버와 worker가 같은 DB를 사용하는 구조에서는 결과 Kafka 왕복을 추가하지 않았다. 명령 Outbox/Inbox만 사용하고, 결과와 기관 저장을 같은 트랜잭션으로 확정한다. 금융 Connector 분리 시 결과 메시지 경계를 추가한다. 인증서 session을 가짜로 생성하거나 인증서 자료를 다시 저장하지 않는다.

### 실행 시간과 복구

- 접수 deadline: 생성 후 10분. 원격 변경 직전 남은 시간 6분 이상을 다시 확인한다. 출처 목록 조회가 오래 걸려 예산을 소진했으면 원격 변경을 시작하지 않는다.
- CODEF read + connect timeout은 345초 이하이어야 활성화할 수 있다. 접수와 은행 처리 완료의 p95는 별개이며 실측 성능은 아직 측정하지 않았다.
- 실행 lease: 15분. 만료된 worker의 결과는 token + generation으로 차단한다. 재확인 경로의 목록/계좌 조회 두 번도 lease 안에 들어가도록 여유를 둔다.
- worker fixed delay 1초, Outbox relay 500ms, recovery 10초. 실행과 relay/recovery는 별도 scheduler를 사용한다.
- 전송 시작 marker 이전 처리 오류는 남은 시간 안에 재선점 가능하다. 프로세스 중단 후 lease가 만료됐을 때 접수 deadline도 지났으면 EXPIRED로 종료한다. marker 이후는 UNKNOWN으로 옮겨 목록 조회만 수행한다. 원격 변경 POST는 자동 재시도하지 않는다.
- UNKNOWN 재확인은 30초 후 재시도한다. 전송 후 대상이 없다는 이유만으로 재발행하지 않는다.
- 완료/명확한 실패/전송 전 만료에는 비밀 입력을 제거한다. UNKNOWN/ACTION_REQUIRED 입력은 deadline 이후 활성 lease가 끝나면 제거한다.

### 설정과 배포 전 조건

`CERTIFICATE_REFERENCE_ENABLED=false`가 기본값이다. 기존 배포는 새 SQL 없이 기동 가능하며 새 endpoint/worker는 등록되지 않는다. 기존 인증서 기능을 끄더라도 남아 있는 미확정 참조 작업의 변경 가드는 유지한다.

활성화 전 검토 순서:

1. V58을 포함한 PR을 검토/병합하고 배포 대상 DB에 Flyway가 적용되었는지 확인한다. 기존 데이터의 인증 방식은 UNKNOWN이며 재로그인 또는 별도 1회 동기화가 필요하다.
2. 기존 인증서 암호화 키/계좌 자격정보 암호화 키 및 CODEF 설정을 준비한다. LOCAL/KMS 기존 구현을 재사용한다. HMAC에 사용한 기존 confirmation key 교체도 기존 작업 지문에 영향을 주므로 진행 중 작업을 먼저 확인한다.
3. 공통 Kafka transport를 활성화하고 `finance.certificate-reference.command.v1` 및 `finance.certificate-reference.command.v1.dlt`를 미리 생성한다. 자동 topic 생성은 꺼져 있다. 기존 transport의 ACK/관측/재시도 설정을 사용한다.
4. 필요하면 `CERTIFICATE_REFERENCE_COMMAND_TOPIC`, `CERTIFICATE_REFERENCE_COMMAND_DLT_TOPIC`, `CERTIFICATE_REFERENCE_COMMAND_GROUP`으로 topic/group을 지정한다. 기존 인증서/알림/리캡 topic과 겹치면 기동을 차단한다.
5. 모든 API/worker 인스턴스가 인증 방식 기록과 작업 가드를 포함한 새 코드로 교체된 뒤 `CERTIFICATE_REFERENCE_ENABLED=true`를 적용한다. 인증서 기능/공통 Kafka/SQL 세 테이블 또는 기관별 인증 방식 컬럼이 없으면 활성화를 차단한다.

### 명시적 제한

- SMS 입력·인증 재개·취소 endpoint는 이번 범위가 아니다. ACTION_REQUIRED는 `supported:false`로 반환한다.
- UNKNOWN/ACTION_REQUIRED는 원격 결과가 확인될 때까지 새 인증 변경을 막는다. ACTION_REQUIRED 자동 재개/해제 UI와 운영 조치 API는 아직 없다. 이 상태를 SQL로 임의 삭제해 재요청하면 원격 중복 실행 위험이 있다.
- UNKNOWN 중 계좌 자격정보가 이미 제거됐는데 대상이 level 3이면 자동 자격정보 복원이 불가능하다. 운영 확인/별도 복구 절차가 필요하다.
- Mock은 소유자의 현재 활성 인증서를 참조한다. 기관별 과거 인증서 지문·실제 은행의 타기관 인증서 등록까지 재현하지 않는다.
- 실제 CODEF 및 dev/prod 배포 검증은 수행하지 않았다. 모바일 UI도 수정하지 않았다.

## 8. 검증과 완료 기록

검증 대상 브랜치는 `feat/569-certificate-reference-connection`이며 기존 작업 체크아웃과 실행 중인 앱·서버는 변경하지 않았다. SQL 테스트는 전용 `reference_test` PostgreSQL에만 적용했다.

- [x] 공식 reference-add/list 실제 본문과 요청/응답 필드 확인. 후보 목록은 로컬 DB, 실제 실행은 CODEF 확인으로 분리.
- [x] Web Request -> Command -> UseCase -> Result -> Web Response 및 기존 공통 오류 매핑 재사용.
- [x] 접수 시 CODEF 미호출, 같은 키/다른 입력, 연결/작업 소유권 검증.
- [x] 실제 PostgreSQL의 접수+Outbox 롤백, 동시 같은 키 접수, Inbox 중복/위조, lease fencing, 불변/연결 변경 가드 검증.
- [x] 실제 Kafka ACK 발행/재수신과 HTTP 202 -> CONNECTED 결과 조회 검증. 금융 provider는 이 통합 테스트에서 결정적 fake를 사용한다.
- [x] CODEF 응답 유실, 원격 성공 후 DB 저장 실패, 전송 시작 후 worker 중단에서 POST 재전송 없이 복구 검증.
- [x] 원격 변경 전 실행 예산 재검증, 입력 제거, 추가 인증 상태 검증.
- [x] 기존 LOCAL envelope 호환성 및 새 참조 AAD/HMAC 검증.
- [x] 기능 비활성 기본 설정, SQL/공통 transport 누락 시 기동 차단, 공통 consumer 설정 검증.
- [x] Mock의 reference-add HTTP 테스트 및 기존 전체 회귀 테스트: 56개 통과.
- [x] 전체 Spring Boot `local` 기동 및 실제 JWT/Redis/JPA/공통 Kafka/CODEF Mock HTTP 경계로 후보 조회 -> 202 접수 -> 동일 키 재요청 -> CONNECTED -> 기존 계좌 후보 1개 조회 검증.
- [ ] 기존 전체 Docker 통합 테스트 및 실제 CODEF/dev/prod 검증 (이번 격리 검증 대상 밖).

실행 명령(격리 로컬 DB 선택 시에만 URL을 지정하며, 미지정 시 테스트 자체 PostgreSQL container를 사용):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava
REFERENCE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:18432/reference_test \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test \
  --tests '*CertificateReference*Test' --tests '*CertificateProtectionTest' \
  --tests '*CodefCertificateReferenceAdapterTest' --tests '*BankAccountServiceTest' \
  --tests '*CertificateApprovedRegistrationTransactionsTest' --tests '*UserAccountDeletionExecutorTest' \
  --tests '*KafkaMessagingConfigurationTest' --tests '*KafkaPublicationTest' --tests '*KafkaMessageContractTest'
```

이 통합 테스트의 DB 초기화는 전용 loopback `reference_test` 또는 테스트가 생성한 container만 허용한다. 기존 앱 DB URL을 넣지 않는다. 격리 Kafka는 테스트 종료 시 종료한다.

최종 검증 수/결과는 이 문서 아래 실행 기록에 남긴다. 배포 대상 DB/공통 Kafka/암호화 설정 확인 전까지 기본 기능 플래그는 비활성으로 유지한다.

### 2026-10-16 최종 실행 기록

- `compileJava` 및 `compileTestJava`: 성공.
- 위 선별 Backend 검증: 84개, 실패 0개, skip 0개. 참조 흐름 PostgreSQL/Kafka 통합 16개 포함.
- Mock 전체 `python3 -m unittest discover -v`: 56개, 실패 0개.
- 실제 서버 검증: 비로그인 401, 인증서 출처 조회 200, 접수/동일 키 replay 202, Kafka CONNECTED 조회 200, 기존 계좌 조회 200 및 대상 계좌 1개 확인. 실제 CODEF가 아닌 별도 로컬 Mock의 가상 사용자/인증서를 사용했다.
- 런타임 검증에서 기존 Discord SMS 설정의 webhook 누락으로 최초 기동이 실패했다. 격리 loopback의 사용하지 않는 URL을 설정한 뒤 정상 기동했다. 실제 SMS/Discord 메시지는 전송하지 않았다.
- HTTP 메서드의 Validation 선언 불일치와 느린 출처 조회 이후 실행 예산 재검증 누락을 통합 테스트 중 찾아 수정했다.
- Kafka 메시지 계약: `src/main/resources/messaging/certificate-reference-command-v1.schema.json`. 엄격 파싱/ID 매칭을 listener에서 적용한다.
- `git diff --check`, 문서 상대 링크, JSON 구문 검증: 성공.
- 기존 서버/앱 데이터·설정·컨테이너는 변경하지 않았다. 격리 검증용 서버·PostgreSQL·Redis·Kafka·Mock 프로세스는 검증 후 종료했다. 변경 사항은 커밋/게시하지 않았다.

## 9. 2026-10-17 변경 결정: 인증 방식은 우리 서버에 저장

사용자 요청으로 후보 조회의 CODEF 목록 호출을 제거한다. `connected_institutions`에 기관별 `authentication_method` (`CERTIFICATE`, `ID_PASSWORD`, `UNKNOWN`) 및 확인 시각을 추가한다. 단순 boolean 대신 현재 성공한 방식을 기록하여 ID/PW 전환도 표현한다.

- PC 인증서 최종 연결 저장 성공 시 `CERTIFICATE` 기록. 임시 인증 성공만으로 사용자 연결에 기록하지 않는다.
- ID/PW 등록/추가/수정 성공 시 `ID_PASSWORD`로 갱신. 실패/취소는 이전 값 유지.
- 인증서 참조 신규 연결 성공 시 `CERTIFICATE` 기록. 이미 존재한 대상은 worker가 확인한 방식을 기록하고 원격 인증을 덮어쓰지 않는다.
- 후보 GET은 본인 활성 연결 + 로컬 인증 방식 + 활성 은행 카탈로그만 사용한다. 실제 참조 실행/UNKNOWN 복구 시에는 CODEF 조회를 유지한다.
- 기존 행은 `UNKNOWN`으로 두며 과거 인증서 이력만으로 현재 방식을 추정하지 않는다. 다시 로그인하거나 별도 1회 동기화 후 후보에 포함할 수 있다. 후보 GET에서 자동 외부 조회/backfill은 하지 않는다.
- 새 JDBC capability로 추가 컬럼만 읽고 쓰며 기존 JPA 모델에 필수 컬럼 매핑을 추가하지 않는다. SQL 미적용 배포는 기존 인증 흐름을 유지하고, 새 참조 기능 활성화는 컬럼 적용을 필수로 검사한다. SQL 적용 후에는 feature flag와 관계없이 정상 로그인 성공 경로가 메타데이터를 기록한다.
- 기존 SQL 초안에만 변경을 포함한다. 사용자 지시대로 정식 migration/DB 적용/PR은 검토 후 함께 진행한다.

영향: 후보 조회 Service/Docs, ID/PW 등록 Service, PC 인증서 최종 저장, 참조 완료 트랜잭션, 새 인증 방식 Port/JDBC Adapter/Enum, SQL 초안 및 기존 생성자 호출 테스트.
검증: 후보 GET에서 외부 호출 없음, 인증서/IDPW 성공 전환·실패 불변, 참조 성공/기존 대상 방식 보존, 기존 행 UNKNOWN, 신규 컬럼 없는 기존 배포 호환성, PostgreSQL 원자 저장/롤백, Java compile 및 관련 회귀 테스트.

### 2026-10-17 검증 기록

- `compileJava`, `compileTestJava` 및 관련 Backend 테스트 90개 통과 (실패 0, skip 0). PostgreSQL/Kafka 통합 20개 포함.
- 후보 GET의 CODEF 미호출, UNKNOWN 기존 행 제외, CERTIFICATE -> ID_PASSWORD 전환, 로그인 실패 시 메타데이터 미갱신 검증.
- PC 인증서 최종 저장의 CERTIFICATE 기록 및 참조 연결 완료 시 인증 방식/기관/작업 결과 원자 저장 검증.
- 인증 방식 저장 실패 시 기관/CONNECTED 결과 롤백 후 원격 재전송 없이 복구 검증.
- 새 컬럼 없는 기존 스키마에서는 기존 로그인 경로 유지, 참조 기능 활성화는 컬럼 누락 시 기동 차단 검증.
- 실제 `local` 서버 기동 + JWT/Redis/JPA/Kafka/CODEF Mock HTTP 흐름을 다시 확인했다. 로컬 인증 방식 후보 조회, 접수/멱등 재요청, CONNECTED, 대상 CERTIFICATE 기록 및 기존 계좌 후보 1개 조회까지 검증했다.
- 최초 런타임 재검증에서는 격리 Mock의 대상 기관 행만 직접 초기화해 인증 방식 목록과 불일치했고 계좌 조회가 CF-04000으로 실패했다. Mock 등록 삭제 API로 두 상태를 함께 초기화한 뒤 전체 흐름을 재검증했다. 사용자 dev/prod Mock 데이터는 변경하지 않았다.
- SQL 초안은 격리 테스트 DB에만 적용했다. 기존 DB 적용/정식 migration/commit/push/PR은 보류한다. Mock 제품 코드 변경은 이번 개선에서 없다.

## 10. 2026-10-17 PR 게시 승인

사용자가 이번 API 및 DB 변경을 함께 PR로 올리도록 승인했다. 이 요청과 앞선 함께 검토 요청을 우선하여 SQL-only 게시 후 중단하는 기본 gate 대신 백엔드 PR 한 건에 정식 migration과 API를 함께 포함한다. SQL은 독립 커밋으로 분리한다. 배포/병합은 이번 승인 범위가 아니다.

- 이슈: #569, 브랜치: `feat/569-certificate-reference-connection`, PR base: `develop`.
- 최신 `origin/develop`의 마지막 버전은 V57이며 열린 migration PR에는 V58이 없다. 정식 파일은 `src/main/resources/db/migration/V58__create_certificate_reference_connection.sql`이다.
- 테스트는 초안 SQL을 수동 실행하지 않고 Flyway의 V58을 직접 적용한다. 기존 dev/prod DB에는 적용하지 않는다.
- CODEF Mock reference-add는 별도 저장소 PR로 함께 검토한다.

커밋 경계:

1. `build(account): 인증서 참조 연결 및 인증 방식 저장 스키마 추가` — V58만 포함.
2. `feat(account): 기존 공동인증서 참조 연결 API 구현` — Port/Domain/Service/Adapter/Infra/Web 및 기존 생성자 호출 호환 변경.
3. `test(account): 인증서 참조 연결과 장애 복구 검증 추가` — 새 테스트와 구현/QA 기록.

이전 절의 SQL 보류·PR 미게시 기록은 당시 작업 상태를 설명한다. 이 절이 현재 게시 상태의 기준이다.

### PR 직전 재검증

- V58을 Flyway로 적용한 PostgreSQL 기반 선별 Backend 테스트 90개: 실패 0, skip 0. `compileJava`/`compileTestJava` 성공.
- 로컬 Docker daemon이 6초 안에 응답하지 않아 기존 Docker 의존 전체 테스트는 이번 로컬 실행에서 수행하지 않았다. 원격 PR CI에서 전체 테스트 결과를 확인한다.
- CODEF Mock 별도 이슈: Team-HowMuch/codef-mock-server#11, 브랜치 `feat/11-account-reference-add`.

## 11. PR #570 리뷰: 제한된 병렬 실행과 접수 역압

단일 @Scheduled 작업은 pool 크기만 늘려도 같은 작업을 동시에 실행하지 않는다. CODEF 한 요청이 4분 이상 걸리면 뒤 요청이 실행 예산 없이 만료될 수 있다는 P1 지적을 반영한다.

- `worker-concurrency`(기본 4, 허용 1~16)개 독립 fixed-delay 실행 루프를 등록한다. 별도 relay/recovery 스케줄러는 유지한다.
- 같은 DB의 QUEUED/RUNNING 및 lease가 유효한 UNKNOWN 확인 작업을 합쳐 concurrency 이하로 제한한다. DB advisory transaction lock으로 동시 접수/선점의 수량 검사를 직렬화한다.
- 용량이 차면 작업/Outbox를 만들지 않고 `CERTIFICATE_REFERENCE_BUSY`(503)로 거절한다. 같은 멱등 키로 재시도할 수 있다. 이미 접수된 같은 키의 재요청은 용량과 관계없이 기존 결과를 반환한다.
- 새 QUEUED 작업을 UNKNOWN 재확인보다 먼저 선점하고 UNKNOWN은 빈 용량에서만 실행한다. 이미 접수한 신규 작업의 실행 자리를 복구 작업이 차지하지 않게 한다.
- 이 값은 현재 DB 전체의 보수적 접수 한도도 결정하므로 같은 환경의 모든 인스턴스에 같은 값을 쓴다. 새 SQL 변경은 없다.
- 검증: 두 worker의 실제 동시 진입, 동시 접수 상한/실패 시 Outbox 없음, UNKNOWN 선점과 접수의 경쟁, 기존 멱등/장애 복구 회귀.


### 리뷰 수정 검증

- 관련 Backend 테스트 95개 통과(실패/오류/skip 0), `compileJava`/`compileTestJava` 성공.
- 변경 후 전체 local Spring Boot 서버에서 JWT/Redis/JPA/Kafka/CODEF Mock 기반 후보 조회 → 202 → 같은 키 재요청 → CONNECTED → 기존 계좌 조회 1개 및 대상 CERTIFICATE 저장을 다시 검증했다.
- 테스트용 PostgreSQL/Redis/Kafka/Mock과 합성 데이터만 사용했다. 실제 CODEF 인증·송금은 실행하지 않았다.
- 리뷰 수정 커밋 경계: `fix(account): 인증서 참조 작업 병렬 실행과 접수 용량 제한`. 토픽 목록/운영 기록은 별도 `docs(kafka)` 커밋으로 분리한다.
