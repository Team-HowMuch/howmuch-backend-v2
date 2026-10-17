# 고정 지출 · 그룹 반복 정산 인수인계

- 확인일: 2026-10-18
- 상태: **후속 구현 완료(격리 워크트리 `feat/574-group-recurring-settlement`, V59 기반)** — 아래 1~3절은 후속 구현 착수 전 분석 기록이며, 최신 상태는 [Feature Plan](group-recurring-settlement.md)과 [운영 메모](group-recurring-settlement-runbook.md)를 따른다.
- 기준: 후속 구현은 `develop` `3d386b3a`(V59 병합)에서 진행했다. 아래 분석은 당시 로컬 작업본과 `origin/develop` (`a30875b1`) 기준이다.
- 원본: [Feature Plan](group-recurring-settlement.md), [운영 메모](group-recurring-settlement-runbook.md)

## 1. 문서가 정의하는 기능

월세·구독료처럼 그룹 멤버가 같은 금액을 반복 부담하는 비용을 설정하고, 지정한 첫 정산일부터 매주 또는 매월 **새 정산 요청**을 자동 생성한다. 실제 자동 출금·송금이나 개인 거래의 고정 지출 탐지는 범위 밖이다.

- 그룹 전용, 생성자 포함 서로 다른 사용자 2명 이상.
- 기존 수동 지출 더치페이 초안에서 등록. 참여자·금액·수령 계좌는 스냅샷으로 고정.
- 등록 즉시 청구하지 않고 원본 초안은 등록 트랜잭션에서 소프트 삭제.
- 회차마다 기존 정산·Share·링크·알림 Outbox를 생성.
- 해지는 이후 회차만 중단. 이미 발행된 정산과 입금 이력은 유지.
- 멤버 탈퇴·그룹 삭제·수령 계좌 무효 등은 발행 직전 재검증하고 자동 해지.

## 2. 후속 구현으로 해소한 항목

- 로컬 V50 대신 최신 `develop`에 병합된 **V59**를 그대로 사용한다(추가 migration 없음).
- V53 `BATCH_*` 메타데이터를 공유하고, 기존 Spring Batch 6 JDBC 인프라(Netting 구성 또는 Boot 자동 구성)를 재사용한다. 새 메타데이터 테이블을 만들지 않는다.
- 카테고리: 등록 입력에 `categoryCode`를 추가하고, 발행 회차는 `settlement_categories.source=RECURRING`으로 기록한다. LLM 재분류를 호출하지 않는다.
- 목록: `scope=ALL/PARTICIPATING/OTHER`, `status=ACTIVE/CANCELLED`, cursor+limit과 focused 요약 쿼리를 추가했다.
- 홈: `GET /api/v2/recurring-settlements/home?limit=5`를 추가했다.
- 첫 정산일: `firstRunDate`를 선택 입력으로 바꾸고, 주입된 `Clock`을 쓰는 `RecurrenceScheduleResolver`가 가장 가까운 미래 일정을 계산한다. `GET .../schedule-preview`로 생성 전 확인한다.
- `SettlementRequestWriter`로 즉시 요청 기록 처리를 추출해 반복 발행과 공유한다.

## 3. 검증 상태

- 실제 PostgreSQL·Redis 통합 테스트를 `RECURRING_TEST_DB_URL`이 지정된 격리 DB에서 실행해 통과했다(2026-10-18, Docker 미사용).
- 실제 JWT·Redis 세션 인증을 쓰는 RANDOM_PORT HTTP 스모크(`RecurringSettlementHttpIntegrationTest`)까지 통과했다. 미인증 401, 그룹 멤버 미리보기, 등록·목록·홈·발행·replay·해지·무관 사용자 차단을 확인했다.
- 출시 정책 확정(09:00, 월말 보정, 누적 회차 발행, 생성자 양수 배분)은 여전히 제품 확인 대상이다.
- 실제 프로세스 강제 종료 복구 훈련, 운영 푸시·실제 은행, 전체 저장소 suite는 포함하지 않았다.

## 4. MVP에서 계속 제외할 항목

반복 조건 수정·일시정지·재개·회차 건너뛰기, 자동 출금·송금, 자동 압축 정산, 고정 지출 탐지, 별도 관리자 UI는 기존 MVP 범위 밖이다. 추가 필요가 생기면 별도 요구사항으로 다룬다.
