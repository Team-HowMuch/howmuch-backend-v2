package com.amateur.howmuchbackend.domain.external.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AdditionalAuthenticationStatusCode implements StatusCode {
    FINANCE_AUTHENTICATION_NOT_FOUND("추가 인증 요청을 찾을 수 없습니다.", 404),
    FINANCE_AUTHENTICATION_EXPIRED("추가 인증 시간이 만료되었습니다. 다시 연결해주세요.", 410),
    FINANCE_AUTHENTICATION_CONFLICT("이미 접수되었거나 종료된 인증 요청입니다.", 409),
    FINANCE_AUTHENTICATION_INPUT_INVALID("SMS 인증번호를 확인해주세요.", 400);
    private final String message;
    private final int status;

    public String getCode() {
        return name();
    }
}
