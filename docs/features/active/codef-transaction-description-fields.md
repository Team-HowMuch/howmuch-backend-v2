# 기능 계획: CODEF 거래 설명 필드 분리 보존 및 은행별 표시 파싱

- 상태: 구현 완료
- 최종 수정일: 2026-09-14
- 관련 이슈: [#378](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/378)
- 관련 migration: [#379](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/379) (머지 완료)

## 1. 목적

CODEF `resAccountDesc1~4`를 하나의 문자열로 합치기 전에 필드 경계를 보존해 은행별 거래처·상대방·거래종류 표시를 정확하게 만든다. 기존 `description`, 거래 중복 키, 정산 연결 계약은 유지한다.

성공 기준:

- [x] CODEF 설명 슬롯이 `description1~4`로 보존된다.
- [x] NH농협·대구은행의 필드 우선 규칙으로 `display.title`과 `display.kind`를 생성한다.
- [x] 분리 필드가 없는 기존 거래는 기존 원문 fallback으로 조회된다.
- [x] 기존 API와 settlement 연결·중복 저장 동작이 회귀하지 않는다.

제외 범위: CODEF 전체 JSON 저장, 가맹점 로고·카테고리·AI 분류, 사용자별 거래처명 편집, 프론트엔드 변경.

## 2. 설계

- `BankTransactionPort.BankTransaction`과 `domain.account.Transaction`에 CODEF 설명 슬롯을 plain Java 값으로 추가한다.
- `CodefBankTransactionAdapter`는 `resAccountDesc1~4`를 각각 읽고, 기존 `description`은 호환용으로 생성한다.
- `account_transactions.description_1~4`에 nullable로 저장한다. V35 migration은 선행 PR #379에서 머지되었다.
- 조회 시 `organization`과 슬롯 값을 Application Policy에 전달한다. 기존 행은 슬롯이 없으므로 `description` fallback을 사용한다.
- 기관별 의미가 확인되지 않은 값은 추정하지 않고 기존 원문을 반환한다.

## 3. 은행별 규칙

- NH농협(`0011`)과 대구은행(`0031`)은 개인정보 제거 fixture를 기준으로 필드 의미와 표시 제목을 검증한다.
- 대구은행은 CODEF 명세의 `거래종류`·`비고`·`거래점` 슬롯을 구분한다.
- 실제 자격증명·계좌번호·개인 이름은 코드와 fixture에 기록하지 않는다.

## 4. 영향 범위

| 영역 | 변경 |
|---|---|
| 외부 Adapter | `resAccountDesc1~4` 분리 매핑 |
| Application | 표시 Policy 입력과 은행별 규칙 |
| Domain | 거래 설명 슬롯 보존 |
| Persistence | V35 컬럼 insert/load 매핑 |
| Web API | 기존 `description`/`display` 계약 유지 |
| Redis/환경 | 변경 없음 |

## 5. 구현 순서

- [x] Port/Domain 값 확장
- [x] CODEF Adapter 필드 보존
- [x] JPA Entity·Repository·Mapper 저장/조회 매핑
- [x] 은행별 표시 Policy 및 기존 행 fallback
- [x] NH·대구은행 fixture와 회귀 테스트
- [x] 컴파일·집중 테스트·diff 검증

### 예상 커밋 경계

```text
feat(account): CODEF 거래 설명 필드 분리 보존
test(account): 은행별 거래 표시 파싱 회귀 검증 추가
docs(account): CODEF 거래 설명 필드 분리 완료 기록
```

## 6. 검증

```bash
./gradlew compileJava
./gradlew test --tests '*CodefBankTransactionAdapterTest' --tests '*AccountTransactionPersistenceIntegrationTest' --tests '*AccountTransactionDisplayNormalizerTest'
git diff --check
git status --short --untracked-files=all
```

PostgreSQL은 V35가 적용된 일회용 DB를 사용한다. 실제 CODEF 호출은 하지 않는다.

검증 결과:

- `./gradlew compileJava` 통과
- `./gradlew test` 전체 Java 테스트 통과
- JavaScript 테스트 35건 통과
- `CodefBankTransactionAdapterTest`, `AccountTransactionPersistenceIntegrationTest`, `AccountTransactionDisplayNormalizerTest` 집중 검증 통과
- `AccountQueryServiceTest`, `SettlementDepositServiceTest` 회귀 검증 통과
- `git diff --check` 통과

## 7. 진행 로그

| 날짜 | 완료 | 다음 | 위험 |
|---|---|---|---|
| 2026-09-14 | #378 이슈 생성, #379 migration 머지, 설명 슬롯 분리·Persistence·NH/DGB 표시 테스트 구현, 전체 검증 통과 | PR 리뷰 반영 | 실제 은행 응답이 아닌 개인정보 제거 fixture 기준이며, 미검증 은행은 fallback |
