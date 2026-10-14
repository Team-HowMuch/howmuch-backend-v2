# Feature Plan: 중단된 정산 수정 API 지원

- 상태: 완료
- 작성일: 2026-10-14
- 관련 이슈: [#533](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/533)
- 기준 문서: [Architecture](../../architecture.md), [Coding Guidelines](../../coding-guidelines.md), [Development Workflow](../../development-workflow.md)

## 1. 목적과 범위

중단된 정산의 생성자가 영수증, 더치페이 금액, 참여자 이름을 수정한 뒤 명시적으로 재개할 수 있게 한다. 기존 영수증 교체와 더치페이 수정은 이미 구현되어 있으므로 해당 흐름을 재사용하고, DRAFT 전용 검사에 막힌 참여자 이름 수정만 확장한다.

완료 조건:
- [x] SUSPENDED 생성자가 세 수정 API를 호출할 수 있고 수정 후에도 SUSPENDED를 유지한다.
- [x] 비생성자, 진행·완료 상태, 잘못된 요청을 거절한다.
- [x] 쓰기 도중 실패하면 전체 변경을 롤백한다.
- [x] 기존 DRAFT 수정과 정산 재개 흐름을 유지한다.

범위 제외: 참여자 추가·삭제 허용 상태 확장, 생성자 자신의 표시 이름 수정, API 신설, 은행 연동 변경, 배포.

## 2. 도메인·시나리오

기존 settlement 도메인을 사용한다. 생성자는 CurrentActorPort로 확인하고 정산 행을 잠근 뒤 소유권, 수정 상태, 입금 여부를 확인한다. 참여자 이름은 정산 내부 표시 이름이며 계정 이름·참여자 식별자·품목 선택·제출·Share를 바꾸지 않는다.

- 정상: SUSPENDED → 영수증/금액/이름 수정 → SUSPENDED → 별도 resume → REQUESTED 또는 AWAITING_DEPOSIT.
- 실패: REQUESTED/AWAITING_DEPOSIT/COMPLETED 또는 다른 사용자 요청을 거절한다.
- 경계: 빈 이름/100자 초과, 없는 참여자, 생성자 참여자, 확정 입금, 할인 초과, 더치페이 합계 불일치·중복/타 정산 ID·음수 금액을 거절한다.
- 기존 DRAFT 생성 과정의 수정은 유지한다. 참여자 추가·삭제·검색은 계속 DRAFT 전용이다.

## 3. 계약과 설계

| API | 기존 UseCase | 변경 |
|---|---|---|
| PUT /api/v2/settlements/{settlementId}/expenses/receipt/{expenseId} | ReplaceSettlementReceiptExpenseUseCase | 기존 검증·재계산·원자적 교체 유지 |
| PUT /api/v2/settlements/{settlementId}/dutch-pay-allocations | ReplaceSettlementDutchPayAllocationsUseCase | 기존 전원 금액·합계 검증·원자적 갱신 유지 |
| PATCH /api/v2/settlements/{settlementId}/participants/{participantId} | RenameSettlementParticipantUseCase | 이름 수정 상태 검사만 DRAFT·SUSPENDED 허용 |

이름은 단일 String 입력이므로 기존 UseCase 인자를 유지하며 새 Command/Result/Port는 만들지 않는다. 기존 SettlementParticipantResult → SettlementParticipantWebResponse 매핑을 사용한다.

Domain의 별도 ensureParticipantRenameable()로 이름 수정 가능 상태를 표현한다. Service는 공용 소유권·행 잠금 조회를 재사용하되 추가·삭제 전용 상태 검사를 이름 수정에 적용하지 않는다. SUSPENDED 이름 수정에도 기존 SettlementRevisionGuard를 사용해 확정 입금 후 변경을 거절한다. 생성자 참여자 보호는 기존 SettlementParticipantModificationPolicy에 유지한다.

Flyway migration required: **No**. 기존 display_name 열과 상태·금액 저장 구조를 사용하며 DB/Redis 스키마, 환경변수, Bean 설정은 변경하지 않는다.

## 4. 영향 범위

| 영역 | 대상·영향 |
|---|---|
| Domain | Settlement의 이름 수정 전용 상태 검사 |
| Application | SettlementParticipantService.rename의 상태·입금 검사 |
| Web | SettlementDocs의 허용 상태·오류 문서 |
| Persistence | 기존 정산 행 잠금과 참여자 update 재사용 |
| 테스트 | 기존 SettlementReceiptReplacementIntegrationTest에 세 API의 누락된 성공·실패·롤백 검증 추가 |

## 5. 구현·커밋 계획

- [x] 기존 API 및 호출 경로 분석, 마이그레이션 불필요 확인
- [x] 이름 수정 상태·입금 검사 및 Swagger 수정
- [x] HTTP·PostgreSQL 회귀 테스트 추가
- [x] 컴파일·관련 테스트·전체 테스트와 문서 경로 확인
- [x] 완료 기록 후 docs/features/completed/로 이동

예상 커밋 경계:
1. fix(settlement): 중단된 정산 참여자 이름 수정 허용 — 설계 문서와 실제 변경.
2. test(settlement): 중단 정산 수정 API 회귀 검증 — 성공·실패·롤백 테스트.
3. docs(settlement): 중단 정산 수정 검증 결과 기록 — 완료 문서.

## 6. 검증

실행 전 기존 개발 DB와 분리된 일회성 PostgreSQL·Redis를 준비한다. Spring proxy를 거친 Service와 실제 DB를 사용하는 기존 통합 테스트에 HTTP 요청·응답, 보존 필드, 실패 후 DB 상태를 검증한다. 전체 테스트는 scripts/test.sh로 실행한다. 실제 모바일 화면·은행 호출은 범위 밖이다.

필수 명령: ./gradlew compileJava, 관련 test, bash scripts/test.sh compileJava test bootJar, git diff --check, git status --short --untracked-files=all.

## 7. 결과·완료 기록

- 구현: 이름 수정 전용 상태 검사와 기존 확정 입금 보호를 연결하고 Swagger에 허용 상태·409 오류를 반영했다.
- 테스트: HTTP·PostgreSQL 통합 테스트 38개로 세 수정 API의 상태·권한·입력 검증, 저장 실패 롤백, 이름 변경 후 재개, 계정 이름·배분 데이터 보존을 확인했다.
- 최종 전체 실행: compileJava·bootJar, Java 1,866개(240 suites), JavaScript 35개 통과. 실패·오류·skip 0.
- 최종 기준: 작업 중 병합된 #532를 포함한 origin/develop 9ce31983. V52 적용 후 전체 재검증 통과.
- 미검증: 실제 모바일 화면과 실제 은행 호출. 테스트의 현재 사용자는 CurrentActorPort로 대체하며, 이번 통합 테스트는 실제 JWT 인증을 포함하지 않는다.
- 스키마·환경변수 변경 없음. 서버 배포는 수행하지 않았다.

- 완료일: 2026-10-14
- 문서 상대 링크, git diff --check 확인 완료.
