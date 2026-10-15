package com.amateur.howmuchbackend.application.port.in.auth.result;

import java.time.LocalDateTime;

public record WidgetTokenResult(String widgetToken, LocalDateTime expiresAt) {
    @Override
    public String toString() {
        return "WidgetTokenResult[widgetToken=***, expiresAt=" + expiresAt + "]";
    }
}
