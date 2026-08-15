package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.LogoutRequest;

public interface LogoutUseCase {
    void logout(LogoutRequest request);
}
