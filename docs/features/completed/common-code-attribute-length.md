# Feature Plan: 공통 코드 속성 길이 통일

- 상태: 완료
- 갱신일: 2026-09-09
- 관련 이슈: #304

## 1. 목적과 성공 기준

V19에서 255자로 확장된 상세 속성을 관리자가 추가·수정할 때 Web 검증과 화면의 100자 제한이 차단한다. attr1~3 모두 100·101·255자를 허용하고 256자는 HTTP 400으로 거절하며, 저장된 값이 조회·수정 과정에서 보존되어야 한다.

## 2. 도메인과 시나리오

기존 commoncode 도메인을 사용한다. 관리자는 그룹 생성 후 PUT으로 새 상세를 추가하거나 기존 상세를 수정한다. POST 그룹 생성에는 상세 값이 없다. 속성은 선택 값이며 null·빈 문자열 처리를 유지한다. 그룹명·상세명과 속성 이름의 길이, 관리자 권한, revision 충돌 처리는 변경하지 않는다.

## 3. 계약과 영향 범위

- `CommonCodeDetailWebRequest` → `CommonCodeDetailCommand` → `UpdateCommonCodeGroupUseCase` → `CommonCodeAdminService`의 기존 흐름 유지.
- `SaveCommonCodeGroupPort` / `LoadCommonCodeGroupPort` → `CommonCodePersistenceAdapter` → JPA/DB, 결과 → Web Response 흐름 유지.
- `CommonCodeDetailWebRequest`의 세 `@Size`와 `CommonCodeJpaEntity`의 세 `@Column`을 255로 맞춘다.
- 관리자 `common-codes.js`의 신규·기존 행 maxlength와 HTML 안내, Swagger 설명을 갱신한다.
- Flyway migration required: **No**. 기존 `V19__extend_common_code_attr_length.sql`이 세 컬럼을 VARCHAR(255)로 정의한다. SQL 수정·추가는 없다.
- UseCase/Command/Result, Port, Domain, Policy, Service, Redis, 보안, Infra 변경: N/A. 기존 계약으로 충분하다.

## 4. 설계 결정

DB 계약에 맞춰 기존 경계의 길이 선언만 수정한다. 계층 간 공용 상수나 새로운 추상화는 만들지 않는다. Java 및 브라우저의 기존 문자열 길이 계산 방식을 유지한다.

## 5. 구현 및 커밋 계획

1. `docs(commoncode): 속성 길이 통일 설계 기록`
2. `fix(commoncode): 상세 속성 입력과 매핑 길이를 255자로 통일`
3. `test(commoncode): 속성 길이 경계와 저장값 보존 검증`

- [x] API·JPA·화면·설명 수정
- [x] 경계 및 기존 값 조회·수정 회귀 검증
- [x] 결과 기록 및 완료 문서 이동

## 6. 검증 계획

- `./gradlew compileJava`
- 공통 코드 Java 테스트: MockMvc의 PUT 중첩 검증 및 Command 전달, 실제 PostgreSQL 신규 상세 저장·재조회·수정, JPA/DB 길이 계약.
- `node --test src/test/js/common-code-admin.test.cjs`: 신규·기존 행 길이 제한과 기존 속성 표시 보존.
- `git diff --check`, 작업 트리 확인, 문서 참조 경로 확인.
- PostgreSQL/Redis 로컬 포트 확인 완료. 테스트는 local 프로필을 사용한다. 전체 테스트 및 실제 브라우저 수동 검증은 별도 수행 여부를 기록한다.

## 7. 진행 및 완료 기록

- 2026-09-09: 최신 origin/develop에서 fix/common-code-attribute-length 생성. V19 및 생성·수정 흐름 확인.
- 검증 결과: `./gradlew compileJava` 통과. 공통 코드 Java 37개(신규 10개), JavaScript 35개(신규 4개) 모두 통과.
- 실제 PostgreSQL에서 100·101·255자 상세 추가, 영속성 컨텍스트 초기화 후 재조회·수정 및 세 컬럼/JPA의 255자 계약을 확인했다. MockMvc는 각 속성 256자 요청의 400 및 저장 호출 차단을 확인했다.
- 초기 local 실행은 기존 DB의 V16 체크섬 불일치로 실패했고, 격리 스키마 실행은 Discord 설정 누락으로 실패했다. 아래 설정으로 재실행하여 통과했다. 기존 DB 이력은 수정하지 않았다.

```bash
DB_URL='jdbc:postgresql://localhost:5432/howmuch?currentSchema=issue304_attribute_test' \
SPRING_FLYWAY_SCHEMAS=issue304_attribute_test \
DISCORD_SMS_WEBHOOK_URL='http://localhost:9/test-only' \
./gradlew test --tests '*commoncode*'
node --test src/test/js/common-code-admin.test.cjs
```

- 전체 Java 테스트, 실제 서버 HTTP 및 브라우저 수동 조작은 미실행. 이번 변경에 맞춘 MockMvc·실제 DB·DOM 대역 검증으로 범위를 한정했다. 브라우저 고유 입력 동작은 수동 확인 범위로 남는다.
- `git diff --check` 및 참조 파일 존재 확인 통과. V19 SQL 변경 없음.
- 완료일: 2026-09-09.
