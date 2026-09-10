# Feature Plan: 회원가입 생년월일 저장

- Status: In progress
- Last updated: 2026-09-10
- Related issue: https://github.com/Team-HowMuch/howmuch-backend-v2/issues/349
- Related ADR: N/A

## 1. Purpose

### Problem

현재 `POST /api/v2/auth/signup`은 생년월일을 받지 않아 신규 사용자의 `users.birth_date`가 항상 `NULL`로 저장된다. 이미 DB 컬럼과 Domain/JPA 매핑은 존재하므로 회원가입 계약의 전달 경로만 완성한다.

### Why now

회원가입 단계에서 수집한 생년월일을 이후 프로필 조회와 은행 연동에서 사용할 수 있어야 한다.

### Success criteria

- [ ] 유효한 `birthDate`가 회원가입 성공 후 `users.birth_date`에 저장된다.
- [ ] 누락·잘못된 날짜는 HTTP 400으로 거부된다.
- [ ] 인증 계약 로그에 생년월일 원문이 노출되지 않는다.

### Out of scope

- 기존 사용자 데이터 백필
- `users.birth_date` `NOT NULL` 제약 추가
- 생년월일 수정 API 및 연령 제한
- 회원가입 Response 변경

## 2. Domain analysis

- Existing domain to reuse: `auth`, `user`
- New domain: N/A
- Relationship: 회원가입이 `user.birthDate`를 초기화하고, 기존 프로필·은행 연동이 이를 읽는다.

## 3. Scenarios

### Normal flow

1. 클라이언트가 `birthDate`를 `yyyy-MM-dd` 문자열로 제출한다.
2. Web DTO가 `LocalDate`로 역직렬화하고 `SignUpCommand`로 전달한다.
3. `SignUpService`가 `User.register()`에 전달한다.
4. 기존 `UserJpaEntity`와 `UserMapper`를 통해 `users.birth_date`에 저장한다.

### Failure flow

- 필드 누락 또는 null: 기존 `INVALID_ARGUMENT`, HTTP 400
- 유효하지 않은 날짜: 기존 `INVALID_ARGUMENT`, HTTP 400

## 4. Contracts

```text
SignUpRequest(challengeId, name, birthDate, pin, pinConfirmation,
              requiredTermsAgreed, marketingAgreed)
  -> SignUpCommand(same fields)
  -> SignUpUseCase
  -> User.register(..., birthDate, ...)
  -> users.birth_date
```

`birthDate`는 필수 `LocalDate`이며 JSON 형식은 `yyyy-MM-dd`다. 연령 및 미래 날짜 제한은 추가하지 않는다.

### Web API

```text
POST /api/v2/auth/signup
Request: birthDate required, string/date, example 1995-03-14
Response: existing ApiResponse<Void>
Authentication: SMS challenge required; no token issued
```

## 5. Persistence and integrations

- `users.birth_date`는 V1부터 nullable로 존재한다.
- `UserJpaEntity`와 `UserMapper`는 이미 `birthDate`를 매핑하므로 변경하지 않는다.
- Flyway migration required: No
- Redis, external API, environment variable: 변경 없음

## 6. Implementation plan

- [x] Issue #349 and feature branch
- [x] Feature Plan
- [ ] `SignUpRequest`, `SignUpCommand`, `SignUpService`, `User.register()` contract wiring
- [ ] Request/Command `toString()` masking
- [ ] Existing unit and persistence regression tests
- [ ] Compile, focused tests, API/OpenAPI verification
- [ ] Commit, push, and PR to `develop`

### Expected commit boundaries

1. `docs(auth): 회원가입 생년월일 저장 계획 수립`
2. `feat(auth): 회원가입 시 생년월일 저장`

## 7. Verification

```bash
./gradlew compileJava
./gradlew test --tests '*SignUpServiceTest' --tests '*AuthRecordMaskingTest' --tests '*LoginProfilePersistenceIntegrationTest'
git diff --check
git status --short --untracked-files=all
```

Acceptance requires a successful signup with `"birthDate":"1995-03-14"`, a matching `users.birth_date` row, HTTP 400 for missing/invalid input, and OpenAPI required `string/date` output.
