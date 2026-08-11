package com.amateur.howmuchbackend.domain.user.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserStatusCode implements StatusCode {
    PROFILE_IMAGE_NOT_FOUND("등록된 아바타를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    private final String message;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
