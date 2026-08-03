package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationConfirmedResponse;

public interface ConfirmSmsVerificationUseCase {
    SmsVerificationConfirmedResponse confirm(ConfirmSmsVerificationRequest request);
}
