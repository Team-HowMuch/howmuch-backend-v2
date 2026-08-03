package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationSentResponse;

public interface SendSmsVerificationUseCase {
    SmsVerificationSentResponse send(SendSmsVerificationRequest request);
}
