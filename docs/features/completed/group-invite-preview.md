# Feature Plan: 공개 그룹 초대 미리보기

- Status: Completed (로컬 구현·검증)
- Last updated: 2026-09-04
- Related issue: [#262 비로그인 그룹 초대 미리보기 API 추가](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/262)
- Related ADR: [Output Port 규칙](../../adr/0003-capability-oriented-output-ports.md)

## 1. 목적과 범위

비로그인 방문자가 초대 링크에서 그룹명과 현재 방장 이름을 확인한다. 기존 가입 API는 조회에 사용하지 않는다.
환경변수·배포·실제 공유자 추적·Universal Links·이미지 공개·정산 링크 정책 변경은 제외한다.

## 2. 도메인과 불변식

기존 group 도메인과 user 조회를 재사용한다. 초대 코드는 그룹 공통 코드이며 만료 시각은 현재 모델에 없다.
`inviterName`은 `Group.ownerId`가 가리키는 현재 방장 이름이다. 소유권 이전 후에는 새 방장 이름이 표시된다.
조회는 그룹과 멤버십을 변경하지 않으며 활성 그룹과 활성 방장만 노출한다.

## 3. 시나리오

1. 인증 없이 코드를 전달한다.
2. 기존 `GroupInviteCodePolicy.normalize`로 strip·대문자 변환한다.
3. 락 없는 코드 조회, `Group.requireActive`, 방장 조회, 활성 멤버 수 집계를 수행한다.
4. 그룹명·방장 이름·활성 멤버 수를 반환한다.

형식 오류·없는 코드·삭제 그룹·비활성 방장은 `404 / GROUP_NOT_FOUND`로 처리한다.
`POST /api/v2/groups/join`의 기존 동작은 유지한다: ACTIVE는 `409 / GROUP_MEMBER_ALREADY_ACTIVE`,
REMOVED는 `409 / GROUP_MEMBER_REMOVED`, LEFT는 재가입 성공이다.

## 4. 계약과 저장소

- `GET /api/v2/group-invites/{code}`, 인증 없음, `GetGroupInviteUseCase.get(String code)`.
- 단일 식별자이므로 Command와 Web Request DTO는 N/A.
- `GroupInviteResult -> GroupInviteWebResponse -> ApiResponse`.
- 성공: `{"success":true,"data":{"groupName":"여행 모임","inviterName":"방장","memberCount":2}}`.
- 이미지 필드는 제외한다. 기존 이미지 API는 활성 멤버 인증이 필요하다.
- `LoadGroupPort.findByInviteCode`를 추가하고 기존 `LoadUserPort`, `LoadGroupMemberPort`를 재사용한다.
- Flyway migration required: No. 기존 컬럼과 초대 코드 고유 인덱스를 사용한다.
- Redis: `group:invite:requests:<remoteAddr>`, 최초 요청부터 60초 TTL, Lua로 증가와 만료를 원자적으로 처리한다.
- Lua는 [별도 리소스](../../../src/main/resources/redis/group-invite-record-request.lua)에 두고 Redis Adapter가 `ClassPathResource`로 로드한다.
- `TrackGroupInviteRequestPort.recordRequest(clientAddress)`를 보안 Interceptor가 호출하고 Redis Adapter가 구현한다.
  JWT Filter가 기존 Auth Port를 사용하는 방식과 같이, 기술 보안 경계에서 외부 저장소를 호출한다.
- 조회 제한: IP당 60회/60초, 초과 시 `429 / TOO_MANY_REQUEST`, `Retry-After: 60`.
- 제한 저장소 실패 시 조회를 허용하지 않는다. Redis Adapter가 DataAccessException을 503으로 변환한다.
  Redis 응답 대기는 기존 클라이언트 timeout 설정을 따른다. 이 작업에서 공통 timeout이나 환경변수를 변경하지 않는다.
- CORS: 이 조회 경로에만 `https://howmuch-web.vercel.app`의 GET 허용.
- 환경변수 변경 없음.

## 5. 설계 결정

정산 링크에는 코드상 공개 조회 레이트 리밋이 없다. 새 의존성 없이 기존 Redis Lua 패턴을 재사용한다.
임의 `X-Forwarded-For`를 신뢰하지 않고 Servlet `remoteAddr`를 사용한다. 프록시/NAT 환경에서는 제한이 공유될 수 있다.
정확한 클라이언트별 제한은 신뢰 프록시 경계 확인 후 처리한다. 고정 창 경계에서는 순간적으로 최대 120회가 가능하다.
인증 서비스에 공개 조회 의존성을 더하지 않고 작은 전용 Application Service를 둔다.
형식 오류의 404 변환은 미리보기 Service에만 적용하여 기존 가입의 400 계약을 유지한다.

## 6. 영향 범위

| 영역 | 변경 |
|---|---|
| API | 공개 Controller·Docs·Web Response |
| Application | 조회 UseCase·Result·Service, 기존 LoadGroupPort 확장 |
| Domain/Policy | 새 타입 없음, 기존 활성 상태와 코드 정규화 재사용 |
| Persistence | Repository·Adapter에 락 없는 finder |
| Redis | Port·Adapter가 요청 사용량 기록, 키·TTL·저장소 오류 변환 소유 |
| Security/Infra | 해당 GET만 permitAll, 경로 한정 요청 제한·CORS |
| 기존 계약 | 가입·그룹 상세·이미지·정산 링크 유지 |

## 7. 구현 순서와 커밋 경계

- [x] 설계와 마이그레이션 불필요 확인
- [x] UseCase·Result·Output Port
- [x] Application Service·Persistence Adapter
- [x] Controller·DTO·Docs
- [x] Security·요청 제한·CORS
- [x] 검증과 문서 갱신
- Domain 모델·새 Policy·외부 API: N/A, 기존 구현 재사용
- 커밋 경계: `feat(group): 공개 초대 미리보기 조회 추가` (사용자의 이슈·PR 게시 요청에 따라 단일 기능 커밋으로 게시)

## 8. 검증

기존 타 프로젝트 컨테이너 대신 별도 PostgreSQL·Redis를 사용한다.
루트 AGENTS.md의 변경 동작 테스트 규칙을 적용한다. 하위 workflow 문서의 과거 테스트 금지 문구보다 우선한다.

- `./gradlew compileJava`
- 관련 그룹 테스트 및 새 공개 API 통합 테스트: 정규화·404·방장·인원·조회 무변경·인증 경계·429·TTL·CORS·OpenAPI
- local 프로필 실행 후 HTTP 정상/오류 조회
- 문서 상대 링크·참조 경로, `git diff --check`, `git status --short --untracked-files=all`

실행한 관련 테스트:

```bash
./gradlew compileJava test \
  --tests '*GroupInviteIntegrationTest' \
  --tests '*GroupMembershipServiceTest' \
  --tests '*GroupQueryServiceTest' \
  --tests '*GroupControllerTest' \
  --tests '*GroupInviteCodePolicyTest' \
  --tests '*GroupOpenApiIntegrationTest'
```

별도 PostgreSQL 18.3·Redis 8 컨테이너에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`를
프로세스 환경으로 주입했다. 기존 컨테이너·EC2 환경변수는 변경하지 않았다.

| 검증 | 결과 |
|---|---|
| compileJava, 관련 테스트 | 성공, 40개 테스트, 실패 0 |
| 익명 조회·정규화·방장 이전·활성 인원·조회 무변경 | 새 통합 테스트 통과 |
| 형식 오류·공백 코드·없는 코드·삭제 그룹 | 통합 테스트 404 확인 |
| CORS·인증 경계·OpenAPI | GET 미리보기만 공개, 이미지·가입·하위 경로 보호 확인 |
| Redis 카운터·TTL·429·위조 X-Forwarded-For | 통합 테스트 통과 |
| 실제 local HTTP | 정상 200, 형식 오류 404, 이미지 401, 한도 초과 429 확인 |
| Redis 중단 | 최종 구현에서 503 / TEMPORARILY_UNAVAILABLE, CORS 헤더 유지 확인 |

첫 Redis 중단 검증은 클라이언트의 15초 대기를 초과하여 실패했다. 최종 장애 검증에서는 **검증 프로세스에만**
`--spring.data.redis.timeout=2s --spring.data.redis.connect-timeout=2s`를 적용하고 2초 후 503을 확인했다.
운영 타임아웃은 변경하지 않았으며 운영에서도 2초라고 보장하지 않는다.
전체 테스트 스위트는 변경 영역 밖이므로 실행하지 않았다.

## 9. 진행 기록

| 날짜 | 완료 | 다음 작업 |
|---|---|---|
| 2026-09-04 | 실제 가입·이미지·보안·저장소 흐름 확인 | 구현 및 검증 |
| 2026-09-04 | 구현 및 관련 테스트 40개, local HTTP 200·404·401·429 통과 | Redis 키·TTL의 Adapter 소유권 수정, 장애 처리 검증 |
| 2026-09-04 | AGENTS.md 계층 책임 재점검, Redis Adapter 분리, 최종 40개 테스트 및 장애 503 검증 | 배포는 별도 요청 시 진행 |
| 2026-09-04 | Lua를 별도 리소스로 분리하고 ClassPathResource로 로드, compileJava 및 GroupInviteIntegrationTest 4개 통과 | 기존 60회/60초 동작 유지 |

## 10. 완료 기록

- 완료일: 2026-09-04
- 최종 동작: 공개 미리보기에서 현재 방장 이름·그룹명·활성 멤버 수 반환, 가입 부작용 없음.
- 검증 결과: 컴파일·관련 테스트·local HTTP 검증 완료.
- 미검증: 실제 EC2 프록시 IP 전달 방식, 배포 및 웹 연동
- 남은 제약: 프록시/NAT IP 공유 제한, 기존 Redis timeout에 따른 장애 응답 대기.
- 게시 범위: 사용자 요청에 따라 이슈 #262와 연결해 `develop` 대상 PR로 게시한다. Flyway 마이그레이션 불필요.
