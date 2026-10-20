# mock2 Android 송금 Mock 연결

- 상태: 구현·검증 완료, develop PR #611
- 날짜: 2026-10-21

## 목적과 성공 기준

기존 웹 Mock을 유지하면서 `SETTLEMENT_TRANSFER_LINK_ADAPTER=mock2`를 명시했을 때만 별도 Android 송금 Mock 앱의 금액 화면을 연다. 받는 사람을 다시 선택하지 않고 기존 은행·계좌·금액·수신인 파라미터를 재사용한다.

| 설정 값 | 송금 링크 |
|---|---|
| `toss` | 기존 실제 토스 링크 |
| `mock` | 기존 웹 Mock URL. `SETTLEMENT_MOCK_TRANSFER_BASE_URL` override 유지 |
| `mock2` | 고정 `howmuch-mock://send` 앱 URI |

기존 dev/local의 기본 `mock`과 운영 기본 `toss`는 바꾸지 않는다. 기존 웹 기본 주소 환경변수는 mock2에 영향을 주지 않는다.

## 설계와 영향 범위

기존 settlement 도메인, `SettlementTransferLinkPort`, Mock adapter의 URL 조립을 재사용한다. Infra의 adapter enum에 MOCK2를 추가하고 설정에 따라 동일 adapter를 고정 앱 URI로 생성한다. 새로운 API/UseCase/Port/Policy를 만들지 않는다. API의 `provider=TOSS`는 기존 모바일 호출자 호환을 위해 유지한다.

`mock`의 URL 검증은 기존 HTTPS/HTTP loopback 규칙을 유지하며 앱 URI를 허용하지 않는다. `mock2`는 설정에서 임의 앱 URL을 받지 않는다. DB/Redis 구조와 CODEF Mock API 변경 없음. Flyway migration required: **No**.

앱 URI는 정보 전달이며 송금은 앱에서 별도 확인해야 실행한다. 전용 스킴은 실제 토스 `supertoss://` 라우팅을 가로채지 않는다.

## 모바일 연동 조건

별도 APK(`kr.howmuch.transfermock`, 표시 이름 토스) 설치와 HowMuch 호출자 변경이 필요하다. Android에서 사전 조회가 false여도 implicit VIEW 실행은 가능한 경우를 실제 기기에서 확인했다. Mock URI는 직접 `Linking.openURL`로 열고 실패 시 Mock APK 설치 안내를 표시하도록 프런트 소스에 반영했다. 모바일 변경과 별도 APK는 이 서버 PR에 포함하지 않는다.

## 구현·검증

- [x] 설정 → Properties → adapter → URL → 모바일 호출 경계 확인
- [x] 기존 mock/default 동작 유지, mock2 명시 설정으로 분리
- [x] enum 바인딩과 단일 adapter bean 선택 검증
- [x] mock2 고정 앱 주소와 한글/특수문자 파라미터 검증
- [x] 기존 웹 override가 mock2를 바꾸지 않는 것 검증
- [x] 최신 origin/develop 기반 별도 worktree에 해당 기능만 적용
- [x] compileJava, compileTestJava, 관련 테스트 63개 통과
- [x] git diff --check 통과

```sh
./gradlew compileJava test --tests '*SettlementTransferLinkPropertiesTest' --tests '*MockSettlementTransferLinkAdapterTest' --tests '*DevMockTransferLinkConfigurationTest' --tests '*SettlementTransferLinkConfigTest' --console=plain
```

Properties 19개, URL adapter 25개, dev profile binding 3개, adapter 설정/선택 통합 16개로 총 63개다. 전체 테스트 소스 컴파일은 통과했고 선택한 테스트는 외부 인프라를 요구하지 않는다. 전체 test 실행과 DB/Redis/Kafka를 포함하는 dev 서버 기동은 수행하지 않았다.

별도 앱은 실제 기기에서 cold/warm 링크, 입금자명 수정, 격리 Mock API/ledger 반영, 완료 후 복귀, 응답 유실 후 동일 UUID 재시도 중복 방지를 검증했다. 이 검증은 배포 환경의 인증된 정산 버튼 전체 경로를 대신하지 않는다.

## 적용과 복귀

develop 배포 후 환경변수를 다음과 같이 설정하고 서버를 재시작한다.

```text
SETTLEMENT_TRANSFER_LINK_ADAPTER=mock2
```

웹 Mock으로 돌아갈 때는 `mock`으로 변경한다. `SETTLEMENT_MOCK_TRANSFER_BASE_URL`을 앱 URI로 바꾸지 않는다. 기존 HowMuch의 호출자 변경 적용 및 별도 APK 설치 후 실제 정산 송금 안내 응답과 앱 진입을 확인한다.

PR 생성/업데이트는 서버 배포 완료가 아니다. GitHub Actions 초기 실행은 계정 결제/사용 한도 문제로 작업 시작 전에 중단됐고 실행 단계/테스트 로그가 생성되지 않았다. 계정 Billing & plans 조치 후 CI 재실행이 필요하다. workflow나 결제 설정은 변경하지 않았다.

커밋 경계: mock2 선택에 따른 Android 송금 링크 한 기능 단위. DB migration, 실제 은행 송금, 운영 TOSS 변경은 범위 밖이다.
