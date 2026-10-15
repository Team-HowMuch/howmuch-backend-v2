# 인증서 로컬 통합 테스트
- Status: 로컬 검증 완료
- 목적: 실제 로컬 로그인과 Kafka 인증서 등록, 모바일 승인, 계좌 후보 조회를 재현한다.
- 범위: 기존 CODEF mock에 인증서 등록/갱신/취소 지원, synthetic 로컬 사용자 seed, connector HTML 모바일 테스트 클라이언트.
- DB: 기존 users에 테스트 데이터만 추가하며 스키마 변경 및 Flyway migration은 필요 없다.
- 보안: loopback 전용, 가상 인증서와 가상 계좌만 사용. 실제 암호 검증은 CODEF mock 범위가 아니다.
- 검증: 정상 로그인 → pairing → 등록/upload → Kafka READY_FOR_CLAIM → approval → 최종 YES → BOUND → 계좌 조회.
- 커밋 경계: 이번 요청은 로컬 실행/검증이며 commit/push하지 않는다.

## 검증 결과
- 로컬 가상 계정을 BCrypt 해시로 seed하고 기존 로그인 API를 사용했다. 인증/인가 우회 endpoint는 추가하지 않았다.
- 기존 로컬 인증서 wrapping/confirmation 키를 유지하고 누락된 ACCOUNT_CREDENTIAL_LOCAL_KEY를 준비했다. runtime 설정은 저장소 밖 권한 600 파일에 보관한다.
- 최초 실행에서 최종 YES가 BANK_ACCOUNT_CREDENTIAL_CONFIGURATION_INVALID로 실패했다. 로컬 키 설정 후 BOUND를 확인했다.
- 초기 재시도에서 이전 미승인 세션 때문에 CERTIFICATE_CONFLICT가 발생했다. 해당 가상 사용자의 이전 테스트 pairing을 정상 취소 API로 정리했다.
- 실제 로컬 API/Redis/PostgreSQL/Kafka/CODEF mock으로 로그인 → pairing → 등록/upload → READY_FOR_CLAIM → approval → YES → BOUND → 연결 목록 1개/계좌 후보 1개를 확인했다. 기존 연결 갱신 경로도 통과했다.
- Python mock 테스트 3개 통과. Java 변경 없음. backend 기동 과정에서 compileJava 성공(UP-TO-DATE), readiness UP.
- 실제 은행과 실제 공동인증서 비밀번호 검증 및 브라우저 전체 수동 클릭 검증은 수행하지 않았다.

## 게시
- 사용자 요청에 따라 `test/certificate-local-mock`에서 mock/검증/문서를 하나의 `test(codef)` 커밋으로 게시한다.
- 이슈 #527, PR 대상 develop. 로컬 사용자 seed·개인 runtime 키·가상 인증서 파일은 게시하지 않는다.
- 추가 검증: 최신 origin/develop 기반 `./gradlew compileJava --no-daemon` 통과.
