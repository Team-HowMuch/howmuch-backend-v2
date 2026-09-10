package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.SignUpUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.command.SignUpCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SignUpService implements SignUpUseCase {
    private static final int MAX_PUBLIC_ID_GENERATION_ATTEMPTS = 5;

    private final PinPolicy pinPolicy;
    private final IdentityPolicy identityPolicy;
    private final PublicIdPolicy publicIdPolicy;
    private final AuthChallengeStorePort authChallengeStorePort;
    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void signUp(SignUpCommand command) {
        var challenge = authChallengeStorePort.findById(command.challengeId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.AUTH_CHALLENGE_EXPIRED));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (challenge.isExpired(now)) {
            authChallengeStorePort.deleteById(challenge.challengeId());
            throw ApplicationException.of(AuthStatusCode.AUTH_CHALLENGE_EXPIRED);
        }
        if (!pinPolicy.isValid(command.pin())) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
        if (!command.pin().equals(command.pinConfirmation())) {
            throw ApplicationException.of(AuthStatusCode.PIN_CONFIRMATION_MISMATCH);
        }
        if (!command.requiredTermsAgreed()) {
            throw ApplicationException.of(AuthStatusCode.REQUIRED_TERMS_NOT_AGREED);
        }
        if (loadUserPort.findByPhoneNumber(challenge.phoneNumber()).isPresent()) {
            throw ApplicationException.of(AuthStatusCode.PHONE_NUMBER_ALREADY_REGISTERED);
        }

        User user = User.register(
                identityPolicy.phoneHash(challenge.phoneNumber()),
                normalizeName(command.name()),
                command.birthDate(),
                generateAvailablePublicId(),
                challenge.phoneNumber(),
                passwordEncoder.encode(command.pin()),
                now,
                command.marketingAgreed()
        );
        saveUserPort.save(user);
        authChallengeStorePort.deleteById(challenge.challengeId());
    }

    private String generateAvailablePublicId() {
        for (int attempt = 0; attempt < MAX_PUBLIC_ID_GENERATION_ATTEMPTS; attempt++) {
            String candidate = publicIdPolicy.generate();
            if (!loadUserPort.existsByPublicId(candidate)) {
                return candidate;
            }
        }
        throw ApplicationException.of(UserStatusCode.PUBLIC_ID_GENERATION_FAILED);
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw ApplicationException.of(AuthStatusCode.INVALID_AUTH_REQUEST);
        }
        return name.trim();
    }
}
