# 코드 품질 리뷰: #456 생성자만 제출한 품목 정산의 수동 중단 허용

## 범위와 확인 근거

- 브랜치: `fix/456-settlement-creator-suspend` (`origin/develop` 기준 작업 트리 diff)
- 목표: 생성자 제출만 있을 때 `REQUESTED -> SUSPENDED`를 허용하고, 생성자 외 참여자 제출은 계속 `SETTLEMENT_NOT_SUSPENDABLE`로 차단한다.
- 확인한 변경: `SettlementService`, `SettlementSuspensionPolicy`, OpenAPI 문서, Service/통합/OpenAPI 테스트, 기능 문서.
- 독립 실행: `scripts/test.sh test --console=plain`으로 Service·OpenAPI·품목 배분 통합·할인 테스트를 일회용 PostgreSQL·Redis에서 실행 — 성공 (2026-10-01).
- `git diff --check` — 성공.

## 확인 결과

- `SettlementService.suspend`는 정산 행을 `findByIdForUpdate`로 잠근 뒤 Domain 상태 전이를 먼저 실행해 기존 상태 오류를 유지한다. 제출 writer도 같은 행을 잠그므로 제출과 중단의 판정은 직렬화된다.
- 생성자 참여자 ID가 없으면 빈 집합으로 비교해 모든 제출을 생성자 외 제출로 취급한다. 현재 구현은 fail-closed다.
- 배분 완료 판정은 변경되지 않았다. `SettlementItemAllocationWriter`는 여전히 `submissions.size() < participants.size()`로 생성자 제출을 전체 제출 수에 포함한다.
- OpenAPI 설명은 새 차단 조건과 409 오류를 반영한다.

## Skill-perspective check

`remove-ai-slops` 및 `programming` Skill은 현재 사용 가능한 Skill 목록과 로컬 Skill 경로에서 찾을 수 없어 로드하지 못했다. 제공된 역할 기준으로 대체 점검했다. 변경된 production code는 목표에 필요한 기존 participant Port 조회와 단일 비교만 추가했으며 불필요한 parsing/normalization, interface, abstraction, untyped escape hatch는 없다. 테스트도 구현 상수만 미러링하거나 삭제만 확인하는 형태는 아니다.

## Findings

### CRITICAL

없음.

### HIGH

없음.

### MEDIUM

1. **Fail-closed 경계가 테스트로 고정되어 있지 않음.** `src/main/java/com/amateur/howmuchbackend/application/settlement/service/SettlementService.java:123-130`은 생성자 participant를 찾지 못할 때 빈 집합으로 비교하므로 올바르게 차단한다. 하지만 `src/test/java/com/amateur/howmuchbackend/application/settlement/service/SettlementServiceTest.java:215-269`은 생성자 participant가 존재하는 두 정상 경로만 검사한다. `findAllBySettlementId`가 생성자와 연결된 participant를 반환하지 않고 submission은 존재하는 경우 `SETTLEMENT_NOT_SUSPENDABLE` 및 저장 미호출을 검증하는 Service 테스트를 추가해야 한다. 이 안전 기본값이 이후 단순화 과정에서 fail-open으로 바뀌는 회귀를 막는다.

2. **기능 문서의 완료 상태와 증적 상태가 서로 충돌함.** 추적되지 않은 `docs/features/active/settlement-suspend-creator-submission.md:3,20-23,168-173,201-212`는 `In progress`, 미완료 체크박스, `Pending/N/A` 증적을 유지한다. 반면 변경된 `docs/features/completed/settlement-suspend-submission.md:1-15,116-125`는 #456을 완료로 기록한다. 완료 시 active plan을 completed로 정리하고 실행한 검증 결과를 기록하거나, 완료 문서의 #456 완료 표기를 실제 완료 시점까지 보류해야 한다.

### LOW

없음.

## Finding status after remediation

- MEDIUM 1: 생성자 participant 누락 회귀 테스트를 추가했고, 후속 전체 테스트에서 통과했다.
- MEDIUM 2: #456 Feature Plan을 완료 폴더로 옮기고 체크리스트·검증 결과를 일치시켰다.
- Post-review `scripts/test.sh clean test`: Java 1402건 및 JavaScript 35건 통과; 실패·오류·건너뜀 0건.

## 결론

- Initial `codeQualityStatus`: WATCH (MEDIUM findings 2건).
- Final recommendation after remediation: APPROVE.
- Remaining blockers/findings: 없음.
