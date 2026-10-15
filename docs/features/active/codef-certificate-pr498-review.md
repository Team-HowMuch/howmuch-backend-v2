# PR #498 리뷰 수정 검증

- 날짜: 2026-10-10
- 수정 commit: `ed3bcf09`
- PR: [#498](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/498)
- 코멘트: [P2 Prevent conflicting definitive registration results](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/498#discussion_r4237915056)
- 관련: [Feature Plan](codef-certificate-registration.md), [초기 V45 QA](codef-certificate-provider-schema-qa.md)
- 검증 자료: [결과 JSON](codef-certificate-pr498-results.json), [provider SQL script](../../../tools/verification/certificate-provider-schema-check.py)

## 수정 내용

원본 commit `06e713f7`에서 `SUCCEEDED` REGISTER command에 `REGISTERED`와 `REGISTRATION_FAILED`가 함께 저장되는 것을 실제 PostgreSQL에서 재현했다. 기존 `(command_message_id,message_type)` unique는 서로 다른 확정 결과를 막지 못했다.

V45에 `command_message_id` partial unique index를 추가하고 predicate를 `message_type IN ('REGISTERED','REGISTRATION_FAILED')`로 한정했다. 둘 중 한 결과가 있으면 반대 결과 INSERT는 `23505`로 거부된다. publication 상태는 predicate에 포함하지 않아 이미 발행한 결과도 보호한다. `REGISTRATION_UNKNOWN`은 제외하여 UNKNOWN과 후속 확정 결과를 함께 보존한다. 두 writer의 경쟁도 DB unique index가 보호한다.

추가 index는 모든 command terminal state와 결과 정책을 구현하는 것은 아니다. 이번 수정은 리뷰에서 지적한 확정 결과의 상호 배타성을 보장한다. API/worker/Electron 런타임 코드는 변경하지 않았다. 아직 머지·배포하지 않은 V45를 수정했으며 이미 적용한 migration을 운영 환경에서 재작성하지 않았다.

## 검증 결과

PostgreSQL **16.14**, Flyway **12.4.0** 및 합성 데이터만 사용했다.

| 검증 | 결과 |
|---|---|
| 원본 SQL | SUCCEEDED command에 성공/실패 확정 결과 동시 저장 재현 |
| 빈 DB V1→수정 V45 | 45개 적용, validate 성공, 재실행 0개 |
| V1→V44 baseline | 44개 적용, validate 성공, 재실행 0개 |
| 합성 V44 데이터에서 수정 V45 upgrade | 1개 적용, validate 성공, 재실행 0개 |
| 기존 데이터 유지 | 기존 41개 테이블 row count/정렬 JSON digest 동일, 신규 테이블 4개 |
| 기존 SQL/예약 회귀 | 42개+35개, V45 적용 전/후 모두 통과 |
| provider 검증 | 기존 74개+리뷰 회귀 15개=89개, 빈 DB/upgrade 양쪽 경로 통과 |
| Java 최소 compile | `./gradlew compileJava --no-daemon` 성공, `compileJava UP-TO-DATE` |
| 변경 범위 | V45 SQL 한 파일에 주석/index 3줄 추가 |

총 **166개** SQL 제약·제안 쿼리 실험을 통과했다. 새 15개는 다음을 확인한다.

- 성공→실패, 실패→성공 입력을 모두 거부하고 동일 실패 중복도 거부
- 성공/실패 결과를 발행한 뒤에도 반대 확정 결과 거부
- UNKNOWN→성공과 UNKNOWN→실패 모두 허용, UNKNOWN 자체 중복은 기존 dedup 유지
- 다른 command의 성공/실패는 독립 기록
- 성공 writer 먼저/실패 writer 먼저 두 동시 transaction 순서에서 하나만 commit
- 동일 transaction의 상반된 두 결과 INSERT는 전체 rollback되어 발행할 row가 남지 않음

동시성 검증은 첫 transaction이 INSERT 후 `PgSleep`에 도달했음을 관측하고 두 번째 writer를 시작했다. 89개 script에는 원본 대비 부족했던 이 경우들을 추가했다. SQL-only 규칙에 따라 script·Feature Plan·QA·결과 JSON은 별도 plan worktree에 두고 PR에는 포함하지 않는다.

## 재현 및 범위

```bash
python3 tools/verification/certificate-provider-schema-check.py \
  --container howmuch-api-certificate-schema-review498-20261010 \
  --database certificate_review498_upgrade \
  --output /private/tmp/howmuch-certificate-review498-20261010/upgrade-provider-checks.json
```

Flyway helper·원본/수정 migration snapshot·로그·테이블 fingerprint는 `/private/tmp/howmuch-certificate-review498-20261010`에 보관했다. 기존 두 회귀 script는 container/database 값만 메모리에서 바꿔 사용했다. SQL-only 수정이므로 로컬 `./gradlew test`와 `bootRun`은 실행하지 않았고 실제 CODEF/Kafka/Windows 통합 검증은 이번 범위에 포함하지 않았다. 검증 종료 후 이번 task의 PostgreSQL container와 anonymous volume만 제거하고 로그/결과를 보존했다. dev/prod에 적용하지 않았다. 수정 commit의 CI 결과는 GitHub PR에서 별도 확인한다.
