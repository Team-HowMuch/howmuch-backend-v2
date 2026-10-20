# Feature Plan: 리캡 자동 생성 활성화와 생성 완료 이벤트 발행

- 상태: 구현·개발 서버 적용·검증 완료
- 갱신일: 2026-10-21
- 관련 작업: 월 리캡 알림 연동 #474

## 목적과 완료 기준

개발 서버의 리캡 이미지 영구 저장소를 연결하고 자동 생성을 활성화한다.
리캡 저장과 발행 의도를 같은 트랜잭션에서 기록해, 재시작이나 Kafka 장애로
생성 완료 알림이 유실되지 않게 한다. 같은 리캡을 재발행해도 알림은 한 번만 생성한다.

- [x] 개발 서버의 이미지 쓰기와 원자적 이동 확인, 자동 생성 활성화 및 실제 작업 확인
- [x] 리캡 저장과 발행 대기 기록의 원자성 검증
- [x] Kafka ACK 이후 완료 표시, 장애 재시도 및 만료된 작업 점유 회수 검증
- [x] 기존 `RecapPublished` 계약과 소비자의 사용자·월별 중복 방지 유지
- [x] 생성 → Kafka → 알림함·푸시 대기 기록 흐름 검증

## 기존 경계와 설계

- `RecapSnapshotService`에서 새 리캡 저장 성공 시에만 발행 의도를 기록한다.
  생성 완료 처리의 기존 트랜잭션에 참여한다.
- 기존 생성 작업은 리캡 저장·작업 완료를 같은 트랜잭션으로 보장한다. 이 계약을 유지한다.
- 기존 `notification_creation_outbox`는 정산 FK와 정산 전용 제약이 있어 재사용하지 않는다.
- 별도 `recap_publication_outbox`의 PK/FK를 `monthly_recaps.id`로 둔다.
  이벤트 정보는 불변 리캡에서 조회하여 소유자·월·건수의 중복 저장이나 불일치를 피한다.
- Kafka `messageId`는 리캡 UUID, key는 사용자 ID, `occurredAt`은 리캡 생성 UTC 시각이다.
  사용자 이름·금액·계좌·토큰을 Kafka에 넣지 않는다.
- 재시도도 같은 메시지 ID와 본문을 사용한다. 기존 소비자의 사용자·월별 중복 방지를 유지한다.
- `PENDING → PROCESSING → PUBLISHED`; 발행 실패·점유 만료는 `PENDING`으로 돌려 재시도한다.
  행 잠금과 점유 토큰으로 동시 발행을 제한하고, Kafka ACK 뒤 DB 기록 실패는 중복 발행으로 복구한다.
- 기존 리캡을 마이그레이션으로 일괄 알림 발행하지 않는다.
- 리캡 삭제 시 FK CASCADE로 미발행 이벤트도 정리한다. 탈퇴의 기존 리캡 삭제 경로를 유지한다.

## 계약과 구현 범위

- Input Port: 내부 `PublishRecapEventsUseCase`의 제한된 배치 처리
- Output Ports: `RecapPublicationStorePort`(등록·점유·완료·재시도),
  `RecapEventPublisherPort`(Kafka ACK 확인)
- 새 HTTP API는 추가하지 않는다.
- 발행은 Kafka 활성화 및 별도 발행 활성화 설정에서만 실행한다.
- 이미지 저장소와 자동 생성, Kafka 발행, 알림 소비는 각각 명시적으로 활성화한다.

## DB 변경과 작업 경계

Flyway 변경이 필요하다. 기준 `origin/develop`은 `008d3d33`, 마지막 버전은 V62이다.
작업 중 병합된 #611의 Android 송금 변경도 포함하며 추가 마이그레이션 충돌은 없다. V63에 발행 대기열만 추가한다.
사용자가 이번 요청에 한해 migration gate의 예외를 명시적으로 승인했다.
V63과 Java 발행 코드를 함께 구현·검증하고 개발 서버에 적용한다.
사용자의 게시 요청에 따라 같은 브랜치의 변경을 커밋·push하고 `develop` 대상 PR로 제출한다.

### 커밋 경계

1. `feat(recap): 리캡 생성 완료 이벤트의 안정적 발행 연결` — V63, 발행 코드·설정, 통합 검증
2. `docs(recap): 리캡 자동 생성과 알림 활성화 기록` — 배포·Kafka 운영 안내, 구현·검증 기록

## 검증과 운영

- 일회용 PostgreSQL 18에서 전체 기존 SQL 및 V63 적용, 제약·FK·중복·상태 전이 검증
- 통합 테스트: 트랜잭션 롤백, ACK 실패/재시도, 만료 점유, 동일 리캡 중복 소비 검증
- 개발 서버의 기존 Compose와 이미지 digest를 보존하고 리캡 저장소·생성 설정만 추가
- 이전 설정의 비공개 백업을 남기고 readiness·등록/처리 결과·기존 시연 데이터 보존 확인
- 운영 서버 변경, 과거 리캡 대량 알림, 기존 테스트 정산 수정은 범위에서 제외한다.


## 완료 결과

- 개발 서버에 `/opt/howmuch-infra/uploads/recap-images` 영구 저장소를 연결했다.
  앱 UID 10001로 쓰기·원자적 이동을 검증했으며, generation/publication/recap consumer를 모두 활성화했다.
- 리캡·알림·Kafka 설정/전송 관련 Java 144개, JavaScript 35개 통과.
  `origin/develop`의 #611 병합 이후 리캡 발행·Android 송금 회귀 Java 72개와 JavaScript 35개도 통과했다.
- 일회용 PostgreSQL 18에서 Flyway V1~V63 적용과 실제 Kafka 브로커를 사용하는 통합 테스트를 수행했다.
  ACK 직후 종료·재발행에도 알림함과 푸시 대기 기록이 각각 한 건임을 확인했다.
- Docker 기반 기존 Kafka 통합 테스트는 로컬 Docker daemon이 없어 수행하지 못했다.
  이번 발행 흐름은 embedded Kafka의 실제 ACK/재전달 테스트와 개발 서버 mTLS 연결로 검증했다.
- 개발 서버 V63 적용 성공, 새 앱 readiness UP 확인.
  공개 API에서도 알림 목록과 9월 리캡 상세가 모두 HTTP 200이며, 알림의 9월 이동 동작이 활성 상태임을 확인했다.
  테스트 리캡 한 건의 발행은 `PUBLISHED`, 시도 1회, 실패 없음이다.
  `warstroll`의 9월 리캡 알림과 푸시 작업이 각 한 건 생성됐고 푸시 상태는 `SUCCEEDED`다.
- 개발 서버의 9월 기존 데이터 중 분담금이 누락된 3개 생성 작업은 `RETRY`다.
  해당 정산이나 금융 데이터는 변경하지 않았다.
- 테스트 목적의 단일 리캡을 명시적으로 발행했다. 다른 과거 리캡은 일괄 발행하지 않았다.
- 최신 기준 커밋은 `008d3d33`이며, 작업 브랜치는 `codex/recap-auto-kafka`다.
  사용자 요청에 따라 SQL과 발행 코드를 함께 구현했고, 후속 게시 요청으로 PR에 포함한다.
- 개발 서버에는 검증한 작업 소스로 만든 로컬 이미지 `howmuch-recap-dev:c51a9ca7ed1e`를 적용했다.
  정식 CI 배포에 유지하려면 이 코드 변경을 병합해야 한다.
  실제 소스 보관본·앱 파일·checksum·직전 Compose/image·빌드 로그는 서버의
  `/home/ubuntu/howmuch-recap-release-c51a9ca7ed1e/`에 보관했다.
  적용 소스 SHA256은 `c51a9ca7ed1eb08abea0b5cb80c02ada7885af56b191b352cedfd605f270e4bc`다.
- 실패 시 이전 앱으로 복구하는 배포 절차를 준비했고, V63은 삭제하지 않는다.
