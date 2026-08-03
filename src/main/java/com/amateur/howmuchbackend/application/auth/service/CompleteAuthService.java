package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.application.port.in.auth.CompleteAuthUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.CompleteAuthRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class CompleteAuthService implements CompleteAuthUseCase {
    private final AuthChallengeStorePort authChallengeStorePort;
    private final UserRepositoryPort userRepositoryPort;
    private final LoginService loginService;
    private final SignUpService signUpService;

    @Override
    @Transactional
    public TokenResponse complete(CompleteAuthRequest request) {
        AuthChallenge challenge = authChallengeStorePort.findById(request.challengeId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.AUTH_CHALLENGE_EXPIRED));
        if (challenge.isExpired(LocalDateTime.now(ZoneOffset.UTC))) {
            authChallengeStorePort.deleteById(challenge.challengeId());
            throw ApplicationException.of(AuthStatusCode.AUTH_CHALLENGE_EXPIRED);
        }

        TokenResponse response = userRepositoryPort.findByIdentityKey(challenge.identity().identityKey())
                .map(user -> loginService.login(challenge, user, request.pin()))
                .orElseGet(() -> signUpService.signUp(challenge, request.pin()));

        authChallengeStorePort.deleteById(challenge.challengeId());
        return response;
    }
}
