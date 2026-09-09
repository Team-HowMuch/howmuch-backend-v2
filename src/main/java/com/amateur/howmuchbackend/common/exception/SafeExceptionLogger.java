package com.amateur.howmuchbackend.common.exception;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Never pass exception messages, causes or suppressed exceptions to the logging backend. */
@Slf4j
public final class SafeExceptionLogger {
    private SafeExceptionLogger() {}

    public static void log(Throwable exception, int status) {
        var event = status >= 500 ? log.atError() : log.atWarn();
        event.addKeyValue("event", "request_exception")
                .addKeyValue("status", status)
                .addKeyValue("exceptionType", exception.getClass().getName())
                .addKeyValue("codeLocations", Arrays.stream(exception.getStackTrace())
                        .filter(frame -> frame.getClassName().startsWith("com.amateur.howmuchbackend."))
                        .limit(12)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                        .collect(Collectors.joining(",")))
                .log("Request exception");
    }
}
