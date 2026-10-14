# Feature Plan: 홈 위젯 정산 요약과 새로고침

- 상태: 설계 완료, 구현 진행
- 갱신일: 2026-10-14
- 관련 이슈: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/529

## 목적과 범위

앱을 열지 않고 진행 중 정산의 받을 돈·낼 돈을 확인하고 입금을 대조한다.
`REQUESTED`, `AWAITING_DEPOSIT` 전체 목록을 반환하며 초안·중단·완료·삭제와 생성자의 자기 몫은 제외한다.
참여자 송금 후 지연 대조, 모바일 위젯 UI와 실제 은행 검증은 제외한다.

## 도메인과 계약

기존 settlement, auth 도메인을 사용한다. 신규 Domain 상태는 없다.
- `GetSettlementWidgetSummaryUseCase`, `RefreshSettlementWidgetSummaryUseCase` → `SettlementWidgetSummaryResult` → Web Response.
- `GET /api/v2/settlements/widget-summary`: 받을/낼 목록, 각각 합계·건수, `lastReconciledAt`.
- `POST /api/v2/settlements/widget-summary/refresh`: 생성자 정산 전체 대조 후 같은 응답.
- `POST /api/v2/auth/widget-token`: 앱 ACCESS 토큰으로 현재 로그인 세션에 묶인 `WIDGET` 토큰 발급. 응답 `widgetToken`, UTC `expiresAt`.
- 위젯 토큰은 위 두 정산 API만 호출할 수 있다. REFRESH와 ACCESS의 기존 만료·회전 정책은 유지한다.
- 만료/로그아웃/새 로그인으로 세션이 달라지면 401. 위젯 토큰으로 다른 인증 API 접근은 401.
- 받을 돈은 Share별 확인 입금을 차감한다. 선택 중 품목 정산은 상세의 기존 Policy로 총액에서 생성자 몫을 뺀 예상액을 사용하고 `amountUnconfirmed=true`로 표시한다.
- 미납 인원은 생성자를 제외한 미납/미확정 참여 건수다. 제출 완료한 0원 몫은 제외한다.
- 낼 돈은 본인의 확정 Share 잔액이다. Share가 양수이면 `PAYABLE`, 미제출 품목 정산이면 `SELECTION_REQUIRED`, 나머지는 `WAITING`. 미확정 금액은 null이며 합계에 넣지 않는다.
- `lastReconciledAt`은 마지막으로 성공한 위젯 전체 대조의 조회 기준 시각이다. 일반 정산 개별 대조 시각과 구분한다. 최초/Redis 기록 만료 시 null.

## 외부 경계와 저장 설계

- `LoadSettlementWidgetPort`: 진행 정산 및 참여자·Share·확인 입금·제출 사실 일괄 조회. PostgreSQL Adapter 구현.
- 기존 품목/지출/선택 조회 Port 및 `SettlementItemAllocationPolicy`를 예상액 계산에 재사용.
- `SettlementWidgetRefreshStorePort`: 사용자별 대조 잠금·쿨다운·마지막 성공 시각. Redis Adapter 구현.
- `CurrentAuthSessionPort`: 현재 요청의 사용자/세션 식별. Security Adapter 구현.
- `WidgetTokenPort`: 세션 만료 이하로 WIDGET JWT 생성. 기존 JWT Adapter 구현.
- Flyway migration required: **No**. 기존 테이블과 인덱스를 사용하며 장기 인증의 원본은 기존 Redis AuthSession이다. DB 컬럼/테이블 추가 없음.
- Redis: 사용자별 `settlement:widget:{userId}:lock`(10분 lease), `cooldown`(60초), `last-reconciled`(30일). 잠금 소유 토큰의 원자적 검증·갱신·해제. 실패에도 쿨다운 유지.
- 동일 수취 계좌 정산을 묶어 가장 이른 snapshot 시각부터 CODEF 조회 한 번(월별 호출은 기존 동작). DB 트랜잭션 밖에서 조회하며 기존 Writer의 계좌→정산 잠금/재검증/매칭/알림 재사용.
- 각 CODEF 월별 호출 전 lease 갱신, Writer 호출 전 갱신. CODEF 기본 read timeout 305초보다 긴 lease 사용. 설정으로 한 호출이 10분을 넘거나 프로세스가 장시간 정지하면 갱신 실패 후 후속 반영을 거절한다.
- 대조 실패는 기존 오류 반환, 성공한 이전 정산의 반영은 유지하고 전체 성공 시각은 갱신하지 않는다. 연타/동시 요청은 429.
- 새 환경변수나 기존 API 변경 없음.

## 영향과 불변 조건

| 영역 | 변경 |
|---|---|
| Application | 요약/새로고침/토큰 UseCase와 Service, 요약 Policy |
| Persistence | 위젯 전용 bulk projection |
| Redis | 대조 lease와 마지막 성공 기록 |
| Security | WIDGET 토큰의 정확한 method/path 제한과 세션 확인 |
| Web | API 3개, Response 매핑, Docs |
| 기존 대조 | 월별 fetch hook 추가, 기본 호출 동작 유지 |

정산 상세/목록/미납 합계, 기존 토큰 응답·회전, 입금 중복 및 모호한 매칭 보호를 유지한다.
예상액 보정은 REQUESTED 품목 정산에만 기존 Policy를 호출한다. 정산별 추가 조회가 생기므로 목록 크기에 비례하는 비용이 남는다.

## 구현 순서와 커밋 경계

1. `docs(settlement): 홈 위젯 API 설계 기록`
2. `feat(auth): 세션 연동 위젯 전용 토큰 발급`
3. `feat(settlement): 위젯 정산 요약과 일괄 입금 대조 구현`
4. `test(widget): 위젯 인증과 정산 대조 회귀 검증`

각 기능 커밋은 실제 연결된 Port/Service/Adapter/API를 포함한다. 마이그레이션 단계는 N/A.

## 검증 계획

- `./gradlew compileJava`
- 격리 PostgreSQL(15432), Redis(16379) 및 `scripts/test.sh`의 합성 환경 사용. 운영 `.env` 미사용.
- 정책: 부분/초과/0원 입금, 선택 필요/제출 대기, 자기 몫 및 빈 목록.
- 실제 HTTP: 토큰 발급, 위젯 두 API 허용, 다른 경로/메서드/REFRESH 거절, 로그아웃/세션교체, OpenAPI.
- 실제 DB/Redis: 진행 상태·삭제·타인 분리, lease 동시 요청/소유 검증/쿨다운, 정상·오류 대조 및 같은 계좌 조회 합치기.
- 관련 기존 인증·정산 회귀, `git diff --check`, 문서 링크 확인.

## 결과 및 진행 기록

- 설계 검토 완료. DB 마이그레이션 불필요.
- 검증 결과는 구현 후 기록한다.
