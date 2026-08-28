# Feature Plan: CODEF 설정 변경 보호와 로컬 대체 서버

- Status: Completed
- Last updated: 2026-08-28
- Related issue: [#20 외부 계좌 API 연동](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/20)
- Related plan: [CODEF 은행 연결과 결제계좌 연동](codef-bank-account-connection.md)
- Related ADR: N/A — CODEF 기능 내부의 운영 안전장치와 개발 도구다.

## 1. 목적

### 문제

CODEF API 주소, 클라이언트 자격증명, public key 또는 `connectedId` 암호화 키가 바뀌어도 현재 저장된 연결은 계속 조회 대상이다. 다른 CODEF 환경이나 자격 설정에서 발급한 `connectedId`, 또는 새 암호화 키로 복호화할 수 없는 값은 사용할 수 없으므로 무의미한 외부 호출이나 복호화 실패가 발생한다.

또한 CODEF를 지금 바로 사용할 수 없어 실제 제공자 없이 HowMuch의 연결 흐름을 실행하고 응답 데이터를 조절할 개발용 서버가 필요하다.

### 성공 기준

- [x] CODEF 관련 설정 전체에서 비밀 원문을 노출하지 않는 설정 지문을 계산한다.
- [x] 서버 시작 시 저장된 지문과 현재 지문이 다른 연결을 비활성화한다.
- [x] 비활성 연결은 복호화하거나 외부 조회에 사용하지 않는다.
- [x] 사용자가 다시 연결하면 기존 비활성 행을 새 `connectedId`와 지문으로 재활성화한다.
- [x] Python 표준 라이브러리만으로 OAuth, account create/add, bank account-list를 흉내 내는 서버가 실행된다.
- [x] 관리 웹에서 가상 사용자와 계좌를 저장·조회·삭제할 수 있고 SQLite에 유지된다.
- [x] HowMuch가 환경변수 변경만으로 대체 서버를 사용할 수 있다.

### 제외 범위

- 실제 금융기관 자격증명 또는 실제 금융정보 저장
- CODEF의 모든 상품, 카드·거래내역·2-way 인증 모사
- 외부 공개용 인증·권한·고가용성
- 신규 Spring 테스트케이스

## 2. 조사와 설계 기준

- CODEF 공식 Java SDK는 데모와 정식 클라이언트 정보를 별도로 설정하고 서비스 타입별 endpoint를 사용한다. `connectedId`는 계정 등록 요청으로 발급되어 이후 등록 기관 조회에 쓰인다. 따라서 환경/클라이언트 설정이 달라진 경우 기존 ID의 호환성을 추측하지 않고 재연결하도록 비활성화한다.
- OWASP는 secret rotation 시 이전 secret의 revoke/expiration과 암호화 키 변경에 따른 재암호화 전략을 명시적으로 관리하도록 권고한다. 이번 기능은 자동 재암호화 대신 fail-closed 비활성화를 택한다.
- Spring Boot 공식 문서에 따라 시작 작업은 `@PostConstruct`가 아니라 `ApplicationRunner`로 실행한다.

## 3. 계약

### 설정 지문

다음 값을 길이 구분 후 SHA-256으로 해시한다.

```text
baseUrl, oauthUrl, clientId, clientSecret, publicKey,
connectedIdEncryptionKey
```

원문은 저장하거나 로그에 남기지 않는다. timeout 변경은 연결의 소유 환경을 바꾸지 않으므로 지문에서 제외한다.

### 영속화

`external_connections`에 다음 필드를 저장한다.

- `configuration_fingerprint`: 연결을 발급·암호화할 당시 설정 지문
- `active`: 현재 설정에서 사용 가능한지 여부

시작 시 `active=true`이고 지문이 다른 행을 일괄 `false`로 변경하고, 연결된 `accounts`도 함께 비활성화한다. 재연결 시 기존 기관 행을 제거하고 같은 사용자 행을 새 암호문과 지문으로 갱신한다.

### 대체 서버

```text
POST /oauth/token
POST /v1/account/create
POST /v1/account/add
POST /v1/kr/bank/p/account/account-list
GET  /health
GET  /
GET/POST/DELETE /admin/api/**
```

- CODEF 요청의 URL-encoded JSON과 URL-encoded JSON 응답을 지원한다.
- `loginId + organization`으로 관리 웹의 가상 계좌를 조회한다.
- 비밀번호 암호문은 검증·저장·로그하지 않는다.
- 기본 bind는 `127.0.0.1`이며 실제 데이터 입력을 금지한다.

## 4. 영향 범위

| 영역 | 변경 |
|---|---|
| Domain/Application | 활성 연결만 조회하고 비활성 연결을 재연결하는 저장 계약 |
| Persistence | 설정 지문·active 필드, 시작 시 비활성화, 재활성화 |
| Infra | 설정 지문 계산, `ApplicationRunner` |
| Dev tool | `tools/codef-mock` Python/SQLite 서버와 관리 웹 |
| Documentation | 실행 방법, 환경변수, 검증 결과 |

## 5. 구현 계획

- [x] 설정 지문 계산과 startup invalidation
- [x] 활성 연결 조회와 비활성 행 재사용 저장
- [x] SQLite schema와 CODEF 호환 API
- [x] 관리 웹 CRUD
- [x] 실행·HowMuch 연동 문서
- [x] Spring 기존 테스트와 Mock end-to-end 검증

### 예상 커밋 경계

실제 커밋은 요청받은 경우에만 만든다.

```text
feat(payment): CODEF 설정 변경 시 기존 연결 비활성화
feat(payment): 로컬 CODEF 대체 서버와 관리 웹 추가
```

## 6. 검증

```bash
./gradlew clean test
python3 tools/codef-mock/server.py
curl 기반 OAuth/create/add/account-list 시나리오
git diff --check
git status --short --untracked-files=all
```

검증에는 가상 데이터만 사용한다. 실제 CODEF 자격증명과 금융기관 정보는 사용하지 않는다.

## 7. 진행 기록

| Date | Completed work | Next work | Discovered risk |
|---|---|---|---|
| 2026-08-16 | CODEF SDK, OWASP secret lifecycle, Spring startup runner 조사와 계약 확정 | 설정 비활성화와 대체 서버 구현 | 운영 `ddl-auto=validate`에는 별도 DDL 반영 필요 |
| 2026-08-16 | 설정 지문·시작 비활성화, Python/SQLite Mock API와 관리 웹 구현 | 운영 DDL과 실제 배포 반영 | 기존 `.env` DB 계정은 schema 생성 권한이 없어 격리 DB로 검증 |
| 2026-08-16 | OAuth/create/add/account-list와 관리 CRUD HTTP 검증, desktop/mobile 브라우저 QA | 실제 CODEF 전환 시 주소와 자격증명 교체 | 실제 금융 데이터 사용 금지 |
| 2026-08-20 | 저장 경계를 `external_connections`/`accounts`로 변경하고 비활성화를 계좌까지 확장 | PostgreSQL 가동 환경에서 Context 테스트 재실행 | 로컬 PostgreSQL 중지 |
| 2026-08-20 | Flyway V2에 설정 지문과 활성 상태를 포함한 결제수단 스키마 추가 | 기존 DB 최초 baseline 후 실제 배포 확인 | 잘못된 대상 DB baseline 방지를 위해 자동 baseline 기본 비활성화 |

## 8. 완료 기록

- Completed on: 2026-08-16
- Final behavior: CODEF 관련 설정 지문이 달라지면 시작 시 기존 연결이 비활성화되고, 재연결 시 같은 사용자 행이 새 지문과 암호문으로 재활성화된다. `python3 tools/codef-mock/server.py`로 SQLite 기반 대체 API와 관리 웹을 실행할 수 있다.
- Verification: 격리 PostgreSQL에서 `./gradlew clean test` 성공 및 지문 불일치 행의 `active=false` 확인. Mock OAuth/create/add/account-list와 관리 CRUD HTTP 시나리오 성공. Chromium desktop/mobile 렌더링, 폼 제출, console error 없음과 모바일 전체 폭 375px 확인.
- Unverified items: 인증된 HowMuch HTTP endpoint부터 Mock까지의 단일 호출은 실제 사용자 세션을 만들지 않아 수행하지 않았다. Spring CODEF adapter와 Mock의 양쪽 계약은 각각 실행 검증했다.
- Remaining risks: 기존 DB 최초 전환 시 대상 확인 후 한 번만 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`가 필요하다. Mock은 localhost 개발 전용이며 remote 공개를 지원 대상으로 보지 않는다.
