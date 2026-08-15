package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.SignUpRequest;

public interface SignUpUseCase {
    void signUp(SignUpRequest request);
}
