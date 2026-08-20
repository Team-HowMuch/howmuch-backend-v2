package com.amateur.howmuchbackend.common.exception.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonStatusCode implements StatusCode {
    INTERNAL_SERVER_ERROR("서버 내부 오류가 발생했습니다", 500),
    INVALID_ARGUMENT("유효하지 않은 인자입니다.", 400),
    ENDPOINT_NOT_FOUND("존재하지 않는 엔드포인트입니다.", 404),
    METHOD_NOT_ALLOWED("허용되지 않은 메서드입니다", 405),
    UNAUTHORIZED("인증이 필요합니다", 401),
    FORBIDDEN("권한이 없습니다", 403),
    TOO_MANY_REQUEST("너무 많은 요청이 들어왔습니다", 429);


    private final String message;
    private final int status;
    @Override
    public String getCode() {
        return name();
    }
}
