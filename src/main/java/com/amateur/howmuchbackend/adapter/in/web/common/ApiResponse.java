package com.amateur.howmuchbackend.adapter.in.web.common;

import com.amateur.howmuchbackend.common.data.BaseResponse;
import com.amateur.howmuchbackend.common.data.ErrorResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public class ApiResponse<T> extends ResponseEntity<BaseResponse<T>> {

    public ApiResponse(BaseResponse<T> body, HttpStatusCode status) {
        super(body, status);
    }

    public ApiResponse(int status) {
        this(new BaseResponse<>(), HttpStatusCode.valueOf(status));
    }

    public ApiResponse(int status, T data) {
        this(new BaseResponse<>(data), HttpStatusCode.valueOf(status));
    }

    public ApiResponse(int status, ErrorResponse error) {
        this(new BaseResponse<>(error), HttpStatusCode.valueOf(status));
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, data);
    }

    public static <T> ApiResponse<T> created() {
        return new ApiResponse<>(201);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, data);
    }

    public static <T> ApiResponse<T> of(int status) {
        return new ApiResponse<>(status);
    }

    public static <T> ApiResponse<T> of(int status, T data) {
        return new ApiResponse<>(status, data);
    }

    public static <T> ApiResponse<T> error(int status, ErrorResponse error) {
        return new ApiResponse<>(status, error);
    }

    public static <T> ApiResponse<T> error(int status, String code, String message) {
        return error(status, ErrorResponse.of(status, code, message));
    }

    public static <T> ApiResponse<T> error(int status, String code, String message, Map<String, String> details) {
        return error(status, ErrorResponse.of(status, code, message, details));
    }
}
