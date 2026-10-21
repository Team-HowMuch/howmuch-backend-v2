# #623 위젯 전용 장기 토큰으로 개별 입금 대조

- 상태: 완료 — 구현 및 격리 HTTP 검증
- 기준 브랜치: origin/develop (8c55fe9c)
- Flyway migration required: No. 기존 JWT WIDGET 타입, Redis 로그인 세션, 발급 API 재사용.

## 목적 및 계약
앱 ACCESS 토큰이 30분 후 만료되어도 위젯에서 생성자 본인의 개별 정산 입금 대조를 호출할 수 있게 한다. 이미 있는 `POST /api/v2/auth/widget-token`은 ACCESS 인증으로 현재 세션 만료까지 유효한 WIDGET 토큰을 발급한다. 기본 refresh 만료는 14일이며, 새 발급 시점에서 무조건 14일을 연장하지 않는다.

- 기존 위젯 요약 GET/일괄 새로고침 POST를 유지하고 `POST /api/v2/settlements/{UUID}/deposits/reconcile`만 추가 허용한다.
- WIDGET tokenType와 정확한 method/path 조합이 권한 범위다. 프로필/계좌/입금 현황 GET/정산 수정/토큰 재발급·갱신/압축 대조에는 허용하지 않는다.
- 현재 사용자로 기존 ReconcileSettlementDepositsUseCase를 실행하며 생성자 소유권 확인은 은행 호출 전에 수행한다.
- 로그아웃/새 로그인/세션 폐기는 즉시 기존 WIDGET 인증을 무효화한다. 같은 세션의 refresh 회전은 기존 위젯 토큰을 무효화하지 않는다. 토큰 자체 만료는 세션 연장으로 늘어나지 않는다.
- 현재 서버에는 PIN 변경/로그인 기기 해제 UseCase가 없다. 신규 기능을 임의로 만들지 않으며 향후 구현 시 같은 AuthSessionPort 폐기를 호출해야 한다. 인증서 기기 페어링은 앱 로그인 기기 해제와 다르다.
- 클라이언트는 발급 결과 widgetToken/expiresAt을 공유 저장소에 넣는다. 위젯에 앱 refresh 토큰을 전달하지 않는다. 모바일 코드/배포는 범위 밖.

## 설계 및 검증
기존 발급 UseCase/Result/WidgetTokenPort/JWT Adapter/Redis AuthSessionPort 계약은 변경하지 않는다. JWT 필터의 WIDGET 허용 경로와 API 문서를 보완한다. 기존 입금 대조 Service 및 은행 호출 예산/입금 불변 규칙은 유지한다. 새 storage/API/환경변수 없음.

- compileJava 및 JWT/보안 회귀 검사
- 격리 PostgreSQL/Redis의 실제 HTTP: ACCESS 만료 이후 WIDGET 대조 성공, 타인 403, 잘못된 메서드·경로 401, 세션 폐기/만료 401, 기존 위젯 API 유지
- 실제 CODEF 호출 없음. 테스트 BankTransactionPort만 사용.
- 커밋 경계: #623 경로·문서·관련 검사 단일 기능 커밋. 사용자 요청 전 push/PR 없음.

## 결과 및 프론트 연동
- `compileJava` 통과. `SettlementWidgetHttpIntegrationTest`, `JwtTokenProviderTest`, `SecurityFilterChainIntegrationTest` 총 46개 통과(실패/오류/스킵 0). 기존 JavaScript 검사 35개 통과.
- 테스트 전용 로컬 PostgreSQL 15433 / Redis 16380 사용. 최초 임시 DB의 SQL_ASCII 인코딩 때문에 기존 한글 마이그레이션이 실패했으며, 해당 일회용 DB만 UTF8로 재생성한 뒤 전체 대상 재실행 통과. 실제 업무 DB/환경설정 미사용.
- 실제 HTTP에서 만료 ACCESS는 401, WIDGET 본인 정산 대조는 200 및 400원 입금 귀속 확인. 타인 정산은 403이며 은행 호출 없음. 허용하지 않은 메서드/잘못된 UUID/추가 경로/입금 GET은 401. 세션 폐기 후 같은 WIDGET 대조는 401.
- 프론트: ACCESS Bearer로 `POST /api/v2/auth/widget-token` 발급 → `data.widgetToken`, `data.expiresAt`을 위젯 공유 저장소에 저장 → WIDGET Bearer로 개별 입금 대조 POST 호출. 만료/세션 폐기 401 시 앱을 통해 재발급한다. 위젯은 refresh 토큰을 받지 않는다.
- 기존 위젯 요약 API 2개도 그대로 지원한다. 새 토큰/스토리지/폐기 시스템을 중복 구현하지 않았다.
- 서버 재배포는 수행하지 않았다. 사용자 후속 요청으로 기능 변경을 커밋하고 develop 대상 PR을 생성한다.
