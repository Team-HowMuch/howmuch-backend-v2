# 아마추어 부스 시연 운영 콘솔

네 시연 계정과 기존 정산을 유지하면서 새 체험 모임, 압축 다음 회차, 알림, 리캡을 준비하는 dev 운영자 전용 도구다. 구현과 확인 기록은 [Feature Plan](../../../docs/features/active/amateur-netting-rounds.md)에 있다.

## 배포 상태

2026-10-21 기준 이전 버전의 콘솔이 dev 호스트에서 실행 중이다. 최신 부스 화면·그룹 및 좌우 2인 템플릿·리캡 UI·외부 HTTPS 배포는 서버 저장소의 `fsync`/`ENOSPC` 장애로 대기 중이다. 실제 압축 다음 회차 저장과 최신 UI의 최종 통합 확인도 남아 있다. 아래 기능 목록은 이 PR의 구현 범위이며 최신 배포 완료를 의미하지 않는다.

## 사전 조건

- 기존 아마추어 dev 템플릿 실행으로 사용자 85~88, 활성 수취 계좌, 모임, 미납 정산 5건이 이미 존재해야 한다. 이 PR에는 전체 초기화 SQL이나 실제 실행 결과를 포함하지 않는다.
- dev Docker 호스트에 Python 3.11 이상, `psql`, Docker CLI가 필요하다. 실행 사용자는 앱 컨테이너 접근 및 DB 연결 권한이 있어야 한다.
- 앱 컨테이너 이름은 `howmuch-infra-app-1`이며 `dev` 프로필과 `howmuch_v2_dev` DB를 사용해야 한다. 다른 프로필/DB는 거부한다. Java helper는 실행 중인 앱의 Java 21 런타임을 사용한다.
- 현재 실행의 manifest를 콘솔 파일과 같은 디렉터리의 `applied.json`에 제공한다. 기존 템플릿의 서버 원본은 `/var/lib/howmuch/templates/amateur/current.json`이다. `template`, `run`, `actors`, `group_id`, `ordinary_ids`, `settlement_ids`, `expected_netting` 필드를 사용한다. 계정 이름과 ID가 현재 전용 템플릿에 고정되어 있어 임의 사용자용 콘솔은 아니다.
- 기존 앱에 별도 ADMIN 운영자 계정이 있어야 한다. 시연 폰의 일반 계정은 콘솔 로그인에서 거부한다. 운영자 번호/PIN과 실제 manifest·상태 파일은 Git에 넣지 않는다. 기존 앱의 사용자별 로그인 세션 정책이 적용된다.

## 실행

파일을 dev 호스트의 `/home/ubuntu/howmuch-amateur-demo/`에 배치하고 manifest를 준비한다. 기존 계정·모임을 다시 만드는 초기화는 실행하지 않는다.

```sh
cd /home/ubuntu/howmuch-amateur-demo
python3 console-server.py
```

서버는 `127.0.0.1:18120`에만 바인딩한다. 지속 실행은 [user service](howmuch-amateur-console.service)를 `~/.config/systemd/user/`에 설치하고 활성화한다. user service의 로그아웃 후 지속 여부는 호스트의 linger 설정을 따른다.

운영자 노트북에서는 [open-console](open-console)을 실행해 SSH 연결을 연다. `HOWMUCH_DEMO_SSH_KEY`, `HOWMUCH_DEMO_SSH_HOST`, `HOWMUCH_DEMO_LOCAL_PORT`로 접속 정보를 바꿀 수 있다. 기본 SSH 키 경로는 로컬 개발 환경의 경로이므로 다른 노트북에서는 명시적으로 설정한다.

```sh
HOWMUCH_DEMO_SSH_KEY=/path/to/dev-key ./open-console
```

운영 주소는 `http://127.0.0.1:18120/admin/amateur`다. 운영자 로그인 정보는 서버 운영자가 별도 비공개 경로로 전달한다.

### 다른 노트북용 HTTPS

[deploy-console](deploy-console)은 이미 준비된 dev 호스트에 콘솔 코드만 배포하고, [전용 Docker 이미지](Dockerfile.console)에서 콘솔과 Cloudflare Quick Tunnel을 함께 실행한다. 실행 중인 앱 컨테이너, manifest, 기존 계정 및 시연 데이터는 교체하지 않는다. 전송한 코드 파일의 저장 확정과 이미지 빌드가 성공한 뒤 전용 콘솔 서비스만 교체한다.

```sh
HOWMUCH_DEMO_SSH_KEY=/path/to/dev-key ./deploy-console
```

터널 주소는 서버의 `console-state/public-origin.txt`에 기록한다. **임시 주소는 터널을 새로 시작하면 바뀐다.** 고정 도메인은 별도 설정이 필요하다. 콘솔 컨테이너는 non-root, 읽기 전용 rootfs, `cap-drop ALL`을 사용하지만 기존 복제 엔진 실행을 위해 Docker socket에 접근하므로 dev 운영자 전용이다. 모든 업무 요청은 기존 앱의 ADMIN 권한을 확인한다. 정확한 현재 Host/Origin만 허용하고 로그인 횟수를 제한한다.

## 부스에서 쓰는 순서

| 할 일 | 운영 화면 | 폰에서 할 일 |
| --- | --- | --- |
| 기본 3분 시연 | 설명 시연 시작 → 단계 안내 | 오른쪽 문채원 생성 → 최수아 참여·송금 안내 |
| 왼쪽 자유 체험 | 왼쪽 자유 체험 · 2명 데이터 넣기 | 한승환·최영헌만 새 체험 모임 선택 |
| 오른쪽 메뉴 선택 체험 | 오른쪽 설명 시연 · 2명 데이터 넣기 | 문채원·최수아만 새 카페 모임 선택 |
| 압축 다시 보여주기 | 완료 확인 → 다음 회차 준비 | 문채원 폰에서 그룹 목록 새로고침 → 안내된 새 모임 |
| 별도 압축 연습 | 압축 정산 시나리오 넣기 → 회차 이력에서 선택 | 안내된 새 연습 모임 선택 |
| 직접 준비 | 직접 시나리오 만들기 → 유형·제목·생성자·참여자·금액 저장 → 데이터 넣기 | 생성된 새 모임 선택 |
| 그룹만 준비 | 그룹만 만들기 또는 직접 시나리오의 그룹 유형 | 선택한 멤버에게 새 그룹 표시 |
| 알림 테스트 | 받을 폰·유형·정산/월 선택 → 보내기 | 알림함/푸시 확인 → 운영 화면에서 수신 확인 |
| 리캡 준비 | 계정·월 → 확정 리캡 준비 또는 실제 내역 미리보기 | 지난달 확정 리캡은 앱 리캡 화면에서 조회 |

영수증 시나리오는 인식된 아메리카노·케이크 품목의 선택 단계부터 시작한다. OCR 촬영 자체를 검증하려면 앱에서 직접 영수증을 촬영한다. 정산은 2~4명, 그룹만 생성은 1~4명으로 저장할 수 있다. 생성한 그룹 이름에는 ID가 붙어 반복 시연의 모임을 구분한다.

### 시연이 겹치거나 응답이 끊길 때

- 옆 폰이 영수증 체험 중이면 해당 폰을 이동시키지 않고 새 압축 모임만 준비한다. 그룹 목록과 새 모임 선택은 각 폰에서 직접 진행한다.
- 홈·AI 합계를 설명 중이면 폰을 `홈·AI 금액 설명 중`으로 표시한다. 새 정산은 계정 전체 합계를 바꾸므로 데이터 추가를 차단한다. 설명이 끝나면 대기로 바꾼다. 이 상태는 운영자가 수동 표시하며 앱 자체 조작을 잠그지는 않는다.
- 폰 상태를 동시에 수정하면 revision 충돌을 감지한다. 다시 조회한 후 선택한다.
- 회차 준비·시나리오 생성의 응답이 끊기면 결과 확인 버튼을 사용한다. 같은 UUID로 재시도해 중복 생성을 피한다.
- 진행 중인 압축이 있으면 새 회차 준비를 차단한다. 정상 완료 후 `NEXT`, 기준과 달라진 상태는 `RECOVER`로 기존 내역을 남기고 새 회차를 만든다.

### 알림과 리캡

알림은 실제 알림함과 기존 발송 outbox에 추가한다. 정산 요청·독촉·리마인드·정산 완료·입금 확인·월별 리캡을 지원하며 실제 참여 및 정산 상태를 확인한다. 제공자 접수와 운영자의 폰 수신 확인을 구분한다. 기록된 확인 당시 네 계정에는 푸시 수신 정보가 없어 발송이 실패했다. 각 폰에서 로그인·알림 허용 후 새 요청으로 확인해야 한다. 리마인드는 즉시 발송 테스트이며 자동 예약 시간 검증은 아니다.

리캡은 실행 중인 앱의 실제 집계 로직을 사용한다. 지난달까지 없는 snapshot만 생성하고 기존 리캡은 보존한다. 이번 달은 저장 없이 웹에서만 미리보기로 제공한다. 완료 내역이 없는 달은 빈 결과로 안내한다. 제휴 혜택은 서버와 동일하게 없다. 전역 리캡 배치 설정은 변경하지 않는다.

## CLI와 helper 유지보수

[next-netting-round](next-netting-round)는 실제 압축 완료 후 같은 디렉터리의 manifest와 `state/`를 사용해 다음 회차를 준비한다. [엔진](prepare-netting-round.py)은 `--apply` 없이 실행하면 생성 INSERT를 검증 후 롤백한다. 같은 dev DB의 sequence 번호에는 빈 값이 생길 수 있다.

Java helper를 바꾸면 Java 21로 컴파일하고 해당 `.class`의 Base64 파일도 갱신한다. 소스와 산출물에는 실제 키·계좌번호가 포함되지 않는다.

```sh
mkdir -p /tmp/howmuch-demo-classes
javac --release 21 -d /tmp/howmuch-demo-classes NettingRoundCrypto.java RecapConsole.java
python3 - <<'PY'
import base64
from pathlib import Path
for source, target in [('NettingRoundCrypto', 'netting-round-crypto.b64'), ('RecapConsole', 'recap-console.b64')]:
    Path(target).write_text(base64.b64encode(Path('/tmp/howmuch-demo-classes', source + '.class').read_bytes()).decode() + '\n')
PY
```

검증 결과와 남은 항목은 Feature Plan에 기록한다.
