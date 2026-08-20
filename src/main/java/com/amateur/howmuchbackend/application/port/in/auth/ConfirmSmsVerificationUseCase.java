package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationConfirmedResult;

public interface ConfirmSmsVerificationUseCase {
    SmsVerificationConfirmedResult confirm(ConfirmSmsVerificationCommand command);
}
