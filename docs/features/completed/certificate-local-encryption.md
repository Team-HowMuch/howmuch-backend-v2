# 인증서 로컬 암호화 호환성 수정

- Status: 구현 및 최신 develop 기준 전체 검증 완료

## 목적과 완료 기준
기존 계좌 암호화의 로컬 키 지원과 일치하도록 인증서도 프로필과 무관하게 명시적 로컬 키로 동작한다. Kafka, 승인 흐름, API, TTL, 인증 경계는 유지한다. dev 프로필에서 인증서 API 기동과 암호화 왕복을 검증한다.

## 설계와 호환성
- 기존 CertificateSecretProtectionPort와 CertificateEnvelope를 재사용한다. 새 UseCase/Port/Domain/Policy는 필요 없다.
- Flyway: No. payload/wrappedKey/keyId 저장 구조와 `local:v1` 식별자를 유지한다.
- KMS key ID가 있으면 신규 암호화는 기존 KMS 방식을 사용한다. 없으면 CERTIFICATE_LOCAL_WRAPPING_KEY(32-byte Base64)를 사용한다. 프로필 우회는 하지 않는다.
- 두 키를 함께 설정하면 기존 LOCAL 암호문도 읽을 수 있다. KMS 암호문은 저장된 keyId로 복호화해 설정 키 변경 시에도 기존 키 권한이 있으면 읽을 수 있다. KMS 오류를 임의로 LOCAL로 숨기지 않는다.
- 계좌의 local:v1: 문자열, certificate의 바이트 envelope, AAD, 키 분리는 그대로 유지한다. 인증서 키와 ACCOUNT_CREDENTIAL_LOCAL_KEY는 공유하지 않는다.
- AES-256-GCM(12-byte nonce, 128-bit tag) 바이트 처리만 같은 security 패키지의 공통 구현으로 추출한다. 각 Adapter가 직렬화, 목적별 AAD, 오류 변환을 소유한다.
- 계좌의 이중 암호문/일시 KMS 장애 fallback은 기존 동작 그대로다. 인증서 envelope는 wrapping 하나만 저장하므로 이중 저장/fallback을 이번에 추가하지 않는다.
- 키는 자동 재생성하지 않는다. 원래 키가 없으면 해당 암호문을 복호화할 수 없으므로 백업과 보관이 필요하다.

## 영향 범위와 구현 순서
1. 공통 AES-GCM 구현 추출, 계좌/인증서 Adapter 적용.
2. 인증서 profile 제한 제거, 명시적 키 검증 및 저장 keyId 복호화.
3. 기존 포맷 fixture, KMS↔LOCAL, 변조/AAD, 계좌 회귀 테스트.
4. 배포 문서, dev 비공개 키 영속 설정 및 런타임 확인.

## 예상 커밋 경계
- fix(security): 기존 암호문과 호환되는 인증서 로컬 암호화 지원
- docs(certificate): 인증서 키 설정 및 dev 검증 기록
최초 로컬 수정 요청에서는 커밋/푸시/PR을 생성하지 않았다. 아래 2026-10-13 후속 게시 요청에서 이를 진행한다.

## 검증
compileJava, 관련 security/account/CODEF/certificate 테스트, bootJar, dev 기동 및 API 명세, git diff --check. 실제 고객 인증서/비밀번호를 자동 요청에 사용하지 않는다. prod 런타임은 수정하지 않는다.

## 최초 결과 (2026-10-12 수동 검증 기록)
- 최신 origin/develop(93377784) 기준 codex/certificate-local-encryption 작업 트리에서 작업했다. 기존 작업 트리의 변경을 건드리지 않았다.
- compileJava, bootJar 성공. PostgreSQL/Redis disposable 인스턴스 및 Testcontainers Kafka를 사용한 관련 Java 테스트 103개 통과(실패/오류/스킵 0), JavaScript 35개 통과.
- 수정 전 포맷과 동일한 독립 Node crypto fixture로 계좌/인증서 기존 암호문 읽기를 검증했다.
- HTTP 통합 테스트의 synthetic 승인/등록 흐름, persistence/경합 테스트를 포함했다. 실제 고객 인증서는 사용하지 않았다.
- 초기 검증 실행에서 bootJar 뒤 --tests 인자를 배치해 옵션 오류가 발생했다. 인자 순서를 수정한 위 실행이 성공했다.
- dev 적용 완료: CERTIFICATE_ENABLED=true, CERTIFICATE_LOCAL_WRAPPING_KEY 전용 키 영속 저장, .env 권한 0600. 기존 프로필 dev, Kafka SSL 및 나머지 환경 변수는 유지했다.
- dev 이미지: howmuch-certificate-local:dev-1126c6cc91d0. 앱 11.293초 기동, restart_count=0. command/result consumer 각 3개 파티션 할당. 공개 /v3/api-docs HTTP 200, /api/v2/certificate-claims 노출 확인.
- dev synthetic upload가 READY_FOR_CLAIM에 도달해 실제 로컬 암호화 저장을 확인했다. 직후 정상 취소했다. 모바일 승인 및 실제 CODEF 등록/계좌 조회는 이번 검증에서 실행하지 않았다.
- private 운영 백업: /home/ubuntu/howmuch-kafka-dev/app-integration-backups/local-encryption-20261012T004209Z. 기존 compose/.env와 적용 후 키가 있는 .env.local-encryption-active를 분리 보관한다. 키 값은 문서/로그/소스에 기록하지 않았다.
- 배포 산출물은 커밋되지 않은 변경을 포함한 수동 dev 검증 이미지다. 다음 정식 배포에 이 코드가 포함되어야 유지된다. 커밋/푸시/PR은 생성하지 않았다. prod 런타임은 변경하지 않았다.
- 기존 management 접근 CIDR로 인한 CI readiness 403 문제는 이번 암호화 수정 범위에 포함하지 않았다. 공개 API 확인을 management readiness 통과로 보고하지 않는다.
- 세부 테스트/운영 결과: [검증 기록](certificate-local-encryption-results.json).


## PR 게시 요청 (2026-10-13)

사용자가 남아 있는 수정본의 PR 생성을 요청하면서 codex/ 브랜치 접두어 변경을 지정했다. 브랜치를 `fix/certificate-local-encryption`으로 바꾸고 최신 `origin/develop` (`a662f1e2`) 위로 갱신했다. 기존 수정은 모두 보존했고 SQL 변경은 여전히 없다. 인증서 저장 프로필 제한 제거·AES-GCM 공통화·기존 암호문 읽기·KMS 우선 선택·저장된 KMS keyId 사용이 게시 범위다.

- 위 수동 dev 배포 기록은 과거 상태다. 2026-10-13 현재 dev는 GHCR 정식 이미지이며 `CERTIFICATE_ENABLED=false`, 로컬 wrapping key 존재, KMS key ID 없음으로 확인했다. 이번 요청은 PR 생성이며 서버 환경/배포는 변경하지 않는다.
- 기존 테스트의 명시적 키 구성 검증에 KMS만 있고 로컬 키가 없는 기존 운영 구성을 추가해 생성자 호환을 확인한다.
- 임시 PostgreSQL/Redis를 새로 만들고 CI의 `scripts/test.sh`로 최신 develop 기준 컴파일·전체 테스트·bootJar 및 JavaScript 회귀를 다시 실행한다. 실서비스 DB/Redis·실제 은행·AWS KMS는 사용하지 않는다.
- 커밋 경계: (1) `fix(security): 인증서 로컬 암호화 지원과 기존 암호문 호환성 유지` — 소스·회귀 테스트, (2) `docs(certificate): 인증서 키 설정과 PR 검증 결과 정리` — 배포 문서·완료 계획·검증 기록.
- 관련 이슈는 기존 인증서 기능 #489를 참조한다. 해당 이슈 전체를 이번 수정으로 자동 종료하지 않는다.

## PR 게시 검증 결과 (2026-10-13)

- Branch: `fix/certificate-local-encryption`, base `origin/develop` (`a662f1e2`). 기존 codex/ 접두어를 제거했다.
- `bash scripts/test.sh compileJava test bootJar`: 성공. Java **1,749개** (232 suites), JavaScript **35개** 통과. 실패/오류/skip 0. 임시 PostgreSQL 16.14 및 Redis 8, Testcontainers PostgreSQL/Kafka를 사용했다. 컴파일 및 bootJar도 성공했다.
- 암호화 왕복·legacy 독립 fixture·변조/AAD·로컬 키 오류·KMS 단독 설정·기존 로컬 암호문의 KMS 모드 복호화·저장된 KMS keyId·KMS 장애의 실패 처리, 실제 HTTP 인증서 승인/등록 통합 테스트가 전체 회귀에 포함됐다. AWS KMS 실서버 및 실제 은행 호출은 수행하지 않았다.
- `git diff --check`와 완료 문서의 상대 링크 검증 통과. SQL/설정 기본값 변경 없음. netting 및 Mock/connector 변경은 PR에 포함하지 않는다.
- 로컬 전체 검증 로그 `/tmp/howmuch-certificate-pr-tests.log`, 세부 결과는 같은 폴더의 `certificate-local-encryption-results.json`. 이전 수동 dev 검증은 해당 JSON의 `historicalVerification20261012`로 구분한다.
- 현재 요청에서는 원격 게시와 PR만 진행한다. dev/prod 배포는 실행하지 않는다. dev 인증서 기능은 배포 후 기존 로컬 wrapping key를 유지하고 `CERTIFICATE_ENABLED=true`로 활성화해야 한다. prod는 기존 KMS 설정을 유지한다.

## PR #513 리뷰 반영 (2026-10-13)

- 리뷰 1건: 배포 문서와 달리 canonical 운영 가이드가 dev/prod에서 LOCAL을 거부하고 현재 설정 KMS key ID로 복호화한다고 설명한 불일치를 지적했다.
- `ops/certificate/README.md`의 키 설정 표와 교체/복구 안내를 실제 Adapter에 맞췄다. 프로필과 무관한 명시적 LOCAL 키, 두 키 동시 설정 시 KMS 우선 쓰기 및 기존 LOCAL 읽기, KMS 실패 시 fallback 없음, 저장 key ID의 Decrypt 권한 보존, replica별 동일 LOCAL 키와 별도 계좌/HMAC 키 사용을 명시했다.
- 코드·환경·DB 변경 없는 문서 수정이다. 직전 전체 회귀 Java 1,749개·JavaScript 35개 통과 기록은 위와 같다. 이번 수정에서는 문서 상대 링크/anchor, stale KMS 안내 제거 및 `git diff --check`를 검증한다.
