package com.amateur.howmuchbackend.domain.treasurer.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TreasurerStatusCode implements StatusCode {
    // 남의 답변인지 없는 답변인지 구분해 알리지 않는다. 존재 여부가 새면 안 된다.
    TREASURER_ANSWER_NOT_FOUND("신고할 답변을 찾을 수 없습니다.", 404),
    TREASURER_ANSWER_ALREADY_REPORTED("이미 신고한 답변입니다.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
