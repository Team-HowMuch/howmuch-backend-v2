package com.amateur.howmuchbackend.common.data;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String code,
        String message,
        LocalDateTime timestamp,
        Map<String, String> details
) {
    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(status, code, message, LocalDateTime.now(), null);
    }

    public static ErrorResponse of(int status, String code, String message, Map<String, String> details) {
        return new ErrorResponse(status, code, message, LocalDateTime.now(), details);
    }
}
