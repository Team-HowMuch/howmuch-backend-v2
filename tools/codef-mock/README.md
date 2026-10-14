# HowMuch CODEF Mock

CODEF를 바로 사용할 수 없을 때 HowMuch의 은행 연결과 계좌 연동 흐름을 실행하기 위한 로컬 전용 대체 서버다. Python 표준 라이브러리만 사용하며 OAuth, 계정 등록·추가, 개인 은행 계좌 후보 조회와 관리 웹을 한 프로세스에서 제공한다.

실제 금융기관 ID, 비밀번호, 계좌번호 또는 개인정보를 입력하지 않는다. CODEF 요청의 비밀번호 암호문은 무시하며 저장하거나 로그에 남기지 않는다.

## 실행

저장소 루트에서 실행한다.

```bash
python3 tools/codef-mock/server.py
```

- 관리 웹: <http://127.0.0.1:18080>
- 상태 확인: <http://127.0.0.1:18080/health>
- SQLite: `tools/codef-mock/data/codef-mock.db`
- 기본 사용자: `demo-user`
- 기본 기관코드: `0020`

`data/`는 실행 중 생성되는 로컬 데이터이므로 Git에 포함하지 않는다.

## HowMuch 연결 설정

가장 빠른 방법은 Mock 전용 프로필을 함께 켜는 것이다. `application-codef-mock.yaml`에는 localhost 전용 가상 OAuth credential, public key, 고정 암호화 키가 있어 별도 CODEF 환경변수가 필요 없다.

```bash
SPRING_PROFILES_ACTIVE=local,codef-mock ./gradlew bootRun
```

관리 웹에는 비밀번호 필드가 없다. HowMuch API의 `password`는 RSA 암호화 경로만 검증하기 위해 `fake-password`처럼 비어 있지 않은 임의 값을 전송한다. Mock은 이 암호문을 검증·저장·로그하지 않고 `loginId + organization`으로 가상 계좌를 찾는다. OAuth의 `mock-client / mock-secret`만 실제로 검증한다.

아래는 프로필 대신 환경변수로 직접 설정하는 경우다.

다음 값을 프로세스 환경 또는 로컬 `.env`에 설정한다. `CODEF_CONNECTED_ID_ENCRYPTION_KEY`는 한 번만 생성해 계속 같은 값을 사용해야 한다. 실행할 때마다 바꾸면 시작 시 기존 연결이 비활성화된다.

```bash
CODEF_BASE_URL=http://127.0.0.1:18080
CODEF_OAUTH_URL=http://127.0.0.1:18080/oauth/token
CODEF_CLIENT_ID=mock-client
CODEF_CLIENT_SECRET=mock-secret
CODEF_PUBLIC_KEY=<tools/codef-mock/mock-public-key.txt의 한 줄 값>
CODEF_CONNECTED_ID_ENCRYPTION_KEY=<openssl rand -base64 32로 한 번 생성한 값>
```

HowMuch API 요청 예시의 금융기관 비밀번호는 Mock이 검증하거나 저장하지 않으므로 가상 문자열을 사용한다.

```json
{
  "organization": "0020",
  "loginId": "demo-user",
  "password": "fake-password",
  "birthDate": "19990101"
}
```

## 선택 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `CODEF_MOCK_HOST` | `127.0.0.1` | bind 주소 |
| `CODEF_MOCK_PORT` | `18080` | HTTP 포트 |
| `CODEF_MOCK_DB` | `tools/codef-mock/data/codef-mock.db` | SQLite 경로 |
| `CODEF_MOCK_CLIENT_ID` | `mock-client` | 로컬 OAuth client ID |
| `CODEF_MOCK_CLIENT_SECRET` | `mock-secret` | 로컬 OAuth client secret |

기본 설정은 외부 접속을 받지 않는다. 격리된 개발 네트워크에서만 `CODEF_MOCK_ALLOW_REMOTE=true`와 별도 가상 credential을 설정해 remote bind를 허용한다.

## 인증서 앱 로컬 테스트

`loginType: "0"` 등록은 `derFile`, `keyFile`, `password`의 존재만 확인하고 가상 `demo-user`로 연결한다. 인증서 본문은 decode/저장/로그하지 않으며 실제 공동인증서 암호 검증을 대신하지 않는다. 기존 ID/PW `loginType: "1"` 경로는 유지한다.

인증서 신규 등록, 기존 연결 조회(`/v1/account/list`), 추가/갱신(`/v1/account/add`, `/v1/account/update`), 취소(`/v1/account/delete`)를 지원한다. 등록된 기관의 계좌 후보는 기존 mock 관리 웹에서 수정한다.

```bash
python3 -m unittest discover -s tools/codef-mock -p 'test_server.py'
```
