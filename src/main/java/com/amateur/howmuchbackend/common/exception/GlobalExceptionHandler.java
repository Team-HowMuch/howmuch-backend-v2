package com.amateur.howmuchbackend.common.exception;

import com.amateur.howmuchbackend.common.data.BaseResponse;
import com.amateur.howmuchbackend.common.data.ErrorResponse;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleApplicationException(ApplicationException exception) {
        var statusCode = exception.getStatusCode();
        SafeExceptionLogger.log(exception, statusCode.getStatus());
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
        SafeExceptionLogger.log(exception, 400);
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
        SafeExceptionLogger.log(exception, 400);
        ErrorResponse error = ErrorResponse.of(400, "INVALID_ARGUMENT", exception.getMessage());
        return ResponseEntity.badRequest().body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<BaseResponse<ErrorResponse>> handleRequestConversionException(Exception exception) {
        SafeExceptionLogger.log(exception, 400);
        ErrorResponse error = ErrorResponse.of(
                400,
                "INVALID_ARGUMENT",
                "요청값이 올바르지 않습니다."
        );
        return ResponseEntity.badRequest().body(new BaseResponse<>(false, null, error));
    }

    /**
     * 업로드 한도를 넘었다. 요청이 잘못된 것이므로 500이 아니라 413으로 답한다.
     *
     * <p>멀티파트 파싱은 핸들러를 찾기 전에 끝난다. 그래서 컨트롤러 안의
     * {@code @ExceptionHandler}로는 잡히지 않고 여기서만 잡을 수 있다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception
    ) {
        SafeExceptionLogger.log(exception, CommonStatusCode.PAYLOAD_TOO_LARGE.getStatus());
        ErrorResponse error = ErrorResponse.of(
                CommonStatusCode.PAYLOAD_TOO_LARGE.getStatus(),
                CommonStatusCode.PAYLOAD_TOO_LARGE.getCode(),
                CommonStatusCode.PAYLOAD_TOO_LARGE.getMessage()
        );
        return ResponseEntity.status(error.status()).body(new BaseResponse<>(false, null, error));
    }

    /**
     * 멀티파트 본문 자체가 깨졌다. 한도 초과는 위에서 더 구체적으로 잡는다.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleMalformedMultipart(MultipartException exception) {
        SafeExceptionLogger.log(exception, 400);
        ErrorResponse error = ErrorResponse.of(400, "INVALID_ARGUMENT", "요청값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleOptimisticLockingFailure(OptimisticLockingFailureException exception) {
        SafeExceptionLogger.log(exception, 409);
        ErrorResponse error = ErrorResponse.of(
                CommonStatusCode.CONCURRENT_UPDATE.getStatus(),
                CommonStatusCode.CONCURRENT_UPDATE.getCode(),
                CommonStatusCode.CONCURRENT_UPDATE.getMessage()
        );
        return ResponseEntity.status(error.status()).body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class
    })
    public ResponseEntity<BaseResponse<ErrorResponse>> handleTemporaryPersistenceFailure(
            RuntimeException exception
    ) {
        SafeExceptionLogger.log(exception, 503);
        ErrorResponse error = ErrorResponse.of(
                CommonStatusCode.TEMPORARILY_UNAVAILABLE.getStatus(),
                CommonStatusCode.TEMPORARILY_UNAVAILABLE.getCode(),
                CommonStatusCode.TEMPORARILY_UNAVAILABLE.getMessage()
        );
        return ResponseEntity.status(error.status())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new BaseResponse<>(false, null, error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<ErrorResponse>> handleException(Exception ex) {
        SafeExceptionLogger.log(ex, 500);

        ErrorResponse error = ErrorResponse.of(
                CommonStatusCode.INTERNAL_SERVER_ERROR.getStatus(),
                CommonStatusCode.INTERNAL_SERVER_ERROR.getCode(),
                CommonStatusCode.INTERNAL_SERVER_ERROR.getMessage()
        );
        return ResponseEntity.status(error.status()).body(new BaseResponse<>(error));
    }
}
