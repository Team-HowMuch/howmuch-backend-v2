# Feature Plan: 제출된 품목 정산의 수동 중단 차단

- 상태: 완료
- 갱신일: 2026-09-30
- 관련 이슈: [#454](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/454)

## 1. 목적

생성자가 본인 몫을 제출했는데 다른 참여자가 미제출이면 서버가 수동 중단을 허용한다. 상세 응답의 참여자 목록은 생성자를 제외하므로 클라이언트 검사만으로는 이를 막을 수 없다.

- [x] 생성자를 포함해 제출 기록이 하나라도 있으면 수동 중단을 409로 거부한다.
- [x] 제출 전 품목 선택만 저장된 정산은 중단할 수 있다.
- [x] 생성자 권한·기존 상태 오류·재개·자동 중단 동작을 유지한다.
- 범위 제외: 프론트 변경, 상세 참여자 응답 변경, 입금 대기 상태의 중단, 스키마 변경.

## 2. 도메인 분석

기존 `Settlement`, `SettlementItemSubmission`을 사용한다. 제출은 품목 선택이나 Share 존재 여부와 다르며, 선택 품목이 없는 제출과 할인으로 Share가 아직 없는 제출도 포함한다.

- 행위자: 로그인한 생성자.
- UseCase: `SuspendSettlementUseCase.suspend(UUID)`.
- 상태 전이: 수동 `REQUESTED → SUSPENDED`는 제출 기록이 없을 때만 허용한다.
- 자동 중단: 전원 제출 후 배분 오류가 있으면 기존 `Settlement.suspend()`를 계속 사용한다.

## 3. 시나리오

1. 현재 사용자와 정산 생성자를 확인하며 정산 행을 잠근다.
2. Domain의 수동 중단 전이로 `REQUESTED` 상태를 검사한다.
3. 생성자를 제외하지 않고 정산의 전체 제출 기록을 조회한다.
4. 기록이 있으면 `SETTLEMENT_NOT_SUSPENDABLE`(409), 없으면 변경된 정산을 저장한다.

생성자만 제출, 다른 참여자만 제출, Share 없는 제출도 거부한다. 잘못된 상태와 권한은 기존 오류를 먼저 반환한다. 품목 제출 writer도 같은 정산 행 잠금을 사용하므로 조회·검증·저장은 기존 트랜잭션에서 실행한다.

## 4. 계약

- Input/응답: 기존 UseCase, `SettlementResult`, `SettlementWebResponse` 유지. 본문 없음.
- Output: 기존 `LoadSettlementPort.findByIdForUpdate`, `SettlementItemAllocationStorePort.findAllSubmissions`, `SaveSettlementPort.update` 재사용.
- Policy: `SettlementSuspensionPolicy`가 제출 존재 여부로 수동 중단을 판정한다. 여러 참여자의 사실을 조합하는 규칙이므로 Application에서 소유한다.
- API: `POST /api/v2/settlements/{settlementId}/suspend`, 성공 200, 새 오류 `SETTLEMENT_NOT_SUSPENDABLE`(409).
- Flyway migration required: **No**. 기존 제출 테이블·쿼리 재사용.
- Redis, 외부 API, 환경 변수, 설정 변경: 없음.

## 5. 설계 결정

Service가 상태 전이 결과를 먼저 만들고 Policy 통과 후 저장한다. `Settlement`는 불변 객체이므로 정책 거부 시 상태 변경이 저장되지 않는다. Domain의 상태 오류 우선순위를 보존한다.

Share나 선택 기록 검사 대신 실제 제출 기록을 사용한다. 자동 중단까지 차단하면 전원 제출 후 배분 오류 복구 흐름을 깨뜨리므로 정책은 수동 중단 UseCase에만 적용한다. 기존 전체 조회로 충분하며 신규 Port·존재 쿼리는 추가하지 않는다.

## 6. 영향 범위

| 영역 | 변경 |
|---|---|
| Application | `SettlementService`, `SettlementSuspensionPolicy` |
| 오류 계약 | `SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE` |
| API 문서 | `SettlementDocs` 수동 중단 조건·409 설명 |
| 테스트 | Service·Controller 회귀, 기존 품목 제출·자동 중단·OpenAPI 검증 |
| 영속성·Domain 상태 전이·보안 설정 | 기존 구현 유지 |
| 문서 | 이 Feature Plan |

## 7. 구현 계획과 커밋 경계

- [x] 마이그레이션: N/A, 저장 구조 변경 없음.
- [x] UseCase/Command/Result·Output Port: 기존 계약 재사용.
- [x] Domain: 기존 상태 전이 재사용, 오류 코드만 추가.
- [x] Application Policy 및 Service 연결.
- [x] Output Adapter: 기존 전체 제출 조회 재사용.
- [x] Swagger 오류 계약 갱신.
- [x] Infra: N/A, 기존 component scan·트랜잭션 재사용.
- [x] 테스트·문서·검증.

```text
fix(settlement): 품목 제출 후 정산 수동 중단 차단
test(settlement): 수동 중단의 제출 여부 검증 보강
docs(settlement): 수동 중단 차단 설계와 검증 기록
```

각 커밋은 컴파일 가능한 상태로 만들며 첫 커밋에는 기존 테스트의 생성자 주입 변경도 포함한다.

## 8. 검증

- `./gradlew compileJava`
- 일회용 PostgreSQL·Redis를 확인한 뒤 `scripts/test.sh`로 Service·Controller·품목 제출·Domain·OpenAPI 테스트 실행.
- 전체 테스트 실행 가능 여부 확인.
- `git diff --check`, 문서 링크·경로 확인, 작업 상태 확인.

| 항목 | 결과 |
|---|---|
| `./gradlew compileJava compileTestJava --offline` | 통과 |
| 집중 Java 테스트 | 13개 클래스, 103건 통과; 실패·오류·건너뜀 0건 |
| JavaScript 테스트 | 35건 통과 |
| 전체 Java 테스트 | 186개 클래스, 1401건 통과; 실패·오류·건너뜀 0건 (`bash scripts/test.sh test --offline`) |
| `git diff --check`·문서 경로 확인 | 통과 |
| 운영 API 수동 호출 | 미수행; 일회용 PostgreSQL·Redis의 Service·Controller·OpenAPI 테스트로 검증 |

집중 테스트 실행 명령:

```bash
bash scripts/test.sh compileJava test --offline \
  --tests '*SettlementServiceTest' --tests '*SettlementControllerTest' \
  --tests '*SettlementItemAllocationAtomicityIntegrationTest' \
  --tests '*SettlementItemAllocationServiceTest' \
  --tests '*SettlementCreatorItemAllocationServiceTest' \
  --tests '*SettlementMemberItemAllocationServiceTest' \
  --tests '*SettlementDiscountedAllocationWriterTest' \
  --tests '*SettlementTest' --tests '*SettlementOpenApiIntegrationTest'
```

`compose.test.yml`의 일회용 PostgreSQL·Redis가 healthy인 것을 확인하고 테스트 전용 환경 변수를 설정해 실행했다. 실제 애플리케이션의 `.env`는 사용하지 않았다.

## 9. 진행 로그

- 2026-09-30: 최신 `origin/develop`에서 요청·제출·자동 중단·Persistence 흐름을 조사하고 마이그레이션 불필요로 결정했다.

- 2026-09-30: 구현·테스트를 각각 커밋했다. 집중 검증 103건, 전체 Java 1401건, JavaScript 35건 통과. Swagger 및 diff 검증을 완료했다.

## 10. 결정 로그

- 2026-09-30: 전체 제출 기록을 검사하며 생성자를 필터링하지 않는다. 선택·Share와 제출을 혼동하지 않기 위해 실제 제출 저장소를 재사용한다.
- 2026-09-30: 수동 경로에만 Application Policy를 적용한다. 자동 배분 오류 중단과 기존 Domain 상태 검증을 보존한다.

## 11. 완료 기록

- 완료일: 2026-09-30.
- 최종 동작: 생성자를 포함해 제출 기록이 있으면 수동 중단을 거부하고 저장하지 않는다. 제출 전 선택은 중단 후에도 보존한다. 자동 중단과 재개는 유지한다.
- 미검증 사항: 운영 API 수동 호출.
- 남은 위험: 운영 API 수동 검증은 별도 배포 환경에서 필요하다.
