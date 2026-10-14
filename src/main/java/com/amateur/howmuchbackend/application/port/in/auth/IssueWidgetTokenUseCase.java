package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.result.WidgetTokenResult;

public interface IssueWidgetTokenUseCase {
    WidgetTokenResult issue();
}
