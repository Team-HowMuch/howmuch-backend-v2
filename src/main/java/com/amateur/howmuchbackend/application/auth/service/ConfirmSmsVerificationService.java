package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.application.port.out.auth.SmsVerificationStorePort;
import com.amateur.howmuchbackend.application.port.in.auth.ConfirmSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationConfirmedResponse;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfirmSmsVerificationService implements ConfirmSmsVerificationUseCase {
    private final IdentityPolicy identityPolicy;
    private final SmsVerificationPolicy smsVerificationPolicy;
    private final SmsVerificationStorePort smsVerificationStorePort;
    private final AuthChallengeStorePort authChallengeStorePort;

    @Override
    @Transactional
    public SmsVerificationConfirmedResponse confirm(ConfirmSmsVerificationRequest request) {
        String phoneNumber = identityPolicy.normalizePhoneNumber(request.phoneNumber());
        String phoneHash = identityPolicy.phoneHash(phoneNumber);
        SmsOtpState state = smsVerificationStorePort.findByPhoneHash(phoneHash)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_EXPIRED));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        if (!state.verificationRequestId().equals(request.verificationRequestId())) {
            throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_CODE_MISMATCH);
        }
        if (state.isExpired(now)) {
            smsVerificationStorePort.deleteByPhoneHash(phoneHash);
            throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_EXPIRED);
        }

        if (!state.codeHash().equals(SendSmsVerificationService.hashCode(phoneHash, request.verificationCode()))) {
            SmsOtpState increased = state.increaseAttemptCount();
            if (increased.attemptCount() >= smsVerificationPolicy.maxAttempts()) {
                smsVerificationStorePort.deleteByPhoneHash(phoneHash);
                throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_ATTEMPTS_EXCEEDED);
            }

            Duration remainingTtl = Duration.between(now, state.expiresAt());
            smsVerificationStorePort.save(phoneHash, increased, remainingTtl);
            throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_CODE_MISMATCH);
        }

        smsVerificationStorePort.deleteByPhoneHash(phoneHash);
        AuthChallenge challenge = new AuthChallenge(
                UUID.randomUUID().toString(),
                state.phoneNumber(),
                now.plus(smsVerificationPolicy.challengeTtl())
        );
        authChallengeStorePort.save(
                challenge,
                smsVerificationPolicy.challengeTtl()
        );

        return new SmsVerificationConfirmedResponse(challenge.challengeId(), challenge.expiresAt());
    }
}
