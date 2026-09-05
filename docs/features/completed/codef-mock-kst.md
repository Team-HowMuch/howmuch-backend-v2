# Feature Plan: CODEF Mock KST 거래 시각과 입금 대조 회귀 검증

- Status: Complete
- Last updated: 2026-09-05
- Related issue: [상위 Mock #8](https://github.com/Team-HowMuch/codef-mock-server/issues/8), [Mock #9](https://github.com/Team-HowMuch/codef-mock-server/issues/9), [Backend #278](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/278)
- Related ADR: N/A — 기존 시간 계약 준수

## 1. 목적

Mock 이체가 UTC 벽시계를 저장해 Backend KST 해석 시 9시간 과거로 인식된다. 개발 환경 정산 입금 대조와 KST 00~09시 거래 조회가 실패한다. 자동 이체와 관리 화면 기본값을 KST로 통일하고 백엔드 계약을 회귀 검증한다.

성공 기준: UTC 2026-09-05 00:16:19가 Mock 20260905 091619로 저장되고 Backend에서 원래 UTC로 복원되어 신규 정산이 완료된다.

제외: 기존 거래 보정·삭제, 정산 규칙 완화, API 변경, 자동 병합, 실제 금융 송금.

## 2. 도메인 분석

기존 settlement와 외부 CODEF 경계 재사용. Mock 벽시계는 KST, Backend 내부 LocalDateTime은 UTC이다. 정산 생성자가 입금 대조를 요청하고 유일한 납부자·금액·스냅샷 시각 조건을 만족하는 입금만 연결된다. 전체 납부 확인 시 AWAITING_DEPOSIT → COMPLETED, 반복 대조는 중복 입금을 만들지 않는다.

## 3. 시나리오

1. 스냅샷과 납부금액 확정 후 Mock 이체로 양쪽 원장 생성.
2. Backend가 KST 날짜 범위를 월별 조회하고 KST 응답을 UTC로 변환.
3. 스냅샷 이후 입금을 대조하여 정산 완료.

경계: KST 자정·08:59:59·09:00:00·월말·연말, 스냅샷 이전/동일/이후, 중복 대조. 기존 잔액 부족·실패 원자성을 유지한다.

## 4. 계약

기존 UseCase·Command·Result·Web DTO·BankTransactionPort 재사용. API 경로, 응답, 인증, 오류 변경 없음. Mock transactionDate/transactionTime 및 resAccountTrDate/resAccountTrTime은 KST yyyyMMdd/HHmmss. 수동 입력은 추가 변환하지 않는다. 고정 시드는 기존 숫자를 KST fixture로 명시한다.

- Flyway migration required: No. 스키마·Entity·Redis 변경 없음.
- Migration PR: N/A.
- 환경변수·프로필 변경 없음.

## 5. 설계 결정

Python 표준 라이브러리 timezone(timedelta(hours=9), "KST")로 생성하고 양쪽 원장에 동일한 단일 시각을 사용한다. 브라우저는 Intl.DateTimeFormat timeZone=Asia/Seoul을 사용한다. 기본 OS 시간대 변경에 의존하지 않는다.

백엔드 운영 코드는 유지하고 테스트 범위에서 현재 시각을 고정한다. 기존 거래는 출처 구분 필드가 없고 시각 변경 시 AccountTransactionKey가 달라져 중복으로 인식될 수 있으므로 일괄 +9시간 보정을 하지 않는다.

## 6. 영향 범위

Mock server.py·web/index.html·test_server.py·README. Backend 어댑터·서비스·정책 테스트와 이 문서. Backend 운영 코드, Persistence, Security, Infra 불변.

## 7. 구현 계획과 커밋

두 저장소 브랜치 fix/codef-mock-kst. Mock 최신 main, Backend 최신 develop에서 분기. 사용자 요청으로 이슈 생성·커밋·push·PR 작성까지 수행한다.

- Migration/Input Port/Output Port/Domain/Policy/Service/Web DTO/Infra 구현: N/A — 기존 계약 유지.
- [x] Mock 자동 이체·시드·조회 테스트
- [x] Mock 관리 화면 KST 기본값
- [x] Backend 어댑터·서비스·정책 테스트
- [x] 문서·검증 결과·PR

Mock 커밋:
1. fix(codef): 모의 이체 거래 시각을 KST로 저장
2. fix(codef): 관리 거래 입력 기본 시각을 KST로 통일
3. docs(codef): 거래 시간대 계약과 기존 데이터 처리 방침 명시

Backend 커밋:
1. docs(settlement): CODEF 거래 시간대 수정 및 검증 계획 수립
2. test(codef): KST 거래 시각의 UTC 변환 경계 검증
3. test(settlement): KST 조회 구간과 입금 완료 회귀 검증

## 8. 검증

Mock: 격리 SQLite와 고정 시계, python3 -m unittest -v. 브라우저 UTC·Asia/Seoul 기본값 및 날짜 변경 후 초기화. 시드·수동 입력·재시작 보존, 이체 원자성과 잔액 회귀.

Backend: ./gradlew compileJava 및 ./gradlew test --tests '*CodefBankTransactionAdapterTest' --tests '*SettlementDepositMatchingPolicyTest' --tests '*SettlementDepositServiceTest'. DB·Redis·필수 환경 확인 후 격리 연동 검증. git diff --check, git status --short --untracked-files=all 및 문서 링크 검사.

### 결과

- Mock Python HTTP 테스트 10개 통과: KST 단일 시각, 날짜 범위, 양쪽 원장, 시드·수동·재시작 보존. Python 3.14에서는 기존 SQLite 연결/HTTPError 정리 ResourceWarning이 있으나 실패는 없었다.
- 실제 Chrome UTC·Asia/Seoul에서 KST 자정과 연도 변경 후 초기화 통과, 페이지 오류 없음. Mock PR에 전후 스크린샷 포함.
- Backend compileJava 및 관련 29개 테스트(어댑터 7·서비스 15·정책 4·영속 통합 3) 모두 통과. 격리 PostgreSQL의 SettlementDepositPersistenceIntegrationTest 통과, Flyway V1~V31 및 Hibernate validate 확인.
- local,codef-mock 기동: 격리 PostgreSQL·별도 Redis(16389)·Mock(18089), 외부 SMS loopback·Outbox 비활성.
- 실제 Mock POST /admin/api/transfers → 인증된 Backend POST /api/v2/settlements/{id}/deposits/reconcile → PostgreSQL: UTC JVM에서 COMPLETED, confirmedAmount=6000. 재대조 후에도 입금 1건·6000원. 스냅샷 생성 후 2.018초 입금으로 검증.
- 서울 시간대 JVM의 같은 경로는 기존 추가 문제 재현: Mock 20260905 094129가 백엔드 DB에 2026-09-04 15:41:29로 저장되었다. Mock 저장은 올바르지만 native INSERT/JDBC 시간대 경계에서 추가 이동이 발생하는 것으로 보인다. Backend 운영 코드는 변경하지 않았으며 UTC JVM에서는 정상 완료했다. 서울 JVM 환경에서는 이번 Mock 수정만으로 완료가 보장되지 않는다.
- 전체 Backend 테스트, 실제 금융 송금, PR 병합 및 배포 후 검증은 미수행. 변경 관련 테스트로 범위를 제한했고 병합은 승인 범위에서 제외했다.
- 초기 sandbox Gradle 캐시·loopback 접근 실패는 권한 확장 후 해결했고, 작성 중 어댑터 테스트 타입 오류는 수정 후 통과했다.

## 9. 진행 기록

2026-09-05: 최신 기준 브랜치 분기, 상위 및 실제 하위 이슈 연결, 설계 확정.

## 10. 결정 기록

2026-09-05 사용자 확정: codex/ 접두사 없음, 두 저장소 PR 2개, 기존 데이터 보존. 조직 템플릿 type: bug는 저장소에 없어 기존 bug 라벨과 GitHub Bug 타입 사용.

## 11. 완료 기록

Completed on: 2026-09-05. Mock 생성·관리 화면 수정과 Backend 회귀 테스트 구현·검증 완료. 기존 데이터 보존. 서울 JVM의 추가 저장 시각 문제는 별도 검토 사항이다. PR 병합은 수행하지 않는다. Mock main 병합 시 배포 workflow가 실행되므로 배포 후 새 가상 거래 검증까지 확인한 뒤 상위 이슈를 종료한다. 기존 실패 거래 복구는 별도 작업이다.
