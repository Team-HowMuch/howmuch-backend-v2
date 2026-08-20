package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.user.policy.NicknamePolicy;
import com.amateur.howmuchbackend.application.port.in.auth.SignUpUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.SignUpRequest;
import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
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
public class SignUpService implements SignUpUseCase {
    private final PinPolicy pinPolicy;
    private final IdentityPolicy identityPolicy;
    private final NicknamePolicy nicknamePolicy;
    private final AuthChallengeStorePort authChallengeStorePort;
    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void signUp(SignUpRequest request) {
        var challenge = authChallengeStorePort.findById(request.challengeId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.AUTH_CHALLENGE_EXPIRED));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (challenge.isExpired(now)) {
            authChallengeStorePort.deleteById(challenge.challengeId());
            throw ApplicationException.of(AuthStatusCode.AUTH_CHALLENGE_EXPIRED);
        }
        if (!pinPolicy.isValid(request.pin())) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
        if (!request.pin().equals(request.pinConfirmation())) {
            throw ApplicationException.of(AuthStatusCode.PIN_CONFIRMATION_MISMATCH);
        }
        if (!request.requiredTermsAgreed()) {
            throw ApplicationException.of(AuthStatusCode.REQUIRED_TERMS_NOT_AGREED);
        }
        if (loadUserPort.findByPhoneNumber(challenge.phoneNumber()).isPresent()) {
            throw ApplicationException.of(AuthStatusCode.PHONE_NUMBER_ALREADY_REGISTERED);
        }

        User user = User.register(
                identityPolicy.phoneHash(challenge.phoneNumber()),
                normalizeName(request.name()),
                nicknamePolicy.normalize(request.name()),
                challenge.phoneNumber(),
                passwordEncoder.encode(request.pin()),
                now,
                request.marketingAgreed()
        );
        saveUserPort.save(user);
        authChallengeStorePort.deleteById(challenge.challengeId());
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw ApplicationException.of(AuthStatusCode.INVALID_AUTH_REQUEST);
        }
        return name.trim();
    }
}
