# Feature Plan: Mock 송금 링크 입금자명 자동 입력

- Status: In progress
- Last updated: 2026-10-21
- Related PR: 후속 기록

## 1. 목적과 성공 기준

현재 Mock 앱은 `senderName`을 받으면 자동 표시하지만 서버 링크에는 해당 값이 없어 직접 이름을 입력해야 한다. 일반 정산의 회원 본인 경로와 링크 참여 경로에서 선택된 정산 참여자의 `displayName`을 전달해 Mock 출금계좌 예금주와 관계없이 올바른 입금자명을 미리 채운다.

- [ ] 회원과 비회원 링크 모두 해당 정산·참여자에서 이름을 조회한다.
- [ ] `mock2` 앱 URI 및 기존 `mock` 웹 URL의 `senderName`이 한글/공백/특수문자를 보존한다.
- [ ] 실제 Toss adapter와 기존 네 인자 호출 결과는 유지한다.
- [ ] 완납 시 링크·추가 이름 조회가 없고 누락된 참여자는 불완전한 링크를 반환하지 않는다.
- [ ] 관련 테스트, compileJava, dev 배포와 런타임 확인을 완료한다.

## 2. 도메인과 경계

기존 settlement 도메인을 사용한다. 입금 매칭은 정산 참여자 이름을 사용하므로 로그인 프로필이나 Mock 예금주 대신 `SettlementParticipant.displayName`을 기준으로 한다. 인증·참여 문맥 검증은 기존 Application Service가 수행하며 Reader는 검증된 settlementId/participantId 쌍으로 기존 LoadSettlementParticipantPort를 조회한다. 정산/입금 상태 변경은 없다. 상계 송금은 별도 reference 매칭 계약을 사용하므로 이번 일반 정산 이름 변경에 포함하지 않는다.

## 3. 계약과 설계

- 기존 Input Port, Web DTO와 응답 필드는 유지한다. 반환 URL에 선택적인 `senderName`만 추가한다.
- SettlementTransferLinkPort에 senderName을 받는 5인자 기본 overload를 추가하고 기존 4인자로 위임한다. 실제 Toss는 기존 구현을 사용한다.
- MockSettlementTransferLinkAdapter는 5인자 overload를 구현하고 senderName을 UTF-8 URL 인코딩해 전달한다. 4인자는 이름 없이 기존 출력으로 위임한다.
- SettlementTransferInstructionReader가 잔액이 남은 경우에만 참여자 이름을 조회해 overload를 호출한다.
- 참여자가 없으면 기존 SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND 오류로 종료한다.
- Flyway migration required: No. DB/Redis/API schema, 환경변수와 프로필 변경 없음. `mock2` 설정 유지.
- APK 0.2.2는 이미 링크 senderName을 지원한다. APK/프런트 코드 변경 없음.

## 4. 영향과 구현 순서

1. Output Port overload와 Mock Adapter를 함께 변경한다.
2. Reader의 참여자 조회 및 기존 회원·링크 흐름 테스트를 갱신한다.
3. URI 인코딩, 이름 누락/완납/실제 Toss 호환성을 검증한다.
4. PR을 생성하고 dev에 배포한다. 기존 사용자 요청의 서버 PR·즉시 dev 배포 범위로 수행한다.
5. 실제 배포 버전·mock2·readiness 및 설치된 APK의 링크 이름 자동 표시를 확인한다. 원격 가상 송금은 실행하지 않는다.

N/A: 새 UseCase/Domain/Policy/Web DTO/Infra/마이그레이션. 기존 경계에 이름 전달만 추가한다.

예정 커밋: `fix(settlement): Mock 송금 링크에 정산 참여자 이름 자동 전달`

## 5. 검증

- compileJava 및 Mock/Toss adapter, 회원·링크 송금 안내, 설정 테스트.
- Reader→실제 Mock Adapter 통합 테스트로 참여자별 URL 이름 확인.
- `git diff --check`, 변경 파일만 검토.
- dev 배포 시 이전 버전/구성 백업, 변경 버전 및 readiness 확인. 기존 이미지 저장소 이슈가 재현되면 상태를 먼저 확인하고 검증 가능한 배포 방법을 사용한다.

## 6. 진행 및 결과

- 2026-10-21: 최신 origin/develop 기반 깨끗한 기존 작업 디렉터리에서 codex/mock-transfer-sender 분기. 무관한 원본 작업 디렉터리 변경은 유지한다. 구현 전 계약·마이그레이션 불필요 확인.
- 2026-10-21: compileJava, bootJar 및 관련 테스트 77개 통과(실패/오류/건너뜀 0). 회원/비회원 참여자 이름 전달, 실제 Mock Adapter의 앱/웹 URL 한글·특수문자 인코딩, 완납 시 이름 조회 생략, 누락 참여자 오류, 실제 Toss URL 호환성을 확인했다. `git diff --check` 통과.
- dev 배포 및 런타임 확인: 진행 중.
