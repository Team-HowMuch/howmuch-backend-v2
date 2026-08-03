package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.CompleteAuthRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;

public interface CompleteAuthUseCase {
    TokenResponse complete(CompleteAuthRequest request);
}
