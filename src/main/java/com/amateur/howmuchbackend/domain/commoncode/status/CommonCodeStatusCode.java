package com.amateur.howmuchbackend.domain.commoncode.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonCodeStatusCode implements StatusCode {
    INVALID_COMMON_CODE("공통 코드가 올바르지 않습니다.", 400),;

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
