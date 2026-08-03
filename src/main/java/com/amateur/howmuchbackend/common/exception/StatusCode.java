package com.amateur.howmuchbackend.common.exception;

import org.springframework.http.HttpStatus;

public interface StatusCode {
    String getMessage();
    HttpStatus getHttpStatus();
    String getCode();
}
