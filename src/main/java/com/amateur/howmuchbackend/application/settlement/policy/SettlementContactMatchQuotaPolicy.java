package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SettlementContactMatchQuotaPolicy {
    static final int MAX_REQUESTS_PER_WINDOW = 10;
    static final int MAX_CONTACTS_PER_WINDOW = 2_000;
    static final Duration REQUEST_WINDOW = Duration.ofMinutes(1);
    static final Duration CONTACT_WINDOW = Duration.ofHours(24);

    public Duration requestWindow() {
        return REQUEST_WINDOW;
    }

    public Duration contactWindow() {
        return CONTACT_WINDOW;
    }

    public void validate(SettlementContactMatchUsage usage) {
        if (usage.requestCount() > MAX_REQUESTS_PER_WINDOW
                || usage.contactCount() > MAX_CONTACTS_PER_WINDOW) {
            throw ApplicationException.of(CommonStatusCode.TOO_MANY_REQUEST);
        }
    }
}
