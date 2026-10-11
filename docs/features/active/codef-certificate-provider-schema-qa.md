# API 내부 인증서 provider V45 SQL 검증

초기 commit `06e713f7`의 검증 기록이다. 확정 결과 충돌을 막는 후속 수정과 최신 166개 검증은 [PR #498 리뷰 QA](codef-certificate-pr498-review.md)를 참조한다.

- 날짜: 2026-10-10
- 기준: `origin/develop` `74070103`, V44까지 적용된 최신 migration
- 관련: [Feature Plan](codef-certificate-registration.md), [ADR-0007](../../adr/0007-api-resident-certificate-execution-and-electron-client.md), [SQL 이슈 #496](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/496)
- SQL branch: `feat/496-api-certificate-provider-migration`, commit `06e713f7`
- 변경 파일: `src/main/resources/db/migration/V45__create_api_certificate_provider_storage.sql` 한 파일
- 결과: [경로별 요약](codef-certificate-provider-verification.json), [신규 74개 상세](codef-certificate-provider-checks.json)

## 결과

PostgreSQL **16.14**, Flyway **12.4.0**, JDBC **42.7.11**로 합성 데이터만 사용했다. 검증용 DB는 `127.0.0.1:11445`의 전용 disposable container이며 dev/prod와 무관하다. 검증 종료 후 이 container와 그 anonymous volume만 제거했고 로그·결과 JSON은 보존했다.

| 검증 | 결과 |
|---|---|
| 빈 DB V1→V45 | 45개 적용, validate 성공, 재실행 0개 |
| V1→V44 baseline | 44개 적용, validate 성공, 재실행 0개 |
| 합성 V44 데이터가 있는 DB V44→V45 | 1개 적용, validate 성공, 재실행 0개 |
| 기존 데이터 유지 | 기존 41개 테이블의 row count와 정렬된 JSON row digest 모두 동일; 신규 테이블 4개 |
| 기존 V44 제약/쿼리 실험 | 적용 전 42개, 적용 후 42개 통과 |
| 현재 예약 무결성 회귀 | 적용 전 35개, 적용 후 35개 통과 |
| 신규 provider 제약/쿼리 실험 | 빈 DB 경로와 upgrade 경로 각각 74개 통과 |
| Java 최소 compile | `./gradlew compileJava --no-daemon` 성공 |
| diff / 문서 | `git diff --check`, 상대 링크 및 참조 경로 검증 |

신규 74개는 임시 upload의 envelope 형태·일회성·TTL 상한·소비 후 비밀 컬럼 제거, command receipt의 중복 ID/operation·불변 digest, 두 worker의 선점 경쟁, lease 교체/지연 CAS, provider 요청 시작 marker의 불변성과 시작 뒤 자동 retry 거부, UNKNOWN 확인 복구를 포함한다.

연결 귀속은 V44 PC 승인 사실·명의확인 참조와 일치해야 하며 다른 사용자의 legacy connection을 참조할 수 없다. 승인된 연결의 orphan 정리·owner 변경을 거부하고, provider connection/job/result를 한 transaction에 기록하다 잘못된 결과를 넣으면 전체 rollback됨을 확인했다. result publication retry는 CODEF job attempt를 바꾸지 않고, 지연 ACK·발행 완료 후 사실 변경을 거부한다. session을 정리한 뒤에도 Inbox/result dedup 기록은 유지된다.

## 재현 자료

SQL-only 규칙상 이 문서와 script는 기능 plan worktree에 보관하고 migration PR에는 넣지 않는다.

- [기존 42개 실험](../../../tools/verification/certificate-schema-check.py)
- [현재 예약 35개 실험](../../../tools/verification/certificate-current-claim-check.py)
- [provider 74개 실험](../../../tools/verification/certificate-provider-schema-check.py)

최신 migration을 Flyway filesystem location에 복사한 뒤 Java helper에서 `migrate() → validate() → migrate()` 순서로 실행했다. helper/classpath와 Flyway 로그, 적용 전 41개 테이블 fingerprint는 `/private/tmp/howmuch-api-certificate-20261010`에 보관했다. 기존 두 script의 container/database 값은 메모리에서 일회 검증 DB로 치환했으며 원본은 수정하지 않았다.

신규 실험 실행:

```bash
python3 tools/verification/certificate-provider-schema-check.py \
  --container howmuch-api-certificate-schema-20261010 \
  --database certificate_final_upgrade \
  --output /private/tmp/howmuch-api-certificate-20261010/final-provider-checks.json
```

## 구현 및 검증하지 않은 범위

이 결과는 **SQL 제약과 제안 쿼리의 실험**이다. Application의 worker/lease CAS·재시도·명의확인, AES-GCM/KMS 실제 암호화와 TTL 정리, CODEF HTTP 호출, Kafka topic/ACL·offset·DLT, Electron IPC·installer·Windows 실행, 모바일 QR은 구현하거나 실행하지 않았다. `./gradlew test`와 `bootRun`은 이번 SQL-only 범위에서 실행하지 않았다. 전체 CI 성공을 주장하지 않는다.

Application은 `session → claim → provider job/connection → external connection` 잠금 순서와 `40P01`/`40001` bounded retry를 구현해야 한다. SQL이 완료 worker의 lease token/generation CAS나 현재 시각에 의한 expiry 검사를 대신하지 않는다. approved/BOUND 연결 삭제는 사용자 탈퇴의 공통 실행기에 비밀 제거·remote 결과 reconciliation·매핑 정리를 연결해야 한다.

## 적용 시 확인

V44 다음에 V45를 적용한다. V45는 기존 `external_connections`에 `(id,user_id)` unique constraint를 추가한다. 기존 PK 때문에 중복 row가 생기지는 않지만, 인덱스 생성과 ALTER TABLE 잠금은 발생하므로 운영 row 수와 배포 시간을 확인한다. rollback을 위해 비밀 저장 테이블을 DROP하지 않는다. 이 작업에서는 dev/prod migration과 기능 활성화를 수행하지 않았다.
