package com.amateur.howmuchbackend.domain.external.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExternalConnectionStatusCode implements StatusCode {
    CODEF_CONFIGURATION_INVALID("CODEF 연동 설정이 올바르지 않습니다.", 500),
    BANK_CREDENTIAL_INVALID("은행 로그인 정보를 확인해주세요.", 422),
    CODEF_ADDITIONAL_AUTH_REQUIRED("금융기관 추가 인증이 필요하지만 현재 지원하지 않습니다.", 409),
    CODEF_UPSTREAM_UNAVAILABLE("외부 금융기관과 통신할 수 없습니다. 잠시 후 다시 시도해주세요.", 503),
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
