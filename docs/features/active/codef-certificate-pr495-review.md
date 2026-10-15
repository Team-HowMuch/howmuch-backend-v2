# PR #495 현재 예약 상태 리뷰 수정

- 날짜: 2026-10-10
- 대상: [리뷰 코멘트](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/495#discussion_r4232435460)
- 범위: 미병합 V44 SQL 한 파일. 새 endpoint나 Java/Windows 구현은 포함하지 않는다.
- 관련 문서: [Feature Plan](codef-certificate-registration.md), [기존 QA](codef-certificate-schema-qa.md)

## 재현과 수정

기존 FK는 같은 세션의 예약 ID/generation만 검사했다. 예약만 EXPIRED, REJECTED, CANCELLED, APPROVED로 바꾸고 세션을 CLAIM_RESERVED로 두는 변경이 모두 commit되는 것을 원본 `054f54aa` 스키마에서 재현했다.

`validate_certificate_current_claim()`과 세션·예약 양쪽의 지연 constraint trigger를 추가했다. 세션 INSERT/UPDATE, 예약 INSERT/UPDATE/DELETE가 끝나면 transaction 최종 상태의 실제 current claim이 RESERVED인지 검사한다. 현재 예약이 없는 정상 종료/삭제 상태는 허용한다. queued NEW의 과거 상태를 검사하지 않으므로 양쪽 테이블의 갱신 순서를 강제하지 않는다.

검사는 세션과 실제 current claim을 잠가 동시 transaction이 서로의 이전 상태만 보고 통과하는 문제도 막는다. 후속 Application은 session → claim 순서로 잠그고 PostgreSQL deadlock(`40P01`)/serialization(`40001`)을 재시도해야 한다. 반대 순서의 동시 writer에서는 DB가 한 transaction을 거부할 수 있으며 자동 재시도 구현이 완료됐다는 의미가 아니다.

## 검증 결과

| 항목 | 결과 |
|---|---|
| 원본의 claim-only terminal 변경 | 네 상태 모두 불일치 commit 재현 |
| `./gradlew compileJava --no-daemon` | BUILD SUCCESSFUL |
| PostgreSQL 16 / Flyway 12.4.0: V1~V42 + 수정 V44 | 적용 43개, validate 성공, 재실행 0개 |
| PostgreSQL 16 / Flyway 12.4.0: V1~V42 + V43 + 수정 V44 | 적용 44개, validate 성공, 재실행 0개 |
| 기존 SQL 검증 | 42개 통과 |
| 이번 회귀 검증 | 35개 통과 |
| 수정 commit `49521b65`의 GitHub CI | 전체 Java·JavaScript 테스트와 Docker 이미지 빌드 성공, Deploy to EC2 skipped |

[CI 실행 #38035937499](https://github.com/Team-HowMuch/howmuch-backend-v2/actions/runs/38035937499)의 head는 `49521b65e6da840bc1c38ab08f398677d851a357`이다. 리뷰 코멘트에 수정/검증 결과를 답변하고 스레드를 해결 처리했다. PR diff는 V44 SQL 한 파일이며 기존 테이블·런타임·서버에는 적용하지 않았다.

추가 회귀 검증은 claim-only 네 terminal 변경의 commit 거부·rollback, 세션만 terminal claim에 연결할 때 거부·rollback, 정상 종료/승인의 session-first 및 claim-first 양쪽 순서, 예약 교체, 과거 예약 변경 시 실제 current claim 검사, current claim 단독 삭제 거부와 session cascade 삭제 허용을 포함한다.

두 writer 경쟁은 READ COMMITTED / REPEATABLE READ 각각에서 session-first·claim-first를 나누어 검증했다. `pg_stat_activity`에서 첫 transaction의 sleep 진입을 확인한 후 두 번째를 시작했다. 불일치 결과를 두 transaction이 모두 commit할 수 없으며 최종 불변 조건이 유지됨을 확인했다.

- [회귀 실험 스크립트](../../../tools/verification/certificate-current-claim-check.py)
- [35개 결과](codef-certificate-current-claim-results.json)
- 원본 재현/실제 Flyway 로그/기존 42개 결과: `/tmp/howmuch-certificate-review-20261010/`

검증용 일회 PostgreSQL에서만 실행했다. dev/prod DB에는 적용하지 않았으며 API/Kafka worker/Windows/실은행 검증은 이 수정의 범위가 아니다. V43 선행 적용 조건은 그대로 유지한다. SQL-only PR에는 설계/QA/실험 파일을 포함하지 않는다.
