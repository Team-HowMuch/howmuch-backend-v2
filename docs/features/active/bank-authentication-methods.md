# Feature Plan: 은행별 인증 방식 설정

- Status: 구현·로컬 검증 완료 — PR 검토 및 배포 대기
- Last updated: 2026-10-14
- Related issue: [#542](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/542)
- Related ADR: N/A

## 1. Purpose

### Problem

`BANK.attr3`는 현재 `1`이면 계좌번호와 계좌 비밀번호가 필요한지를 나타내는 Boolean 설정이다. ID/PW 인증을 지원하는 은행 목록은 `BankCatalog.ID_PASSWORD_BANKS`에 코드로 별도 관리한다. 이 구조에서는 은행별 인증 수단을 설정 데이터만으로 표현하거나 변경하기 어렵다.

### Why now

공동인증서 연결과 ID/PW 연결을 은행별로 일관되게 안내하고, 계좌 자격정보가 추가로 필요한 은행도 한 설정으로 관리해야 한다.

### Success criteria

- [x] `BANK.attr3`에 `1` 공동인증서만, `2` 공동인증서와 ID/PW, `3` 공동인증서와 ID/PW 및 계좌 자격정보를 저장한다.
- [x] 공개 은행 카탈로그는 활성 은행의 수준 1·2·3을 반환하고, 인증서 등록 앱은 수준 3에서 계좌 입력란을 표시한다.
- [x] 기존 ID/PW 연결 가능 은행과 계좌 자격정보 요구 동작이 데이터 전환 뒤에도 유지된다.
- [x] 은행 목록 API가 인증 방식 수준을 제공하며 기존 클라이언트 호환성을 보존한다.
- [x] 계좌 등록 검증이 하드코딩된 은행 목록 대신 `BANK.attr3`를 따른다.

### Out of scope

- 실제 은행이나 CODEF가 해당 인증 방식을 지원하는지 실시간 검사
- 인증 UI의 개편
- 모바일이 선택한 은행을 PC 화면에 전달하는 QR/session 흐름 변경

## 2. Domain analysis

### Domain boundary

- Existing domain to reuse: Account / Bank catalog 및 Common Code
- Reason a new domain is required: N/A
- Relationships to other domains: Account 연결 API와 인증서 기관 목록 API가 `BANK` 공통 코드를 읽는다.

### Ubiquitous language

| Term | Meaning | Code representation |
|---|---|---|
| 인증 방식 수준 | 은행 연결에서 제공할 인증 수단과 추가 계좌 자격정보 범위 | `BANK.attr3` (`1`, `2`, `3`) |
| 계좌 자격정보 | 수준 3 은행 연결에 필요한 계좌번호와 계좌 비밀번호 | 기존 `BankAccountCredentials` |

### Actors and UseCases

| Actor | Goal | UseCase |
|---|---|---|
| 앱 사용자 | 은행별로 가능한 인증 방식에 맞춰 연결 | 기존 은행 목록 및 연결 UseCases |
| 운영자 | 은행별 연결 방식을 공통 코드로 설정 | 기존 Common Code 관리자 |

### State and invariants

- `1`: 공동인증서만 제공한다.
- `2`: 공동인증서와 ID/PW를 제공한다.
- `3`: 공동인증서와 ID/PW를 제공하며 ID/PW 연결에서 계좌번호와 계좌 비밀번호도 요구한다.
- 유효하지 않거나 비어 있는 설정을 ID/PW 지원으로 간주하지 않는다.

## 3. Scenarios

### Normal flow

1. 사용자가 은행 목록을 요청한다.
2. 서버가 `BANK.attr3`를 읽고 은행의 인증 방식 수준과 ID/PW 연결 가능 여부를 구성한다.
3. 수준 3 은행은 ID/PW 연결과 공동인증서 등록 모두 계좌번호와 계좌 비밀번호를 받는다.
4. 서버는 저장된 수준과 요청 입력을 검증하고 기존 CODEF 연결 로직을 수행한다.

### Failure flow

- ID/PW 미지원 은행을 ID/PW로 연결하면 `BANK_NOT_SUPPORTED`를 반환한다.
- 수준 3 은행에 계좌번호 또는 계좌 비밀번호가 없으면 기존 `BANK_ACCOUNT_CREDENTIAL_REQUIRED`를 반환한다.
- 설정되지 않았거나 잘못된 `attr3` 값은 ID/PW 미지원으로 처리한다.

### Boundary conditions

- 기존 BANK 은행의 `attr3`는 계좌 자격정보 Boolean 의미에서 새 방식 수준 의미로 데이터 전환한다.
- v3 응답에는 `requiresAccountCredentials`를 중복 반환하지 않는다. v2 레거시 응답은 기존 boolean 계약을 유지한다. 기존 내부 UseCase의 메서드는 연결 및 계좌 조회 정책에 사용한다.

## 4. Contracts

### Input Port and boundary mapping

```text
UseCase: 기존 GetBankCatalogUseCase / ConnectBankUseCase
Command: 기존 ConnectBankCommand
Result: 기존 BankCatalogResult에 authenticationLevel 추가
Web Request DTO -> Command: 요청 필드 변경 없음
Result -> Web Response DTO: authenticationLevel 반환
```

### Output Ports

새 Port는 필요하지 않다. 기존 `LoadCommonCodePort`를 재사용한다.

### Application Policy

- Decision owned by the Policy: N/A; `BANK.attr3` 값은 단일 공통 코드 설정이다.
- Facts loaded by the Service and passed to the Policy: N/A
- Why the rule is not an Entity-local invariant: N/A
- Future inputs deliberately not implemented yet: 기관별 실시간 CODEF 지원 상태

### Web API

```text
GET /api/v3/banks
Request: 없음
Response: 활성 은행 전체의 organization, name, iconUrl, authenticationLevel (1, 2, 3)
Errors: 기존 공통 오류 계약
Authentication: 공개 (PC 인증서 앱에서 로그인 없이 조회)

GET /api/v2/accounts/banks
Request: 없음
Response: 기존 ID/PW 연결 은행(수준 2, 3)의 organization, name, iconUrl, requiresAccountCredentials (레거시 계약 유지)
Authentication: 기존 계약 유지
```

인증서 전용 `GET /api/v2/certificate-registrations/institutions`는 제거하고 공통 공개 카탈로그를 사용한다. 앱은 `authenticationLevel == 3`일 때만 계좌번호와 계좌 비밀번호 입력란을 표시한다.

### Persistence and external integrations

- Database Entity, constraint, or query changes: 스키마 변경 없음. 기존 공통 코드 `attr3` 설정 의미와 값만 전환한다.
- Flyway migration required (`Yes` / `No`): Yes — 기존 설정을 새 숫자 체계로 전환한다.
- Planned migration: `V54__set_bank_authentication_levels.sql`; 기존 ID/PW 목록 중 우리은행·대구은행은 `3`, 나머지 ID/PW 은행은 `2`, 그 외 BANK는 `1`로 설정한다. 비활성 은행도 동일하게 전환한다.
- Migration pull request (`Pending` / URL): [#537](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/537) 초안으로 생성 후 사용자 요청에 따라 닫음. migration 커밋은 현재 기능 브랜치에 보존한다.
- Redis key, TTL, or serialization changes: 없음
- External API or message changes: 없음
- Environment variable or profile changes: 없음

## 5. Design decisions

### Business logic

- Rules and their locations: `BANK.attr3`가 인증 방식의 유일한 원천이며, `BankCatalog`가 이를 해석한다.
- Behavior owned by Domain: 기존 은행 연결 상태를 유지한다.
- Decisions owned by Application Policy: N/A
- Flow orchestrated by Application: 기존 은행 등록 및 조회 흐름을 재사용한다.

### Technology and patterns

- Constraint to solve: 현재 Boolean 속성과 하드코딩된 ID/PW 은행 목록이 은행별 연결 방식을 분리 관리한다.
- Selected approach: 기존 `BANK.attr3`를 1·2·3 수준으로 사용하고 API에는 수준을 추가하되 기존 Boolean 응답을 유지한다.
- Reason for selection: Common Code 구조를 재사용하면서 설정과 앱 표시를 단계적으로 전환할 수 있다.
- Alternatives considered: 별도 테이블/컬럼 — 이번 요구에는 스키마 확장 비용이 더 크다.
- Trade-offs and limits: `attr3`에 유효한 수준만 입력되도록 관리자 입력과 운영 데이터 검증이 필요하다.

## 6. Impact surface

| Area | Impact | Verified file or contract |
|---|---|---|
| API | 공통 공개 은행 카탈로그에 인증 수준 제공, 인증서 전용 기관 목록 제거 | `BankCatalogController`, `BankCatalogV3WebResponse`, 레거시 `BankCatalogWebResponse` |
| Application | Boolean 사용을 인증 수준으로 대체하고 인증서 등록 필수값 검증에도 적용 | `BankCatalog`, `CertificateRegistrationService`, `CertificateWorkerService` |
| Domain | 영향 없음 | Existing account domain |
| Persistence | BANK attr3 의미·값 전환 | V54 migration |
| Redis | 영향 없음 | N/A |
| Security | 은행 카탈로그를 공개 조회로 허용 | `SecurityConfig` |
| Configuration | 영향 없음 | N/A |
| Documentation | Swagger 및 Feature Plan 갱신 | Account / Certificate docs |

Existing behavior that must remain unchanged:

- 우리은행과 대구은행은 계좌번호 및 계좌 비밀번호 입력을 계속 요구한다.
- 현재 ID/PW 연결 가능 은행 집합은 데이터 전환 직후 동일하게 유지한다.
- 인증서 등록의 계좌 자격정보 요구 여부는 `BANK.attr3`만으로 검증한다. 레거시 기관 설정의 계좌 플래그는 제거한다. PC 앱은 공통 카탈로그의 `authenticationLevel`을 사용한다.

## 7. Implementation plan

- [x] Migration-only pull request to `develop` (`Required`)
- [x] UseCase and Application Command/Result — 기존 UseCase 재사용, Result만 확장
- [x] Required Output Ports — N/A
- [x] Domain models and rules — N/A
- [x] Application Policies — N/A
- [x] Application Service — `BankCatalog` 수정
- [x] Output Adapters — 기존 Common Code Adapter 재사용
- [x] Web Request/Response DTOs, Controllers, and Swagger — 두 기관 목록 응답 확장
- [x] Infra configuration and Bean wiring — N/A
- [x] Documentation and verification results

### Expected commit boundaries

```text
chore(account): 은행 인증 방식 설정 데이터 추가   # migration-only PR
feat(account): 은행 인증 수준 기반 연결 지원       # 후속 구현 PR
```

## 8. 로컬 검증 및 게시 범위

- 최신 `origin/develop` 위로 rebase 후 `./gradlew compileJava test --tests '*CertificateHttpIntegrationTest' --tests '*BankCatalogTest' --tests '*CertificateInstitutionCatalogAdapterTest' --no-daemon` 통과.
- 공개 v3 수준 1·2·3, v2 Boolean 응답 유지, 우리은행·대구은행 추가 입력과 승인까지 HTTP 통합 테스트로 검증했다.
- `git diff --check` 통과. 실제 CODEF/운영 환경 인증과 배포는 수행하지 않았다.
- SQL-only #537은 사용자 요청으로 닫혔으며 해당 V54 커밋을 이번 기능 PR에 포함한다. Java 및 migration을 함께 배포한다.
- 문서 검증 결과는 `docs(account): 은행 인증 수준 구현 검증 결과 기록` 커밋으로 분리한다.

## 9. PR 리뷰 반영 및 단계적 배포

- 인증서 Result/Properties/YAML에서 계좌 요구 Boolean을 제거했다. 수준 1·2는 계좌 자격정보를 요구하지 않고 3만 요구한다.
- V54은 `use_yn`과 관계없이 모든 BANK 행을 전환한다. 비활성→재활성화 SQL 회귀 검증을 HTTP 통합 테스트에 추가했다.
- v3 Swagger 메타데이터는 `BankCatalogDocs`로 이동했다.
- 선행 호환 reader [#545](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/545)를 먼저 병합·배포하고 그 이미지 SHA를 롤백 기준으로 기록한다. 그다음 #543의 V54과 새 reader를 배포한다.
- V54 이후에는 호환 reader 이미지까지만 롤백한다. 더 오래된 `1` 전용 reader로 돌아가지 않는다. 호환 기간에 초기 수준/지원 은행 매핑은 변경하지 않는다.
- 검증: 호환 reader의 `1`·`3` 회귀 테스트 및 본 PR의 인증 수준 카탈로그 단위 테스트 통과. HTTP·영속성 통합 테스트는 Docker daemon ping 응답 대기에서 진행되지 않아 실행 프로세스만 종료했다(exit 143). 신규 비활성 은행 SQL 회귀 테스트는 컴파일됐으나 DB 실행은 검증하지 못했다. Docker 복구 후 재실행이 필요하다.

## 10. CI 마이그레이션 번호 충돌 수정 (#546)

- CI run 38560959473의 첫 실패는 Flyway version 53 중복이다. 정산 V53과 은행 인증 V53이 develop에서 합쳐지며 발생했다.
- 정산 파일은 V53으로 유지하고 은행 인증 파일만 V54로 이동한다. SQL 본문과 schema 계약은 변경하지 않는다. 본 문서의 은행 전환 버전 참조도 V54로 갱신했다.
- 새 migration을 설계하는 작업이 아니라 병합된 미배포 파일의 번호 충돌을 수정하는 작업이다. 자동 Flyway repair나 DB 삭제는 하지 않는다.
- 영향: 은행 migration 파일명, SQL 회귀 테스트의 ClassPathResource, Gradle resource/test 사전 검사, 관련 문서.
- Gradle `verifyMigrationVersions`는 DB 없이 숫자 버전 중복을 검사한다. 선행 0과 trailing zero/dot/underscore 표현을 정규화해 동일 버전을 구분하지 않는다.
- 검증: 정상 migration 목록 통과 및 임시 V054 중복 fixture 실패 확인, 컴파일·카탈로그 단위 테스트, 원격 PR CI.
- 커밋 경계: `fix(account)` 번호와 참조 경로 수정; `build(db)` 중복 번호 사전 검사.
- 로컬 결과: 임시 V054 fixture가 V54와 중복임을 감지해 실패했고 fixture는 제거했다. `./gradlew clean verifyMigrationVersions compileJava test --tests '*BankCatalogTest' --tests '*CertificateInstitutionCatalogAdapterTest' --no-daemon` 통과.
- 최신 origin/develop 및 열린 PR의 migration 파일을 재확인해 V54가 비어 있음을 확인했다.
