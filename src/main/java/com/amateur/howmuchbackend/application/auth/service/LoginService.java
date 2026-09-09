package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.LoginUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.command.LoginCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;
import com.amateur.howmuchbackend.application.port.out.auth.LoginAttemptStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.RecordUserLoginPort;
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
    private final LoadUserPort loadUserPort;
    private final RecordUserLoginPort recordUserLoginPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    @Override
    @Transactional
    public TokenResult login(LoginCommand command) {
        String phoneNumber = identityPolicy.normalizePhoneNumber(command.phoneNumber());
        validatePin(command.pin());

        String phoneHash = identityPolicy.phoneHash(phoneNumber);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var attempt = loginAttemptStorePort.reserve(phoneHash, pinPolicy.maxAttempts(), pinPolicy.lockDuration())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED));

        User user = loadUserPort.findByPhoneNumber(phoneNumber).orElse(null);
        if (user == null || !passwordEncoder.matches(command.pin(), user.pinHash())) {
            throw ApplicationException.of(attempt.attemptCount() >= pinPolicy.maxAttempts()
                    ? AuthStatusCode.PIN_ATTEMPTS_EXCEEDED
                    : AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
        }
        if (!user.active()) {
            throw ApplicationException.of(AuthStatusCode.USER_INACTIVE);
        }

        loginAttemptStorePort.resetAfterSuccess(phoneHash, attempt.generation());
        User updated = user.markLoggedIn(now);
        recordUserLoginPort.recordLogin(user.id(), updated.lastLoginAt());
        return authTokenService.issueNewSession(updated);
    }

    private void validatePin(String pin) {
        if (!pinPolicy.isFormatValid(pin)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
    }

}
