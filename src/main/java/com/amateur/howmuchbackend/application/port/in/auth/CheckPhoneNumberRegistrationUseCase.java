package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.PhoneNumberRegistrationCheckCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.PhoneNumberRegistrationResult;

public interface CheckPhoneNumberRegistrationUseCase {
    PhoneNumberRegistrationResult check(PhoneNumberRegistrationCheckCommand command);
}
