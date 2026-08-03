package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.DevLoginUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.DevLoginRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
@RequiredArgsConstructor
public class DevLoginService implements DevLoginUseCase {
    private final IdentityPolicy identityPolicy;
    private final UserRepositoryPort userRepositoryPort;
    private final LoginService loginService;

    @Override
    public TokenResponse login(DevLoginRequest request) {
        String identityKey = identityPolicy.normalize(
                request.name(),
                request.residentNumber(),
                request.phoneNumber()
        ).identityKey();
        User user = userRepositoryPort.findByIdentityKey(identityKey)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        return loginService.loginWithoutChallenge(user, request.pin());
    }
}
