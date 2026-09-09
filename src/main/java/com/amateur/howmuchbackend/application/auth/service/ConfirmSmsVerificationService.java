package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.application.port.out.auth.SmsVerificationStorePort;
import com.amateur.howmuchbackend.application.port.in.auth.ConfirmSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationConfirmedResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public SmsVerificationConfirmedResult confirm(ConfirmSmsVerificationCommand command) {
        String phoneNumber = identityPolicy.normalizePhoneNumber(command.phoneNumber());
        String phoneHash = identityPolicy.phoneHash(phoneNumber);
        SmsOtpState.Outcome outcome = smsVerificationStorePort.verifyAndConsume(
                phoneHash, command.verificationRequestId(),
                SendSmsVerificationService.hashCode(phoneHash, command.verificationCode()),
                smsVerificationPolicy.maxAttempts());
        switch (outcome) {
            case EXPIRED -> throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_EXPIRED);
            case CODE_MISMATCH -> throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_CODE_MISMATCH);
            case ATTEMPTS_EXCEEDED -> throw ApplicationException.of(AuthStatusCode.SMS_VERIFICATION_ATTEMPTS_EXCEEDED);
            case VERIFIED -> { }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AuthChallenge challenge = new AuthChallenge(
                UUID.randomUUID().toString(),
                phoneNumber,
                now.plus(smsVerificationPolicy.challengeTtl())
        );
        authChallengeStorePort.save(
                challenge,
                smsVerificationPolicy.challengeTtl()
        );

        return new SmsVerificationConfirmedResult(challenge.challengeId(), challenge.expiresAt());
    }
}
