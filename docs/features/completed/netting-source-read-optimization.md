# 압축정산 원본 조회 최적화

- 상태: 완료 (로컬 검증, 미배포)
- 갱신: 2026-10-19
- 기준: develop c6b293b4

## 목적과 성공 기준

그룹 정산 100건을 압축할 때 정산마다 그룹 전체를 다시 읽어 약 17~19초가 걸리는 문제를 줄인다. 은행 조회·권한·동시 변경 방지·입금 기록의 의미는 유지한다.

- 반복 안전성 검사가 해당 원본 정산의 식별 정보만 조회한다.
- 전체 원본 조회에서 정산별 참여자·채무·기존 입금 키의 N+1 조회를 없앤다.
- 같은 HTTP 시나리오(2·20·100건, 은행 1초 지연×2회)로 전후를 비교한다.
- 기존 동시 수정·계좌 변경·탈퇴·입금·lease·재시도·압축 완료 검증을 통과한다.

## 도메인과 계약

기존 settlement/netting 도메인의 조회 최적화다. API·UseCase·Domain·계산 알고리즘·상태 전이는 변경하지 않는다.

`LoadGroupNettingDebtPort`에 `findAwaitingSourceIdentity(groupId, settlementId)`를 추가한다. 반환은 `SourceIdentity(settlementId, receivingAccountId, receivingAccountDigest)`의 Optional이다. 두 caller는 NettingBankQueryGuard와 NettingOriginalDepositWriter다. 기존 그룹·참여자 eligibility와 계좌 digest 조건을 그대로 적용하되 필요한 한 정산만 읽는다. 원래 검사가 비교하지 않던 version 조건을 임의로 추가하지 않는다. 최종 commit의 기존 version 검증은 유지한다.

Adapter의 전체 조회는 metadata 1회, 참여자 1회, 채무/확정 합계 1회, 입금 키 1회로 조립한다. 빈 입력은 즉시 반환하고 정산·참여자·채무·입금 키의 정렬과 금액 합계 의미를 유지한다.

## 저장소 변경 판단

- Flyway migration: **No**. 기존 PK·FK·인덱스와 읽기 쿼리만 사용한다.
- Redis·환경변수·외부 API·인증·배포 설정 변경: 없음.
- 압축정산 기능의 기존 안전성 검사와 트랜잭션 경계를 축소하지 않는다.
- 압축 결과 cache나 은행 조회 병렬화는 범위 밖이다.

## 영향 범위와 실행 순서

1. 기존 성능 harness를 최신 develop에서 다시 실행해 기준값 보존.
2. Output Port 및 JDBC Adapter의 단건 식별 조회와 전체 일괄 조회 구현.
3. 두 안전성 검사 caller 교체.
4. 기존 통합 테스트에 eligibility·집계·정렬 검증 추가, 성능 harness에 반복 전체 조회가 없어졌는지 검사 추가.
5. Java 컴파일, 관련 회귀 테스트, 동일 HTTP 성능 테스트 수행.
6. 결과·한계·재현 명령 기록. 초기 구현은 로컬 검증까지 수행했고, 후속 요청으로 [이슈 #588](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/588)와 PR을 게시한다. 배포는 범위 밖이다.

## 시나리오

정상 압축·재압축, 부분 입금, 과거 입금 이력 보존, 동일 요청 재실행, 비회원/탈퇴/권한 변경, 다른 그룹 소속 정산 제외, 삭제·상태 변경, 계좌 식별 변경, worker lease 소실, source 없는 그룹, 빈 목록/자기 부담 제외를 확인한다. 다중 쿼리 조립 동안 읽기 일관성은 기존 final revalidation/lock 정책으로 보장하며 새로운 snapshot 보장을 주장하지 않는다.

## 예정 커밋 경계

후속 게시 요청에 따라 원본 조회 최적화와 병렬 조회를 하나의 개선 단위로 함께 커밋한다.

- `refactor(settlement): 압축정산 반복 조회 최적화 및 계좌 조회 병렬화`
- 설계·구현·검증 증거는 위 변경과 함께 검토한다. 사용하지 않는 Port만 따로 남기지 않는다.

## 진행과 결과

- [x] 기준 소스와 설계·마이그레이션 필요 여부 확인
- [x] 기준값 재측정
- [x] 구현
- [x] 기능 회귀·성능 검증
- [x] 결과 기록


## 검증 결과와 완료 기록

- Java 컴파일 및 테스트 소스 컴파일 통과.
- 관련 138개 테스트 전부 통과, 빈 DB HTTP 재실행도 통과.
- 동일 기준 비교: 20건 3.79 → 3.01초, 100건 18.00 → 4.57초. 은행 지연 총 2초는 동일하다.
- 전체 원본 조회는 100건 기준 206 → 4회. 단건 식별 검사 202회는 유지한다.
- 기능·정렬·집계 검증과 기존 동시 변경 방지를 유지했다.
- 최종 동작: 두 반복 검사에 단건 조회, 전체 원본 로딩에 일괄 조회를 사용한다.
- 미검증: 실은행·다인원 HTTP 부하·다수 그룹 동시 처리. 로컬 비교 수치를 운영 SLA로 해석하지 않는다.
- 게시: [이슈 #588](https://github.com/Team-HowMuch/howmuch-backend-v2/issues/588) / 브랜치 `refactor/588-netting-query-optimization`. develop 배포는 하지 않았다.
- 상세 명령·결과·한계: [QA 비교 결과](../../qa/netting-source-read-optimization-20261019.md).
- 완료일: 2026-10-19
