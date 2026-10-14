# Feature Plan: 모바일 선택 금융기관 고정

- Status: 구현·로컬 검증 완료, develop 반영 중
- Last updated: 2026-10-14

## 목적과 완료 조건
모바일에서 은행을 선택한 뒤 PC QR을 스캔한다. 현재 claim 요청에는 qrToken만 있고 PC에서 은행을 다시 선택하여 사용자 요구와 다르다.
- 모바일 claim이 organization을 필수로 받는다.
- PC polling 응답이 등록 세션 생성 전에도 organization을 반환한다.
- PC 은행 dropdown은 해당 은행으로 선택되고 항상 disabled이다.
- authenticationLevel=3일 때만 계좌번호/계좌 비밀번호 필드를 표시한다. 기존 생년월일 요구는 유지한다.
- PC가 다른 organization으로 등록을 요청하면 거절한다. 재시도에도 은행을 유지한다.
- 다른 사용자, 만료, 취소, 중복 claim에 대한 기존 보호를 유지한다.

## 도메인·계약·설계
기존 certificate pairing 모델과 CertificatePairingStorePort를 확장한다. 새로운 저장 시스템이나 Port는 만들지 않는다.
- POST /api/v2/certificate-pairings/{id}/claim: {qrToken, organization}. Web Request -> PairCertificateDeviceCommand -> 기존 UseCase. organization은 4자리 은행 코드이며 기존 은행 카탈로그로 유효성을 검증한다.
- pair 트랜잭션에서 사용자와 organization을 원자적으로 저장한다. 동일 사용자/동일 은행 재요청은 멱등, 동일 사용자/다른 은행은 CERTIFICATE_CONFLICT.
- CertificatePairingResult.organization은 pairing 저장값을 우선 사용한다. PC createRegistration은 저장된 organization과 요청값을 비교한다. 모바일이 지정하지 않은 구형 pairing으로 신규 등록을 허용하지 않고 새 QR을 안내한다.
- 기존 등록/업로드/최종 모바일 승인 경로와 Kafka는 유지한다. 추가 환경변수는 없다.
- 앱 client.pairingStatus는 organization을 renderer까지 전달한다. renderer는 카탈로그에 해당 은행이 있는지 검증하고 select 값/disabled를 고정한 뒤 requirements()를 실행한다. 임의 기본 은행이나 PC 선택으로 대체하지 않는다.
- 수동 테스트 HTML은 v3 은행 목록을 조회하고 모바일 역할의 은행 선택값을 claim에 넣는다.

## 저장 구조와 migration gate
- Flyway migration required: Yes
- V55__add_certificate_pairing_organization.sql: certificate_pc_pairings.organization_code VARCHAR(4), nullable for legacy rows and pre-claim pairing. 형식 CHECK 및 한번 설정된 코드의 변경/삭제 금지 trigger.
- 이전 세션이 연결된 취소되지 않은 pairing은 해당 세션의 organization으로 backfill한다. 취소된 pairing은 기존 불변 규칙을 유지하여 변경하지 않는다. 세션이 없는 구형 pairing은 추측하지 않는다.
- 기존 guard_certificate_pc_pairing 및 V51 실패 재시도 규칙은 변경하지 않는다.
- 최신 origin/develop은 V54. 열린 PR #342에는 migration이 없음을 확인했다.
- 저장 규약에 따라 DB-only PR #550 생성 후 종료했고, 사용자가 계속 진행을 요청하여 후속 서버·앱 구현을 진행한다.

## 영향 범위
ClaimCertificatePairingWebRequest, PairCertificateDeviceUseCase, CertificatePairingService, CertificatePairing 모델/Store/JdbcAdapter, pairing Docs, 앱 client.cjs/renderer.js 및 manual.html.
DB 변경은 additive이며 구버전 서버는 새 칼럼을 무시한다. 구버전 앱의 PC 임의 선택은 새 서버에서 요청 일치 검증으로 차단된다. 서버/앱/모바일을 함께 배포해야 전체 흐름이 완료된다.

## 단계·예상 커밋
- [x] build(certificate): 모바일 선택 금융기관 저장 칼럼 추가 — SQL only PR
- [x] feat(certificate): 모바일 금융기관 선택을 pairing에 고정 — 후속 서버 구현/검증
- [x] fix(certificate): PC 금융기관 선택 고정 및 모바일 테스트 요청 반영 — 후속 앱 구현/검증
- [ ] 정상 claim·PC polling·level별 필드·불일치·중복·실패 후 재시도·취소 검증

## 이번 검증 계획
실제 로컬 PostgreSQL에서 V55 적용, 구형 insert/update 호환, 유효/무효 코드, 설정 코드 변경/삭제 거부, legacy backfill을 확인한다. git diff --check 및 migration 버전 중복 검사. 전체 앱 흐름 검증은 구현 후 진행한다.

## 검증 결과
- 로컬 PostgreSQL에서 V55 적용, linked backfill, cancelled legacy 불변, unlinked NULL 호환, 기존 V51 guard 유지, 코드 형식/변경/삭제 거부, 원자적 owner/bank 설정, 멱등 update, 취소, 구버전 insert, deferred constraints 검증 통과(11개 확인). 전체 검증 transaction rollback.
- verifyMigrationVersions 통과. Java/API/앱 변경은 아직 수행하지 않았다.

## 후속 구현 검증
- compileJava 및 compileTestJava 통과.
- Docker daemon이 응답하지 않아 Testcontainers HTTP/Kafka 전체 실행은 수행하지 못했다.
- 대신 전용 로컬 PostgreSQL DB에 Flyway V1~V55를 적용하고, 기존 integration test fixture와 실제 Service/JDBC/Transaction으로 12개 시나리오를 직접 실행해 통과했다. 모바일 은행 저장/조회/변경 거절, 미지원 은행, legacy 누락, 초기 연결, 사용자 멱등/격리, capability, 최종 Yes/No, 실패 표시, 취소, 만료를 포함한다. Kafka 외부 네트워크는 검증 범위 밖이다.
- 같은 실제 Service/JDBC에 standalone MockMvc를 연결하여 organization 누락 400, claim 저장/응답, PC polling 응답을 확인했다. JWT 필터는 이 확인에서 제외했다.
- 앱 Node 테스트 46개, 실제 Electron synthetic smoke 및 manual HTML proxy의 organization 전달 검증 통과.
- 실제 모바일 앱 코드가 제공되지 않아 모바일 구현은 제외했다. API 계약 및 manual HTML에서 변경 흐름을 제공한다.
