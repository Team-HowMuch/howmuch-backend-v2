package com.amateur.howmuchbackend.common.exception;

import com.amateur.howmuchbackend.common.data.BaseResponse;
import com.amateur.howmuchbackend.common.data.ErrorResponse;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleApplicationException(ApplicationException exception) {
        var statusCode = exception.getStatusCode();
        ErrorResponse error = ErrorResponse.of(
                statusCode.getStatus(),
                statusCode.getCode(),
                exception.getMessage()
        );
        return ResponseEntity.status(statusCode.getStatus())
                .body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ErrorResponse error = ErrorResponse.of(
                400,
                "INVALID_ARGUMENT",
                "요청값이 올바르지 않습니다.",
                details
        );
        return ResponseEntity.badRequest().body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        ErrorResponse error = ErrorResponse.of(400, "INVALID_ARGUMENT", exception.getMessage());
        return ResponseEntity.badRequest().body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleException(Exception ex) {
        ex.printStackTrace();

        ErrorResponse error = ErrorResponse.of(
                CommonStatusCode.INTERNAL_SERVER_ERROR.getStatus(),
                CommonStatusCode.INTERNAL_SERVER_ERROR.getCode(),
                CommonStatusCode.INTERNAL_SERVER_ERROR.getMessage()
        );
        return ResponseEntity.status(error.status()).body(new BaseResponse<>(error));
    }
}
