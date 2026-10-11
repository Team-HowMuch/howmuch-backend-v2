# 금융사 연결 목록과 계좌 후보 조회

> 현재 승인/저장 구현은 [모바일 동의 계획](certificate-mobile-consent.md)을 따른다. 아래 최초 검증의 BIND 미구현 메모는 당시 기록이다. 이후 실제 QR 승인→우리은행 BOUND→계좌 후보 2건 조회를 확인했다. [실제 검증 집계](certificate-live-connection-results.json).

## 목적과 범위

계좌 선택 전에도 사용자가 자신에게 귀속된 금융사 연결을 확인하고 기존 연결로 계좌 후보를 다시 가져올 수 있게 한다. `connectedId` 원문과 자격정보는 반환하지 않는다. 사용자에게 귀속되지 않은 PC 인증 임시 연결은 목록에서 제외한다.

기존 `external_connections`와 `connected_institutions` 및 `accounts`를 읽으므로 신규 Flyway 마이그레이션은 필요하지 않다. 기존 사용자당 활성 연결 하나라는 저장 제약은 유지하며, 응답은 금융사별 목록으로 제공한다. 인증 방식은 현재 기관 테이블에 기록되지 않아 추정하여 반환하지 않는다. 인증 방식 메타데이터 저장, 인증서 최종 승인·명의확인·BIND 구현은 별도 범위다.

## 계약과 구현

- `GetBankConnectionsUseCase`: 로그인 사용자의 활성·현재 CODEF 구성에 속하는 금융사 연결 목록. 연결 ID, 기관 코드/이름/아이콘, 상태, 기관 연결 생성 시각, 저장된 활성 계좌 개수.
- `GetBankConnectionAccountsUseCase`: 연결 ID와 기관 코드, 선택 생년월일로 기존 연결의 계좌 후보 조회. 기존 `BankConnectionResult`와 후보 Key 생성 규칙을 재사용한다.
- `LoadBankConnectionsPort`: 목록에 필요한 비밀 없는 연결 요약을 읽는 실제 persistence 경계. 후보 조회는 기존 연결 store와 CODEF Port를 사용한다.
- `GET /api/v2/accounts/connections`
- `GET /api/v2/accounts/connections/{connectionId}/accounts?organization=0020&birthDate=19990101`

Web → Query/UseCase → Result → Response 흐름을 유지한다. CurrentActor로 소유자를 확인한 뒤에만 CODEF 호출을 허용하고, 다른 사용자/비활성/구성 불일치 연결은 404로 처리한다. 기관에 저장된 추가 계좌 자격정보를 기존 보호 서비스를 통해 사용한다. 후보 조회는 신규 CODEF 계정 등록이나 계좌 저장을 하지 않는다. 목록 GET은 CODEF를 호출하지 않는다. 인증서 작업의 기존 Kafka command/result, Inbox/Outbox는 변경하지 않는다.

## 검증과 변경 단위

Java compile 및 관련 단위/Web 테스트: 타인 연결과 미등록 기관 차단, 추가 자격정보 전달, 기존 후보 Key 호환성, 금융사별 계좌 개수, 원문 connectedId 미노출, JWT 미인증 거부, 입력 검증을 확인한다. 환경을 확인한 뒤 기존 account 테스트를 함께 실행한다. 실제 인증서 BIND가 아직 없어 이 API 추가만으로 인증서 QR에서 계좌 연동이 완료됐다고 보고하지 않는다.

예상 변경 단위: `feat(account): 금융사 연결 목록과 기존 연결 계좌 후보 조회 추가`. 커밋·push·PR은 사용자 요청에 따라 보류한다.

## 검증 결과 — 2026-10-11

- `./gradlew compileJava` 성공. 관련 Application/Web/실제 PostgreSQL 조회/HTTP 보안 필터 테스트 52개 성공. PostgreSQL·Redis 정상 상태를 확인하고 전용 로컬 DB 환경에서 실행했다. 실제 projection은 타인·비활성·구성 불일치 연결을 제외하며 connectedId 복호화 없이 동작하는 것을 검증했다.
- 초기 테스트의 mock 생성 순서, 테스트용 Discord 설정 누락, fixture public_id 형식 문제를 수정한 뒤 같은 52개 테스트를 재실행해 모두 통과했다.
- 활성 인증서 등록 작업/유효 세션이 없는 것을 확인하고 로컬 API를 재시작했다. 실제 HTTP로 로그인 fixture의 연결 목록 200/빈 배열, 미인증 401, 비소유 연결 404, 잘못된 입력 400 및 Swagger bearerAuth 요구를 확인했다. [로컬 HTTP 결과](bank-connection-query-results.json). 실제 인증서의 계좌 조회 성공을 검증한 것은 아니다.
- 실제 로컬 DB의 command Outbox PUBLISHED 29건, result Outbox PUBLISHED 22건과 provider Inbox 처리 기록을 확인했다. 등록/결과 전송은 Kafka이며 이번 조회 기능은 기존 동기 계좌 조회 경계를 재사용한다.
- 인증 방식 메타데이터, 최종 승인·명의확인/BIND, 실제 인증서 계좌 조회, dev/prod 배포는 후속 작업이다. UI 화면은 이번 API 변경에 포함하지 않았다. 전체 Java suite는 수행하지 않았으며 커밋·push·PR은 생성하지 않았다.
