package com.amateur.howmuchbackend.domain.recap.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RecapStatusCode implements StatusCode {
    RECAP_NOT_FOUND("해당 월의 리캡을 찾을 수 없습니다.", 404),
    RECAP_IMAGE_NOT_FOUND("리캡 동행자 사진을 찾을 수 없습니다.", 404);
    private final String message;
    private final int status;
    @Override public String getCode() { return name(); }
}
