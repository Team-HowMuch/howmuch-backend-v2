# Feature Plan: 정산 상세 참여자 프로필 이미지

- Status: Completed
- Last updated: 2026-09-09
- Related issue: #334

## 1. 목적

정산 상세 참여자를 사진으로 구분할 수 있도록 `participants[].avatarUrl`을 추가한다.
앱은 이미 nullable 경로를 처리한다. 이미지가 없거나 비회원이면 null을 반환한다.
기존 금액 계산·정렬·상세 공개 범위를 유지하며 이미지 업로드나 저장 구조는 변경하지 않는다.

## 2. 도메인 분석

기존 settlement와 user 도메인을 재사용한다. 생성자는 납부 참여자 전체를, 연결 참여자는
본인 행만 조회한다. 참여자와 사용자는 `linkedUserId`로 연결하며 이름으로 대조하지 않는다.
상태 전이나 새 Policy는 필요하지 않다.

## 3. 시나리오

- 생성자: 연결 사용자 사진이 있으면 기존 참여자 이미지 조회 경로를 반환한다.
- 참여자: 본인 행에 기존 `/api/v2/user/me/avatar` 경로를 반환한다.
- 사진 없음·미연결·사용자 없음: null을 반환한다.
- 무관한 사용자: 기존 `SETTLEMENT_ACCESS_DENIED`를 유지한다.
- Share 생성 전에도 사진을 반환하고 기존 `NOT_READY`와 금액을 유지한다.

## 4. 계약

- `GetSettlementUseCase` → `SettlementDetailResult.ParticipantPayment.hasAvatar`
  → `SettlementDetailWebResponse.ParticipantPayment.avatarUrl`
- `GET /api/v2/settlements/{settlementId}`에 nullable 문자열 필드만 추가한다.
- 기존 `LoadUserPort.findAllByIds` / `UserPersistenceAdapter`로 표시 대상 연결 사용자를 일괄 조회한다.
- Flyway migration required: No. 기존 `avatarFileName`을 사용한다.
- 새 Output Port, Domain, Policy, Adapter, Infra, Redis, 환경 변수 변경: N/A.

## 5. 설계 결정

Application은 이미지 보유 여부만 반환하고 Web DTO가 상대 API 경로를 조립한다.
기존 참여자 이미지 API는 생성자 전용이므로 참여자 본인은 기존 내 이미지 API를 사용한다.
이를 통해 기존 인가 정책을 유지하면서 양쪽 화면에서 사용 가능한 경로를 제공한다.
개별 사용자 조회 대신 표시 대상 ID를 모아 한 번 조회해 N+1을 방지한다.

## 6. 영향 범위

- Application: SettlementDetailService, SettlementDetailResult
- Web: SettlementDetailWebResponse, SettlementDocs
- 검증: SettlementDetailServiceTest, SettlementControllerTest, SettlementOpenApiIntegrationTest
- 기존 참여자 이미지 API와 내 이미지 API의 권한 및 저장 동작은 유지한다.

## 7. 구현 계획 및 커밋 경계

1. `docs(settlement): 정산 상세 프로필 이미지 설계 기록`
2. `feat(settlement): 정산 상세 참여자 이미지 경로 제공`
3. `test(settlement): 정산 상세 프로필 이미지 계약 검증`

마이그레이션·새 UseCase·Port·Domain·Policy·Adapter·Infra 구현은 N/A(기존 기능 재사용).

## 8. 검증

`./gradlew compileJava`, 관련 서비스·Web·OpenAPI 테스트, `git diff --check`를 실행한다.
단위 테스트는 외부 서비스가 필요 없고 OpenAPI 통합 검증은 별도 PostgreSQL·Redis 환경을 확인한다.
문서 경로와 링크를 확인한다. 실제 HTTP 및 전체 테스트 실행 여부를 완료 시 기록한다.

## 9. 진행 기록

- 2026-09-09: 최신 origin/develop 기반 브랜치 생성 및 기존 이미지 경로·권한 확인.

## 10. 결정 기록

- 기존 그룹 응답처럼 `hasAvatar`에서 Web 경로를 조립한다.
- 참여자 본인은 본인 프로필 API를 사용해 생성자 전용 이미지 API의 권한을 확대하지 않는다.

## 11. 완료 기록

- 완료일: 2026-09-09. nullable `avatarUrl`과 화면별 접근 가능한 경로 제공 완료.
- `./gradlew compileJava compileTestJava` 통과.
- `bash scripts/test.sh compileJava test --tests '*SettlementDetailServiceTest' --tests '*SettlementControllerTest' --tests '*SettlementParticipantServiceTest' --tests '*UserControllerTest' --tests '*SettlementOpenApiIntegrationTest'` 통과.
- 별도 PostgreSQL DB와 로컬 Redis를 확인한 환경에서 Java 81개, JavaScript 35개 통과.
- 사진 유무·미존재 사용자·비회원·참여자 공개 범위·권한 거부·Share 생성 전 상태 및 JSON/Swagger 필드 계약 검증 완료.
- `git diff --check` 통과. 영향 범위에 적은 소스 및 테스트 경로의 존재 확인.
- 전체 테스트와 실제 소켓 HTTP/앱 이미지 렌더링은 미실행(관련 서비스·MockMvc·Spring 통합 검증으로 범위 제한).
- 기존 이미지 기능과 동일하게 상세 조회 후 사진 삭제 시 이미지 요청이 404가 될 수 있다.
- 추가 스키마 작업은 없다.
