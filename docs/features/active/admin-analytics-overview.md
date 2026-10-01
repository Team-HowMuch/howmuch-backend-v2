# 기능 계획: 관리자 지표 조회 API

- 상태: 구현 중 — Amplitude 실조회와 프론트 결측·기간 계약은 자격 증명 확보 후 검증
- 이슈: [#458](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/458)
- 기준: 2026-10-01, `develop` 8b500f757b92a63db5f0a4d6b986f588d13fb3cd
- 원안: 관리자 대시보드 인계 문서(2026-10-01)
- 관련 기능: [관리자 계정 삭제](admin-user-deletion.md)

## 1. 목적과 완료 기준

관리자 대시보드의 목데이터를 실제 Amplitude 수집 결과로 바꿀 수 있도록 `GET /api/v2/admin/analytics/overview`를 제공한다. 대상은 가입 완료, 정산 요청 발송, 정산 종료 이벤트와 정산 방식·금액이다.

- ADMIN만 조회한다. 비로그인 401, 일반 사용자 403을 서버가 보장한다.
- 요청 기간의 KST 날짜를 빠짐없이 오름차순으로 반환하고, 발생하지 않은 이벤트 건수는 0이다.
- KPI와 일별 건수 합계가 일치한다. `signupToSettlementFunnel`은 실제 전환율이 아닌 독립 이벤트 건수 비교다.
- 방식별 건수와 일별 평균 금액이 같은 기간의 Amplitude 결과와 일치한다. 결측·미분류 데이터를 임의로 0원이나 특정 방식에 넣지 않는다.
- 외부 조회 실패를 정상 0건으로 표시하지 않으며, 프론트에 응답 예시와 오류 계약을 전달한다.

관리자 생성·승격, 실제 전환 퍼널, DB 기준 정산 건수 재계산, 관리자 삭제 API 재개발, 프론트 코드 수정은 범위 밖이다.

## 2. 현재 코드에서 확인한 사실

| 영역 | 현재 상태와 설계 영향 |
| --- | --- |
| 관리자 인증 | `SecurityConfig`는 `/api/**`에 로그인만 요구한다. `AdminUserDeletionService.requireAdmin()`이 `CurrentActorPort`로 현재 사용자를 얻고 `LoadUserPort`로 활성 `ADMIN`을 확인한다. 새 API도 서버에서 같은 권한 검사를 해야 한다. |
| 응답·오류 | `AdminUserDeletionController`의 `ApiResponse`가 `BaseResponse(success/data/error)`를 반환하고 `GlobalExceptionHandler`가 오류를 같은 형식으로 변환한다. |
| 외부 연동·캐시 | `CodefRestClientConfig`/`ReceiptOcrConfig`의 `RestClient`와 제한 시간 패턴을 재사용할 수 있다. Caffeine은 이미 설치돼 있다. 새 DB·Redis 저장소는 필요하지 않다. |
| 이벤트 의미 | 앱의 `trackEvent()`는 전송 실패를 비즈니스 요청과 분리한다. `signup_completed`는 가입 API 성공 뒤, `settlement_request_sent`는 요청 API 성공 뒤, `settlement_closed`는 앱이 완료 상태 전환을 관찰할 때 전송한다. **수집 이벤트 건수는 서버의 확정 가입·정산 건수와 다를 수 있다.** |
| 분배 방식 | 앱 코드는 `split_type: "dutch"`와 `"itemized"`를 전송한다. Amplitude에 저장된 실제 값과 결측 비율은 실조회로 확인해야 한다. |
| 현재 작업 | 이전에 선행 작성한 Java·테스트·설정은 제거했다. `develop`에는 지표 API가 없다. 기존 `application-dev.yaml` 수정과 `.worktrees`는 유지한다. |

원안의 프로젝트 `864419`, 타임존 `Asia/Seoul`, 최근 90일 3/10/3건은 인계 문서의 관찰값이다. 현재 백엔드 자격 증명으로 재검증된 값은 아니다.

## 3. 도메인과 API 계약

기존 이벤트의 관리자용 읽기 모델이므로 새 Domain Entity, 상태 전이, Application Policy는 두지 않는다. `GetAdminAnalyticsOverviewUseCase` 하나가 목표를 표현한다. Application Service는 ADMIN 확인·기간 검증·응답 조립을 맡고, 목적형 Output Port 하나를 통해 Amplitude 조회 Adapter와 대화한다.

```text
GET /api/v2/admin/analytics/overview?from=YYYY-MM-DD&to=YYYY-MM-DD
Authorization: Bearer accessToken (active ADMIN)
200: BaseResponse<AdminAnalyticsOverview>
```

원안의 `period`, `kpis`, `eventTimeSeries`, `signupToSettlementFunnel`, `splitTypeDistribution`, `averageAmountTrend` 필드와 camelCase 이벤트 키를 유지한다. Amplitude의 snake_case 이벤트명은 백엔드에서 매핑한다. `from`/`to`는 KST 기준 양 끝 포함으로 제안한다. 기본 범위는 **오늘 포함 최근 30일(오늘과 이전 29일)**, 초기 최대 범위도 30일로 제안한다. 실제 프론트의 기간 선택 범위를 보고 제한을 확정한다.

1. KPI와 일별 추이는 고유 사용자·고유 `settlement_id`가 아닌 **수집된 이벤트 발생 횟수**다. 같은 이벤트가 두 번 전송되면 2건이다.
2. `signupToSettlementFunnel`은 세 KPI를 재사용한 독립 비교다. Funnel Analysis API나 전환율 계산은 필요 없다. 프론트 표기는 “이벤트별 건수 비교”로 맞춘다.
3. `dutch → DUTCH_PAY`, `itemized → ITEM_BASED`로 매핑한다. null·빈 값·미지 값은 두 값 중 하나로 추측하지 않는다. 현 두 값 계약에서는 제외하고, 제외 건수와 화면 문구를 프론트와 확정한다.
4. 유효한 숫자 `total_amount`를 가진 요청만 평균의 분자·분모에 넣는다. 평균 대상이 없는 날과 실제 0원을 구분한다. 현 `averageAmount: number` 계약을 유지하려면 결측 날짜 생략을 우선 검토한다. 차트에 공백이 필요하면 먼저 `number | null` 계약을 합의한다.

오류 초안: 날짜 형식·역전·기간 초과 400, 비로그인 401, 비ADMIN 403, Amplitude 설정/인증/한도/시간 초과/응답 형식 실패 503. 외부 429를 관리자 입력 오류로 취급하지 않는다. 자격 증명과 외부 원문은 클라이언트·로그에 내보내지 않는다.

## 4. 외부 조회 방식과 구현 전 증거

**선택한 방식은 Dashboard REST Event Segmentation**이다. 공식 문서에 기간 `start`/`end`, 일 단위 `i=1`, 이벤트 발생 횟수 `m=totals`, 속성 평균 `m=value_avg`, `series/seriesLabels/xValues`, 프로젝트 타임존이 명시돼 있다. 프로젝트 API Key와 Secret Key로 인증한다. 날짜별 금액 평균은 `total_amount`를 수치 속성으로 지정한 `value_avg` 조회를 쓴다. 실제 평균 분모·결측 처리와 응답 형태는 Secret Key 확보 후 검증해야 한다. [Dashboard REST 문서](https://amplitude.com/docs/apis/analytics/dashboard-rest)

저장 차트의 **Chart Query API는 별도 Developer API**다. 기간 재지정은 가능하지만 Bearer 토큰(PAT/OAuth)과 `analytics:read` 권한, 저장 차트 ID가 필요하다. 원안의 “모든 API가 Basic 인증”이라는 설명은 수정한다. 차트와 권한이 이미 준비돼 있고 실조회 결과가 유리할 때만 대안으로 선택한다. [Developer API 인증](https://amplitude.com/docs/apis/developer), [Chart Query](https://amplitude.com/docs/apis/developer/analytics/query-chart)

구현 전에 서버용 자격 증명을 안전하게 설정하고 다음의 **값이 제거된** 조회 증거를 남긴다.

| 확인 항목 | 통과 기준 |
| --- | --- |
| 프로젝트·타임존 | 자격 증명 대상이 `864419`이고 집계 타임존이 `Asia/Seoul`임을 확인 |
| 건수 | 세 이벤트를 같은 기간에 `m=totals`로 조회하고 날짜·응답 배열·빈 기간을 확인 |
| 방식 | `split_type`의 실제 반환 라벨, null·미지 값, 그룹 누락·상한을 확인 |
| 금액 | `total_amount`의 `value_avg` 응답, 결측·0원 처리와 평균 분모를 확인 |
| 데이터 범위 | 테스트/QA 이벤트가 섞이는지 확인하고 표시 정책을 결정 |
| 실패·한도 | 권한 부족·429·시간 초과·잘린 결과와 조회 비용을 확인 |

2026-10-01 사용자 요청에 따라 자격 증명 확보 전에 공식 응답 스키마를 기준으로 구현한다. 응답 형태가 다르면 정상 0건으로 만들지 않고 503으로 반환한다. 실제 응답 검증 전까지 이 동작은 임시 계약이다. 한 화면 조회에 Event Segmentation 5회(기본 30일, 그룹 조회 2회)를 순차 호출한다. 호출량이 관측되기 전까지 캐시는 추가하지 않는다.

## 5. 구현 순서와 영향 범위

1. 서버 자격 증명·프로젝트·타임존을 확인하고 다섯 지표의 익명화된 실응답 샘플을 확보한다. 실패하면 조회 방식을 다시 결정한다.
2. 프론트 담당과 기본/최대 기간, “퍼널” 표기, 평균 결측, 미분류 방식 표시를 확정한다. 실제 프론트 저장소의 `analyticsOverview.ts`는 현재 작업 공간에서 찾지 못했으므로 원안만으로 계약 확정이라고 표시하지 않는다.
3. UseCase/Result/Output Port와 Service를 만든다. ADMIN 확인, 기간 검증, 날짜 채우기, KPI 조립을 Application에 둔다. 외부 호출 중 DB 트랜잭션을 오래 잡지 않도록 조회 트랜잭션 경계를 결정한다.
4. 확인된 **한 종류의** Amplitude API만 연결한다. 설정·HTTP 클라이언트는 Infra, 응답 파싱·오류 변환은 Outbound Adapter에 둔다. 호출량 근거에 맞춰 캐시를 추가한다.
5. Web Response 매핑, Controller, Swagger를 붙이고 기존 `ApiResponse` 계약을 따른다.
6. 계약·권한·외부 실패·결측을 검증하고 동일 기간 Amplitude 결과와 대조한 후 프론트에 응답 예시·오류 규칙을 전달한다.

영향은 `adapter.in.web.analytics`, `application.port.in.analytics`, `application.analytics.service`, `application.port.out.analytics`, `adapter.out.analytics`, `infra.analytics.config`, `application.yaml`, Swagger와 이 계획 문서다. Domain, JPA, Redis 저장 구조, Flyway migration은 변경하지 않는다. **Flyway gate는 불필요하다.**

예상 커밋 경계: `docs(analytics): 관리자 지표 조회 계약 정리` → `feat(analytics): 관리자 지표 조회 API 추가` → 필요 시 `test(analytics): 지표 조회 검증 보강`. Port만 남는 중간 커밋은 만들지 않는다.

## 6. 검증 계획과 현재 상태

- 코드 작성 뒤 `./gradlew compileJava`, 적절한 계층의 계약·권한·외부 오류 검증, `git diff --check`를 수행한다. PostgreSQL·Redis·환경변수 전제 없이 `bootRun`/전체 테스트 성공을 주장하지 않는다.
- 실계정 비교는 동일 프로젝트·KST 기간·이벤트·필터로 수행한다. 원안의 90일 3/10/3건을 최근 30일 고정 기대값으로 사용하지 않는다.
- 관리자 삭제 API는 `develop`에 있으며 원격 기본 브랜치도 `develop`이다. 이전 배포 확인은 삭제 경로와 미인증 401까지였고, 테스트 ADMIN 계정의 정상·차단 흐름은 증명되지 않았다.
- 2026-10-01 현재 로컬 `.env`에 `AMPLITUDE_API_KEY`가 추가됐다. Dashboard REST 조회는 API Key와 Secret Key를 함께 쓰므로 이 키만으로는 실제 응답을 조회할 수 없다. 로컬과 GitHub `development` Environment의 비밀 이름 목록에서 `AMPLITUDE_SECRET_KEY`는 확인되지 않았다. Amplitude 콘솔도 로그인 화면이다. 기존 EC2 SSH 키로 과거 호스트와 IP에 접속을 시도했으나 모두 시간 초과되어 서버 런타임 설정을 확인하지 못했다. 따라서 실제 Amplitude 응답·결측 비율·평균 집계 형태는 아직 검증되지 않았다. Secret Key **값이 아닌 보관 위치와 현재 접근 경로**가 필요하다.
- 프론트 인계 문서에는 `averageAmountTrend`의 값 타입이 `number`이고 기본 조회가 최근 30일이라고 적혀 있다. 실제 `howmuch-admin-dashboard` 저장소는 로컬과 접근 가능한 Team-HowMuch 저장소 목록에서 찾지 못했다. 값이 없는 날짜 생략과 최대 30일 제한은 제안 상태이며 프론트 코드 또는 담당자의 확인 전에는 확정하지 않는다.
- `develop` 8b500f75의 CI는 테스트 단계 실패로 배포가 건너뛰어졌다. 최신 커밋의 서버 반영을 주장하지 않는다.
- 사용자 요청에 따라 이슈 #458을 만들고 `origin/develop` 8b500f75에서 `feat/458-admin-analytics-overview`를 분기했다. PR 생성 전까지 구현·테스트·독립 코드 리뷰를 진행한다. 실제 Amplitude 응답과 프론트 계약은 Secret Key 및 저장소 접근 후 재검증한다.

## 7. 구현·리뷰 기록

- `GET /api/v2/admin/analytics/overview`를 추가했다. 서비스가 활성 ADMIN을 확인하고 KST 기본 30일·최대 30일을 검증한다. 외부 호출 중 DB 트랜잭션을 잡지 않도록 서비스에 조회 트랜잭션을 두지 않았다.
- Amplitude Dashboard REST Event Segmentation을 다섯 번 순차 조회한다. 세 이벤트의 `totals`, `split_type` 그룹별 `totals`, `total_amount`의 `value_avg`를 사용한다. 설정이 없거나 외부 오류·예상하지 못한 응답이면 503이며, 빈 이벤트 날짜만 0으로 채운다.
- `./gradlew compileJava`와 집중 테스트 3개 클래스·8개 케이스가 통과했다. PostgreSQL·Redis·실제 Amplitude를 쓰지 않는 검증이다. 전체 통합 테스트와 실제 Amplitude 값 대조는 수행하지 않았다.
- 테스트 후 분리된 서브에이전트 리뷰에서 금액별 `totals` 그룹 조회의 1,000개 상한이 전체 대시보드 503을 유발할 수 있다고 지적했다. `value_avg` 조회로 변경하고 집중 테스트를 재실행했다. 실제 `value_avg` 응답과 평균 분모는 Secret Key 확보 후 검증한다.
