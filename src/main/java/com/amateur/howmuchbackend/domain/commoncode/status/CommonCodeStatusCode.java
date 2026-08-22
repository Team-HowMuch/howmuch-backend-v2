package com.amateur.howmuchbackend.domain.commoncode.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonCodeStatusCode implements StatusCode {
    INVALID_COMMON_CODE("공통 코드가 올바르지 않습니다.", 400),
    COMMON_CODE_GROUP_NOT_FOUND("공통 코드 그룹을 찾을 수 없습니다.", 404),
    COMMON_CODE_GROUP_ALREADY_EXISTS("이미 존재하는 공통 코드 그룹입니다.", 409),
    COMMON_CODE_DETAIL_ALREADY_EXISTS("이미 존재하는 공통 코드입니다.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
