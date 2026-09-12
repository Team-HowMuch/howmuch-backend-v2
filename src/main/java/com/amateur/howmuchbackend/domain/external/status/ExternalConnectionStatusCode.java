package com.amateur.howmuchbackend.domain.external.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExternalConnectionStatusCode implements StatusCode {
    CODEF_CONFIGURATION_INVALID("CODEF 연동 설정이 올바르지 않습니다.", 500),
    BANK_CREDENTIAL_INVALID("은행 로그인 정보를 확인해주세요.", 422),
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
