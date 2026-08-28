# 기능 계획: group 브랜치 아키텍처 가이드 이식

- 상태: 완료
- 최종 수정일: 2026-08-28
- 관련 ADR: [ADR-0001 목적 중심 Port와 Application Policy](../../adr/0001-purpose-oriented-ports.md), [ADR-0002 Framework-neutral StatusCode](../../adr/0002-framework-neutral-status-code.md), [ADR-0003 Capability 중심 Output Port](../../adr/0003-capability-oriented-output-ports.md)

## 1. 목적

`feature/#7-group`에서 검토된 헥사고날 작성 규칙이 현재 `feature/#20-account`에 없어 같은 역할의 클래스와 Port가 서로 다른 기준으로 추가될 수 있다. group 구현 코드를 합치지 않고 문서 규칙만 현재 코드와 Flyway 설정에 맞춰 이식한다.

## 2. 성공 기준

- [x] 컴포넌트별 위치, 책임, 의존성과 최소 작성 형태를 한 문서에서 확인할 수 있다.
- [x] `Web Request -> Command -> UseCase -> Result -> Web Response` 목표 경계를 기록한다.
- [x] Output Port를 구현 기술이 아니라 실제 Application capability로 명명한다.
- [x] 현재 코드에 남은 `request`/`response`, Repository Port, Domain `HttpStatus` 의존을 완료된 것으로 숨기지 않고 전환 항목으로 기록한다.
- [x] 현재 Flyway 및 `ddl-auto=validate` 원칙을 유지한다.

## 3. 범위 제외

- group 도메인 구현 코드 병합
- 현재 인증·사용자·결제수단 계약과 패키지의 일괄 리팩터링
- `StatusCode` 구현 변경
- README 변경: group 브랜치에 해당 변경이 없음

## 4. 반영 문서

- `AGENTS.md`
- `docs/architecture.md`
- `docs/coding-guidelines.md`
- `docs/development-workflow.md`
- `docs/templates/feature-plan.md`
- `docs/adr/0001`~`0003`

## 5. 결정

- group 브랜치 문서를 그대로 덮지 않고 현재 코드에 없는 구현은 목표 구조 또는 전환 항목으로 표현한다.
- 새 코드에는 목표 구조를 적용하되 기존 코드 이동은 별도 Feature Plan이 있는 경우에만 수행한다.
- 실제 caller와 외부 경계가 없는 Port, Mapper, Config, Factory는 만들지 않는다.

## 6. 검증

```bash
./gradlew compileJava
git diff --check
git status --short --untracked-files=all
```

- Markdown 상대 링크 검사
- group 브랜치 문서 커밋 `acf838f`, `7696f0d`와 반영 범위 대조

## 7. 완료 기록

- 완료일: 2026-08-20
- 결과: group 브랜치의 아키텍처 문서 세트를 현재 브랜치 상태에 맞게 이식했다.
- 남은 작업: ADR-0001과 ADR-0002의 코드 전환은 해당 기능이 명시적으로 요청될 때 별도 범위로 수행한다.
