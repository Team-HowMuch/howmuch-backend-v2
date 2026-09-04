# Feature Plan: 정산 Mock 송금 링크 및 어댑터 선택

- Status: Complete
- Last updated: 2026-09-05
- Related issue: #264, #265, #266, #267
- Related ADR: N/A

## 1. Purpose

### Problem

정산 참여자가 배포된 Mock 송금 화면에서 확정 금액과 수취계좌로 테스트하려면 기존 Toss 링크 외의 URL이 필요하다. 기관코드가 없으면 화면 진입 후 송금이 실패하고, 수취인 이름이 없으면 기본 문구가 표시된다.

### Why now

Mock 화면 배포가 완료되어 서버의 링크 생성과 설정 선택을 연결한다.

### Success criteria

- [x] Mock 링크에 bankCode, bank, accountNo, amount, receiver를 전달한다.
- [x] local·dev는 Mock, 공통·prod는 Toss를 기본으로 사용하며 환경변수로 바꾼다.
- [x] 두 송금 안내 API와 기존 Toss 링크 계약을 유지한다.
- [x] 링크 조회가 실제 송금 또는 납부 상태 변경을 일으키지 않는다.

### Out of scope

클라이언트 수정, sender·redirect 전달, 실제 송금, 기존 거래 삭제, 프록시 변경, 입금 대조 변경.

## 2. Domain analysis

- 기존 settlement 도메인과 수취계좌 스냅샷을 재사용한다.
- 수취인은 정산 생성자 이름이 아니라 snapshot.accountHolder()이다.
- 공개 링크 참여자와 로그인 참여자 모두 공통 Reader를 통해 안내를 받는다.
- 상태 전이는 없다. 기존 확정 Share·권한·조회 가능 상태를 유지한다.

## 3. Scenarios

1. 기존 UseCase가 참여 문맥 또는 현재 회원으로 참여자를 결정한다.
2. Reader가 정산·Share·수취계좌 스냅샷을 조회한다.
3. 선택된 어댑터가 코드·계좌·금액·예금주로 링크를 생성한다.
4. 기존 transferLinks 배열에 provider=TOSS와 생성 URL을 반환한다.

미지원 은행 또는 잘못된 입력은 Optional.empty()를 반환하고 기존 SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED(422)로 연결한다. 계좌번호는 하이픈 제거 후 숫자 1~14자리, 금액은 양수이다. Mock은 4자리 bankCode의 선행 0을 보존하고 쿼리 값을 UTF-8 인코딩한다. 수취인 값이 없으면 receiver를 생략한다.

## 4. Contracts

### Input Port and boundary mapping

기존 GetSettlementTransferInstructionUseCase와 ResolveMyTransferInstructionUseCase, Result 및 Web DTO를 재사용한다.

### Output Ports

SettlementTransferLinkPort.create(String bankCode, String accountNumber, long amount, String receiverName)으로 확장한다. 호출자는 SettlementTransferInstructionReader이며 snapshot.accountHolder()를 전달한다. Toss 어댑터는 receiverName을 사용하지 않는다.

### Application Policy

N/A. 기존 조회 규칙을 유지하며 URL 규격과 Bean 선택은 기술 경계가 소유한다.

### Web API

- GET /api/v2/settlement-links/{token}/transfer-instruction
- GET /api/v2/settlements/{settlementId}/my-transfer-instruction
- 응답: 기존 transferLinks[{provider, url}] 및 나머지 필드 유지
- provider: Mock에서도 TOSS 유지(기존 클라이언트 선택 호환)
- 기존 인증·권한·404·422 계약 유지

### Persistence and external integrations

- Flyway migration required: No. Entity·쿼리·Redis 변경 없음.
- Mock URL: https://howmuch-mock-send.vercel.app/send
- 쿼리 순서: bankCode, bank, accountNo, amount, receiver
- Mock 은행명: BankCatalog.findBankName(); Toss 전용 attr2에 의존하지 않는다.
- 백엔드가 Mock API에 HTTP 요청을 보내지 않는다.

## 5. Design decisions

- MockSettlementTransferLinkAdapter를 기존 Toss 옆에 추가한다.
- infra.settlement.config의 Properties가 enum TOSS/MOCK을 바인딩한다.
- Config가 두 구현 중 한 개만 Bean으로 생성하며 Toss의 @Component는 제거한다.
- Reader의 List<SettlementTransferLinkPort> 주입은 유지한다.
- howmuch.settlement.transfer-link.adapter: ${SETTLEMENT_TRANSFER_LINK_ADAPTER:toss}
- local·dev의 adapter 기본값만 mock으로 재정의한다.
- howmuch.settlement.transfer-link.mock.base-url: ${SETTLEMENT_MOCK_TRANSFER_BASE_URL:https://howmuch-mock-send.vercel.app/send}
- 알 수 없는 enum 값은 기동 실패. Mock 선택 시 base URL을 검증한다.
- URL은 HTTPS 또는 HTTP loopback만 허용하고 user-info·query·fragment가 없는 절대 URL이어야 한다. 쿼리는 어댑터가 생성한다.
- 설정 변경은 재시작 후 적용된다. Toss로 되돌릴 때 SETTLEMENT_TRANSFER_LINK_ADAPTER=toss를 사용한다.
- 새 Port나 공용 URL 프레임워크는 추가하지 않는다.

## 6. Impact surface

Application: Port·공통 Reader·관련 테스트. Adapter: 기존 Toss 및 신규 Mock. Infra: Properties·Config. Configuration: application.yaml/local/dev. Web: Swagger 설명과 계약 테스트. Domain·저장·Security 변경 없음. 전체 URL·계좌·예금주를 로그 또는 설정 오류 메시지에 포함하지 않는다.

## 7. Implementation plan

- [x] Migration-only pull request: N/A, 저장 변경 없음
- [x] UseCase and Application Command/Result: N/A, 기존 계약 재사용
- [x] Required Output Ports: 기존 Port에 receiverName 추가
- [x] Domain models and rules: N/A
- [x] Application Policies: N/A
- [x] Application Service: 공통 Reader 전달
- [x] Output Adapters: Toss 유지·Mock 추가
- [x] Web Request/Response DTOs, Controllers, and Swagger: DTO·Controller 유지, 설명 보완
- [x] Infra configuration and Bean wiring
- [x] Documentation and verification results

### Expected commit boundaries

브랜치: feat/settlement-mock-transfer-link, 최신 origin/develop 기준. PR 하나, 대상 develop. 자동 병합하지 않는다.

```text
docs(settlement): 모의 송금 링크 구현 계획 수립
refactor(settlement): 송금 링크 생성에 수취인 정보 전달
feat(settlement): 모의 송금 어댑터와 설정 선택 추가
test(settlement): 송금 링크 선택과 응답 계약 검증
```

## 8. Verification

### Commands

```bash
./gradlew compileJava
./gradlew test --tests '*TransferLink*' --tests '*TransferInstruction*'
git diff --check
git status --short --untracked-files=all
```

통합 테스트는 격리 DB howmuch_transfer_link_264를 사용하고 SETTLEMENT_NOTIFICATION_OUTBOX_ENABLED=false로 실행했다. 실제 명령의 테스트 필터에는 `--tests '*SettlementOpenApiIntegrationTest'`도 포함했다. dev 프로필 bootRun은 같은 DB와 로컬 Redis를 사용하고 외부 SMS 주소는 loopback의 사용하지 않는 포트로 지정했다. 검증 후 임시 서버와 DB를 정리했다.

### Completion criteria

- [x] Mock 인코딩·선행 0·계좌 정규화·수취인 생략·잘못된 입력 검증
- [x] 표시용 은행명이 있으면 attr2 없이 Mock 링크 생성
- [x] 환경별 기본값·override·단일 Bean·오설정 기동 실패 검증
- [x] 두 API 응답·권한·오류·기존 Toss 회귀 검증
- [x] PostgreSQL·Redis·필수 환경변수 확인 후 프로필 기동과 API 검증
- [x] 생성 링크 화면 자동 입력 확인(송금 버튼을 누르지 않음)
- [x] 실제 검증과 미검증 사항 기록

### Results

| 항목 | 결과 | 근거 |
|---|---|---|
| 사전 조사 | Pass | 두 조회 경로·스냅샷 예금주·은행 Catalog·기존 응답 확인 |
| GitHub 연결 | Pass | 상위 #264와 실제 서브이슈 #265·#266·#267 |
| 컴파일·관련 테스트 | Pass | compileJava 및 74개 테스트, 실패 0 |
| 설정 선택 | Pass | 실제 YAML로 none/local/dev/prod, 기본 local, 환경변수 override, 단일 Bean, 오설정 검증 |
| Spring 통합 | Pass | 격리 PostgreSQL DB에서 V1~V30 적용, Hibernate validate 및 SettlementOpenApiIntegrationTest 통과 |
| dev 기동 | Pass | localhost:18084, PostgreSQL·Redis 연결, Tomcat 정상 기동 |
| HTTP·OpenAPI | Pass | 두 API의 Mock 설명·응답 코드 확인, 회원 미인증 401, 헤더 있는 미존재 링크 404 |
| 배포 화면 | Pass | 예시 링크에서 현승민·우리은행·수취계좌·2,000원 자동 표시; 송금 미실행 |
| 헤더 누락 요청 | 기존 문제 발견 | 전체 서버에서는 MissingRequestHeaderException이 500으로 처리됨. 변경하지 않은 전역 예외 처리 경계이며 이 기능 범위에서 수정하지 않음 |
| 전체 테스트 | Not run | 변경 영역 74개 및 전체 애플리케이션 OpenAPI 통합으로 검증 범위를 제한 |
| 실제 송금·인증된 실데이터 조회 | Not run | 실제 거래 생성 및 기존 데이터 사용은 이번 검증에서 제외 |

## 9. Progress log

| 날짜 | 완료 작업 | 다음 작업 | 발견 사항 |
|---|---|---|---|
| 2026-09-05 | 계획 확정·브랜치·이슈 연결 | Port 확장 | DB 변경 불필요 |
| 2026-09-05 | Port·어댑터·설정·74개 테스트·기동·화면 검증 | PR 제출 | 기존 헤더 누락 500 발견 |

## 10. Decision log

| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-09-05 | local·dev Mock, 공통·prod Toss | 사용자 확정 |
| 2026-09-05 | provider=TOSS 유지 | 클라이언트 선택 경로 호환 |
| 2026-09-05 | 예금주와 표시용 은행명 사용 | 실제 수취계좌 정보이며 Toss 규격과 분리 |

## 11. Completion record

- Completed on: 2026-09-05
- Final behavior: 설정에 따라 기존 Toss 또는 Mock 링크를 반환하고 Mock에 기관코드와 수취계좌 예금주를 전달한다.
- Unverified items: 실제 송금·인증된 실데이터 HTTP 조회·전체 테스트 스위트. 정상 API 응답은 MockMvc와 Application 테스트로 검증했다.
- Remaining risks: 외부 Mock 화면의 가용성은 링크 생성 성공과 별개이다.
- Follow-up work: 기존 전역 예외 처리의 Link-Participant-Context 헤더 누락 500을 별도 검토할 수 있다.
