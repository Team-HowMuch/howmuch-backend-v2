package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.CheckPhoneNumberRegistrationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.command.PhoneNumberRegistrationCheckCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.PhoneNumberRegistrationResult;
import com.amateur.howmuchbackend.application.port.out.auth.PhoneNumberRegistrationCheckLimitPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckPhoneNumberRegistrationService implements CheckPhoneNumberRegistrationUseCase {
    private static final int MAX_REQUESTS_PER_ADDRESS = 60;
    private static final int MAX_REQUESTS_PER_PHONE = 1;

    private final IdentityPolicy identityPolicy;
    private final SmsVerificationPolicy smsVerificationPolicy;
    private final PhoneNumberRegistrationCheckLimitPort checkLimitPort;
    private final LoadUserPort loadUserPort;

    @Override
    @Transactional(readOnly = true)
    public PhoneNumberRegistrationResult check(PhoneNumberRegistrationCheckCommand command) {
        String phoneNumber = identityPolicy.normalizePhoneNumber(command.phoneNumber());
        String phoneHash = identityPolicy.phoneHash(phoneNumber);
        if (!checkLimitPort.tryConsume(
                command.clientAddress(),
                phoneHash,
                MAX_REQUESTS_PER_ADDRESS,
                MAX_REQUESTS_PER_PHONE,
                smsVerificationPolicy.resendCooldown()
        )) {
            throw ApplicationException.of(CommonStatusCode.TOO_MANY_REQUEST);
        }

        boolean registered = loadUserPort.findByPhoneNumber(phoneNumber)
                .filter(User::active)
                .isPresent();
        return new PhoneNumberRegistrationResult(registered);
    }
}
