package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.DevLoginRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;

public interface DevLoginUseCase {
    TokenResponse login(DevLoginRequest request);
}
