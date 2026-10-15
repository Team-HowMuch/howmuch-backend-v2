# 인증서 Backend SQL 검증 기록

- 날짜: 2026-10-10
- 범위: `V44__create_certificate_registration_schema.sql`의 저장 제약과 제안된 SQL 경쟁 처리
- 기준: Backend `origin/develop` `0a098776198eae80b545af2d86c054f5cbe5949a`, 열린 #475 V43 `47dd3f47691be29c7017b2bf1eceaf24b03a51ec`
- 환경: 일회 PostgreSQL 16 Alpine Docker, Java 21, 실제 Flyway 12.4.0
- 관련 문서: [Feature Plan](codef-certificate-registration.md), [결과 JSON](codef-certificate-schema-results.json)

## 수행 결과

| 검증 | 결과 |
|---|---|
| `./gradlew compileJava certificateRuntimeClasspath -I /tmp/howmuch-certificate-20261010/runtime.init.gradle --no-daemon` | BUILD SUCCESSFUL. 추가 task는 임시 init script에서 runtime classpath만 추출 |
| 실제 Flyway: 최신 V1~V42 + V44 | 43개 migration, target 44, validate 성공 |
| 실제 Flyway: V1~V42 + 열린 V43 + V44 | 44개 migration, target 44, validate 성공 |
| 두 경로 migrate 재실행 | 각각 추가 적용 0개 |
| 실제 PostgreSQL 합성 fixture/SQL 쿼리 실험 | 42개 통과 |

V44를 먼저 적용한 뒤 V43을 뒤늦게 적용하는 배포 순서는 지원하지 않는다. #475 V43 선행 병합/적용을 PR에서 요구한다. 기존 Flyway out-of-order 정책은 변경하지 않는다.

처음 전체 SQL을 psql로 직접 재생했을 때 V30이 `flyway_schema_history`를 조회하여 실패했다. 직접 재생 성공으로 보고하지 않았으며 이후 실제 Flyway 엔진으로 위 두 경로를 모두 검증했다. 초기 fixture의 public_id 누락/형식 오류도 수정한 뒤 최종 스크립트를 새 DB에서 실행했다.

## 확인한 경계

- READY 상태의 기관·참조·QR hash/기간, capability/connection/QR 중복
- 세션당 유효 예약 한 건, 세션/예약/회원/generation 복합 FK, 회원당 승인 중 또는 BOUND 한 건
- 예약의 신원/기간 불변, 세션을 넘는 예약 deadline 거부, RESERVED 예약의 승인 거부, APPROVED 예약의 만료 거부
- 명의검증 참조 없는 승인 거부, 승인 사실 변경/승인 후 TTL 만료/BOUND 역전 거부
- REGISTER upload 및 BIND 승인·connection·version 일치, 완전한 lease, attempt/failure code 제약
- 기존 회원 탈퇴와 신규 FK의 RESTRICT 관계: 후속 탈퇴 공통 실행기 연계 전 기능 활성화 금지
- 상태+Outbox rollback, stale lease CAS 0행 및 현재 lease CAS 성공
- Inbox messageId 중복 거부, 원문 대신 digest 검사, 다른 digest 재전달의 충돌 탐지 가능성, 세션 정리 뒤 Inbox 유지
- 병렬 QR 예약 한 건만 성공, 같은 회원 병렬 승인 한 건만 성공, 만료·세대 변경 후 오래된 예약 승인 CAS 거부

실험은 [스크립트](../../../tools/verification/certificate-schema-check.py)에 보관했다. 스크립트는 이름이 고정된 일회 Docker PostgreSQL과 `certificate_with_v43_v3` DB에 합성 회원/세션을 INSERT한다. 운영/개발 서비스 DB에 실행하지 않는다. 실제 Flyway 적용을 완료한 동일한 일회 환경에서 `python3 tools/verification/certificate-schema-check.py`로 재현할 수 있다. 별도 Python 패키지는 필요 없으며 Docker CLI와 Python 3가 필요하다.

Flyway 실행기·runtime classpath·raw 실행 로그는 `/tmp/howmuch-certificate-20261010/`에 있다. SQL-only PR에는 SQL 한 파일만 포함하며 Feature Plan/QA/실험 스크립트는 별도 기능 worktree에 보관한다.

## 수행하지 않은 검증과 출시 조건

- 신규 Java endpoint/UseCase, Kafka publisher/listener, CODEF Connector 인증서 처리: 구현 전
- Windows WPF build/설치/실행/코드서명: 구현 전
- 모바일 scanner·최종 승인 UI 및 실기기: 구현 전
- 실인증서·실은행·CODEF 상품 권한/추가 입력의 기관별 확정: 수행하지 않음
- 검증된 명의확인 계약, 기존 ID/PW 연결 충돌, 회원 탈퇴/해제 연계: 후속 구현의 출시 조건
- #494의 기존 connectedId 필수 저장 구조와 Connector connectionRef 계좌 후보/선택 호환·후속 migration: 설계/구현 전. V44만으로 계좌 사용 가능 상태를 만들지 않음
- dev/prod migration 적용 및 서비스 배포: 수행하지 않음

제약 검증과 제안 쿼리 실험을 실제 API·worker의 구현 완료 또는 end-to-end 성공으로 해석하지 않는다. Inbox hash 충돌 처리·각 상태 전이의 actor/TTL 검사·lease CAS는 후속 Application/Adapter 구현과 테스트가 별도로 필요하다.
