# Feature Plan: 더치페이 0원 참여자 허용

- Status: In progress
- Last updated: 2026-10-19
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/583
- Related ADR: N/A — 기존 정산 경계를 유지한다.

## 1. 목적과 성공 기준

몰아주기 결과를 저장한 뒤 생성자 또는 다른 참여자의 배분이 0원이어도 정산을 요청·재개할 수 있어야 한다.

- [ ] 전원 배분과 총액 일치를 검증하고 양수 배분만 Share로 확정한다.
- [ ] 0원 참여 기록은 보존하고 정산 요청 알림·납부 진행 인원에서 제외한다.
- [ ] 확정된 더치페이의 0원 참여자는 PAY 목록과 총무의 낼 정산에서 제외한다.
- [ ] 참여 링크·상세에는 0원을 명시하고 송금 대상이 되지 않는다.
- [ ] 양수 Share 입금으로 기존 완료 판정이 정상 작동한다.

범위 밖: 게임 구현, 품목 정산의 배분·진행률 규칙 변경, 반복 정산의 양수 배분 규칙 변경, 입금 확인 알고리즘 변경.

## 2. 도메인과 시나리오

기존 settlement 도메인을 사용한다. 참여자는 참여 기록이며 Share는 확정된 양수 납부액이다.
생성자는 배분 저장 후 request 또는 resume를 호출한다. 합계가 총액과 같으면 DRAFT/SUSPENDED → AWAITING_DEPOSIT으로 전환한다.
0원은 Share를 만들지 않으며 생성자 몫이 0원이어도 동일하다. 음수는 `SettlementDutchPayAllocation`에서 계속 거절한다.
누락·중복·다른 정산 배분·총액 불일치·오버플로 거절은 유지한다. 생성자만 양수인 경우도 요청을 허용하며 기존 입금 확인 경로의 완료 판정을 유지한다.

## 3. 계약과 설계

- 기존 `RequestSettlementUseCase`, `ResumeSettlementUseCase`, 목록·상세·링크 UseCase 및 Command/Result 형태를 재사용한다.
- 기존 `SaveSettlementRequestPort`, Share/참여자 조회 Port, `LoadSettlementPort`, 알림 기록을 재사용한다. 새 Port는 없다.
- `SettlementRequestPolicy`: 전원 배분·총액 검증 후 양수 Share만 생성한다.
- `SettlementRequestWriter`: 더치페이는 생성된 Share의 참여자 중 연결된 회원에게만 요청 알림을 기록한다. 품목 정산은 기존 수신 대상을 유지한다.
- 목록 SQL과 상세 summary: 확정된 더치페이(AWAITING_DEPOSIT/COMPLETED/NETTING_IN_PROGRESS)는 Share 있는 비생성자만 납부 인원에 포함한다.
- 확정된 더치페이는 Share 없는 참여자를 PAY 및 총무 PAY 조회에서 제외한다. RECEIVE의 전체 참여 인원은 보존한다.
- 상세 참여 기록은 0원/PAID(추가 납부 없음)로 표시하되 납부 완료 인원에는 산입하지 않는다. 링크의 해당 참여자 amount도 0이다. 기존 송금 조회는 Share 부재로 거절한다.
- SUSPENDED 편집 중 Share가 제거된 상태는 미확정으로 유지한다. 재개 시 다시 확정하며 납부 인원을 갱신한다.
- HTTP 경로·필드·인증은 유지하고 request/resume·목록·상세·링크 OpenAPI 설명을 갱신한다.
- 더 이상 사용하지 않는 `SETTLEMENT_SHARE_AMOUNT_REQUIRED`를 제거한다. `INVALID_SETTLEMENT_SHARE_AMOUNT`와 Share 양수 불변식은 유지한다.

### 저장소 및 외부 연동

- Flyway migration required: **No**. 0원 배분은 기존 스키마가 허용하며 Share는 기존처럼 양수만 저장한다. 기존 컬럼·제약 변경 없이 조회 SQL만 조정한다.
- Migration pull request: N/A.
- Redis/설정/보안/외부 API 변경: N/A. 기존 알림 기록·발송 경로의 수신 대상만 좁힌다.

## 4. 영향 범위와 구현 순서

1. 요청 Policy/Writer와 단위 테스트, 재개 통합 테스트.
2. 목록·총무 SQL, 상세/링크 응답과 관련 회귀 테스트.
3. 실제 PostgreSQL 요청→Share/알림/조회→입금 완료 검증과 API 설명·기존 배분 문서 갱신.

Domain 상태/불변식, UseCase/Command/Result 구조, 새 Output Port, Infra/설정/Bean 변경은 N/A다.

### 커밋 경계

- `feat(settlement): 더치페이 0원 참여자의 요청과 재개 허용`
- `fix(settlement): 0원 참여자의 납부 목록과 진행 수치 정리`
- `docs(settlement): 더치페이 0원 참여 계약과 검증 결과 정리`

## 5. 검증

- Java 21 `./gradlew compileJava` 및 관련 단위 테스트.
- 폐기 가능한 로컬 PostgreSQL/Redis를 준비한 뒤 `scripts/test.sh test --tests ...`로 요청·재개·목록·상세·링크·입금 회귀를 검증한다. 애플리케이션 `.env`는 읽지 않는다.
- 정산 Share/배분 Domain의 음수·0원 불변식 테스트를 유지한다.
- `git diff --check`, 변경 문서의 상대 링크/경로 확인, `git status --short --untracked-files=all`.
- 결과: 검증 후 기록한다.

## 6. 결정 및 완료 기록

- 2026-10-19: 별도 allowZeroShares 옵션 없이 일반 더치페이에서 0원 허용. 참여자를 삭제하지 않고 납부 의무만 제외한다.
- 2026-10-19: 품목 정산과 중단 편집 중 미확정 표시를 유지하기 위해 확정 상태의 더치페이에만 조회 필터를 적용한다.
- 완료일/미검증 항목/잔여 위험: 검증 후 기록한다.
