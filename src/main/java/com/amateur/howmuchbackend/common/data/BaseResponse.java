package com.amateur.howmuchbackend.common.data;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaseResponse<T>(
        boolean success,
        T data,
        ErrorResponse error
) {

    public BaseResponse(T data) {
        this(true, data, null);
    }

    public BaseResponse() {
        this(true, null, null);
    }

    public BaseResponse(ErrorResponse error) {
        this(false, null, error);
    }
}
