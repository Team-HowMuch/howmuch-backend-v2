# Feature Plan: 정산 상세 수금 계좌 정보

- Status: Completed
- Last updated: 2026-09-05
- Related issue: [#280 정산 상세 응답에 수금 계좌 정보 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/280)
- Related ADR: N/A

## 1. 목적

정산 상세 화면의 상세 정보 탭에서 생성자가 선택한 수금 계좌의 은행명·계좌번호·은행 아이콘을 표시한다.
기존 `SettlementReceivingAccountSnapshot` 저장과 `GET /api/v2/settlements/{settlementId}` 경로를 재사용한다.

성공 기준:

- `CREATOR` 응답에 `bankName`, `accountNumber`, `institutionIconUrl`을 최상위 nullable 필드로 반환한다.
- `PARTICIPANT` 응답은 세 필드를 `null`로 반환한다.
- DRAFT의 스냅샷 없음은 `null`로 반환하고, 진행 상태의 스냅샷 누락은 `404 / SETTLEMENT_DEPOSIT_DATA_NOT_FOUND`로 처리한다.

범위 밖: DB/Flyway 변경, 계좌 스냅샷 저장 흐름 변경, `my-transfer-instruction` 응답 변경, 프론트·EC2 배포.

## 2. 도메인 분석

- 기존 settlement 도메인과 `SettlementDetailService`를 확장한다.
- 수금 계좌 스냅샷은 요청 처리 시 생성되며 `REQUESTED` 이상 상태에서는 존재해야 한다.
- DRAFT는 계좌 선택 전 상세 조회가 가능하므로 스냅샷이 없어도 정상이다.
- 참여자는 이미 `viewerRole`로 판별되므로 수금 계좌 Port를 호출하지 않는다.

## 3. 계약

Application Result와 Web Response에 다음 최상위 필드를 추가한다.

```text
bankName: String?
accountNumber: String?
institutionIconUrl: String?
```

- `bankName`: 활성 `BankCatalogResult.name`, 없으면 `organization` 코드
- `accountNumber`: 기존 스냅샷 Adapter가 복호화한 계좌번호
- `institutionIconUrl`: `BankCatalogResult.iconUrl`, 없으면 null
- CREATOR + DRAFT 또는 계좌 정보 없음: 세 필드 null
- CREATOR + REQUESTED/AWAITING_DEPOSIT/SUSPENDED/COMPLETED + 스냅샷 없음: `SETTLEMENT_DEPOSIT_DATA_NOT_FOUND` 404
- PARTICIPANT: 스냅샷 미조회 및 세 필드 null

기존 `LoadSettlementReceivingAccountSnapshotPort`와 `BankCatalog.getBanks()`를 재사용한다.
새 Output Port, Entity, migration은 만들지 않는다.

## 4. 구현 계획

- [x] `SettlementDetailResult`와 `SettlementDetailWebResponse`에 세 필드 추가
- [x] `SettlementDetailService`에 CREATOR 전용 스냅샷·은행 카탈로그 조립 추가
- [x] OpenAPI required/nullable 문서 갱신
- [x] 서비스·컨트롤러·OpenAPI 테스트 추가 또는 갱신
- [x] 관련 검증 및 문서 완료 기록

예상 커밋:

```text
feat(settlement): 정산 상세에 수금 계좌 정보 추가
```

브랜치: `feat/settlement-detail-receiving-account`, 대상: `develop`

## 5. 검증

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementDetailServiceTest' \
  --tests '*SettlementControllerTest' \
  --tests '*SettlementOpenApiIntegrationTest' \
  --tests '*SettlementTransferInstructionServiceTest'
git diff --check
```

검증 시나리오:

- CREATOR + 스냅샷의 은행명·계좌번호·아이콘 매핑
- CREATOR + DRAFT의 null 응답
- 진행 상태 + 스냅샷 누락의 404
- PARTICIPANT의 null 응답과 스냅샷 Port 미호출
- OpenAPI 세 필드와 nullable 계약
- 기존 송금 안내·스냅샷 복호화·정산 상세 회귀

## 6. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 |
|---|---|---|
| 2026-09-05 | 최신 `origin/develop` 확인, 이슈 #280 생성, 전용 브랜치 생성, 구현과 단위/Web 계약 테스트 완료 | 커밋·PR |

## 7. 완료 기록

- 완료일: 2026-09-05
- 검증 결과: `./gradlew compileJava`, `SettlementDetailServiceTest`, `SettlementControllerTest`, `git diff --check` 통과
- 미검증: `SettlementOpenApiIntegrationTest`는 Docker Compose PostgreSQL 미기동으로 컨텍스트 생성 실패
- 남은 위험: PostgreSQL이 제공되는 환경에서 OpenAPI nullable 스키마 통합 검증 필요
