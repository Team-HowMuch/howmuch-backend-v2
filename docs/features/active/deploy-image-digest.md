# 배포 이미지 digest 고정 (#309)

- 상태: 구현 준비
- 갱신일: 2026-09-10

## 목적과 완료 조건

배포 담당자가 브랜치 태그 덮어쓰기로 다른 실행의 이미지를 배포하는 문제를 방지한다.
동일 build가 발행한 digest를 deploy에 전달하고, 커밋·실행 ID·재실행 번호·이미지 참조를 기록한다.
동시 실행 및 재실행에도 전달된 digest를 사용하고, 누락·잘못된 digest는 배포 전에 거부한다.

## 설계와 영향 범위

- 기존 CI/CD 영역만 변경한다. Java UseCase, Port, Domain, Policy, API 및 Infra Bean 변경은 N/A.
- Flyway migration required: No. DB·Redis 저장 구조 변경 없음.
- `.github/workflows/backend-ci-cd.yml`: build output으로 완전한 이미지 참조를 전달한다. EC2의 기존 Compose에 임시 override를 적용해 app 이미지를 고정하고 실제 컨테이너 이미지 ID를 확인한다.
- `compose.yml`: 직접 실행 시에도 `APP_IMAGE`를 필수로 요구한다.
- EC2 기본 Compose 파일은 `compose.yml`을 사용한다. 서버 소유 DB·Redis·볼륨 설정은 유지한다.
- 브랜치 태그는 호환성을 위해 계속 발행하지만 배포에서는 조회하지 않는다. PR 및 workflow_dispatch의 미배포 조건과 배포 concurrency는 유지한다.
- 자동 rollback, readiness 검사, 배포 순서 보장은 범위 밖이다.

## 시나리오와 검증

1. build가 게시한 digest를 검증하고 소문자 GHCR 저장소명과 결합한다.
2. deploy가 전달받은 참조로 app을 pull/up하고 로컬 이미지 ID와 실행 컨테이너의 이미지 ID를 비교한다.
3. A 이후 B가 태그를 바꿔도 A의 참조는 바뀌지 않는다. deploy 재실행은 기존 build output을, 전체 재실행은 새 build output을 사용한다.
4. 잘못된 참조, pull/up 실패, 실행 이미지 불일치는 실패 처리한다. override는 종료 시 제거한다.
5. YAML/셸 구문, mock Docker 기반 동시 실행·재실행·실패 경로, Compose 병합, 문서 링크 및 `git diff --check`를 확인한다.
6. 실제 GHCR 게시·EC2 배포는 자격 증명과 대상 환경이 필요한 검증으로 별도 기록한다.

## 커밋 경계

1. `ci(deploy): 빌드 digest로 배포 이미지 고정` — workflow 및 Compose 변경과 설계 기록.
2. `test(deploy): 동시 실행과 재실행 이미지 식별 검증` — 회귀 검증 및 운영 안내, 결과 기록.

## 진행 및 결과

- [x] 최신 origin/develop 및 기존 PR 컨벤션 확인
- [x] 설계 및 migration 불필요 확인
- [ ] 구현 및 검증
