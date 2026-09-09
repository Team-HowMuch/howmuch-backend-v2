# 공통 코드 동시 생성 충돌 처리

- 상태: 완료
- 작성일: 2026-09-09
- 관련 이슈: #303

## 목적과 완료 조건

관리자의 동일 groupCode 생성 요청이 모두 미존재 검사를 통과한 경우, 늦은 저장이 먼저 커밋한 그룹을 수정하는 문제를 해결한다.

- 동일 코드 생성은 하나만 성공하고 나머지는 COMMON_CODE_GROUP_ALREADY_EXISTS(409)로 실패한다.
- 실패한 생성은 기존 그룹 속성·revision·상세 값과 활성 상태를 변경하지 않는다.
- 미존재 조회와 저장 사이에 다른 트랜잭션의 커밋을 삽입해 재현한다.

## 도메인과 시나리오

기존 commoncode 도메인과 관리자 생성/수정 UseCase를 유지한다. groupCode는 그룹의 유일 식별자이며 생성은 미존재→존재, 수정은 존재→변경이다. 관리자 권한 조회는 CurrentActorPort와 LoadUserPort를 그대로 사용한다.

정상 생성은 그룹과 상세 저장 후 커밋하고 캐시를 비운다. 경쟁 생성은 PK 충돌 시 전체 트랜잭션을 롤백하며 상세 저장과 캐시 무효화에 도달하지 않는다. 수정 대상이 없으면 생성하지 않고 COMMON_CODE_GROUP_NOT_FOUND로 실패한다.

## 계약과 설계

- HTTP POST/PUT `/api/v2/admin/common-codes/groups` 계열의 DTO→Command→UseCase→Result→Response 경계를 유지한다.
- CommonCodeAdminService가 SaveCommonCodeGroupPort.create/update를 명시적으로 호출한다.
- 동일한 저장 능력의 두 의도이므로 기존 Port에 두 메서드를 둔다. 새 Port·Policy·Domain 모델은 불필요하다.
- Adapter.create는 새 CommonCodeGroupJpaEntity를 saveAndFlush한다. 기존 Persistable.isNew가 persist를 보장하며 재조회/upsert를 하지 않는다.
- Adapter.update는 존재하는 Entity만 수정한다. 상세 병합·누락 상세 비활성화 및 커밋 후 캐시 무효화는 유지한다.
- 기존 common_code_group_pkey 오류 변환과 원인 예외 보존을 재사용한다.
- 사전 조회만으로는 경쟁을 막지 못하므로 기존 DB PK를 최종 경계로 사용한다.
- Flyway migration: **No**. 기존 PK·revision·Entity 매핑을 사용하고 스키마를 변경하지 않는다.
- Redis, 외부 API, 환경 설정, 보안 변경: 없음.

## 영향과 제외 범위

Application Service, SaveCommonCodeGroupPort, Persistence Adapter 및 관련 테스트가 변경된다. 생성/수정 API 형식, 권한 검사, 상세 비활성화, 캐시 TTL은 유지한다. 별도 F10 수정 revision 경쟁 보호, CODEF, 다른 도메인 정리는 제외한다. 구버전 서버의 생성 경로에는 문제가 남으므로 전환 시 해당 요청을 종료해야 한다.

## 구현 및 커밋 경계

1. `docs(commoncode): 동시 생성 충돌 처리 설계 기록`
2. `fix(commoncode): 생성과 수정 저장 의도 분리` — Port·Service·Adapter와 기존 테스트 계약 적응
3. `test(commoncode): 동시 생성 덮어쓰기 회귀 검증` — 실제 PostgreSQL 교차 실행 및 경계 검증

UseCase/Command/Result, Domain, Policy, Web DTO, Infra, migration 구현은 변경 필요가 없어 N/A이다.

## 검증 계획

격리 PostgreSQL에서 실제 JPA 저장 경계로 경쟁을 검증한다. 두 요청이 미존재 조회를 완료한 후 A 커밋→B 저장을 강제하고, 실패 상태와 원본 그룹·상세를 새 트랜잭션으로 확인한다. 정상 수정 및 기존 캐시 회귀도 실행한다. 단위 테스트는 외부 서비스가 필요 없다.

- `./gradlew compileJava`
- 공통 코드 Service·Adapter·캐시·Controller 및 새 통합 테스트
- `git diff --check`, `git status --short --untracked-files=all`

## 진행 및 결정 기록

- 2026-09-09: develop과 #303, 기존 PR 컨벤션 확인. 기존 Persistable/PK를 활용해 schema 변경 없이 해결한다.
- 2026-09-09: 구현과 실제 DB 교차 실행 회귀 검증 완료.

## 검증 결과와 완료 기록

- `./gradlew compileJava`: 통과.
- 격리 PostgreSQL 18(15433)·Redis(16380) 및 Java 21에서 `bash scripts/test.sh compileJava test --tests '*CommonCode*'`: Java 18개(신규 6개), JavaScript 31개 통과.
- 선행 커밋 이후 지연 생성(상세 유무), 실제 두 스레드 동시 INSERT, 중복 생성의 상세 덮어쓰기 차단, 정상 수정·누락 상세 비활성화, 미존재 수정 거부를 검증했다.
- 기존 upsert 동작을 임시 복원하면 선행 커밋 2건과 상세 덮어쓰기 1건이 실패했다. 수정 코드를 복원한 최종 테스트는 모두 통과했다.
- 테스트 작성 중 타입 추론 오류와 정리 SQL 테이블명 오류를 수정한 뒤 최종 재실행했다.
- `git diff --check` 통과. 문서 내 관련 로컬 경로와 계약을 확인했다.
- 전체 Java 테스트·별도 실제 HTTP 요청은 실행하지 않았다. 관련 Service/Adapter 및 Controller 단위 테스트 범위로 검증했다.
- 완료 조건: 단일 생성 성공, 409 충돌, 기존 그룹·revision·상세 보존 모두 확인.
- 잔여 범위: F10 수정 revision 경쟁 보호는 별도 이슈이며 이번에 변경하지 않았다. 구버전 생성 요청 종료 후 전환이 필요하다.
