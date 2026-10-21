# Feature Plan: 정산 링크 참여자 프로필 이미지 (#618)

- Status: Completed
- Last updated: 2026-10-21
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/618
- 기준: origin/develop `6337ef47`, branch `fix/618-settlement-link-avatar`

## 1. 목적과 범위

공개 정산 링크의 프로필 선택 화면에서 회원 사진을 표시할 데이터가 빠져 모든 참여자가 기본 이미지로 표시된다. `participantCandidates`, APP_DIRECT의 `selectedParticipant`, 참여자 선택 결과에 nullable `avatarUrl`을 제공한다. 활성 회원이며 프로필 사진이 있을 때만 URL을 채우고, 비회원·탈퇴·삭제된 회원·사진 미등록은 null이다.

웹 렌더링은 이슈에 명시된 별도 작업이다. 프론트 수정·운영 배포는 포함하지 않는다. 사용자 후속 요청에 따라 백엔드 변경을 커밋하고 develop 대상 PR로 게시한다.

## 2. 기존 경로와 설계

- 기존 `SettlementLinkEntryService`의 링크 형식·해시·유효기간·활성 상태·정산 상태 검사를 재사용한다. 조회와 선택의 금액·표시 순서·요청자 여부·참여 문맥 계약은 유지한다.
- `LoadUserPort.findAllByIds`로 중복을 제거한 linkedUserId를 한 번에 읽는다. 이미지 본문은 후보 조회에서 읽지 않는다. 인증된 직접 진입은 선택된 회원만, 브라우저 조회는 전체 후보의 회원만 배치 조회한다.
- 기존 이미지 API는 인증 또는 정산 생성자 권한이 필요해 공개 웹의 avatarUrl로 재사용할 수 없다. `GetSettlementLinkParticipantAvatarUseCase`와 `GET /api/v2/settlement-links/{token}/participants/{participantId}/avatar`를 추가하고, 같은 링크의 정산에 속한 참여자만 조회한다. `ProfileImageStoragePort.load`를 재사용한다.
- 공개 URL은 Web 경계에서 조합한다. 기존 참여자·그룹 API와 같은 `ImageCacheVersion`을 재사용해 Application Result에는 `avatarVersion`, 실제 Web Response에는 nullable `avatarUrl`을 추가한다. 파일명과 내부 userId를 응답에 노출하지 않는다. 이슈의 Result.avatarUrl 제안 대신 기존 계층 관례를 따른다.
- 아바타 경로도 매번 유효한 링크를 검증한다. 이미지 응답은 `Cache-Control: no-store`로 링크 만료·폐기 후 캐시 재사용을 막는다. 기존 `/api/v2/settlement-links/**` 공개 보안 매칭은 변경하지 않는다.

## 3. 계약과 실패 경로

- 링크 조회: 후보/직접 선택 참여자의 `avatarUrl` = `/api/v2/settlement-links/{token}/participants/{participantId}/avatar?v={version}` 또는 null.
- 참여자 선택: `participant.avatarUrl` 동일 계약.
- 아바타 조회: 로그인 없이 이미지 content-type과 본문, 유효하지 않은 링크는 기존 `SETTLEMENT_LINK_NOT_FOUND`(404), 다른 정산·없는 참여자는 `SETTLEMENT_PARTICIPANT_NOT_FOUND`(404), 비회원·탈퇴·사진 없음·스토리지 파일 없음은 `PROFILE_IMAGE_NOT_FOUND`(404).
- 기존 도메인·상태 전이 및 입금 처리 변경 없음. 별도 Policy N/A(기존 링크/참여자/활성 회원 검증 재사용).

## 4. 저장소와 외부 경계

Flyway migration required: **No**. 기존 users.avatar_file_name 읽기, 기존 사용자 배치 조회와 로컬 프로필 스토리지 Port만 사용한다. 새 Output Port/Adapter, DB·Redis·환경변수·프로파일·보안 설정 변경은 없다.

## 5. 구현과 검증 계획

- [x] Controller → Service → 기존 사용자 배치 조회·이미지 스토리지·공개 보안 경로 추적
- [x] 무마이그레이션 설계 확정
- [x] Application Result·이미지 조회 UseCase·Service 구현
- [x] Web Response·Controller·Swagger 구현
- [x] 후보/직접 진입/선택, null 경계, 배치 조회 횟수, 링크 권한 및 이미지 응답 회귀 테스트
- [x] Java 21 compileJava, 관련 테스트, diff와 문서 경로 확인

예상 커밋 경계: `fix(settlement): 공개 정산 링크 참여자 프로필 이미지 제공`. 실제 커밋은 사용자 요청 후 수행한다. AGENTS.md의 변경 동작 테스트 추가 지침이 상세 workflow의 오래된 신규 테스트 금지 문구보다 우선한다.

## 6. 검증 결과

- Java 21 `compileJava` 성공. 아래 관련 테스트 총 **36개 통과**(실패·오류·스킵 0). 전체 저장소 테스트는 실행하지 않았다.
- 단위·컨트롤러: 후보 순서/기존 금액 유지, 배치 조회 1회와 개별 조회 없음, APP_DIRECT·선택 결과 URL, 비회원·탈퇴·사진 미등록·삭제 회원 null, 이미지 본문·content-type·no-store, 링크·정산·참여자 제한을 검증했다.
- 실제 HTTP: 실제 Spring Security/Controller/Service/PostgreSQL과 모의 이미지 스토리지를 사용해 비로그인 목록의 URL로 이미지를 조회했다. 비회원·없는 참여자·탈퇴·완료 정산·폐기/만료 링크의 404를 검증했다. 이미지 저장 Adapter 자체는 기존 구현을 재사용하며 이번 HTTP 테스트에서는 mock 처리했다.
- 로컬 격리 PostgreSQL(54468, howmuch618)·Redis(63868)를 사용했다. 개발·운영 서버 데이터에는 접근하지 않았다. 로그: `build/qa618-verification.log`.
- 테스트 준비 중 비회원 fixture의 source를 CONTACT로 수정했고, 실제 앱과 같은 UTC 초기화를 위해 HTTP 테스트를 `useMainMethod=ALWAYS`로 실행했다. 운영 로직을 완화하지 않았다.
- `git diff --check` 통과. 스키마·환경설정·프론트 변경 없음. 사용자 후속 요청으로 `fix/618-settlement-link-avatar` 브랜치를 커밋·푸시하고 PR로 게시한다. 배포는 수행하지 않는다.

실행 명령:

```bash
# 위의 격리 DB/Redis 환경에서 실행
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava test \
  --tests '*SettlementLinkEntryServiceTest' \
  --tests '*SettlementLinkEntryControllerTest' \
  --tests '*SettlementLinkAvatarHttpIntegrationTest' \
  --tests '*SecureSettlementLinkTokenAdapterTest' --console=plain
```

### 프론트 연동

`avatarUrl`은 API 서버 기준 상대 경로다. 웹에서 API base URL과 조합하여 이미지 src에 사용하고 null이면 기존 기본 프로필을 표시한다. 별도 로그인 토큰·참여 문맥 헤더는 필요 없다. 이슈에 명시된 웹 렌더링은 별도 작업이다.
