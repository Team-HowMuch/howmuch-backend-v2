# Feature Plan: 대조 불가 정산의 위젯 새로고침 격리

- 상태: 진행 중
- 작성일: 2026-10-22
- 관련 이슈: [#629](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/629)

## 1. 목적과 범위

앱 복귀 시 해지 계좌·연결 해제·스냅샷 누락 정산 한 건 때문에 전체 새로고침이 실패하는 문제를 해결한다. 대조 가능한 정산은 계속 반영하고 저장된 요약을 200으로 반환한다. 개별 정산 대조 API, 백그라운드 워커, 권한·매칭 규칙, 은행 오류 처리 정책은 변경하지 않는다.

## 2. 도메인과 계약

기존 settlement 도메인과 `RefreshSettlementWidgetSummaryUseCase`를 사용한다. 입력 없이 현재 사용자 본인이 생성한 REQUESTED·AWAITING_DEPOSIT 정산을 처리하며 `SettlementWidgetSummaryResult` → `SettlementWidgetSummaryWebResponse` 매핑을 유지한다.

`POST /api/v2/settlements/widget-summary/refresh`의 ACCESS/WIDGET 인증, 반환 필드, 60초 쿨다운은 유지한다. 스냅샷 누락·비활성 계좌·개별 대조 중 대상 변경은 건너뛰며 연결이 없으면 은행 조회 없이 요약을 반환한다. 은행 오류, 예산 429, 리스 만료, 예상하지 않은 예외는 그대로 전파한다.

기존 `LoadSettlementWidgetPort`, `LoadSettlementPort`, `LoadSettlementReceivingAccountSnapshotPort`, `ExternalConnectionStorePort`, `SettlementWidgetRefreshStorePort`를 재사용한다. `LoadAccountPort`의 활성 계좌 조회를 계좌 그룹마다 사용해 해지 계좌에 대한 불필요한 외부 조회를 막는다. 새로운 Port·Policy는 필요 없다.

## 3. 시나리오와 불변식

- 스냅샷 없는 정산과 정상 정산 혼재: 누락 대상을 제외하고 정상 입금 반영.
- 활성 은행 연결 없음: 은행 조회·Writer 없이 저장 요약 반환, 성공 시각 미갱신.
- 비활성 계좌와 정상 계좌 혼재: 비활성 계좌는 은행 조회 전 제외, 정상 계좌 대조.
- 사전 조회 후 정산 삭제·상태/스냅샷 변경·계좌 비활성화: Writer의 네 가지 허용 오류만 잡고 다음 정산 처리.
- 허용 오류: `CONCURRENT_UPDATE`, `SETTLEMENT_NOT_FOUND`, `SETTLEMENT_DEPOSIT_DATA_NOT_FOUND`, `SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE`.
- catch 범위는 `reconcileForWidget`만 포함한다. 조회 오류·권한 오류·429·리스 만료는 성공으로 바꾸지 않는다.
- 성공 시각: 최소 한 건 대조가 정상 종료되고 전체 순회가 치명적 오류 없이 끝난 경우만 기록한다. 모두 건너뛰거나 연결이 없으면 기존 시각을 유지한다.
- 서비스 전체 트랜잭션을 추가하지 않는다. Writer별 트랜잭션 실패가 다음 정산을 rollback-only로 만들지 않도록 유지한다.
- 로그에는 정산 ID·계좌 ID·오류 코드만 기록하며 계좌번호·예금주·예외 메시지를 기록하지 않는다.

## 4. 저장소 및 외부 시스템

- Flyway migration required: No. 테이블·제약·쿼리·저장 구조 변경 없음.
- 마이그레이션 PR: N/A.
- Redis 키·TTL·직렬화 변경 없음. 기존 finish/lease 처리 유지.
- 외부 API 계약·설정·환경변수 변경 없음.

## 5. 설계 결정과 영향

| 영역 | 변경 및 확인 대상 |
|---|---|
| Application | `SettlementWidgetRefreshService`: 대상 격리, 성공 시각 조건 |
| Account | 기존 `LoadAccountPort`로 활성 계좌 사전 확인, Writer의 잠금 후 재검사 유지 |
| Persistence/Domain | 기존 Writer·Reader 및 입금 매칭 그대로 사용 |
| API/문서 | `SettlementWidgetDocs`: 건너뛰기와 성공 시각 의미 명시 |
| Redis/Security/Infra | 변경 없음 |
| 테스트 | 서비스 단위 오류 경계 및 실제 HTTP/PostgreSQL/Redis 회귀 검증 |

추가 조회를 피하고 Writer 오류만 잡는 방식은 해지 계좌의 은행 호출 자체가 실패하는 경우를 막지 못하므로 채택하지 않는다. 전체 예외를 무시하는 방식은 장애를 정상으로 숨기므로 제외한다.

## 6. 구현 및 커밋 계획

- [x] 마이그레이션 판정: N/A
- [x] UseCase/Command/Result: N/A, 기존 계약 사용
- [x] Output Port/Adapter: N/A, 기존 활성 계좌 조회 사용
- [x] Domain/Policy: N/A, 개별 대조 규칙 유지
- [x] Application Service 수정
- [x] Web Swagger 설명 수정 (DTO·Controller 변경 N/A)
- [x] Infra: N/A
- [ ] 회귀 테스트 및 검증 결과 기록

예정 커밋:

1. `fix(settlement): 위젯 새로고침에서 대조 불가 정산 격리` — 서비스, Swagger, 설계 문서.
2. `test(settlement): 위젯 부분 대조와 오류 경계 회귀 검증` — 단위·HTTP 테스트, 완료 결과 문서.

## 7. 검증

Java 21과 격리 PostgreSQL/Redis를 사용한다. 실제 은행 API는 mock으로 대체한다. 루트 AGENTS.md의 변경 동작 테스트 지침을 우선 적용한다.

- `./gradlew compileJava`
- `bash scripts/test.sh test --tests '*SettlementWidgetRefreshServiceTest' --tests '*SettlementWidgetHttpIntegrationTest' --tests '*SettlementWidgetSummaryPolicyTest'`
- 문서 링크/로컬 경로 확인, `git diff --check`, `git status --short --untracked-files=all`

완료 기준: 정상 입금 반영·모두 스킵·연결 해제·경합 스킵·치명적 오류 전파·성공 시각·쿨다운·리스 해제를 검증하고 Swagger와 일치시킨다.

## 8. 진행과 완료 기록

- 2026-10-22: 이슈·HTTP→Application→은행 조회/Writer→저장소 흐름 확인. 스키마 변경 불필요.
- 검증 결과: 진행 중.
- 실제 은행·모바일 앱·서버 배포 검증은 범위 밖이다.
