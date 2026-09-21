# Feature Plan: 정산 링크 조회 요청 시각 추가

- Status: Completed
- Last updated: 2026-09-21
- Related issue: #408
- Related ADR: 없음

## 1. 목적

### 문제

비회원이 정산 링크로 참여할 때 `GET /api/v2/settlement-links/{token}` 응답에 정산 요청 발송 시각이 없어 프론트가 `participation_completed` 이벤트의 `minutes_since_request`를 계산할 수 없다. 회원 경로는 정산 목록의 `requestedAt`을 사용하지만 링크 진입 경로는 같은 기준 시각을 받지 못한다.

### 해결 이유

링크 진입과 앱 내 참여의 분석 기준을 통일해야 정산 요청부터 참여까지의 전환 시간을 비교할 수 있다. 기존 정산 링크 생성 시각과 정산 생성 시각을 재사용하므로 별도 저장 구조를 추가하지 않는다.

### 성공 기준

- [x] 링크 조회 응답에 `requestedAt`이 포함되고, 링크 생성 시각을 반환한다.
- [x] 링크 생성 시각이 없는 데이터는 정산 생성 시각으로 대체한다.
- [x] APP_DIRECT와 BROWSER_SELECTION 두 진입 모드가 동일한 기준 시각을 반환한다.
- [x] Swagger 계약과 Web/Application 테스트가 실제 응답 필드와 일치한다.

### 범위 제외

- 데이터베이스 스키마와 Flyway migration
- 정산 목록 API의 `requestedAt` 계산 변경
- 프론트엔드의 `minutes_since_request` 이벤트 계측
- 링크 생성·재발급 정책 변경

## 2. 도메인 분석

### 도메인 경계

- 재사용 도메인: settlement
- 관계: `SettlementLink`와 `Settlement`의 생성 시각을 링크 조회 응답으로 조립한다.

### 용어

| 용어 | 의미 | 코드 표현 |
|---|---|---|
| 요청 시각 | 링크 참여 요청이 발송된 것으로 간주하는 기준 시각 | `requestedAt` |
| 링크 생성 시각 | 공유 링크가 생성된 시각 | `SettlementLink.createdAt()` |
| 정산 생성 시각 | 링크 시각이 없을 때 사용하는 대체 시각 | `Settlement.createdAt()` |

### 행위자와 UseCase

| 행위자 | 목표 | UseCase |
|---|---|---|
| 링크 방문자 | 정산 참여에 필요한 링크 정보를 조회 | `ResolveSettlementLinkUseCase` |

### 상태와 불변식

- 링크 조회의 기존 만료·활성·정산 상태 검증은 그대로 유지한다.
- `requestedAt`은 링크 생성 시각을 우선하며, 링크 시각이 없을 때만 정산 생성 시각을 사용한다.
- APP_DIRECT와 BROWSER_SELECTION은 동일한 요청 시각을 공유한다.

## 3. 시나리오

### 정상 흐름

1. 방문자가 유효한 정산 링크를 조회한다.
2. 서비스가 기존 `LinkAccess`에 로드된 링크와 정산의 생성 시각을 조합한다.
3. Application Result와 Web Response에 `requestedAt`을 포함한다.

### 실패 흐름

- 링크가 없거나 만료·비활성·사용 불가이면 기존과 동일하게 `SETTLEMENT_LINK_NOT_FOUND`를 반환한다.

### 경계 조건

- 링크 생성 시각이 null인 경우 정산 생성 시각으로 대체한다.
- APP_DIRECT와 BROWSER_SELECTION 모두 같은 값을 반환한다.

## 4. 계약

### Input Port와 경계 매핑

```text
UseCase: ResolveSettlementLinkUseCase
Command: 없음
Result: SettlementLinkEntryResult.requestedAt
Web Request DTO -> Command: 없음
Result -> Web Response DTO: SettlementLinkEntryWebResponse.requestedAt
```

### Output Ports

이번 변경에 새 Output Port는 없다. 서비스가 이미 로드하는 `SettlementLink`와 `Settlement` 값을 재사용한다.

### Application Policy

별도 Policy가 필요하지 않다. 두 Entity의 생성 시각을 우선순위에 따라 조합하는 단순 응답 조립이며 기존 서비스가 소유한다.

### Web API

```text
GET /api/v2/settlement-links/{token}
Request: path variable token
Response: data.requestedAt (UTC, offset 없는 LocalDateTime)
Errors: 기존 링크 조회 오류 유지
Authentication: 공개 조회, 유효한 Bearer 인증 시 APP_DIRECT 분기
```

### Persistence와 외부 연동

- 데이터베이스 Entity/제약/쿼리 변경: 없음
- Flyway migration required: No
- Planned migration: 없음
- Migration pull request: N/A
- Redis key/TTL/직렬화 변경: 없음
- 외부 API/메시지 변경: 없음
- 환경 변수/프로필 변경: 없음

## 5. 설계 결정

### 비즈니스 로직

- Domain은 기존 생성 시각 상태를 그대로 소유한다.
- Application Service가 링크 생성 시각 우선, 정산 생성 시각 fallback 규칙을 응답에 조립한다.
- 기존 링크 접근 검증과 참여자 분기 순서는 변경하지 않는다.

### 기술과 패턴

- 해결할 제약: 목록 API와 링크 조회 API가 요청 시각을 서로 다르게 노출한다.
- 선택한 방식: 기존 `SettlementLink`와 `Settlement`의 `createdAt`을 `SettlementLinkEntryResult`와 Web Response에 추가한다.
- 선택 이유: 추가 조회·컬럼·migration 없이 목록 API의 `COALESCE(link.created_at, settlement.created_at)` 의미를 재사용할 수 있다.
- 검토한 대안: 별도 요청 시각 컬럼 추가는 기존 데이터 재계산과 migration을 요구하므로 범위와 이득에 비해 과하다.
- 한계: 현재 도메인에서 링크 생성 시각은 필수이므로 fallback은 호환성과 목록 API 의미를 위한 방어적 처리다.

## 6. 영향 범위

| 영역 | 영향 | 확인 파일/계약 |
|---|---|---|
| API | 응답 필드 1개 추가 | `SettlementLinkEntryWebResponse` |
| Application | Result와 서비스 조립 확장 | `SettlementLinkEntryResult`, `SettlementLinkEntryService` |
| Domain | 변경 없음 | `SettlementLink`, `Settlement` |
| Persistence | 변경 없음 | 기존 created_at 매핑 |
| Redis | 변경 없음 | 해당 없음 |
| Security | 변경 없음 | 기존 공개 링크 정책 |
| Configuration | 변경 없음 | 해당 없음 |
| Documentation | Swagger 설명과 Feature Plan 갱신 | `SettlementLinkEntryDocs` |

유지해야 하는 기존 동작:

- 링크 만료·상태 검증과 오류 코드는 그대로 유지한다.
- 참여자 목록, APP_DIRECT 자동 매칭, 단기 참여 문맥 발급, 결제 정보 필드는 그대로 유지한다.

## 7. 구현 계획

- [x] Migration-only pull request to `develop` (N/A: 기존 컬럼만 사용)
- [x] UseCase와 Application Command/Result
- [x] Required Output Ports (N/A: 새 외부 대화 없음)
- [x] Domain models and rules (N/A: 기존 상태 재사용)
- [x] Application Policies (N/A: 별도 정책 없음)
- [x] Application Service
- [x] Output Adapters (N/A: persistence 변경 없음)
- [x] Web Request/Response DTOs, Controllers, and Swagger
- [x] Infra configuration and Bean wiring (N/A: 설정 변경 없음)
- [x] Documentation and verification results

### 예상 커밋 경계

```text
docs(settlement): 링크 조회 요청 시각 설계 기록
feat(settlement): 링크 조회 응답에 요청 시각 추가
test(settlement): 링크 조회 요청 시각 계약 검증
```

## 8. 검증

### 명령

```bash
./gradlew compileJava
./gradlew test --tests '*SettlementLinkEntryServiceTest' --tests '*SettlementLinkEntryControllerTest' --tests '*SettlementOpenApiIntegrationTest'
git diff --check
git status --short --untracked-files=all
```

서비스·컨트롤러 집중 테스트는 실행했고, OpenAPI 통합 테스트는 로컬 DB의 기존 Flyway checksum 문제로 컨텍스트 초기화 단계에서 실패했다. 전체 테스트는 별도 실행하지 않았다.

### 완료 기준

- [x] 링크 생성 시각이 있는 APP_DIRECT/BROWSER_SELECTION 응답 검증
- [x] fallback 규칙 검증
- [ ] Swagger `requestedAt` 필드 확인 (OpenAPI 통합 테스트 환경 문제로 미검증)
- [x] 미실행 검증과 이유 기록

### 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| Java 컴파일 | 통과 | `./gradlew compileJava` |
| 서비스·컨트롤러 계약 테스트 | 통과 | `./gradlew test --tests '*SettlementLinkEntryServiceTest' --tests '*SettlementLinkEntryControllerTest'` |
| OpenAPI 통합 테스트 | 미통과 | 로컬 DB의 Flyway V16 checksum mismatch (`applied -1039115587`, `resolved 192761889`) |
| diff 검사 | 통과 | `git diff --check` |

## 9. 진행 기록

| 날짜 | 완료 작업 | 다음 작업 | 발견 위험 |
|---|---|---|---|
| 2026-09-21 | 이슈·기존 흐름·최신 `origin/develop` 확인, 설계 초안 작성 | Result/Service/Web 계약 구현 | 도메인상 링크 생성 시각은 필수지만 목록 API 의미를 위해 fallback 유지 |
| 2026-09-21 | Result·Service·Web 응답·Swagger와 APP_DIRECT/BROWSER_SELECTION/fallback 테스트 구현 | PR 생성 | 로컬 Flyway V16 checksum mismatch로 OpenAPI 통합 테스트 미검증 |

## 10. 결정 기록

| 날짜 | 결정 | 이유 | 대안/영향 |
|---|---|---|---|
| 2026-09-21 | `SettlementLink.createdAt()` 우선, `Settlement.createdAt()` fallback | 목록 API와 동일한 요청 시각 의미를 기존 값으로 계산 | migration 없이 구현, 링크 생성 시각이 없는 비정상/레거시 데이터에도 대응 |

## 11. 완료 기록

- 완료일: 2026-09-21
- 최종 동작: `GET /api/v2/settlement-links/{token}`가 링크 생성 시각 우선의 `requestedAt`을 반환하고, 없으면 정산 생성 시각을 반환한다.
- 미검증 항목: OpenAPI 통합 테스트와 전체 테스트. 로컬 DB의 Flyway V16 checksum mismatch를 수정하지 않고 보존했다.
- 남은 위험: Swagger 스키마의 실제 생성 결과는 CI 또는 migration checksum이 정리된 DB에서 확인해야 한다.
- 후속 작업: 프론트에서 링크 참여 `minutes_since_request` 계측
