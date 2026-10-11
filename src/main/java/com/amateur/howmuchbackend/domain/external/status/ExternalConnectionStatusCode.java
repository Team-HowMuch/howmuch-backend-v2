package com.amateur.howmuchbackend.domain.external.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExternalConnectionStatusCode implements StatusCode {
    CODEF_CONFIGURATION_INVALID("금융 연결 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요.", 500),
    BANK_CERTIFICATE_PASSWORD_INVALID("인증서 암호를 확인해주세요. 계속 실패하면 인증서를 다시 가져와주세요.", 422),
    BANK_CERTIFICATE_FILE_INVALID("인증서 파일을 사용할 수 없습니다. 인증서를 다시 가져와주세요.", 422),
    BANK_CERTIFICATE_REGISTRATION_REQUIRED("해당 금융기관에 인증서를 등록한 뒤 다시 시도해주세요.", 422),
    BANK_CERTIFICATE_EXPIRED("만료된 인증서입니다. 인증서를 갱신하거나 재발급해주세요.", 422),
    BANK_CERTIFICATE_UNAVAILABLE("만료·폐기되었거나 효력이 정지된 인증서입니다. 금융기관에서 확인해주세요.", 422),
    BANK_CERTIFICATE_INVALID("이 인증서로 금융기관에 로그인할 수 없습니다. 인증서 종류와 등록 상태를 확인해주세요.", 422),
    BANK_ACCOUNT_PASSWORD_INVALID("계좌 비밀번호를 확인해주세요. 반복해서 입력하면 계좌 이용이 제한될 수 있습니다.", 422),
    BANK_ACCOUNT_PASSWORD_LOCKED("계좌 비밀번호 오류 횟수를 초과했습니다. 금융기관에서 이용 제한을 해제해주세요.", 423),
    BANK_ACCOUNT_NUMBER_INVALID("입력한 계좌번호와 본인 보유 계좌 여부를 확인해주세요.", 422),
    BANK_BIRTH_DATE_INVALID("생년월일을 확인해주세요.", 422),
    BANK_IDENTITY_MISMATCH("입력한 본인 정보와 금융기관 인증 정보가 일치하는지 확인해주세요.", 422),
    BANK_SERVICE_UNAVAILABLE("금융기관에서 인터넷뱅킹 가입 여부와 이용 상태를 확인해주세요.", 422),
    BANK_QUERY_PERIOD_INVALID("조회 기간을 확인해주세요.", 400),
    BANK_CREDENTIAL_INVALID("은행 로그인 정보를 확인해주세요.", 422),
    BANK_ACCOUNT_CREDENTIAL_REQUIRED("계좌번호와 계좌 비밀번호를 입력해주세요.", 400),
    BANK_ACCOUNT_RECONNECT_REQUIRED("은행 연결 정보를 사용할 수 없습니다. 금융기관을 다시 연결해주세요.", 409),
    BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID("저장된 계좌 인증 정보를 사용할 수 없습니다.", 500),
    BANK_ACCOUNT_CREDENTIAL_CONFIGURATION_INVALID("계좌 인증 정보 암호화 설정이 올바르지 않습니다.", 500),
    BANK_CREDENTIAL_LOCKED("은행 로그인 계정이 잠겼습니다. 해당 은행에서 잠금을 해제한 뒤 다시 시도해주세요.", 423),
    CODEF_ADDITIONAL_AUTH_REQUIRED("금융기관 추가 인증이 필요하지만 현재 지원하지 않습니다.", 409),
    CODEF_USER_ACTION_TIMEOUT("금융기관 인증 입력 시간이 만료되었습니다. 처음부터 다시 시도해주세요.", 408),
    CODEF_RATE_LIMITED("금융기관 조회 한도를 초과했습니다. 안내된 대기 시간 이후 다시 시도해주세요.", 429),
    CODEF_UPSTREAM_UNAVAILABLE("외부 금융기관과 통신할 수 없습니다. 잠시 후 다시 시도해주세요.", 503),
    CODEF_UNMAPPED_ERROR("외부 금융기관에서 확인되지 않은 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", 502),
    CODEF_RESPONSE_INVALID("외부 금융기관 응답이 올바르지 않습니다. 잠시 후 다시 시도해주세요.", 502),
    BANK_CONNECTION_FAILED("은행 연결에 실패했습니다.", 422),
    BANK_NOT_SUPPORTED("지원하지 않는 은행입니다.", 400),
    EXTERNAL_CONNECTION_NOT_FOUND("사용 가능한 외부 금융 연결을 찾을 수 없습니다.", 404),
    ACCOUNT_CANDIDATE_INVALID("연동할 계좌 후보가 유효하지 않습니다.", 400);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
