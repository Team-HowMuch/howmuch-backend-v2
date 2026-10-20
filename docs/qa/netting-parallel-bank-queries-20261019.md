# 압축정산 은행 조회 병렬화 QA — 2026-10-19

## 결과

독립 계좌를 최대 2개씩 병렬 조회하도록 수정했다. DB 반영과 압축 commit은 기존 순서대로 실행한다. 최근 결과 재사용·조회 기간·월별 호출·예산 조건은 변경하지 않았다.

동일한 빈 DB/Redis의 HTTP harness로 비교한 결과:

| 원본 정산 | 직전 순차 조회 버전 | 병렬 조회 버전 | 단축 | 은행 요청 수 / 최대 동시 수 |
|---:|---:|---:|---:|---:|
| 2건 | 2.85초 | 1.57초 | 44.8% | 2회 / 2개 |
| 20건 | 3.01초 | 1.80초 | 40.4% | 2회 / 2개 |
| 100건 | 4.57초 | 2.85초 | 37.6% | 2회 / 2개 |

구성원 2명·계좌 2개, 은행 Port mock 호출마다 1초 지연을 주었다. 따라서 순차 조회의 약 2초 대기가 병렬 조회에서 약 1초로 겹친다. 실제 은행 응답 시간이나 동시 요청 허용량을 측정한 것은 아니다. 두 번의 수정 후 실행은 20건 1.56~1.80초, 100건 2.85~3.20초였다. 첫 요청에는 초기화/JIT 영향이 있어 작은 표본의 변동을 성능 보장으로 해석하지 않는다.

POST 접수부터 background worker 완료·결과 GET까지 측정했다. 실제 Spring Boot 랜덤 포트와 HttpClient를 사용하며, worker dispatcher는 직접 실행해 scheduler 대기 시간은 제외했다. fixture 생성·actor mock·가상 입금 기록의 범위는 앞선 [조회 최적화 QA](netting-source-read-optimization-20261019.md)와 같다. 현재 수치는 로컬 수정본이며 develop에 배포한 결과가 아니다.

## 구현

- `NettingBankTransactionReader`: 최대 2계좌 window를 전용 2-thread ExecutorService에 제출한다. 계좌 내부의 월별 조회는 기존 fetcher를 그대로 사용한다.
- 같은 계좌가 여러 원본/기존 압축에 등장하면 최초 요청 하나에 합치며, 기존처럼 가장 이른 시작 시각을 사용한다. 종료 시각은 해당 task의 실제 조회 시작 시점 UTC 현재 시각이다.
- 은행 thread는 최초/월별 현재 actor·lease·계좌 guard를 실행한다. DB 트랜잭션을 종료하고 외부 호출한다. thread-local 로그인 정보를 새 thread에 복사해서 권한을 결정하지 않는다.
- 성공과 RuntimeException을 계좌별로 구분한다. 실패를 빈 거래로 처리하지 않고 원래 오류를 순서대로 다시 던진다. 실패 window 이후의 새로운 계좌는 요청하지 않는다.
- worker의 원본/감시 계좌 순서대로 결과를 사용해 기존 transactional writer를 호출한다. 먼저 반영한 입금은 유지하고, 앞선 계좌 실패 뒤의 성공 응답은 반영하지 않는다. 부분 결과로 새 압축을 완료하지 않는다.
- CREATE·재압축 predecessor·RECONCILE에 적용했다. DETECT와 최종 검증은 유지한다.
- interruption/rejected submission은 기존 TEMPORARILY_UNAVAILABLE로 처리하고, 미완료 future를 취소한다. 종료 시 executor에 shutdownNow를 호출한다. 늦은 은행 응답 자체에는 DB writer 접근 경로가 없다.

Infra Bean과 Application orchestration만 추가했다. API·DB schema·Redis key·환경변수 변경은 없다. Flyway migration은 필요하지 않다.

## 검증

최종 **회귀 테스트 144개 + HTTP 성능 시나리오 1개 통과**, 실패·생략 0.

- 기존 source 조회 최적화·입금/재압축/권한/탈퇴/계좌 identity/lease/재시도/완료/Redis/계산 테스트를 유지했다.
- 실제 4개 계좌·5개 원본에서 최대 동시 2개, 동일 계좌·동일 조회 기간 중복 없음, 은행 호출이 DB 트랜잭션 밖임을 확인했다.
- 선행 계좌의 부분 입금이 후행 계좌 실패에도 보존되는 경우와, 첫 계좌 실패 뒤 다른 계좌의 성공 응답을 반영하지 않는 경우를 모두 확인했다.
- Reader 단위 테스트 4개: 병렬 실행·최대 2개, 실패 결과 유지·후속 window 중지, executor 거부, 대기 interruption 시 취소·interrupt flag 유지.
- 두 계좌의 영구/일시 실패 테스트에서 은행 호출 기대값은 account당 attempt 1회로 갱신했다. 영구 실패를 재시도하거나 오류 코드를 바꾼 것은 아니다. 병렬 window에 이미 시작한 상대 계좌의 호출은 빠른 실패에도 실행될 수 있다.
- HTTP에서 2·20·100건 압축 성공, 같은 키의 중복 실행 방지, 입금 후 압축·모든 원본 완료, 전체 source 조회 4회 유지를 확인했다.
- Java/test source compile 및 git diff --check 통과. Reader의 실패 검사도 현재 2계좌 window만 검사해 계좌 수에 따른 반복 전체 스캔을 만들지 않는다.

공유 CODEF token cache는 volatile/synchronized로 보호되고, 요청 payload·RSA Cipher는 호출별로 생성되는 소스도 확인했다. 실은행·추가 인증·OAuth 네트워크를 포함한 동시 호출 QA를 대신하는 근거는 아니다.

## 재현

```bash
docker compose -p howmuch-netting-parallel-qa-20261019 -f compose.test.yml up -d --wait
NETTING_HTTP_PERF_QA=true \
NETTING_HTTP_PERF_OUTPUT=build/reports/netting-parallel-bank-rerun.json \
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test \
DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused \
./gradlew test --tests '*GroupNettingHttpPerformanceTest.httpScenarioWithSlowBankAndScale'
```

자격정보는 compose.test.yml에 정의된 폐기용 테스트 값이다. 환경변수가 없으면 성능 harness는 실행되지 않는다. 전후 원시 결과와 JUnit summary:

- [순차 조회](evidence/20261019/netting-source-read-after-isolated.json)
- [병렬 조회: 빈 DB](evidence/20261019/netting-parallel-bank-after-isolated.json)
- [병렬 조회: 회귀 테스트와 함께 실행](evidence/20261019/netting-parallel-bank-after.json)
- [전후 비교](evidence/20261019/netting-parallel-bank-comparison.json)
- [144개 회귀 테스트](evidence/20261019/netting-parallel-bank-regression-junit.json)
- [HTTP 성능 테스트](evidence/20261019/netting-parallel-bank-isolated-junit.json)
- [최종 Reader 단위 재검증](evidence/20261019/netting-parallel-bank-reader-junit.json)

실은행의 동시 요청 제한, 다수 그룹 동시 부하, 다른 profile에서의 외부 인증은 미검증이다. 제한 응답은 기존 재시도 정책을 따르며 병렬 처리로 총 은행 호출 수가 감소하는 것은 아니다. 최근 sync 재사용과 수집 기간 조정은 사용자 요청대로 후속 작업으로 남겼다.

기존 dev 사용자·정산은 변경하지 않았다. 후속 게시 요청에 따라 [이슈 #588](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/588)와 브랜치 `refactor/588-netting-query-optimization`에 이번 개선을 연결한다. dev 배포는 하지 않았다. 해당 테스트 compose 프로젝트는 실행 후 정리했다.


## 이슈 #588 게시 전 최종 검증

- 기준 develop `c6b293b4`, 브랜치 `refactor/588-netting-query-optimization`.
- 폐기용 PostgreSQL 18·Redis 8에서 `bash scripts/test.sh clean test`: Java 2,417개 실행 통과, 실패·오류 0. 기본 조건에서 반복 정산 25개와 opt-in HTTP 성능 subclass 44개(상속 테스트 포함)는 생략됐다.
- 반복 정산 25개와 성능 harness의 새 HTTP 시나리오 1개를 조건을 활성화하고 따로 선택해 **26개 전부 통과**했다. 성능 subclass가 상속한 테스트는 부모 suite에서 이미 실행되어 다시 중복 집계하지 않는다. 따라서 최종 **서로 다른 Java 테스트 2,443개 통과**다.
- HTTP 재검증은 최종 코드에서 2·20·100건 압축 성공, 중복 요청, 최대 2계좌 병렬·총 호출 2회, 입금 후 모든 원본 완료를 다시 확인했다. 전체 suite가 사용한 DB에서 수행한 기능 검증이며, 위 빈 DB 전후 성능 표의 새로운 측정값으로 사용하지 않는다.
- JavaScript 35개, CI와 동일한 PyYAML 6.0.2를 설치한 별도 venv에서 배포 스크립트 Python 8개 통과. 첫 Python 실행의 누락 의존성을 보완한 뒤 재실행했다.
- 최종 서식 정리 후 `compileJava`, `compileTestJava`, `verifyMigrationVersions`, `git diff --check` 통과. 성능 harness는 reflection 대신 같은 패키지의 fixture helper를 사용하며, 기본 출력은 `build/reports`로 지정해 보존된 측정 결과를 덮어쓰지 않는다.
- [게시 검증 요약](evidence/20261019/netting-pr-588-full-suite.json). 원격 CI 결과와 로컬 검증은 구분한다.
