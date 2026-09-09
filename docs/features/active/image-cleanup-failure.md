# 이미지 정리 실패 처리 (#305)

- 상태: 구현 중
- 기준: origin/develop 6e3197d

## 목적과 완료 조건
그룹·프로필 이미지 교체가 DB에 커밋된 뒤 이전 파일 삭제 실패로 오류 응답을 받는 문제를 수정한다. 커밋 성공을 유지하고 실패 파일을 로그로 추적하며, 롤백 시 새 파일만 정리한다.

## 도메인·계약·영향
기존 group/user 이미지 저장 Port와 UseCase를 재사용한다. Web DTO → Command → Service → Result → Web Response 흐름과 인증, Domain, DB, Redis, 설정은 변경하지 않는다. GroupImageTransactionSupport와 UpdateMyProfileService의 정리 경계 및 회귀 테스트가 대상이다. 그룹 생성·삭제도 기존 트랜잭션 지원을 사용한다.

- Flyway migration required: No (저장 구조 변경 없음)
- 신규 UseCase/Command/Result, Output Port, Domain/Policy, Adapter, Web/Swagger, Infra: N/A (기존 계약 유지)

## 설계와 시나리오
- 커밋: 이전 파일과 현재 파일이 다를 때만 이전 파일 삭제. RuntimeException을 격리하고 도메인, 파일명, 단계, 예외 유형을 경고 로그로 기록한다.
- 롤백: STATUS_ROLLED_BACK일 때 새 파일 삭제. 실패를 기록하며 원래 실패를 보존한다.
- STATUS_UNKNOWN: 커밋됐을 가능성이 있으므로 새 파일을 보존한다.
- 프로필 저장 실패 직후 보상 삭제도 예외를 격리한다.
- 트랜잭션 동기화가 없는 기존 직접 호출 경로에도 동일한 커밋 정리 규칙을 적용한다.
- 동일 파일·null 이전 파일은 삭제하지 않는다. 파일은 기존 UUID 생성 방식과 변경 잠금으로 보호된다.
- 자동 재시도/영속 큐, 동시 변경 정책, 호스트 권한, CODEF는 제외한다. 로그 기반 수동 후속 정리는 삭제 전 DB의 현재 참조를 재확인해야 한다. 로그 유실 시 추적할 수 있다는 보장은 없다.
- 실패 로그에 예외 메시지/스택을 넣지 않아 경로·민감값 노출을 피한다.

## 커밋 계획
1. fix(image): 이미지 정리 실패 격리와 추적 기록 (설계 문서 포함)
2. test(image): 커밋 실패 격리와 롤백 이미지 정리 회귀 검증 (완료 기록 포함)

## 검증 계획
DB·Redis를 사용하지 않는 Mockito 및 Spring 트랜잭션 동기화 테스트로 커밋 후 실패, 롤백, 불확실한 완료, 동일 파일 보호, 로그 기록을 검증한다. ./gradlew compileJava, 관련 테스트, git diff --check, 문서 경로 확인을 수행한다. 루트 AGENTS.md의 변경 동작 테스트 지침을 상세 workflow의 오래된 테스트 금지 문구보다 우선 적용한다.

## 진행
- [x] 요구사항·호출 흐름·마이그레이션 필요 여부 확인
- [ ] 구현·회귀 검증·문서 완료
