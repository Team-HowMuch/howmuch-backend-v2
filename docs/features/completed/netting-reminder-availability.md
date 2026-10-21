# Feature Plan: 압축 정산 송금별 독촉 가능 시각 (#619)

- Status: Completed
- Last updated: 2026-10-21
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/619
- 기준: origin/develop `6337ef47`, branch `feat/619-netting-reminder-availability`

## 1. 목적과 완료 조건

압축 정산의 독촉 버튼을 누르기 전에는 앱이 쿨다운을 알 수 없다. 송금 응답마다 nullable `reminderNextAvailableAt`(UTC, offset 없는 LocalDateTime)을 제공하여 서버 정책과 일치하는 남은 시간을 표시할 수 있게 한다.

- [x] 그룹 압축 이력 목록·현재·상세·알림/원본 기반 상세에서 동일 필드를 반환한다.
- [x] 수동/자동 독촉이 공유하는 기존 `last_at`에 수동 독촉의 `SettlementReminderPolicy.COOLDOWN`을 더한다. 아직 지나지 않은 시각만 반환하고 미기록·만료·정확한 종료 경계는 null이다.
- [x] 한 응답의 모든 송금 ID를 중복 제거하여 한 번에 조회한다. 빈 목록은 조회하지 않는다. 기존 목록의 다른 조회 구조는 범위 밖이다.
- [x] 기존 권한·상태·부분 입금·검토·재압축 진행 중 독촉 제한과 429 동작은 변경하지 않는다.
- [x] 회귀 테스트와 Java 21 컴파일을 검증하고 develop 대상 PR로 게시한다.

프론트·서버 배포·독촉 정책 변경은 포함하지 않는다. null은 활성 쿨다운 없음만 뜻하며 독촉 권한/가능 상태 자체를 보장하지 않는다. 종료·대체·부분 입금 기록에서도 저장된 쿨다운은 동일 의미로 제공하며 버튼의 다른 상태 조건은 기존 계약을 따른다.

## 2. 설계와 외부 경계

기존 netting 도메인의 읽기 기능이며 상태 전이는 없다. `NettedTransferReminderStorePort`에 `findLastRemindedAt(Collection<UUID>)`를 추가하고 기존 Jdbc Adapter에서 IN 쿼리 한 번으로 Map을 반환한다. 새 Output Port/Adapter를 만들 필요가 없다.

`GroupNettingQueryService`가 접근 검사를 마친 결과의 송금 ID 전체를 읽고, Clock의 UTC 현재 시각과 기존 COOLDOWN 정책으로 결과를 계산한다. 목록은 모든 압축 건에 걸친 단일 일괄 조회를 사용한다. 기존 Result.Transfer → WebResponse.Transfer 변환에 필드를 추가하고 Swagger를 갱신한다.

Flyway migration required: **No**. 기존 `netted_transfer_reminders.last_at` 읽기만 추가한다. 테이블·Redis·환경변수·프로파일·인증·독촉 쓰기 로직은 바꾸지 않는다. 별도 Domain/Policy/Infra 구성은 N/A이며 기존 정책 상수를 재사용한다.

## 3. 영향 범위와 검증

- Input Result / Web Response: Transfer의 nullable `reminderNextAvailableAt` 추가. 기존 필드 유지.
- Output Port / Adapter: 실제 호출이 있는 배치 읽기 추가. 빈 입력은 SQL 생략.
- Query Service: 목록·상세의 접근 검사 뒤 배치 조회와 UTC 계산. 다른 사용자에게 독촉 권한을 부여하지 않는다.
- 문서: GroupNettingDocs 및 Transfer 스키마의 필드·UTC·null 의미 설명.
- 단위: 여러 압축의 여러 송금에 대한 조회 1회, 미기록/만료/경계/유효 기록, 빈 응답, 조회 권한 거부.
- 통합: 실제 PostgreSQL에서 독촉 후 쿨다운과 재독촉 429, 목록/상세 JSON·Swagger·만료/자동 기록 반영을 검증한다. 기존 압축 통합 회귀도 실행한다.

## 4. 진행

- [x] 기존 독촉 Writer·스토리지·조회·Web 경계 추적
- [x] 무마이그레이션 설계 및 조회 범위 확정
- [x] Port·Adapter·Result·Query·Response·Swagger 구현
- [x] 단위·실제 DB/HTTP 회귀 검증
- [x] diff·문서 확인 및 PR 게시

예상 커밋 경계: `feat(settlement): 압축 송금 독촉 쿨다운 종료 시각 제공`. 사용자 '#619도' 요청은 직전 #618의 수정·검증·PR 작업을 이어가는 것으로 해석한다.

## 5. 검증 결과

- Java 21 `compileJava` 성공, 관련 테스트 **68개 통과**(실패·오류·스킵 0): GroupNettingQueryServiceTest 6개 + GroupNettingDirectIntegrationTest 62개. 전체 저장소 테스트 결과는 아니다.
- 단위: 여러 압축의 송금 4건을 단 한 번에 조회, 미기록/만료/정확한 경계의 null, UTC 변환, 빈 목록/빈 송금 SQL 생략, 권한 거부 시 조회 없음.
- 실제 PostgreSQL·Redis·Batch·MockMvc: 독촉 접수 202 후 재독촉 429 유지, 상세·목록·current·알림/원본 기반 조회의 동일 종료 시각, 자동 주기로 예약한 공유 기록의 수동 COOLDOWN 반영, 만료 후 null, Swagger의 nullable·UTC·필수 키 검증. 기존 압축 통합 회귀도 함께 실행했다.
- 로컬 격리 PostgreSQL(54469, howmuch619)·Redis(63869)를 사용했다. 개발·운영 데이터에는 접근하지 않았다. 검증 로그: `build/qa619-verification.log`.
- `git diff --check` 통과. DB migration·인증·환경설정·프론트·서버 배포 변경 없음.
- `feat/619-netting-reminder-availability`를 커밋·푸시하고 develop 대상 PR로 게시한다.

실행 명령(위의 격리 DB/Redis 연결 환경에서):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava test \
  --tests '*GroupNettingQueryServiceTest' \
  --tests '*GroupNettingDirectIntegrationTest' --console=plain
```

프론트는 `transfers[].reminderNextAvailableAt`을 UTC로 해석하여 현재 시각보다 미래면 남은 시간을 표시한다. null이면 활성 쿨다운은 없지만 수취인 권한·입금 상태·검토/진행 상태 검사는 기존대로 필요하다.
