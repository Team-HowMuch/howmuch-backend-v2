package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationSentResult;

public interface SendSmsVerificationUseCase {
    SmsVerificationSentResult send(SendSmsVerificationCommand command);
}
