# Feature Plan: 정산 목록의 독촉 가능 시각

- Status: Completed
- Last updated: 2026-10-19
- Related issue: [#593](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/593)
- Related ADR: 기존 Application/Port/Adapter 경계 유지
- 기준: `origin/develop`의 `b63935bc`

## 1. Purpose

목록 카드에서 독촉 버튼을 표시할 때 상세 API를 추가 호출하지 않고 쿨다운을 판단한다. `GET /api/v2/settlements` 항목에 UTC `LocalDateTime | null`인 `reminderNextAvailableAt`을 추가한다.

### Success criteria

- [x] RECEIVE에서 상세와 같은 대상·쿨다운 계산을 사용한다.
- [x] PAY, 비활성 상태, 대상 없음, 활성 TTL 없음은 null이다.
- [x] 기존 금액·상호명·카테고리·필터·권한 계약을 유지한다.
- [x] 대상별 DB 조회 없이 목록의 참여·제출·입금 사실을 한 번에 읽는다.
- [x] OpenAPI 및 전체 로컬 테스트로 검증한다.

### Out of scope

독촉 발송 정책·30분 TTL·자동 독촉·모바일 화면·운영 배포는 변경하지 않는다.

## 2. Domain analysis

기존 Settlement와 참여자·품목 제출·Share·확정 입금·Redis 독촉 쿨다운을 재사용한다. 생성자가 보는 REQUESTED는 연결된 미제출 참여자, AWAITING_DEPOSIT은 연결된 Share 보유 완전 미납 참여자가 대상이다. 생성자·미연결·부분/전액 입금·확정 0원(Share 없음)은 제외한다. 대상 중 활성 TTL의 가장 늦은 만료 시각을 반환한다. 시각이 null이어도 상태·납부 여부에 따라 독촉이 불가능할 수 있으므로 발송 API가 최종 검증한다.

## 3. Scenarios

1. 인증된 회원의 탭·상태·그룹·월 조건으로 기존 목록을 조회한다.
2. RECEIVE의 REQUESTED/AWAITING_DEPOSIT 항목만 대상으로 참여·제출·Share·확정 입금 사실을 일괄 조회한다.
3. 상세와 공유하는 Application Policy로 대상 참여자를 선택한다.
4. 기존 쿨다운 Reader가 Redis TTL을 읽어 정산별 최신 만료 시각을 계산한다.
5. Result → Web Response 매핑으로 UTC nullable 값을 제공한다.

PAY·빈 목록·완료/중단·대상 없음에서는 추가 TTL 조회를 하지 않는다. TTL 만료·조회 직후 상태 변경은 상세와 동일한 조회 시점 의미를 갖고 발송 시 다시 검증한다. 저장소 장애를 임의의 성공/null로 숨기지 않는다.

## 4. Contracts

### Input Port and boundary mapping

`GetSettlementsUseCase`/Command는 유지한다. `SettlementListItemResult`와 `SettlementListItemWebResponse`에 `LocalDateTime reminderNextAvailableAt`을 추가한다. 기존 Result 생성 경로는 보조 생성자로 호환하고 실제 목록 Service는 새 필드를 명시적으로 전달한다.

### Output Ports

| Port | Purpose | Caller | Adapter |
|---|---|---|---|
| `LoadSettlementReminderFactsPort` | 정산 ID 집합의 참여·제출·Share·확정 입금 사실 조회 | `SettlementReminderCooldownReader` | `SettlementReminderFactsPersistenceAdapter` |
| `SettlementReminderCooldownPort` | 기존 예약·해제·잔여 TTL 조회 | 기존 Reader | 기존 Redis Adapter |

사실 record는 외부 기술 타입을 노출하지 않는다. 후보 판정은 `SettlementReminderPolicy`, TTL 만료 시각은 기존 Reader가 담당한다. Web/JPA/Redis 타입을 Application에 가져오지 않는다.

### Web API

`GET /api/v2/settlements`: 인증·파라미터·오류 유지. `reminderNextAvailableAt`은 상세와 동일한 UTC offset 없는 날짜 시각이다. PAY와 활성 쿨다운이 없는 경우 null이다.

### Persistence and external integrations

- Flyway migration required: **No**. 기존 테이블과 Redis TTL을 읽는 파생 응답이다.
- 신규 schema·인덱스·Redis key·TTL·환경변수·외부 API 변경 없음.
- PostgreSQL 일괄 조회는 요청 목록 ID로 제한하고 삭제된 정산을 제외한다. 입금은 상세와 동일하게 Share별 `SUM(amount)`를 사용한다.

## 5. Design decisions

상세 Service를 목록 항목마다 호출하면 계좌·은행·이미지·지출까지 불필요하게 읽고 쿼리가 늘어난다. 필요한 원시 사실만 일괄 조회하고 공유 Policy와 기존 TTL Reader를 사용한다. Redis는 상세와 같은 참여자별 TTL 조회를 유지한다. 영속 만료 시각을 추가 저장하거나 새 캐시는 만들지 않는다.

## 6. Impact surface

| Area | Impact |
|---|---|
| Application | Result, 목록 조립, 후보 Policy, 기존 쿨다운 Reader의 일괄 경로, 상세의 공유 Policy 사용 |
| Persistence | 실제 호출되는 사실 Port와 JDBC Adapter 추가 |
| Web | 목록 Response, Docs의 UTC/null/탭 설명 |
| Tests | 기존 Service/Reader/Controller/OpenAPI/실제 DB 회귀 검증 보강 |
| Domain/Security/Infra | 변경 없음 |

## 7. Implementation plan

- [x] Migration-only PR: N/A, 기존 데이터 조회만 필요
- [x] Result와 실제 호출되는 사실 Port 정의
- [x] 공유 후보 Policy·Reader·목록 Service 연결
- [x] 일괄 JDBC Adapter·Web Response·Docs 연결
- [x] 기존 테스트 및 실제 HTTP/DB/Redis 검증
- [x] 전체 Java·JavaScript·Python 테스트와 결과 기록
- [x] 새 Domain/Infra: N/A

Expected commit boundaries: `feat(settlement): 정산 목록에 독촉 가능 시각 추가`. 이 대화에서 요청한 방식대로 전체 로컬 검증 통과 후 별도 원격 브랜치에 커밋·푸시한다. 전체 회귀에서 발견한 기존 인증서 테스트의 바이너리/문자열 변환 오류는 `test(certificate): 암호문 검증에서 바이너리 값 직접 조회`로 별도 분리한다.

## 8. Verification

다른 로컬 작업의 고정 포트를 피한 전용 Docker PostgreSQL 18·Redis 8을 사용하고 `RECURRING_TEST_DB_URL` 등으로 반복 정산 조건부 테스트도 활성화한다. 최종 실행에서는 `NETTING_HTTP_PERF_QA=true`로 실제 HTTP 성능 회귀도 포함한다. Testcontainers 기반 인증서·Kafka 테스트를 포함해 `bash scripts/test.sh clean test`를 실행한다. Python은 격리 환경의 PyYAML 6.0.2로 `python -m unittest discover -s scripts/tests -v`를 독립 실행한다.

기존 테스트에서 목록 UTC/null 매핑·Policy 조건·일괄 조회·Redis TTL 만료·입금/제출 제외·상세와 동일한 결과를 검증한다. `git diff --check` 및 문서 링크/경로를 확인한다. 실제 은행·SMS·푸시·운영 DB는 사용하지 않는다.

| Item | Result |
|---|---|
| 구현·기능 회귀 | 목록 HTTP/영속 36개, 목록 Service 24개, 정산 OpenAPI 3개 및 기존 상세·쿨다운 회귀 통과 |
| 전체 로컬 테스트 | Java 2,494개 / 295 suites, JavaScript 35개 통과. 실패·오류·건너뜀 0건. Python 8개 통과 |
| diff·문서 검증 | `git diff --check` 및 참조 경로 확인 통과 |

## 9. Progress log

- 2026-10-19: 이슈·최신 develop·상세 후보/TTL 계산 경로 확인, migration 불필요 판단, 설계 기록.
- 집중 검증: 최초 135개 중 제출 fixture에 제출 행이 없는 기대값 오류 1건을 확인하고 실제 제출 행을 생성하도록 보완했다. 목록 영속/HTTP 36개 및 JavaScript 35개 재검증 통과. 전체 실행은 새 DB에서 수행한다.
- HTTP 통합 검증은 Spring Security 필터를 유지한 MockMvc와 실제 Service·PostgreSQL·Redis를 사용한다. 조회자 포트만 테스트 사용자로 대체한다. RECEIVE 시각·PAY null·부분 입금 후 null·미인증 401을 검증한다.

## 10. Decision log

- 기존 상세의 가장 늦은 활성 TTL과 대상 규칙을 유지한다. 가장 빠른 만료 시각 등 새로운 정책으로 변경하지 않는다.
- DB는 사실만 조회하고 후보 판단은 Application Policy에서 공유한다.
- 기존 회귀 테스트를 확장하며 무관한 테스트/정책/캐시를 추가하지 않는다.

## 11. Completion record

- Completed on: 2026-10-19
- Final behavior: RECEIVE 목록에 상세와 같은 UTC 독촉 가능 시각을 반환한다. PAY·비활성 상태·후보 없음·활성 TTL 없음은 null이며 미제출·완전 미납 후보 판정을 상세와 공유한다.
- Unverified items: 실제 모바일 화면·외부 은행/푸시·운영 배포
- Remaining risks: 목록 응답 이후 TTL/납부 상태가 바뀔 수 있으며 독촉 API에서 최종 재검증

### 전체 회귀 중 발견한 기존 테스트 오류

첫 전체 실행은 Java 2,487개 중 실패 1건, 기본 비활성 HTTP 성능 suite에서 건너뜀 44건이었다. `CertificateSmsAuthenticationIntegrationTest.genericBankContinuationPersistsEncryptedResultWithoutCertificateParent`가 실패했다. `convert_from(encrypted_payload, 'LATIN1')`이 랜덤 암호문의 NUL 바이트를 PostgreSQL text로 표현하려 해 `invalid byte sequence for encoding "LATIN1": 0x00`를 반환했다. BYTEA를 `byte[]`로 직접 읽고 Java의 ISO-8859-1로 일대일 매핑해 기존 평문 미포함 assertion을 유지한다. 저장 형식·암호화·provider 호출은 바꾸지 않는다.

- 최종 전체 실행: `NETTING_HTTP_PERF_QA=true`와 반복 정산 전용 DB를 함께 사용했다. 실제 HTTP 성능/상속 회귀 51개, 인증서 SMS 통합 31개를 포함해 Java 2,494개가 모두 통과했다. 기능/테스트 코드의 최종 실행 이후 변경은 검증 문서뿐이다.

- Python 최종 독립 실행: 8개 통과. 인증서 테스트 안정화는 `e8878c79`로 분리했다.
