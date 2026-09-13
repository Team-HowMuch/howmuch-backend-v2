# 기능 계획: CODEF 거래내역 표시 정보 표준화

- 상태: 진행 중
- 최종 수정일: 2026-09-13
- 상위 이슈: [#371 CODEF 거래내역 Toss식 표시 정보 표준화](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/371)
- 관련 ADR: 없음

## 1. 목적

### 문제

계좌 거래내역 API가 CODEF 설명 필드를 이어 붙인 원문만 제공해 은행별 거래처명과 거래 유형을 일관되게 표시하기 어렵다. 사용자는 첨부 화면처럼 거래처명·입출금·잔액을 빠르게 확인할 수 있어야 한다.

### 성공 기준

- [ ] 기존 거래 원문·외부 거래 키·정산 연결을 보존한다.
- [ ] 검증된 은행 거래는 표시용 거래처명과 유형을 반환한다.
- [ ] 미검증·모호한 거래는 원문 fallback으로 반환한다.
- [ ] 기존 API 필드와 클라이언트 호환성을 유지한다.

### 제외 범위

- 은행 연결 방식 또는 지원 은행 확대
- 아이콘·가맹점 로고·자동 카테고리·AI 분류
- 원문 JSON 저장, 사용자별 이름 수정, 프론트엔드 화면 구현

## 2. 도메인 분석

### 도메인 경계

- 기존 `account` 도메인을 재사용한다.
- 표시 정규화는 Application Policy가 담당하고 Domain `Transaction`은 변경하지 않는다.
- CODEF 필드 해석은 `adapter.out.codef`의 현재 원문 매핑을 재사용한다.

### 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 원문 설명 | CODEF 설명 필드를 합친 저장값 | `Transaction.description` |
| 표시 제목 | 화면에 우선 표시할 거래처·상대방 이름 | `display.title` |
| 표시 유형 | 거래 방법의 표준화 값 | `display.kind` |

### 불변식

- `description`, `externalTransactionKey`, `settlements`는 정규화로 변경하지 않는다.
- 정규화 실패 시 임의 추정하지 않고 원문과 `OTHER`를 반환한다.
- DB에 표시값을 저장하지 않아 기존 migration과 거래 중복 키를 변경하지 않는다.

## 3. 시나리오

### 정상 흐름

1. `GET /api/v2/accounts/{accountId}/transactions`가 저장된 거래와 계좌 기관 코드를 읽는다.
2. Application Policy가 기관별 규칙 → 공통 규칙 순서로 원문을 정규화한다.
3. 검증된 제목과 유형을 `display`로 응답한다.

### 실패·경계 흐름

- 설명이 비어 있거나 의미가 불명확하면 제목은 공백 정리 원문, 유형은 `OTHER`다.
- fixture가 없는 기관은 규칙을 추가하지 않고 fallback-only 상태로 둔다.
- 같은 거래의 저장 키와 정산 연결은 기존 값 그대로 유지한다.

## 4. 계약

### Application Policy

`AccountTransactionDisplayNormalizer`가 `organization`, `description`, `type`을 받아 다음 결과를 반환한다.

```text
display.title: String
display.kind: TRANSFER | CARD | ATM | FEE | OTHER
```

새 Output Port나 외부 호출은 추가하지 않는다.

### Web API

기존 `AccountTransactionWebResponse`에 다음 additive 필드를 추가한다.

```json
"display": {
  "title": "쿠팡(쿠페이)",
  "kind": "CARD"
}
```

`type`, `amount`, `transactedAt`, `description`, `afterBalance`, `settlements`는 기존 계약을 유지한다.

### 은행별 검증 자료

활성 ID/PW 기관 `0003`, `0004`, `0011`, `0020`, `0037`, `0039`, `0045`, `0071`, `0081`, `0088` 각각에 대해 sanitized CODEF fixture와 다음 정보를 기록한다.

- 로그인·거래내역 필수 입력 및 기간 규칙
- `resAccountDesc1~4` 의미와 거래 유형 판별 근거
- 입출금·거래 후 잔액 필드와 오류/추가 인증 응답
- 검증 상태 `VERIFIED`, `FALLBACK_ONLY`, `UNVERIFIED`

현재 구현 기준 상태:

| 기관 | 상태 | 적용 범위 |
|---|---|---|
| `0011` 농협은행 | `FALLBACK_ONLY` | 공통 표시 규칙과 은행명·계좌 토큰 제거만 테스트 |
| `0003`, `0004`, `0020`, `0037`, `0039`, `0045`, `0071`, `0081`, `0088` | `UNVERIFIED` | 공통 규칙만 적용하고 기관별 의미 추정 금지 |

CODEF 포털에서 기관별 sanitized fixture를 확보한 뒤에만 `VERIFIED` 규칙으로 승격한다.

## 5. 설계 결정

- 조회 시 계산을 선택한다. 규칙 변경을 즉시 반영하고 migration/backfill을 피한다.
- 기관별 규칙은 fixture로 확인된 경우에만 추가한다. 공개 문서에 없는 의미는 추측하지 않는다.
- 아이콘·카테고리·가맹점 검색은 정확도와 카탈로그가 확보될 때 별도 기능으로 분리한다.

## 6. 영향 범위

| 영역 | 영향 |
|---|---|
| Application | 거래 표시 정규화 Policy와 Result 확장 |
| Web | 거래 응답에 `display` 추가 |
| Adapter | 기존 CODEF 원문 매핑 유지, fixture 테스트 보강 |
| Persistence | 변경 없음 |
| Migration | 필요 없음 |
| Frontend | 이번 PR에서는 변경하지 않음 |

## 7. 구현·검증 순서

- [x] 은행별 검증 상태를 문서화하고 미검증 기관은 fallback으로 제한
- [x] `AccountTransactionDisplayNormalizer` 및 Result/Web Response `display` 추가
- [x] Account 조회 흐름에 기관별 정규화 적용
- [x] 공통·기관별·fallback 단위 테스트
- [x] 기존 거래 저장·중복 키·정산 연결 회귀 테스트
- [x] Controller JSON 계약 테스트
- [x] `./gradlew compileJava`, focused 테스트, `git diff --check` 실행
- [ ] PostgreSQL·Redis가 준비된 환경에서 전체 테스트 실행
- [ ] 문서 상태를 완료로 갱신하고 PR에 `Closes #371` 연결

### 예상 커밋 경계

```text
feat(account): CODEF 거래내역 표시 정보 표준화
test(account): CODEF 거래 표시 정규화 회귀 검증 추가
```
