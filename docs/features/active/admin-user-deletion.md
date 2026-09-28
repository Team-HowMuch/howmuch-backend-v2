# 기능 계획: 관리자 대리 계정 삭제

- 상태: 구현 및 로컬 검증 완료 (2026-09-28)
- 이슈: [#444](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/444)
- 선행 migration: [#445](https://github.com/Team-HowMuch/howmuch-backend-v2/pull/445) (V41, `develop` 병합 완료)
- 관련 계획: [회원 탈퇴 API](user-account-deletion.md)

## 문제와 완료 기준

앱에 로그인할 수 없는 사용자의 이메일 삭제 요청을 관리자에게 DB 직접 수정 없이 처리할 경로가 없다. 기존 자가 탈퇴의 차단·익명화 정책을 그대로 적용하고 대리 처리 이력을 남긴다.

- ADMIN만 휴대폰 번호로 활성 사용자를 조회하고 대상 ID로 탈퇴 처리할 수 있다. 비관리자는 두 API 모두 403이다.
- 활성 그룹 OWNER 또는 미완료 정산이 있으면 자가 탈퇴와 같은 409로 차단한다.
- 성공 시 일반 그룹 멤버 이탈, 사용자 익명화, 세션·푸시 해제, 커밋 후 아바타 정리까지 자가 탈퇴와 동일하다.
- 관리자 ID·대상 ID·UTC 시각의 `ADMIN_USER_DELETION` 감사 행이 탈퇴와 같은 DB 트랜잭션에 저장된다. 실패/롤백 시 성공 감사 행은 없다.
- 기존 `DELETE /api/v2/user/me`와 `LOGOUT` 감사 행은 유지한다.

프론트 UI, 이메일 요청의 신원 확인/승인 워크플로, 운영 ADMIN 자동 지정은 범위 밖이다. ADMIN 계정의 실제 존재 여부와 지정 대상은 배포 전에 별도 확인/승인한다.

## 계약과 흐름

1. `POST /api/v2/admin/users/search`의 `{ "phoneNumber": "..." }`는 ADMIN 확인 후 `IdentityPolicy.normalizePhoneNumber()`로 정규화한다. `LoadUserPort.findByPhoneNumber`의 활성 일반 사용자만 조회하며 응답은 ID·이름으로 제한한다. 번호를 URL·응답·로그에 남기지 않는다.
2. `DELETE /api/v2/admin/users/{userId}`는 ADMIN 확인 후 대상을 잠금 조회한다. ADMIN 대상(자신 포함)은 차단한다. 일반 사용자라면 기존 자가 탈퇴와 공통 실행 흐름에서 `UserDeletionPolicy.evaluate()`/`.anonymize()`를 호출하고 동일한 부수효과를 수행한다. PIN은 요구하지 않는다.
3. 같은 트랜잭션에서 `AuthAuditPort`에 관리자·대상·시각을 전달한다. 저장 실패 시 익명화도 롤백된다.

성공은 200 `ApiResponse<Void>`, 미인증은 401, 비관리자/ADMIN 대상은 403, 잘못된 전화번호는 400, 없는/비활성 대상은 404, OWNER/정산 차단은 기존 409 코드를 따른다. 재시도한 삭제는 404다.

## 설계·영향 범위

- Flyway: 새 migration 불필요. V41이 `auth_audit_logs.target_user_id`와 `ADMIN_USER_DELETION` 필수 대상 제약을 이미 제공한다.
- Application: `SearchAdminUserUseCase`, `DeleteUserAsAdminUseCase`와 관리자 Service를 추가한다. 자가 탈퇴의 공통 종료 절차만 별도 구체 Service로 추출해 두 진입점에서 재사용한다. PIN/ADMIN 인증은 각 진입점이 소유한다.
- Output: 기존 `LoadUserPort`, `SaveUserPort`, 그룹·정산·세션·푸시·이미지 Port를 재사용하고 `AuthAuditPort`에 감사 기록 기능을 추가한다. 신규 저장소 Port는 만들지 않는다.
- Adapter/Web: 기존 `AuthAuditJpaEntity`·Adapter를 확장하고 관리자 Controller/DTO/Swagger를 추가한다. `/api/**`의 기존 인증 설정을 유지하고 Application에서도 ADMIN을 검증한다.
- 정책: `UserDeletionPolicy`와 `User.deactivateForDeletion`은 변경하지 않는다. 활성 그룹 잠금 순서와 아바타 커밋 후 삭제를 그대로 보존한다.
- 결제·정산·금융 기록과 외래키는 변경하지 않는다.

## 검증·진행과 커밋 경계

- [x] 선행 migration PR #445 병합 확인.
- [x] 관리자 권한, 전화번호 정규화/비노출, 대상 선택, 기존 삭제 차단·익명화, 반복 삭제 및 감사 원자성 검증. 동시 삭제는 대상 행 잠금으로 직렬화하며 별도 경합 테스트는 실행하지 않았다.
- [x] `./gradlew compileJava`, 전체 `scripts/test.sh test`, `git diff --check`, 일회용 PostgreSQL·Redis 통합 테스트.
- [ ] 사용자가 지정한 ADMIN 후보 계정의 운영 존재 여부와 현재 role 확인. 확인 전 임의 권한 부여는 하지 않는다.

예상 커밋: `docs(user): 관리자 대리 탈퇴 계약 기록` → `feat(user): 관리자 검색과 대리 탈퇴 처리 추가` → `test(user): 관리자 탈퇴 권한과 감사 이력 검증`. 각 커밋은 컴파일 가능하게 구성한다.
