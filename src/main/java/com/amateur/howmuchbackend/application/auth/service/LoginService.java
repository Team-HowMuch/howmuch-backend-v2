package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.LoginUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.LoginRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import com.amateur.howmuchbackend.application.port.out.auth.LoginAttemptStorePort;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
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
public class LoginService implements LoginUseCase {
    private final PinPolicy pinPolicy;
    private final IdentityPolicy identityPolicy;
    private final LoginAttemptStorePort loginAttemptStorePort;
    private final UserRepositoryPort userRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String phoneNumber = identityPolicy.normalizePhoneNumber(request.phoneNumber());
        validatePin(request.pin());

        String phoneHash = identityPolicy.phoneHash(phoneNumber);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (loginAttemptStorePort.getFailureCount(phoneHash) >= pinPolicy.maxAttempts()) {
            throw ApplicationException.of(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED);
        }

        User user = userRepositoryPort.findByPhoneNumber(phoneNumber).orElse(null);
        if (user == null || !passwordEncoder.matches(request.pin(), user.pinHash())) {
            handleFailure(phoneHash);
        }
        if (!user.active()) {
            throw ApplicationException.of(AuthStatusCode.USER_INACTIVE);
        }

        loginAttemptStorePort.deleteByPhoneHash(phoneHash);
        User updated = user.markLoggedIn(now);
        userRepositoryPort.update(updated);
        return authTokenService.issueNewSession(updated);
    }

    private void validatePin(String pin) {
        if (!pinPolicy.isFormatValid(pin)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
    }

    private void handleFailure(String phoneHash) {
        long failureCount = loginAttemptStorePort.increaseFailureCount(
                phoneHash,
                pinPolicy.maxAttempts(),
                pinPolicy.lockDuration()
        );
        throw ApplicationException.of(failureCount >= pinPolicy.maxAttempts()
                ? AuthStatusCode.PIN_ATTEMPTS_EXCEEDED
                : AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
    }
}
