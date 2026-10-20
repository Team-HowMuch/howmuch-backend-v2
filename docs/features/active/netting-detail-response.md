# Feature Plan: 압축 정산 상세 응답 보강

- 상태: 설계 완료, 구현 중
- 갱신일: 2026-10-20
- 관련 이슈: [#600](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/600)

## 1. 목적과 완료 조건

앱이 압축 정산 상세의 원본 카드·송금 진행 상태·완료일을 원본 상세 API의 추가 호출 없이 표시한다. 예상 압축 결과에서 버튼을 비활성화하는 이유도 구분한다.

- [ ] `sources`에 상호명, 조회자 기준 카테고리, 전체·납부·완료 인원과 기존 요청자명을 제공한다.
- [ ] `transfers`에 `UNPAID / PARTIAL / PAID`, 마지막 입금 확인 시각과 관련 원본 ID를 제공한다.
- [ ] 세대의 `completedAt`, `supersededAt`을 저장된 UTC 시각으로 제공한다.
- [ ] 예상 결과의 `reason`은 `NOT_ENOUGH_TARGETS / NO_REDUCTION / PROCESSING`, 압축 가능하면 `null`이다.
- [ ] 기존 접근 권한, 원본/송금 금액, 입금·재압축 동작과 예상 건수 캐시를 유지한다.

제외: 앱 구현, 상계 알고리즘·금액 배분 변경, 은행 호출 변경, 새로운 이미지 조회 API, 운영 배포.

## 2. 도메인과 시나리오

기존 settlement/netting 도메인을 사용한다. 활성 그룹 멤버는 기존 `GetGroupNettingsUseCase`, `GetGroupNettingEstimateUseCase`로 조회한다. 삭제 그룹·비당사자 제한은 `GroupNettingAccessService`와 기존 흐름에 맡긴다. 새로운 UseCase·Command·도메인은 필요 없다.

정상 흐름은 기존 접근 검사 → 세대·선대 원본·송금 조회 → 카테고리 일괄 조회 → Result → Web Response이다. 원본별 API 호출, 은행 조회 또는 원본별 Repository 호출을 추가하지 않는다.

경계: 영수증 없음·복수 결제 소스의 상호명은 `null`, 없는 카테고리는 `null`, 미입금의 확인 시각은 `null`이다. 재압축은 선대 원본을 포함한다. 미결 검토의 기존 오류와 인증·접근 실패를 유지한다.

## 3. 계약

### 입력 및 출력 경계

기존 조회 URL과 입력을 유지한다. `GroupNettingResult` → `GroupNettingWebResponse`, `GroupNettingEstimateResult` → `GroupNettingEstimateWebResponse`에 필드를 추가한다. 같은 응답을 쓰는 목록·current·원본 경유 조회에도 적용한다.

| Port / 구성 요소 | 변경과 호출자 |
|---|---|
| `LoadGroupNettingPort` | 기존 세대·원본·송금 조회에 시각·요약 정보를 포함. `GroupNettingQueryService`가 사용 |
| `MySettlementCategoryReader` | 기존 개인 선택 → 자동 분류 규칙으로 모든 원본의 카테고리 일괄 조회 |
| `LoadUserPort` | 기존 일괄 이름·publicId 조회 유지 |
| `NettingEstimateCachePort` | 기존 Counts 그대로 유지. `reason`과 진행 여부는 캐시하지 않음 |

### 응답의 의미

- `storeName`: 기존 목록·상세와 동일하게 결제 소스(`RECEIPT_OCR / PAYMENT_HISTORY`)가 정확히 하나이며 OCR인 경우의 description.
- `category`: 조회자의 개인 선택, 없으면 자동 분류. `{code,name,icon}` 또는 `null`.
- `participantCount`: 원본의 전체 참여자. `payableParticipantCount`: 생성자를 제외하고 Share가 있는 참여자(확정된 0원 더치페이는 Share 없음).
- `completedParticipantCount`: 원본 Share의 실제 입금 완료 인원. 원본이 `COMPLETED`가 되면 상계 대체가 완료되었으므로 납부 대상 전원. 부분 상계 송금을 원본별 입금으로 임의 배분하지 않는다.
- `status`: 확인액 0이면 `UNPAID`, 금액 미만이면 `PARTIAL`, 전액이면 `PAID`.
- `confirmedAt`: 자동 귀속의 `matched_at`, 수동 검토 적용의 `resolved_at` 중 마지막 시각. 은행 거래 발생 시각과 다르며 부분 입금에도 제공한다.
- `completedAt / supersededAt`: 저장된 세대 종결 시각. 해당하지 않으면 `null`.
- `sourceSettlementIds`: 송금자 또는 수취인이 미납 채무의 당사자인 관련 원본 ID 목록. 선대 원본 포함, 순서 유지·중복 제거. 원본별 최종 송금 기여액을 의미하지 않는다. 사용자가 관련 원본 목록으로 제공하는 의미를 확인했다.
- `reason`: 진행 중이면 `PROCESSING` 우선, 아니면 대상 부족, 건수 미감소 순. 가능하면 `null`. 미결 검토는 기존 409를 유지한다.

### 저장 구조와 외부 경계

- Flyway migration required: **No**. V53에 세대 종결 시각, 자동·수동 입금 확인 시각, 원본 Share와 당사자가 이미 있다.
- 기존 SQL 조회만 확장한다. 신규 테이블·컬럼·데이터 변경 없음.
- Redis 키·TTL·직렬화, 설정·환경변수, 외부 API 및 보안 경계 변경 없음.
- 프로필 사진은 새 경로를 만들지 않고 기존 publicId·이름 제공을 유지한다.

## 4. 설계 결정과 영향

세대 조회의 원본 SQL에서 상호명·인원·미납 채무 당사자를 일괄 읽는다. 송금 SQL에서 자동/수동 적용 이력을 합쳐 확인액과 마지막 확인 시각을 같은 집계에서 얻는다. 개인 카테고리는 DB Adapter가 사용자별 의미를 해석하지 않도록 기존 Application Reader에서 조합한다.

| 영역 | 영향 |
|---|---|
| API | 상세·목록·current의 공통 응답과 estimate 응답, OpenAPI |
| Application | Result, QueryService, EstimateService와 기존 카테고리 Reader 재사용 |
| Domain | 새 규칙·상태 전이 없음 |
| Persistence | `GroupNettingJdbcAdapter`의 읽기 SQL·Port record 확장 |
| Redis / Security / Infra | 기존 동작 유지 |
| 문서 | 이 계획서와 실제 검증 결과 |

관련 소스: `src/main/java/com/amateur/howmuchbackend/` 아래 `adapter/in/web/settlement/netting`, `application/settlement/netting/service`, `application/port/in/settlement/netting/result`, `application/port/out/settlement/netting/LoadGroupNettingPort.java`, `adapter/out/persistence/settlement/netting/GroupNettingJdbcAdapter.java`.

## 5. 구현과 커밋 경계

- [x] 마이그레이션 필요 여부 결정: N/A, 기존 구조 재사용
- [x] 상세 Result·Port·조회 SQL·Service·Web DTO·OpenAPI와 회귀 검증
- [x] estimate reason Result·Service·Web DTO·OpenAPI와 회귀 검증
- [ ] 검증 결과 기록 및 completed로 이동
- N/A: 새 UseCase·Command, Domain·Policy 추가, 신규 Output Port, Infra wiring

예상 커밋:

```text
docs(settlement): 압축 정산 응답 보강 설계 정리
feat(settlement): 압축 정산 상세에 원본 요약과 송금 진행 정보 추가
feat(settlement): 압축 예상 결과에 불가 사유 추가
docs(settlement): 압축 정산 응답 검증 결과 정리
```

## 6. 검증

폐기용 PostgreSQL 18.6·Redis 8, Java 21, `scripts/test.sh`의 테스트 전용 환경을 사용한다. 실제 `.env`는 읽거나 사용하지 않는다.

- `./gradlew compileJava`, `verifyMigrationVersions`, `compileTestJava`
- 관련 단위·통합 테스트: 상세 HTTP → 실제 DB 요약, 입금 전/부분/전액, 수동 적용, 재압축 이력, 카테고리 조회자 차이, 대상 부족/미감소/진행중과 캐시 적중
- `/v3/api-docs`의 필드·enum·nullable·UTC 설명 확인
- `git diff --check`, 문서 상대 링크·로컬 경로 확인, 전체 테스트(가능한 환경에서 실행)

상위 AGENTS.md의 변경 동작 테스트 지침을 따른다. 하위 상세 workflow의 테스트 추가 금지 문구와 충돌할 때 상위 지침을 적용한다.

| 검증 | 결과 |
|---|---|
| Java compile | 대기 |
| 상세 회귀 | Java 56개·JavaScript 35개 통과. 실제 DB·Redis, HTTP 요약과 OpenAPI 포함 |
| 예상 결과 포함 관련 회귀 | Java 76개·JavaScript 35개 통과, opt-in HTTP 성능 subclass 48개는 전체 회귀에서 활성화 예정 |
| 전체 회귀 | 대기 |
| 문서·diff | 대기 |

## 7. 진행 및 결정 기록

| 날짜 | 결정 / 진행 | 이유 / 후속 |
|---|---|---|
| 2026-10-20 | 최신 develop과 이슈·기존 PR·실제 조회 및 V53 확인 | 기존 컬럼·입금 이력 재사용, 마이그레이션 불필요 |
| 2026-10-20 | 상세 회귀의 기존 fixture 정리 시각을 GREATEST(updated_at, DB now)로 보정 | 기존 동시성 테스트가 미래 시각을 사용해 정리 UPDATE가 불변 조건에 걸리고 다음 테스트에 작업을 남기는 문제 수정 |
| 2026-10-20 | nullable enum을 Web enumAsRef와 기존 nullableReferenceCustomizer로 문서화 | 실제 JSON Schema 검증으로 reason null·세 enum 허용 및 UNKNOWN 거부 확인 |
| 2026-10-20 | 원본별 기여 금액은 추정하지 않음 | 상계 결과와 원본 Share 사이에 고유 금액 배분이 없음 |

## 8. 완료 기록

- 완료일: 대기
- 최종 동작 / 검증 결과: 구현 후 갱신
- 미검증: 실제 앱·실은행·운영 배포
