# 압축정산 계좌 조회 병렬화

- 상태: 완료 (로컬 검증, 미배포)
- 기준: develop c6b293b4 + 로컬 원본 조회 최적화
- 갱신: 2026-10-19

## 목적과 범위

독립 계좌의 은행 조회 대기 시간을 겹쳐 압축 완료 시간을 줄인다. 계좌별 결과 재사용 여부·조회 기간·월별 조회 순서·은행 요청 예산은 바꾸지 않는다. API와 상태 전이는 유지한다.

- 서버 인스턴스에서 은행 조회 thread 최대 2개, 실행별 2계좌 window로 제출한다.
- 같은 계좌는 기존처럼 한 번만 요청하고 모든 원본의 가장 이른 시작 시각을 사용한다.
- 은행 조회만 병렬 실행한다. 입금 반영·관측 거래 저장·최종 commit은 기존 원본/계좌 순서대로 메인 worker에서 처리한다.
- CREATE·재압축의 predecessor 계좌·RECONCILE에 적용한다. DETECT는 기존대로 은행 조회하지 않는다.

## 계약과 안전성

Application의 NettingBankTransactionReader가 기존 fetcher와 JDK ExecutorService를 사용한다. Query는 계좌·기간과 현재 유효한 connection을 다시 검사하는 supplier를 가진다. 은행 thread에서 최초/월별 queryGuard를 실행하므로 thread-local 로그인 정보에 의존하지 않고 execution의 actor와 lease로 검사한다.

계좌별 성공/RuntimeException 결과를 보존하고 window 내 작업을 모두 회수한다. 실패 window 이후의 신규 계좌는 제출하지 않는다. worker는 기존 순서의 결과를 읽고 원래 ApplicationException을 다시 던진다. 앞선 계좌의 입금 커밋은 유지하고, 실패한 계좌를 빈 거래로 취급하지 않으며 부분 압축은 확정하지 않는다. 대기 중 interruption/rejection 시 제출한 작업을 취소하고 기존 일시 실패 코드로 처리한다. 늦게 도착한 응답은 worker DB 반영 경로에 접근하지 않는다.

최초 계좌 사용과 월별 조회에서는 fetch task의 guard를 사용하고, 같은 결과를 다른 원본/감시 계좌에 재사용할 때는 기존 순서의 guard를 재검사한다. 모든 DB writer와 최종 검증은 유지한다. 계좌 변경·탈퇴·lease 소실·입금/원본 변경 시 기존 final validation을 유지한다. HTTP 클라이언트의 timeout과 heartbeat 동작을 유지한다.

## 저장소·외부 계약

- Flyway migration: **No**. 쿼리/테이블/인덱스 변경 없음.
- 신규 Output Port: 없음. 기존 은행 Output Port와 Application fetcher 재사용.
- Infra의 기존 netting configuration에 전용 executor Bean(2 threads, 종료 시 interrupt)을 추가한다.
- Redis·API·요청 기간·재사용 조건·환경변수 변경 없음.
- 외부 은행의 동시 요청 제한 시 기존 RATE_LIMITED 재시도 정책을 따른다. 병렬도가 은행 처리량을 보장하지는 않는다.

## 구현·검증 계획

1. Reader와 executor Bean 구현, worker 요청 dedup/결과 순차 반영 연결.
2. 실제 통합 테스트로 동시성 2·동일 계좌 1회·트랜잭션 밖 조회·성공/실패 혼합·lease/권한/계좌 변경·재시도 검증.
3. 병렬화에 따라 변경되는 은행 호출 횟수 기대값만 의미에 맞게 갱신(계좌당 attempt 1회).
4. 기존 로컬 HTTP 시나리오(2·20·100건, 계좌 2개×1초)로 전후 비교. 진행 API와 다른 GET 응답, 완료 후 원본 보존도 확인.
5. 컴파일·관련 회귀 테스트·결과 문서화. 후속 요청으로 [이슈 #588](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/588)와 PR을 게시한다. 배포는 범위 밖.

## 예정 커밋

후속 게시 요청에 따라 원본 조회 최적화와 함께 커밋한다: `refactor(settlement): 압축정산 반복 조회 최적화 및 계좌 조회 병렬화`

## 진행

- [x] 범위·계좌 중복 제거·실패·경계 조건 설계
- [x] 구현
- [x] 회귀 검증
- [x] 성능 비교
- [x] 결과 기록


## 완료 기록

- 완료일: 2026-10-19
- 최대 2계좌의 전용 executor와 window 실행을 연결했다. 동일 계좌 중복을 제거하고 DB 반영 순서는 유지했다.
- 기존 수집 기간·최근 결과 재사용 조건·API·스키마는 변경하지 않았다.
- 컴파일 통과, 최종 회귀 144개와 HTTP 시나리오 1개 통과. Reader 단위 4개를 최종 재검증했다.
- 동일 빈 DB 비교: 20건 3.01 → 1.80초, 100건 4.57 → 2.85초. 요청 횟수는 계좌 2개에 총 2회로 동일하고 최대 동시 수 2를 확인했다.
- 실제 은행의 동시 호출 한도와 다수 그룹 부하는 미검증이다. 이번 결과는 1초 지연 bank Port mock을 사용한 로컬 실측이다.
- 게시: [이슈 #588](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/588) / 브랜치 `refactor/588-netting-query-optimization`. dev 배포는 하지 않았다.
- 상세: [병렬 조회 QA](../../qa/netting-parallel-bank-queries-20261019.md).
