# 입금자명 부분 일치 오매칭 방지

- 상태: 완료
- 갱신일: 2026-09-09
- 관련 이슈: #298 (상위 #294)

## 목적과 완료 조건

`선영`이 `민선영 입금`에 연결되어 다른 사람의 납부로 확정되는 문제를 방지한다.

- [x] 다른 이름의 부분 문자열을 자동 연결하지 않는다.
- [x] 정상 이름과 허용 표시 표현의 정규화 회귀를 검증한다.
- [x] 동명이인·복수 후보·부분 문자열 충돌은 모호하게 확정하지 않는다.

CODEF 구현, 수동 확인 기능, 과거 저장 이력 보정은 제외한다.

## 도메인과 흐름

기존 settlement 도메인의 생성자가 `ReconcileSettlementDepositsUseCase`를 호출한다.
Controller → SettlementDepositService → 은행 거래 조회 → SettlementDepositWriter →
SettlementDepositMatchingPolicy → SettlementDepositStorePort 순서로 대조한다.
동일 수취 계좌의 미완료 Share 후보를 조회하고, 현재 정산의 유일한 후보일 때만 입금을 저장한다.
이름은 참여자의 `payerName`, 금액은 `remainingAmount`, 시작 시각은 `snapshotCreatedAt`이다.
정상 입금은 확인 금액에 누적하고 전원 완납 시 COMPLETED가 된다. 미일치 거래는 미확인으로 남는다.

## 계약과 정책 결정

- NFKC와 Locale.ROOT 소문자화 후 Unicode 공백을 정규화한다. 이름 안의 공백 제거는 기존 호환 동작을 유지한다.
- 전체 이름 일치를 허용한다. 추가 표현은 공백으로 구분된 마지막 `입금` 한 번만 허용한다.
- 접미어 경계를 확인한 뒤 이름의 공백을 제거한다. `민선영입금`, `입금 민선영`, 임의 메모·괄호·다른 이름의 추가는 허용하지 않는다.
- 전체 이름과 접미어 제거 이름의 해석이 서로 다른 후보에 일치하면 자동 확정하지 않는다.
- null·빈 이름은 후보에서 제외한다. 금액·수입 여부·스냅샷 시각 필터와 복수 후보 거부는 유지한다.
- Policy가 외부 조회 없이 Transaction과 PayableShare를 비교한다. 새 Domain/Port/UseCase는 필요 없다.
- `POST /api/v2/settlements/{settlementId}/deposits/reconcile` 입출력·인증·오류 계약은 유지하고 Swagger 설명을 보완한다.

## 영향과 마이그레이션 판단

Application Policy, Swagger, Policy/Service 테스트에 한정한다.
DB 구조·쿼리·Redis·설정·외부 API 변경 없음. Flyway migration: No.
기존에 부분 일치로만 확인되던 임의 표현은 미확인으로 남는다. 이름 비교는 실명 인증을 보장하지 않는다.

## 구현 및 커밋 경계

1. `docs(settlement): 입금자명 전체 일치 정책 설계 기록`
2. `fix(settlement): 입금자명 전체 일치와 허용 접미어 경계 적용` — 정책·Swagger·기존 테스트 계약 갱신
3. `test(settlement): 부분 이름 오매칭과 모호한 입금 회귀 검증` — 추가 회귀 및 완료 기록

Migration, UseCase/Command/Result, Output Port, Domain, Service, Adapter, Infra 구현은 N/A(기존 계약 재사용).

## 검증 계획

Java 21의 Spring Context 없는 Policy·Mockito Service 단위 테스트를 실행한다.
DB·Redis·외부 환경변수는 해당 테스트에서 사용하지 않는다. 전체 통합 테스트와 실제 은행/HTTP 확인은 별도 환경이 필요하다.

```bash
./gradlew compileJava test --tests '*SettlementDepositMatchingPolicyTest' --tests '*SettlementDepositServiceTest'
git diff --check
git status --short --untracked-files=all
```

## 진행 기록

- 2026-09-09: #298, 기존 PR #322/#321, 호출 흐름·저장 후보 조회·기존 테스트 확인. 최신 origin/develop에서 fix/settlement-depositor-name-matching 생성.
- 검증 결과: `./gradlew compileJava test --tests '*SettlementDepositMatchingPolicyTest' --tests '*SettlementDepositServiceTest'` 통과. Policy 45개, Service 16개, 총 61개(추가 42개), 실패·건너뜀 없음.
- `git diff --check` 통과. 문서의 API 경로와 타입을 실제 코드와 대조했다. 상대 링크 없음.
- 전체 DB·Redis 통합 테스트, 실제 은행 조회·HTTP 검증은 실행하지 않았다. 이번 변경은 순수 정책과 설명이며 저장 호출 경계는 Mockito로 검증했다.
- 완료일: 2026-09-09. 새 대조부터 적용하며 과거 저장 이력은 소급 보정하지 않는다.
