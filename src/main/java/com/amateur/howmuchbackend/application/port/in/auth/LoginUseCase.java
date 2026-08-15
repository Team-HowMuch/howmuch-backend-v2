package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.LoginRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;

public interface LoginUseCase {
    TokenResponse login(LoginRequest request);
}
