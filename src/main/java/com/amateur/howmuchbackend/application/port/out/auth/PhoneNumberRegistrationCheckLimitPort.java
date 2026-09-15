package com.amateur.howmuchbackend.application.port.out.auth;

import java.time.Duration;

public interface PhoneNumberRegistrationCheckLimitPort {
    boolean tryConsume(
            String clientAddress,
            String phoneHash,
            int maxAddressRequests,
            int maxPhoneRequests,
            Duration window
    );
}
