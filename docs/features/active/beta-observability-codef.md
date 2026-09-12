# 기능 계획: 베타 운영 대시보드와 CODEF 안전 범위

- 상태: 로컬 구현·검증 완료, 운영 적용 대기
- 최종 수정일: 2026-09-12
- 관련 기능: [OTel 기반 관측성 구조 전환](observability-otel.md), [CODEF 은행 연결](../completed/codef-bank-account-connection.md)
- Flyway migration: 필요 없음

## 1. 목적과 성공 기준

일요일 베타 운영 전에 애플리케이션 상태, CPU, API 요청량·오류율·p95 latency, CODEF 기관별 성공률과
실패 원인을 Grafana에서 확인하고, 같은 `traceId`와 `area`로 로그와 trace를 왕복 조사할 수 있게 한다.

- [x] 운영 개요, CODEF, 로그·trace 조사 대시보드를 자동 프로비저닝한다.
- [x] 요청 duration histogram과 CODEF API별·기관별·결과별 duration histogram을 노출한다.
- [x] 요청 로그와 trace에 저카디널리티 `area`를 연결한다.
- [x] 현재 입력 계약만으로 안전한 ID/PW 은행만 연결 목록과 연결 검증에서 허용한다.
- [x] 계정 잠금, 추가인증, 사용자 입력 만료, 일일 한도를 서로 다른 API 오류로 변환한다.

제외: EC2 접속·방화벽 변경, 알림 채널/온콜 규칙, 씨티 SMS 2-way 완료, 은행 계좌비밀번호 저장,
대구은행 출금계좌 추가 입력, CODEF 실계정 호출.

## 2. CODEF 사전 확인

공식 은행 개요의 ID 로그인 및 비밀번호 오류 횟수와 보유계좌·수시입출 기관별 파라미터 표를 함께
적용한다. `X`는 ID 로그인을 제공하지 않으며, `-`는 ID/PW 오류를 분리하지 못한다.

| 기관 | 코드 | 잠금 전 오류 횟수 | 현재 베타 | 사전 조건 |
|---|---:|---:|---|---|
| 기업은행 | `0003` | 5 | 허용 | `birthDate` 필요 가능 |
| 국민은행 | `0004` | 5 | 허용 | `birthDate` 필요 가능 |
| 농협은행 | `0011` | 5 | 허용 | e농협회원 조회계좌관리에서 계좌 등록 |
| 우리은행 | `0020` | 3 | 허용 | `birthDate` 필요 가능 |
| 전북은행 | `0037` | 5 | 허용 | 최근 10년, 12개월 단위 조회 |
| 경남은행 | `0039` | 3 | 허용 | 조회 기간 제한 없음 |
| 새마을금고 | `0045` | 5 | 허용 | 최근 5년 조회 |
| 우체국 | `0071` | 구분 불가 | 허용 | 최근 90일 조회 |
| 하나은행 | `0081` | 5 | 허용 | 최근 2년 조회 |
| 신한은행 | `0088` | 5 | 허용 | 조회 기간 제한 없음 |
| SC은행 | `0023` | 구분 불가 | 제외 | 거래내역에 `accountPassword` 필요 가능 |
| 씨티은행 | `0027` | 5 | 제외 | 로그인 장치 변경 시 SMS 2-way 가능 |
| 대구은행 | `0031` | 3 | 제외 | 보유계좌에 출금계좌번호·RSA 비밀번호 필요 |

산업·수협·부산·광주·제주·신협은 ID 로그인을 제공하지 않아 제외한다. K뱅크는 공식 오류 횟수 및
기관별 파라미터 표에 근거가 없어 이번 베타에서 제외한다. 생년월일은 회원 프로필 값을 `yyyyMMdd`로
전달하는 기존 fallback을 재사용한다. 인증 실패는 은행 계정 잠금을 유발할 수 있으므로 자동 재시도하지
않는다. OAuth 401만 기존처럼 access token을 한 번 갱신한다.

## 3. CODEF API와 오류 처리

| 기능 | Endpoint | 현재 입력 | 추가 처리 |
|---|---|---|---|
| 계정 생성/추가/수정 | `/v1/account/create`, `/add`, `/update` | ID, RSA password, 기관, 생년월일 | 기관 허용 목록 선검증 |
| 보유계좌 | `/v1/kr/bank/p/account/account-list` | 기관, `connectedId`, 생년월일 | 대구 추가 입력 미지원으로 기관 제외 |
| 수시입출 거래 | `/v1/kr/bank/p/account/transaction-list` | 기관, 계좌, 기간, 생년월일 | SC 계좌비밀번호 미지원으로 기관 제외 |

오류 매핑은 `CF-12801` 자격증명 오류, `CF-12834` 계정 잠금, `CF-03002` 추가인증,
`CF-12001` 사용자 입력 만료, `CF-00022/CF-00023` 요청 한도, 통신·엔진 오류를 구분한다.
`CF-04000`은 공식 의미가 계정정보 등록 실패이므로 거래내역 미지원으로 취급하지 않는다.

## 4. 관측 계약

- HTTP: `howmuch.http.requests` 유지, `howmuch.http.request.duration` histogram 추가
- CODEF: `howmuch.codef.requests` timer, tags=`api,organization,outcome`
- 로그: 실패 시 `event=codef_request_failed`, `api`, `organization`, `outcome`, `codefCode`; 자격증명 없음
- trace: `area`, `codef.api`, `codef.organization`, `codef.outcome`, `codef.result_code`
- Grafana: `HowMuch Operations Overview`, `HowMuch CODEF Operations`, `HowMuch Logs and Traces`

## 5. 검증

```bash
./gradlew compileJava
./gradlew test --tests '*RequestObservabilityTest' \
  --tests '*CodefBankConnectionAdapterTest' \
  --tests '*CodefBankTransactionAdapterTest'
docker compose -f ops/observability/compose.yml config --quiet
git diff --check
git status --short --untracked-files=all
```

로컬 검증 결과:

- 격리 PostgreSQL·Redis를 사용한 `bash scripts/test.sh test`: Java 1,111건, JavaScript 35건 통과
- Grafana 13.2.0 health 정상, 세 대시보드 provisioning 완료
- 실제 401 요청에서 Prometheus `area=account` count/duration 생성
- 같은 `traceId`로 Loki `request_failed` JSON과 Tempo 4개 span을 조회하고 `area=account` 확인
- Compose 2개와 dashboard JSON 문법, `git diff --check` 통과

운영 적용은 애플리케이션과 관측 스택을 같은 private network에 붙인 뒤 Grafana health, Prometheus
`up`, 세 대시보드 로딩, Loki `traceId` 링크, Tempo `area` 조회를 실제 요청으로 확인해야 완료다.

## 6. 참고

- [CODEF 은행 개요](https://developer.codef.io/products/bank/overview)
- [CODEF 보유계좌](https://developer.codef.io/products/bank/common/p/account)
- [CODEF 수시입출 거래내역](https://developer.codef.io/products/bank/common/p/transaction)
- [CODEF 오류코드](https://developer.codef.io/common-guide/error-code)
