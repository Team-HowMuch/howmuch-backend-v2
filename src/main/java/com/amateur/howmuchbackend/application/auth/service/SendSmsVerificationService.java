package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.port.out.auth.SmsSenderPort;
import com.amateur.howmuchbackend.application.port.out.auth.SmsVerificationStorePort;
import com.amateur.howmuchbackend.application.port.in.auth.SendSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationSentResponse;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SendSmsVerificationService implements SendSmsVerificationUseCase {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityPolicy identityPolicy;
    private final SmsVerificationPolicy smsVerificationPolicy;
    private final SmsVerificationStorePort smsVerificationStorePort;
    private final SmsSenderPort smsSenderPort;

    @Override
    @Transactional
    public SmsVerificationSentResponse send(SendSmsVerificationRequest request) {
        String phoneNumber = identityPolicy.normalizePhoneNumber(request.phoneNumber());
        String phoneHash = identityPolicy.phoneHash(phoneNumber);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        smsVerificationStorePort.findByPhoneHash(phoneHash).ifPresent(existing -> {
            if (existing.isExpired(now)) {
                smsVerificationStorePort.deleteByPhoneHash(phoneHash);
            } else if (!existing.canResend(now)) {
                throw new ApplicationException(AuthStatusCode.SMS_RESEND_TOO_SOON);
            }
        });

        String verificationCode = generateCode();
        LocalDateTime expiresAt = now.plus(smsVerificationPolicy.otpTtl());
        LocalDateTime resendAvailableAt = now.plus(smsVerificationPolicy.resendCooldown());
        SmsOtpState state = new SmsOtpState(
                UUID.randomUUID().toString(),
                phoneNumber,
                hashCode(phoneHash, verificationCode),
                0,
                resendAvailableAt,
                expiresAt
        );

        smsVerificationStorePort.save(
                phoneHash,
                state,
                Duration.between(now, expiresAt)
        );
        try {
            smsSenderPort.send(phoneNumber, verificationCode);
        } catch (RuntimeException exception) {
            try {
                smsVerificationStorePort.deleteByPhoneHash(phoneHash);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }

        return new SmsVerificationSentResponse(
                state.verificationRequestId(),
                phoneNumber,
                expiresAt,
                resendAvailableAt
        );
    }

    private String generateCode() {
        return String.valueOf(100000 + RANDOM.nextInt(900000));
    }

    static String hashCode(String phoneHash, String verificationCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((phoneHash + ":" + verificationCode).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApplicationException(CommonStatusCode.INTERNAL_SERVER_ERROR, "SHA-256 is unavailable.", exception);
        }
    }
}
