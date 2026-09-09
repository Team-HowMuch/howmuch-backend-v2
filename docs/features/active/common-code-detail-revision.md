# 공통 코드 상세 변경 revision 충돌 보호

- 상태: 진행 중
- 작성일: 2026-09-09
- 관련 이슈: #302

## 목적과 완료 조건

같은 revision을 조회한 관리자가 그룹 메타데이터를 유지하고 상세만 수정하면 그룹의 dirty checking이 발생하지 않아 후속 요청이 최신 상세를 덮어쓸 수 있다.

- 상세 추가·수정·명시적 비활성화·누락 비활성화 모두 그룹 revision을 증가시킨다.
- 이전 revision 요청과 동일 revision의 동시 수정은 CONCURRENT_UPDATE(409)로 거부하고 승자의 상세를 보존한다.
- 응답 revision은 커밋된 revision과 같고 실패한 저장은 그룹·상세 변경을 함께 롤백한다.

## 도메인과 계약

기존 commoncode 도메인에서 그룹과 상세 전체를 한 편집 단위로 취급한다. 관리자 권한 확인과 CurrentActorPort/LoadUserPort 조회는 그대로 유지한다.

`PUT /api/v2/admin/common-codes/groups/{groupCode}`의 Web Request → UpdateCommonCodeGroupCommand → UpdateCommonCodeGroupUseCase → CommonCodeGroupResult → Web Response 흐름 및 SaveCommonCodeGroupPort.update 계약을 유지한다. 새로운 UseCase·Port·Policy·Domain·Web·Infra 구성은 N/A이다.

## 설계와 시나리오

Service의 요청 revision 비교에 더해 Adapter에서 저장 대상 revision을 비교한다. 상세 쓰기 전에 EntityManager.lock의 PESSIMISTIC_FORCE_INCREMENT로 DB의 그룹 버전을 즉시 검사·증가시키고 트랜잭션 종료까지 행 잠금을 유지한다. JPA OptimisticLockException은 원인을 보존해 기존 CONCURRENT_UPDATE로 변환한다.

상세만 변경해도 버전 갱신이 일어나며, 두 트랜잭션이 같은 그룹을 읽었다면 버전 조건부 갱신에서 하나만 성공한다. 이후 상세 저장 실패 시 버전 증가도 롤백된다. 결과 조립 전에 증가가 반영되므로 응답에 이전 revision이 남지 않는다. 메타데이터도 변경되면 JPA flush에서 추가 버전 증가가 가능하며 revision은 연속 번호가 아닌 충돌 토큰으로 사용한다.

OPTIMISTIC_FORCE_INCREMENT는 커밋 시점까지 증가가 지연될 수 있어 응답 revision과 상세 쓰기 전 충돌 확인 요구에 맞지 않는다. 수정 시각을 임의 변경해 dirty checking을 유도하는 방식도 시간 정밀도에 의존하므로 사용하지 않는다.

- Flyway migration: **No**. 기존 revision 컬럼과 @Version 매핑만 사용한다.
- 영향: CommonCodePersistenceAdapter, 기존 생성자 테스트 구성, 실제 JPA 회귀 테스트.
- 유지: 생성 충돌 처리, 상세 병합과 비활성화, 커밋 후 캐시 무효화, API·보안·환경 설정.
- 제외: 생성 충돌 #303, CODEF, 다른 도메인 변경.
- 한계: 같은 그룹의 수정은 DB 행 잠금에서 직렬화된다. 배포 전환 시 구버전 수정 요청을 종료해야 한다.

## 구현 및 커밋 경계

1. `docs(commoncode): 상세 revision 충돌 보호 설계 기록`
2. `fix(commoncode): 상세 저장 전 그룹 revision 충돌 보호` — Adapter와 기존 테스트 구성 적응
3. `test(commoncode): 상세 수정 revision과 트랜잭션 경합 검증` — 실제 PostgreSQL 회귀 테스트와 완료 기록

## 검증 계획

격리 PostgreSQL·Redis와 Java 21을 사용한다. 상세 변경 유형별 revision 증가와 응답 일치, 이전 요청 거절, 두 트랜잭션의 조회 후 동시 저장, 조회 이후 다른 커밋이 발생한 지연 저장, 전체 롤백을 확인한다. Service 경유 테스트는 관리자 조회만 대체하고 공통 코드 읽기·쓰기는 실제 Adapter와 JPA를 사용한다.

- `./gradlew compileJava`
- `bash scripts/test.sh compileJava test --tests '*CommonCode*'`
- 기존 구현에서 회귀 실패 확인 후 수정본 재검증
- `git diff --check`, `git status --short --untracked-files=all`

## 진행 기록

- 2026-09-09: #302와 최근 PR 컨벤션, develop의 생성 충돌 수정, 실제 Service→Adapter→JPA 경계 확인. 스키마 변경 없이 구현한다.

## 완료 기록

- 검증 결과: 진행 중
- 미검증 범위: 전체 Java 테스트 및 별도 실제 HTTP 요청
