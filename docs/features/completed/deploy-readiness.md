# 배포 readiness 검증 (#308)

- 상태: 완료
- 기준: origin/develop a8ac009
- 날짜: 2026-09-10

## 목적과 완료 조건

운영자가 컨테이너 생성만으로 배포 성공을 오인하지 않도록, 앱 초기화 완료와 DB·Redis 연결을 확인한 뒤 성공 처리한다. 정상 시작·지연 시작·영구 미준비·프로세스 종료를 검증한다. 이미지 digest 고정(#309), 자동 롤백, 외부 CODEF 검증은 제외한다.

## 설계와 영향

기존 배포 워크플로를 저장소의 `scripts/deploy-readiness.sh`로 추출하고 SSH `script_path`로 실행한다. 서버의 `APP_DIR` Compose `app` 컨테이너 내부에서 loopback 관리 포트의 `/actuator/health/readiness` HTTP 200을 확인한다. readiness 그룹은 `readinessState,db,redis`를 포함한다. 관리 포트 공개와 인증 변경은 없다. 이미지에 curl을 명시적으로 설치한다.

검증 제한 시간은 180초이며 개별 Docker 호출은 timeout으로 제한한다. 종료·재시작 상태는 즉시 실패하고 HTTP 미준비는 재시도한다. 실패 시 container ID, 상태, exit code, OOM, restart count, 마지막 HTTP 코드를 기록한다. 환경변수·원문 앱 로그는 출력하지 않고, 상세 로그는 서버에서 확인한다.

Flyway migration: No. 스키마·Redis 저장 형식 변경 없음. UseCase, Command/Result, Output Port, Domain, Policy, Application, Web DTO는 배포 인프라 변경이므로 N/A.

## 커밋 경계

1. `ci(deploy): 애플리케이션 readiness 확인 후 배포 완료 처리` — 계획·설정·이미지·워크플로·스크립트
2. `test(deploy): 배포 준비 상태 회귀 시나리오 검증` — mock Docker 회귀·CI 연결·검증 기록

## 검증 계획

Python unittest로 실제 셸 스크립트를 mock Docker와 실행하여 정상·지연·영구 실패·종료·재시작·도구 오류를 검증한다. 컴파일, 셸 구문, diff 검사, 문서 경로 검사를 수행한다. 실제 앱 실행은 disposable PostgreSQL·Redis 가용성을 확인한 뒤 시도한다. 실제 EC2 배포는 PR 병합 전 실행하지 않는다.

## 검증 결과

- `./gradlew compileJava --no-daemon`: 통과.
- `python3 -m unittest discover -s scripts/tests -v`: 2개 테스트 메서드, 9개 시나리오 통과. 정상·지연·영구 미준비·종료·재시작·컨테이너 누락·inspect 오류·exec 오류·pull 오류 검증.
- 전용 PostgreSQL 18(25432)·Redis 8(26379)에서 `bash scripts/test.sh test --tests '*ObservabilityHttpIntegrationTest'`: 실제 HTTP 테스트 5건 및 JavaScript 35건 통과. readiness 200, REFUSING_TRAFFIC 503, 공개 포트 차단 확인.
- `bash -n scripts/deploy-readiness.sh`, `git diff --check`: 통과.
- 전체 Java 테스트, prod 프로필, 실제 이미지 빌드·EC2 SSH 배포는 미실행. 실제 배포는 병합 후 CI에서 확인한다.

## 운영 조건과 한계

서버에 Bash, GNU timeout, Docker Compose가 필요하며 Compose 서비스명은 `app`이어야 한다. SSH action은 checkout된 스크립트를 전송하므로 서버에 별도 스크립트 설치는 필요 없다. 컨테이너 내부 `MANAGEMENT_SERVER_PORT` 기본값은 9091이며 기본 관리 경로를 사용한다. 배포 실패 시 자동 롤백은 수행하지 않는다. timeout 진단에 추가로 최대 10초가 사용된다. 이미지 식별은 병합된 #309의 digest 고정·검증과 통합했다.

병행 작업이 공유 checkout의 브랜치를 바꿔 첫 커밋 e658ef3이 다른 작업 브랜치에 기록되었다. #308은 별도 worktree에서 동일 변경을 e76e1e9로 적용했다. 다른 작업의 후속 변경은 수정하지 않았다.

## develop 충돌 해결 (2026-09-10)

- 최신 `origin/develop` 95f7864를 병합했다. SSH 환경변수의 `APP_IMAGE`, 배포 commit/run/attempt를 보존하고 인라인 digest 배포를 기존 readiness 스크립트에 통합했다.
- 동일 Compose override로 pull/up/컨테이너 조회를 수행하고 실제 이미지 ID 확인 후 같은 컨테이너의 readiness를 검사한다. EXIT trap은 실패 진단과 override 삭제를 함께 처리한다.
- 기존 digest 테스트를 `script_path`에서 실제 스크립트를 읽도록 수정했다. CI 테스트 단계는 두 회귀 파일을 함께 실행한다.
- 병합 커밋 경계: `ci(deploy): develop 병합 충돌 해결 및 digest와 readiness 검증 통합`.
- 검증: 컴파일, 셸 구문, YAML 파싱, digest 6건·readiness 2개 메서드(9개 시나리오), diff 및 문서 링크 검사. 실제 EC2 배포와 전체 Java 테스트는 이번 충돌 해결에서 수행하지 않는다.
