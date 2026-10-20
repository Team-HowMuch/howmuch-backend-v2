# Feature Plan: 중단된 영수증 정산 수정 계약 정렬

- Status: Completed
- Last updated: 2026-10-20
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/605
- 기준: origin/develop `60291ec2`

## 1. 목적과 완료 조건

생성자가 중단한 영수증 정산에서 영수증과 자기 품목을 수정한 뒤 참여자를 수정할 때 API마다 허용 상태가 달라 실패한다. 수정 중에는 `SUSPENDED`를 유지하고 재개는 수정 완료 후 별도 동작으로 통일한다.

- [x] 중단된 `ITEM_ALLOCATION` 정산의 생성자가 그룹·ID·연락처 후보를 조회하고 참여자를 추가·삭제한다.
- [x] 생성자가 중단 상태에서 자기 품목을 저장·삭제·제출해도 정산은 중단 상태를 유지하고 Share를 확정하지 않는다.
- [x] 기존 참여자 ID와 남은 참여자의 품목 선택을 유지하고, 변경된 구성에 맞춰 제출 이력과 미입금 Share를 무효화한다.
- [x] 확인된 입금이 있으면 수정과 무효화를 거부한다. 생성자 외 사용자의 중단 상태 변경, 생성자 참여자 삭제, 진행·완료·상계 중 정산의 인원 수정은 계속 거부한다.
- [x] 관련 회귀 테스트와 Java 컴파일을 통과하고 API 문서에 상태 계약을 명시한다.

범위 밖: 운영 배포, DB 변경, 더치페이 중단 상태 인원 추가·삭제.

연동 프론트: PR #367의 `feature/#363-settlement-edit` 최신 `29e7f697`에서 분리한 `feature/#363-suspended-receipt-edit` 브랜치. `useSettlementEditPage`의 임시 resume/suspend를 제거하고 각 단계에서 생성자·중단 상태를 재확인한다. 기존 참여자 초기값은 유지한다. 백엔드 적용 후 이 프론트 변경을 적용해야 하며, 서버만 바꾸고 기존 프론트의 자동 재개를 유지하면 이 흐름은 여전히 실패할 수 있다.

## 2. 도메인과 상태 규칙

기존 Settlement 도메인을 사용한다. 생성자 소유권은 기존 CurrentActorPort 및 Service에서 검사한다. Domain은 인원 수정 허용 상태(`DRAFT`, 또는 `SUSPENDED`+`ITEM_ALLOCATION`)를 검사한다. 입금 여부는 기존 SettlementRevisionGuard로 검사한다.

수정 흐름: `SUSPENDED` 영수증 교체 → 생성자 자기 품목 저장/제출 → 기존 참여자 조회 및 후보 조회 → 참여자 추가/삭제 → `SUSPENDED` 유지. 명시적인 재개 시 기존 `resume` 계약대로 `REQUESTED` 전환 및 제출 이력 초기화가 적용된다.

참여자 추가·삭제는 남은 참여자의 선택 데이터를 보존한다. 제거된 참여자의 선택은 기존 FK cascade로 제거하며, 참여자 삭제 전에 기존 Share를 제거해 FK를 보호한다. 변경 시 제출 이력을 초기화해 이전 구성의 확정을 재사용하지 않는다. 중단 상태의 생성자 선택 수정은 제출 후에도 허용하되 기존 제출과 Share를 초기화하고, 중단 상태 제출은 자동 입금 대기 전환을 하지 않는다.

## 3. 계약과 영향 범위

기존 경로·DTO·UseCase와 Output Port를 재사용한다.

- `GET /api/v2/settlements/{id}/participant-candidates/groups/{groupId}/members`, ID 검색·후보 아바타·연락처 매칭: 생성자, 편집 가능 상태, 중단 시 미입금 검사. 기존 참여자는 후보에서 제외되고 기존 참여자 목록 API에서 유지한다.
- 참여자 추가·삭제: 정산 행 잠금 후 검증하고 같은 트랜잭션에서 확정 정보 무효화 및 변경을 수행한다.
- 생성자 `my-items` 저장·삭제·제출: 정산 행 잠금, 기존 생성자 인증 유지, 중단 시 미입금 검사. 링크/일반 참여자 경로는 기존 `REQUESTED`·미제출 조건 유지.
- 영수증 교체: 기존 `DRAFT`·`SUSPENDED` 및 미입금 계약 유지.
- 오류: 기존 `SETTLEMENT_NOT_EDITABLE`, `SETTLEMENT_REVISION_PAYMENT_EXISTS`, 접근 거부 및 생성자 삭제 금지 오류 유지.

대상: Settlement, SettlementParticipantService, SettlementItemAllocationWriter, 관련 Swagger Docs와 테스트. 새 인터페이스나 설정은 추가하지 않는다.

## 4. 저장소·외부 경계

Flyway migration required: **No**. 기존 행·FK·삭제 Port로 처리하며 스키마·Redis·외부 API·프로파일·환경변수 변경은 없다. 입금 확인 후 Share 삭제와 수정은 정산 잠금이 걸린 기존 트랜잭션에서 수행한다.

## 5. 검증과 진행

- [x] inbound → Service → Domain/Port → persistence 및 FK 추적
- [x] 원인 확인과 상태 계약 설계
- [x] 성공·거부·다시 수정·제출 후 인원 수정 회귀 검증
- [x] compileJava 및 관련 테스트 실행
- [x] Swagger 상태 설명과 프론트 연동 순서 정리
- [x] diff와 문서 링크 확인

예상 커밋 경계: `fix(settlement): 중단된 영수증 정산 수정 상태 계약 통일`. 사용자 요청 전에는 커밋·푸시·PR 생성·배포하지 않는다.

## 6. 검증 결과

- Java 21 `compileJava` 성공. 관련 단위·도메인·실제 HTTP/PostgreSQL 통합 테스트 총 150개 통과(실패·오류·스킵 0). 전체 저장소 테스트 실행 결과는 아니다.
- 통합 테스트 44개에 영수증 교체 → 자기 품목 재수정·제출 → 후보 조회 → 참여자 추가·삭제 → 명시적 재개 흐름, 입금 이후 차단, 삭제 실패 트랜잭션 롤백, 비생성자 진입 차단을 포함한다.
- 테스트용 PostgreSQL(54465)과 Redis(63865)를 로컬 격리 실행했다. 운영·개발 DB에는 연결하지 않았다. 결과 로그: `build/qa605-verification.log`.
- 연동 프론트: `node --test docs/qa/suspended-receipt-edit.test.mjs` 17개 통과, `npm run typecheck` 통과. 실제 단말 UI 검증은 수행하지 않았다.
- API 설명에 중단 상태·입금 제한·선택 보존·제출 초기화 계약을 명시했다. 새 API/환경변수/마이그레이션은 없다.
- 사용자 요청에 따라 백엔드 `fix/605-suspended-receipt-edit`와 프론트 `feature/#363-suspended-receipt-edit` 브랜치로 PR을 게시한다. 운영 배포는 수행하지 않는다.
