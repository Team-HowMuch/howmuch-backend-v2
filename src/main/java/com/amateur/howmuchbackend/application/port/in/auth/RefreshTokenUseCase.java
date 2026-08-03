package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;

public interface RefreshTokenUseCase {
    TokenResponse refresh(RefreshTokenRequest request);
}
