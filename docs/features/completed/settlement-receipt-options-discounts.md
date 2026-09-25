# Feature Plan: 영수증 품목 옵션·할인과 비율 할인 분배

- Status: Implementation complete, verification recorded
- Last updated: 2026-09-25
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/418
- Related frontend issue: https://github.com/Team-HowMuch/howmuch-frontend-v2/issues/278

## 1. 목적과 범위

OCR 영수증의 옵션, 품목 할인, 영수증 전체 할인이 정산 등록 이후에도 조회되고 개인 금액에 반영되어야 한다. 품목별 원금은 옵션을 포함한 할인 전 행 금액이며, 품목 할인과 전체 할인은 별도로 보존한다.

성공 기준:

- 옵션과 두 할인값이 등록 및 품목·지출·참여 조회에서 보존된다.
- 품목 몫은 `amount - item.discount`로 계산한다.
- 전체 할인은 품목 몫 비율로 참여자에게 배분하고 원 단위 나머지는 최대 잔여법으로 처리한다. 배분한 할인 합계는 영수증 전체 할인과 일치한다.
- 6,500원/3,900원/2,600원 선택과 전체 할인 1,000원은 개인 금액 6,000원/3,600원/2,400원이 된다.

제외: 더치페이 규칙 변경, 옵션별 수량 저장, 기존 영수증 데이터 소급 재계산.

## 2. 현재 흐름과 영향 범위

`CreateSettlementReceiptExpenseWebRequest` → `CreateSettlementReceiptExpenseCommand` → `SettlementReceiptExpenseService` → `SettlementReceiptExpenseWriter` → `SaveSettlementExpensePort`/`SaveSettlementItemPort` → JPA. OCR 직접 가져오기는 `ExtractReceiptItemsPort`를 거쳐 같은 Writer를 사용한다. 조회는 `LoadSettlementItemPort` 및 지출·참여 조회 Result를 거친다. `SettlementItemAllocationPolicy`가 선택 몫과 확정 Share를 계산한다.

| 영역 | 후속 구현에서 필요한 변경 |
|---|---|
| API | 영수증 요청의 `discount`, 품목 `discount`·`options`; 품목·지출·참여 응답의 할인 정보 |
| Application | Command/Result, OCR 추출 계약, Writer의 금액 검증, 배분 Policy |
| Domain | 품목 할인·옵션, 지출 할인과 금액 불변식 |
| Persistence | 두 할인 컬럼과 옵션 테이블, Entity/Mapper/Adapter |
| Redis·Security·설정 | 변경 없음 |

기존 영수증과 수동·결제내역 지출은 새 할인 컬럼의 기본값 0으로 유지한다. 옵션 수량은 상위 품목 수량을 따른다. `discountShare`는 참여자별 계산 결과로 반환하며 별도 컬럼은 추가하지 않는다.

## 3. 저장 설계와 마이그레이션 판단

Flyway migration required: **Yes**.

- `settlement_items.discount BIGINT NOT NULL DEFAULT 0`, `0 <= discount <= amount`.
- `settlement_expenses.discount BIGINT NOT NULL DEFAULT 0`, `discount >= 0`. `amount`가 할인 후 결제액이면 할인액이 이보다 클 수 있으므로 상한을 두지 않는다.
- `settlement_items.amount` 제약을 `amount >= 0`으로 완화한다.
- `settlement_item_options(id, settlement_id, item_id, name, price, display_order)`를 추가한다. `(settlement_id, item_id)`는 같은 정산의 품목을 참조하며 품목 삭제 시 함께 삭제한다. 옵션명은 공백 제외 1~500자, 가격과 순서는 0 이상, 품목 내 순서는 중복하지 않는다.
- 후속 구현에서 영수증 지출의 `amount`는 품목 할인과 전체 할인을 반영한 실제 결제 금액으로 저장한다. 옵션 가격은 `items[].amount`에 이미 포함되므로 다시 더하지 않는다. 입력 합계와 할인 범위는 Application에서 검증한다.

전체 할인은 `amount`보다 클 수 있다. 1,000원짜리 영수증에 900원을 할인하면 지출 `amount`는 100원이고 `discount`는 900원이다. V37의 지출 할인 제약은 0 이상만 확인한다.

설계 당시 `origin/develop`의 마지막 버전은 V36이었다. 열린 PR 중 migration 파일은 없었고 과거 V37 PR #405는 닫혀 있었다. V37 migration PR은 #419이다.

할인 없는 품목 정산은 참여자 제출 시 즉시 Share를 생성한다. 전체 할인이 있으면 마지막 참여자가 제출해 품목 몫이 확정될 때까지 Share 생성을 미룬다. 이 기간 `personalAmount`와 `discountShare`는 전체 품목 순금액을 분모로 한 잠정값이다. 완료 시 참여자별 품목 몫 합을 분모로 최대 잔여법을 적용하고 Share를 생성한다. 그래야 이전에 송금한 금액을 나중에 1원 수정하지 않는다.

## 4. 후속 구현 순서와 커밋 경계

1. [x] `build(database): 영수증 품목 옵션과 할인 스키마 추가` — migration-only PR #419.
2. [x] `fix(database): 영수증 전체 할인 상한 제약 수정` — `amount`가 할인 후 금액인 계약을 반영해 PR #419에 추가.
3. [x] `feat(receipt): OCR 품목 옵션과 할인 응답 전달` — OCR 응답 계약을 연결.
4. [x] `feat(settlement): 영수증 옵션·할인 저장과 비율 배분 연결` — Domain, Application, Persistence, Web과 제출 흐름을 연결.
5. [x] `test(settlement): 영수증 옵션·할인과 금액 분배 검증` — 정상·100% 품목 할인·나머지·경계값을 검증.
6. [x] `docs(settlement): 영수증 할인 기능 설계와 검증 기록` — 결정 및 검증 결과를 정리.

3~6은 별도 구현 브랜치에서 진행한다. 구현 PR은 마이그레이션 브랜치를 기반으로 검토받는다.

## 5. 검증 계획

SQL 제약·FK·기본값 검토, 임시 PostgreSQL에 Flyway 적용, `./gradlew compileJava`, `git diff --check`, 변경 파일 목록을 확인한다. 관련 Domain·Policy·Web·Persistence 테스트와 실제 PostgreSQL 환경의 migration 및 Hibernate validation을 확인한다.

## 6. 검증 기록

- 임시 PostgreSQL에 Flyway V1~V37 적용 및 Hibernate schema validation 통과.
- 품목 옵션·할인 영속 통합 테스트와 배분·OCR·Writer 단위 테스트 통과.
- 임시 PostgreSQL·Redis와 필요한 webhook 설정을 사용한 관련 테스트 묶음은 통과했다.
- 같은 환경에서 전체 `./gradlew test`는 1,306건 중 24건 실패했다. 변경 범위의 테스트는 통과했다. 남은 실패는 계좌·그룹·정산 Web 테스트 19건의 500 응답, 2026-09-23으로 고정된 링크 만료 시각을 사용하는 정산 테스트 5건이다. Web 테스트 19건은 기존 `develop` 격리 체크아웃에서도 동일하게 실패했다. 링크 만료 시각 실패는 이번 변경과 무관한 고정 시각 테스트 데이터가 원인이다.
- 할인 배분의 내림 합이 전체 할인보다 작은 경계값에 대한 추가 테스트도 통과했다. 이 경우 조회는 잠정 할인 몫을 반환하고 확정은 거부한다.
