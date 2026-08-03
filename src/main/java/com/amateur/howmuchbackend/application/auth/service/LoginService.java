package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class LoginService {
    private final PinPolicy pinPolicy;
    private final AuthChallengeStorePort authChallengeStorePort;
    private final UserRepositoryPort userRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    @Transactional
    public TokenResponse login(AuthChallenge challenge, User user, String pin) {
        validatePin(pin);
        validateActive(user);
        if (!passwordEncoder.matches(pin, user.pinHash())) {
            handlePinFailure(challenge);
        }

        return completeLogin(user);
    }

    @Transactional
    public TokenResponse loginWithoutChallenge(User user, String pin) {
        validatePin(pin);
        validateActive(user);
        if (!passwordEncoder.matches(pin, user.pinHash())) {
            throw ApplicationException.of(AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
        }

        return completeLogin(user);
    }

    private TokenResponse completeLogin(User user) {
        User updated = user.markLoggedIn(LocalDateTime.now(ZoneOffset.UTC));
        userRepositoryPort.update(updated);
        return authTokenService.issueNewSession(updated);
    }

    private void validatePin(String pin) {
        if (!pinPolicy.isValid(pin)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
    }

    private void validateActive(User user) {
        if (!user.active()) {
            throw ApplicationException.of(AuthStatusCode.USER_INACTIVE);
        }
    }

    private void handlePinFailure(AuthChallenge challenge) {
        AuthChallenge increased = challenge.increasePinAttemptCount();
        if (increased.pinAttemptCount() >= pinPolicy.maxAttempts()) {
            authChallengeStorePort.deleteById(challenge.challengeId());
            throw ApplicationException.of(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED);
        }

        Duration remainingTtl = Duration.between(
                LocalDateTime.now(ZoneOffset.UTC),
                challenge.expiresAt()
        );
        authChallengeStorePort.save(challenge.increasePinAttemptCount(), remainingTtl);
        throw ApplicationException.of(AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
    }
}
