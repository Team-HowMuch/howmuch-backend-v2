# 압축정산 조회 최적화 결과 — 2026-10-19

최신 develop `c6b293b4`를 기준으로 반복 전체 조회와 N+1 조회를 수정했다. 은행 지연을 동일하게 넣은 실제 로컬 HTTP 요청에서 100건의 처리 시간이 18.0초에서 4.6초로 약 75% 줄었다.

## 변경

- NettingBankQueryGuard와 NettingOriginalDepositWriter의 그룹 전체 조회를 해당 정산의 식별 정보 1건 조회로 교체했다.
- 단건과 전체 조회가 같은 eligibility SQL 조건을 공유한다. 상태·삭제 여부·그룹 소속·계좌 digest 조건을 유지한다.
- 전체 조회의 참여자·채무/확정 입금 합계·입금 키를 일괄 조회해 N+1을 제거했다. 정산이 있으면 이 Adapter의 전체 로딩은 4개 SQL로 구성되며, 빈 결과는 목록 쿼리 후 반환한다. 이는 모든 압축 처리의 SQL이 4회라는 뜻이 아니다.
- 그룹 권한·탈퇴·계좌 identity·lease·최종 version 재검사와 트랜잭션 경계는 그대로 유지한다.
- API 계약·계산 알고리즘·저장 스키마·외부 조회 횟수 변경은 없다. Flyway migration은 필요하지 않다.

## 동일 조건 비교

두 기준 실행은 각각 빈 PostgreSQL/Redis에서 성능 harness만 실행했다. 구성원 2명, 원본 2·20·100건, 은행 Port mock 호출당 1초(총 2회) 지연, 읽기 요청은 20개·동시성 4다. 실제 Spring Boot HTTP 서버와 HttpClient를 사용했다. API 접수부터 worker 완료·결과 조회까지 측정했으며 운영 scheduler 대기 시간은 포함하지 않았다.

| 원본 수 | 수정 전 | 수정 후(빈 DB) | 감소율 | 전체 원본 조회 호출 수 |
|---:|---:|---:|---:|---:|
| 2 | 2.76초 | 2.85초 | -3.0% | 10 → 4 |
| 20 | 3.79초 | 3.01초 | 20.4% | 46 → 4 |
| 100 | 18.00초 | 4.57초 | 74.6% | 206 → 4 |

각 규모의 표본이 작고 첫 실행에는 JIT·HTTP 초기화가 포함된다. 2건의 +3.0% 변동을 유의한 회귀로 해석하지 않는다. 138개 회귀 테스트와 함께 실행한 별도 수정 후 측정은 20건 2.89초, 100건 3.92초였다. 따라서 이번 100건 결과는 약 3.9~4.6초 범위이며 더 큰 통계 표본을 측정한 SLA가 아니다.

은행의 2초 지연을 단순 차감하면 20건은 약 1.0초, 100건은 약 2.6초다. 실제 은행 지연은 별도이므로 운영의 완료 시간을 보장하지 않는다. 100건 동기화 단계는 약 17.48초에서 4.17초로 줄었고 저장 단계는 약 0.3초 수준이다.

전체 그룹 조회는 100건에서도 4회다. 그 대신 대상 정산의 단건 식별 조회 202회가 수행된다. 횟수가 같은 호출을 캐시로 숨긴 것이 아니라, 반복되는 무거운 전체 조회를 가벼운 단건 검증으로 바꾼 것이다. 전체 조회 내부의 1+3N 쿼리도 4개 일괄 쿼리로 바꿨다.

## 검증

- `./gradlew compileJava compileTestJava`: 통과.
- 관련 **138개 테스트 통과·실패 0·생략 0**.
- 기존 44개 직접 압축 통합 테스트에 3개 검증을 추가했다: 단건/전체 eligibility와 digest 일치, 다른 그룹 소속 제외, 부분 입금 합계·입금 키·생성자 부담 제외.
- 기존 재압축·권한·탈퇴·동시 계좌 변경·입금·lease·재시도·이력 보존·계산·estimate·Redis cache 검증도 통과했다.
- HTTP 성능 harness는 2·20·100건 모두 중복 요청의 동일 execution, 한 송금으로 압축, 입금 대조 후 모든 원본 COMPLETED를 확인했다. 전체 조회가 규모에 따라 증가하지 않는 검사도 추가했다.
- 빈 DB의 수정 후 HTTP harness 재실행 1개도 통과했다.
- `git diff --check`: 통과.

## 재현

```bash
docker compose -p howmuch-netting-perf-fix-20261019 -f compose.test.yml up -d --wait
NETTING_HTTP_PERF_QA=true \
NETTING_HTTP_PERF_OUTPUT=build/reports/netting-source-read-rerun.json \
DB_URL=jdbc:postgresql://127.0.0.1:15432/howmuch_test \
DB_USERNAME=howmuch_test DB_PASSWORD=howmuch_test \
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused \
./gradlew test --tests '*GroupNettingHttpPerformanceTest.httpScenarioWithSlowBankAndScale'
```

이 명령의 DB 자격정보는 저장소 compose.test.yml에 정의된 폐기용 값이다. 성능 harness는 환경변수로 명시적으로 켰을 때만 실행한다. 현재 게시 브랜치에는 후속 계좌 병렬화도 포함되어 있어 위 명령은 최종 버전을 검증하며, 이 문서의 순차 조회 중간 단계 수치와는 구분한다. 기존 증거 파일은 덮어쓰지 않는다. 이번 전후 비교에서 수정 전 harness는 호출 수를 기록만 했고, 수정 후에는 전체 조회 횟수 증가를 막는 assertion을 추가했다. fixture·지연·HTTP 순서·측정 구간은 같다.

## 근거와 범위

- [수정 전 원시 결과](evidence/20261019/netting-source-read-before.json)
- [수정 후 빈 DB 원시 결과](evidence/20261019/netting-source-read-after-isolated.json)
- [회귀 테스트와 함께 실행한 수정 후 결과](evidence/20261019/netting-source-read-after.json)
- [전후 비교 JSON](evidence/20261019/netting-source-read-comparison.json)
- [138개 회귀 테스트 요약](evidence/20261019/netting-source-read-regression-junit.json)
- [빈 DB 재실행 요약](evidence/20261019/netting-source-read-after-isolated-junit.json)

로컬 전용 fixture만 사용했고 기존 dev 계정·정산은 수정하지 않았다. 실은행·다인원 복잡 그래프의 HTTP 성능·다수 그룹 동시 부하는 이번 측정 범위가 아니다. SQL 문장 수 전체를 DB profiler로 계측한 결과도 아니다.

후속 게시 요청에 따라 [이슈 #588](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/588)와 브랜치 `refactor/588-netting-query-optimization`에 이번 개선을 연결한다. develop 배포는 하지 않았다. 이번 요청으로 로컬 테스트 인프라를 만들었으며 실행 후 해당 compose 프로젝트만 정리한다.
