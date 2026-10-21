# Feature Plan: 압축 송금 독촉 차단 사유 (#625)

- Status: Completed
- Last updated: 2026-10-21
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/625
- 기준: origin/develop `a7814fda`, branch `fix/625-netting-reminder-blocked-reason`

## 1. 목적과 범위

부분 입금·미해결 검토·비활성 압축의 송금 독촉을 앱이 응답만으로 구분하지 못하고, 거절 시 일반 정산의 품목 선택 문구가 노출된다. 모든 압축 조회의 transfers에 nullable `reminderBlockedReason`을 추가하고 동일 정책으로 독촉을 거절한다.

- [x] `NETTING_NOT_ACTIVE` / `DEPOSIT_REVIEW_OPEN` / `DEPOSIT_RECEIVED` / null을 목록·상세·current·알림/원본 기반 상세에 제공한다.
- [x] 기존 조건의 우선순서인 비활성 → 열린 검토 → 입금액 0 아님을 유지한다. 일부/전액 입금 모두 같은 입금 사유다.
- [x] 수동 독촉은 사유별 압축 전용 409 코드와 문구로 거절하고, 자동 독촉은 기존처럼 건너뛴다.
- [x] 조회와 잠금 후 쓰기가 동일한 Application Policy를 사용한다. 추가 DB 조회 없이 기존 읽기 값으로 계산한다.
- [x] 관련 테스트·컴파일·Swagger를 확인하고 develop 대상 PR로 게시한다.

프론트·서버 배포·독촉 정책 완화는 범위 밖이다. null은 세 차단 사유 없음만 뜻하며 수취인 권한, `reminderNextAvailableAt` 쿨다운 및 동시 재압축 진행 제한은 기존대로 별도 적용된다.

## 2. 설계와 계약

기존 netting 도메인·상태·조회/쓰기 Port를 재사용한다. `NettedTransferReminderPolicy`가 압축 상태, 열린 검토 수, 해당 송금의 확인액을 받아 공통 사유와 오류 코드 매핑을 제공한다. 기존 `NettingCompressionPolicy`와 같이 Result의 공개 Reason enum을 정책에서도 사용한다.

- Result.Transfer / WebResponse.Transfer: nullable `reminderBlockedReason` 추가. 공개 enum은 Result 내부의 `ReminderBlockedReason`.
- QueryService: 기존 반환 값을 조합하면서 공통 정책 호출. #619 쿨다운 일괄 조회는 유지하며 추가 I/O 없음.
- Writer: 기존 잠금·수취인 검사 뒤 최신 압축/송금 사실로 같은 정책 호출. 자동 독촉은 스킵, 수동은 사유별 새 NettingStatusCode를 반환한다.
- 오류(409): `NETTING_REMINDER_NOT_ACTIVE`, `NETTING_REMINDER_DEPOSIT_REVIEW_OPEN`, `NETTING_REMINDER_DEPOSIT_RECEIVED`.
- 비활성: 진행 중인 압축 정산의 송금만 독촉 가능. 검토: 입금 내역 검토 중이라 독촉 불가. 입금: 이미 입금이 확인된 압축 송금은 독촉 불가.
- 권한 오류와 `NETTING_PROCESSING`, 기존 쿨다운 429는 유지한다. 일반 정산의 오류 코드·문구는 바꾸지 않는다.

## 3. 저장소와 경계

Flyway migration required: **No**. 기존 상태와 확인액만 읽어 계산한다. DB·Redis·Output Port·Adapter·환경변수·프로파일·인증 변경 없음. 상태 전이와 외부 API 호출은 추가하지 않는다. stateless Application Policy를 Component로 사용하며 새 Infra Config는 만들지 않는다.

## 4. 검증 계획

- 공통 정책의 정상·각 차단·중첩 우선순위와 오류 매핑.
- Query의 이유 표시와 기존 쿨다운 값·일괄 조회 유지.
- Writer의 수동 오류 코드·문구, 자동 스킵·알림/쿨다운 미변경, 권한/재압축/쿨다운 회귀.
- 실제 PostgreSQL·Redis·Batch·MockMvc로 부분/전액 입금, 열린 검토, 완료/대체 압축의 조회·독촉 거절 일치와 Swagger enum·null 계약 확인.
- Java 21 compileJava, 관련 테스트, git diff --check. 격리 로컬 테스트만 사용한다.

## 5. 진행

- [x] 기존 조회·독촉 Writer·상태 코드·응답·테스트 추적
- [x] 무마이그레이션 계약과 공통 정책 설계
- [x] Result·Policy·Query·Writer·Web·Swagger 구현
- [x] 단위·실제 DB/HTTP 회귀 검증
- [x] 문서 정리·커밋·PR 게시

예상 커밋 경계: `fix(settlement): 압축 송금 독촉 차단 사유와 오류 계약 통일`.

## 6. 검증 결과

- Java 21 `compileJava` 성공. 관련 테스트 **105개 통과**(실패·오류·스킵 0): 공통 Policy 11개, Writer 15개, Query 13개, 기존/추가 압축 통합 66개. 전체 저장소 테스트는 실행하지 않았다.
- 공통 규칙·우선순위·전용 409 문구, 조회의 동일 사유, Writer 잠금 뒤 최신 상태 재검사, 자동 독촉 스킵, 수동/자동 간격·수취인 권한·재압축 진행 제한·기존 429를 검증했다.
- 실제 PostgreSQL·Redis·Batch·MockMvc로 부분 입금, 완납에 따른 완료, 미해결 입금 검토, 재압축에 따른 대체 상태를 생성했다. 목록/상세 사유와 수동 독촉의 오류 코드·문구 일치, 차단 시 독촉 기록 미생성, Swagger 필수 nullable enum 계약을 확인했다.
- 기존 #619 테스트의 ISO 소수초 표기 비교를 수정했다. `.539100`과 `.5391` 같은 동일 시각에 대한 문자열 오탐을 피하도록 응답과 같은 ISO_LOCAL_DATE_TIME을 사용하며 운영 응답은 변경하지 않았다.
- 로컬 격리 PostgreSQL(54475, howmuch625)·Redis(63875)에서 실행했다. 개발·운영 데이터에는 접근하지 않았다. 검증 로그: `build/qa625-verification.log`.
- `git diff --check` 통과. DB 스키마·환경설정·프론트·서버 배포 변경 없음.
- 사용자 요청에 따라 `fix/625-netting-reminder-blocked-reason`을 커밋·푸시하고 develop 대상 PR로 게시한다.

실행 명령(위 격리 DB/Redis 연결 환경에서):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava test \
  --tests '*NettedTransferReminderPolicyTest' \
  --tests '*NettedTransferReminderWriterTest' \
  --tests '*GroupNettingQueryServiceTest' \
  --tests '*GroupNettingDirectIntegrationTest' --console=plain
```

프론트는 `transfers[].reminderBlockedReason`이 null이 아니면 해당 사유로 독촉을 차단한다. null인 경우에도 수취인 여부와 `reminderNextAvailableAt`, 재압축 진행 제한을 기존대로 함께 적용해야 한다.
