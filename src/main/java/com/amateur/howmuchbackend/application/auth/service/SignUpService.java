package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.user.policy.NicknamePolicy;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SignUpService {
    private final PinPolicy pinPolicy;
    private final NicknamePolicy nicknamePolicy;
    private final UserRepositoryPort userRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    @Transactional
    public TokenResponse signUp(AuthChallenge challenge, String pin) {
        if (!pinPolicy.isValid(pin)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
        if (userRepositoryPort.findByIdentityKey(challenge.identity().identityKey()).isPresent()) {
            throw ApplicationException.of(AuthStatusCode.INVALID_AUTH_REQUEST);
        }

        User user = User.register(
                challenge.identity(),
                nicknamePolicy.normalize(challenge.identity().name()),
                passwordEncoder.encode(pin),
                LocalDateTime.now(ZoneOffset.UTC)
        );
        return authTokenService.issueNewSession(userRepositoryPort.save(user));
    }
}
