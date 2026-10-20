# Android Mock 송금 딥링크

- 상태: 구현·검증 완료, develop PR 대상
- 날짜: 2026-10-21

## 목적과 성공 기준

기존 웹 Mock으로 향하던 dev 정산 송금 링크가 별도 Android Mock 앱의 금액 화면을 바로 열도록 한다. 받는 사람을 다시 선택하지 않고 기존 `bankCode`, `bank`, `accountNo`, `amount`, `receiver` 계약을 유지한다.

- dev Mock 기본 주소: `howmuch-mock://send`.
- 명시적인 `SETTLEMENT_MOCK_TRANSFER_BASE_URL`은 계속 우선한다.
- 고정된 앱 주소만 추가 허용한다. 다른 커스텀 스킴·유사 호스트·경로·포트·사용자 정보·query·fragment를 기본 주소로 허용하지 않는다.
- 운영 TOSS adapter, 기존 API 응답, 정산·입금 대사 동작을 유지한다.

## 설계와 영향 범위

기존 settlement 도메인, `SettlementTransferLinkPort`, Mock adapter의 URL 조립을 재사용한다. 새로운 API/UseCase/Port/Policy를 만들지 않는다. API의 `provider=TOSS`는 기존 모바일 호출자와의 호환을 위해 유지한다.

변경 범위는 Properties 검증, dev YAML 기본값, URL 생성/프로파일 바인딩 회귀 테스트다. Flyway migration required: **No**. DB/Redis 구조와 CODEF Mock 서버 API 변경 없음.

앱은 링크를 받아 수신 계좌를 Mock 서버에서 확인한 후 금액 화면을 표시한다. 송금은 앱의 별도 확인 버튼으로만 실행한다. 고정 전용 스킴은 HTTPS 도메인 검증 배포가 필요 없으며 실제 토스의 `supertoss://` 스킴을 가로채지 않는다.

## 모바일 연동 조건

별도 APK(`kr.howmuch.transfermock`) 설치와 HowMuch 호출자 변경이 필요하다. Android에서는 `canOpenURL` 사전 조회 결과가 false여도 implicit VIEW 실행이 가능한 경우를 실제 기기에서 확인했다. Mock URI는 `Linking.openURL`로 직접 열고 실패하면 Mock 앱 설치 안내를 표시하도록 프런트 소스에 반영했다. 프런트 변경 및 별도 APK는 이 서버 PR에 포함하지 않는다.

## 구현·검증 기록

- [x] 기존 설정 → Properties → adapter → URL → 모바일 호출 경계 확인
- [x] Migration/새 API/UseCase/Port/Policy/DB/Redis 변경 불필요 확인
- [x] 고정 앱 기본 URI validation 및 dev 기본 주소 변경
- [x] 기존 URL 파라미터와 한글/특수문자 인코딩 검증
- [x] 실제 dev YAML 기본값·환경변수 웹 override 검증
- [x] 최신 `origin/develop`에서 분리한 브랜치에 이번 변경만 적용
- [x] `compileJava`, `compileTestJava`, 관련 테스트 66개 통과
- [x] `git diff --check` 통과

검증 명령:

```sh
./gradlew compileJava test --tests '*SettlementTransferLinkPropertiesTest' --tests '*MockSettlementTransferLinkAdapterTest' --tests '*DevMockTransferLinkConfigurationTest' --tests '*SettlementTransferLinkConfigTest' --console=plain
```

Properties 27개, Mock adapter 25개, dev profile binding 2개, adapter 선택/설정 통합 12개로 총 66개다. 변경 브랜치에서는 기존 테스트 전체 소스도 정상 컴파일됐다. 선택한 테스트는 DB/Redis/Kafka 기동을 요구하지 않는다. 전체 test 실행과 dev 서버 전체 기동은 수행하지 않았다.

별도 Android 앱에서는 cold/warm 링크, 한글 이름/금액 반영, 이름 수정, 격리 Mock API/ledger 반영, 완료 후 복귀, 응답 유실 후 동일 UUID 재시도의 중복 방지를 확인했다. 이 기기 검증은 배포 dev 환경의 인증된 정산 버튼 전체 경로 검증을 대신하지 않는다.

## 배포·잔여 작업

이 PR은 develop을 대상으로 한다. 저장소 CI/CD는 develop push에서 dev 배포를 실행하므로 PR 생성 자체가 배포 완료를 의미하지 않는다. 기존 배포 환경의 `SETTLEMENT_MOCK_TRANSFER_BASE_URL`이 웹 주소라면 `howmuch-mock://send`로 갱신해야 YAML 기본값 대신 기존 값이 적용되는 일을 피할 수 있다.

모바일 호출자 변경 적용, 별도 APK 설치, dev 배포 후 실제 정산 송금 안내 응답 및 앱 진입을 확인한다. 운영 TOSS 설정 변경과 실제 은행 송금은 범위 밖이다.

커밋 경계: `feat(settlement): dev Mock 송금 링크를 Android 앱으로 연결` 한 기능 단위.
